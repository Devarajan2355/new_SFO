package com.sanya.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Guard: Focus Guard's memory and rules. GuardService (the Accessibility service) does the
 * watching and the blocking; this object holds everything it needs to decide.
 *
 *   - Shorts blocker      on/off
 *   - App limits          "limit youtube to 1 hour": a daily cap per app, with a warning at 80%
 *   - Usage today         counted by GuardService from which app is in front (no extra permission)
 *   - Blocks per day      a small count, for the chart (no page content is ever stored)
 *   - Adult word filter   browser address bars, page titles and search boxes are checked against a
 *                         built-in list plus the words and sites you add (see WordGuard.kt)
 *   - Urge mode           10 minutes in which browsers, YouTube and your limited apps are locked
 *   - Watchdog            Sanya tells you if the guard, or its Accessibility service, has been switched off
 *   - Friction lock       anything that LOOSENS a rule waits 15 minutes, then must be asked again
 *
 * WHERE IT IS KEPT: the preferences file "sanya_guard", NOT the Brain file, and it is not in the
 * backup. So editing the Brain, restoring a backup or teaching a slang phrase can never switch the
 * guard off faster than the voice command can.
 *
 *   Tightening (guard on, shorts on, a new or lower limit)         happens at once
 *   Loosening  (guard off, shorts off, remove or raise a limit)    waits 15 minutes
 *
 * Optional Brain settings (they can only ADD protection, never remove it):
 *   setting | shorts extra ids = some_view_id, another_id     more YouTube view ids to treat as Shorts
 *   setting | guard debug = on                                 write the YouTube ids on screen to the
 *                                                              Health check's Last errors (to fix detection)
 */
object Guard {

    data class Limit(val label: String, val minutes: Int)

    /** An installed app: [label] is the cleaned name (lower case), [pkg] the package. */
    data class AppRef(val label: String, val pkg: String)

    val SHORTS_APPS: Set<String> = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube"
    )

    // Best-known view ids of the full-screen Shorts player. YouTube renames these now and then:
    // if Shorts stops being caught, use "guard debug" (see above) and send me what it records.
    private val BUILTIN_IDS = listOf("reel_recycler", "reel_player_page_container")

    private const val PREFS = "sanya_guard"
    private const val KEEP_DAYS = 30L
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val URGE_MIN = 10L
    private const val URGE_MAX_MIN = 60L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cap(s: String): String =
        s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // ---------------------------------------------------------------
    // Switches
    // ---------------------------------------------------------------

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("on", true)

    fun shortsOn(ctx: Context): Boolean = p(ctx).getBoolean("shorts", true)

    fun wordsOn(ctx: Context): Boolean = p(ctx).getBoolean("words", true)

    fun debug(): Boolean = Brain.setting("guard debug", "off") == "on"

    fun shortsIds(): List<String> {

        val extra = Brain.setting("shorts extra ids", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length < 60 && !it.contains(' ') }

        return (BUILTIN_IDS + extra).distinct()
    }

    /** Is the Accessibility service switched on in Android's settings? */
    fun serviceEnabled(ctx: Context): Boolean {

        return try {
            val list = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val me = ComponentName(ctx, GuardService::class.java)

            list.split(":").any { ComponentName.unflattenFromString(it) == me }
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------
    // Installed apps (cached 5 minutes)
    // ---------------------------------------------------------------

    private var appCache: List<AppRef> = emptyList()
    private var appCacheAt = 0L

    @Synchronized
    fun apps(ctx: Context): List<AppRef> {

        val now = System.currentTimeMillis()

        if (appCache.isNotEmpty() && now - appCacheAt < 5 * MINUTE_MS) return appCache

        val list = try {
            val pm = ctx.packageManager
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
                .map { AppRef(Brain.clean(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
                .filter { it.label.isNotEmpty() }
                .distinctBy { it.pkg }
        } catch (e: Exception) {
            emptyList()
        }

        appCache = list
        appCacheAt = now

        return list
    }

    /** The installed app you meant by [spoken] (your nicknames work too), or null. */
    fun findApp(ctx: Context, spoken: String): AppRef? {

        val said = Brain.clean(spoken)
        val q = Brain.clean(Brain.map("nick")[said] ?: said)

        if (q.isEmpty()) return null

        val all = apps(ctx)

        all.firstOrNull { it.label == q }?.let { return it }

        all.filter { it.label.startsWith(q) }.minByOrNull { it.label.length }?.let { return it }

        if (q.length >= 4) {
            all.filter { it.label.contains(q) }.minByOrNull { it.label.length }?.let { return it }
        }

        val best = all.maxByOrNull { Mind.similarity(q, it.label) } ?: return null

        return if (Mind.similarity(q, best.label) >= 0.75) best else null
    }

    // ---------------------------------------------------------------
    // Limits
    // ---------------------------------------------------------------

    fun limits(ctx: Context): List<Limit> {

        val raw = p(ctx).getString("limits", "") ?: ""
        val out = ArrayList<Limit>()

        for (part in raw.split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val m = kv[1].toIntOrNull() ?: continue
            if (kv[0].isNotEmpty() && m > 0) out.add(Limit(kv[0], m))
        }

        return out
    }

    private fun saveLimits(ctx: Context, list: List<Limit>) {
        p(ctx).edit().putString("limits", list.joinToString(";") { it.label + "=" + it.minutes }).apply()
    }

    /** The limit that applies to the app with this package, or null. */
    fun limitFor(ctx: Context, pkg: String): Limit? {

        val ls = limits(ctx)
        if (ls.isEmpty()) return null

        val app = apps(ctx).firstOrNull { it.pkg == pkg } ?: return null

        return ls.firstOrNull { it.label == app.label }
    }

    // ---------------------------------------------------------------
    // Usage today (milliseconds per package). Counted by GuardService.
    // ---------------------------------------------------------------

    private val usage = HashMap<String, Long>()
    private var usageDay = -1L
    private var usageLoaded = false
    private var usageDirty = false

    @Synchronized
    private fun loadUsage(ctx: Context) {

        val today = LocalDate.now().toEpochDay()

        if (usageLoaded && usageDay == today) return

        val pr = p(ctx)
        val saved = pr.getLong("ud", -1L)

        usage.clear()

        if (saved == today) {
            for (part in (pr.getString("u", "") ?: "").split(";")) {
                val kv = part.split("=")
                if (kv.size != 2) continue
                val v = kv[1].toLongOrNull() ?: continue
                usage[kv[0]] = v
            }
        } else {
            pr.edit().putLong("ud", today).putString("u", "").apply()
        }

        usageDay = today
        usageLoaded = true
        usageDirty = false
    }

    @Synchronized
    fun addUsage(ctx: Context, pkg: String, ms: Long) {
        loadUsage(ctx)
        usage[pkg] = (usage[pkg] ?: 0L) + ms
        usageDirty = true
    }

    @Synchronized
    fun saveUsage(ctx: Context) {

        if (!usageDirty) return

        p(ctx).edit()
            .putLong("ud", usageDay)
            .putString("u", usage.entries.joinToString(";") { it.key + "=" + it.value })
            .apply()

        usageDirty = false
    }

    @Synchronized
    fun usedMs(ctx: Context, pkg: String): Long {
        loadUsage(ctx)
        return usage[pkg] ?: 0L
    }

    /** (cleaned app name, milliseconds today), biggest first. Only apps with a launcher icon, 1 minute or more. */
    @Synchronized
    fun topApps(ctx: Context, n: Int): List<Pair<String, Long>> {

        loadUsage(ctx)

        val names = apps(ctx).associate { it.pkg to it.label }

        return usage.entries
            .filter { it.value >= MINUTE_MS && names.containsKey(it.key) }
            .sortedByDescending { it.value }
            .take(n)
            .map { Pair(names.getValue(it.key), it.value) }
    }

    /** True the first time today that [pkg] is warned about (so the 80% warning is spoken once). */
    @Synchronized
    fun markWarned(ctx: Context, pkg: String): Boolean {

        val today = LocalDate.now().toEpochDay()
        val pr = p(ctx)
        val raw = pr.getString("warn", "") ?: ""

        val done =
            if (raw.startsWith("$today|")) raw.substringAfter('|').split(";").filter { it.isNotEmpty() }
            else emptyList()

        if (pkg in done) return false

        pr.edit().putString("warn", "$today|" + (done + pkg).joinToString(";")).apply()

        return true
    }

    // ---------------------------------------------------------------
    // Blocks per day (a number, nothing else)
    // ---------------------------------------------------------------

    private fun readCounts(ctx: Context, key: String): LinkedHashMap<Long, Int> {

        val m = LinkedHashMap<Long, Int>()

        for (part in (p(ctx).getString(key, "") ?: "").split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val d = kv[0].toLongOrNull() ?: continue
            val n = kv[1].toIntOrNull() ?: continue
            m[d] = n
        }

        return m
    }

    @Synchronized
    private fun bump(ctx: Context, key: String) {

        val today = LocalDate.now().toEpochDay()
        val m = readCounts(ctx, key)

        m[today] = (m[today] ?: 0) + 1

        val keep = m.entries.filter { it.key > today - KEEP_DAYS }

        p(ctx).edit()
            .putString(key, keep.joinToString(";") { it.key.toString() + "=" + it.value })
            .apply()
    }

    fun noteBlock(ctx: Context) = bump(ctx, "blocks")

    fun blocksToday(ctx: Context): Int = readCounts(ctx, "blocks")[LocalDate.now().toEpochDay()] ?: 0

    fun urgesToday(ctx: Context): Int = readCounts(ctx, "urges")[LocalDate.now().toEpochDay()] ?: 0

    /** Oldest to newest: blocks on each of the last 14 days. */
    fun blockBars(ctx: Context): List<ValueBar> {

        val m = readCounts(ctx, "blocks")
        val today = LocalDate.now()

        return (13 downTo 0).map { k ->
            val d = today.minusDays(k.toLong())
            ValueBar(d.dayOfMonth.toString(), (m[d.toEpochDay()] ?: 0).toLong())
        }
    }

    // ---------------------------------------------------------------
    // The friction lock
    // ---------------------------------------------------------------

    /**
     * Null means: go ahead (the wait is over). Otherwise it is what she should say.
     * [key] names the exact change, [action] says it in words ("switch Focus Guard off").
     */
    fun gate(ctx: Context, key: String, action: String): String? {

        // While urge mode runs nothing can be loosened, so a craving cannot talk its way past the wait.
        val urge = urgeLeftMs(ctx)

        if (urge > 0L) {
            return "Not while urge mode is on. " + Num.durText(((urge + MINUTE_MS - 1) / MINUTE_MS).toInt()) +
                " to go. Ask me again after it ends."
        }

        val pr = p(ctx)
        val now = System.currentTimeMillis()

        return when (
            val v = GuardRules.check(
                pr.getString("pk", "") ?: "",
                pr.getLong("pa", 0L),
                key,
                now
            )
        ) {

            GuardRules.Verdict.Apply -> {
                GuardLog.note(ctx, "loosen")
                if (key == "master-off") GuardCare.tamperAlert(ctx, "guard")
                if (key == "words-off") GuardCare.tamperAlert(ctx, "filter")
                pr.edit().remove("pk").remove("pa").remove("pt").apply()
                null
            }

            GuardRules.Verdict.Start -> {
                pr.edit().putString("pk", key).putLong("pa", now).putString("pt", action).apply()
                val at = LocalDateTime.now().plusMinutes(GuardRules.DELAY_MIN)
                "Okay. I'll $action, but not right away. Ask me again after " +
                    ReminderParse.clock12(at.hour * 60 + at.minute) + ". The wait is on purpose."
            }

            is GuardRules.Verdict.Wait ->
                "Not yet. " + GuardRules.waitText(v.msLeft) + " to go before I $action."
        }
    }

    private fun clearPendingIf(ctx: Context, key: String) {
        val pr = p(ctx)
        if ((pr.getString("pk", "") ?: "") == key) {
            pr.edit().remove("pk").remove("pa").remove("pt").apply()
        }
    }

    /** What is waiting, in words, or null. For the screen and the status. */
    fun pendingText(ctx: Context): String? {

        val pr = p(ctx)
        val key = pr.getString("pk", "") ?: ""
        val at = pr.getLong("pa", 0L)

        if (key.isEmpty() || at <= 0L) return null

        val age = System.currentTimeMillis() - at

        if (age > GuardRules.EXPIRE_MS) return null

        val what = pr.getString("pt", "") ?: ""

        return if (age < GuardRules.DELAY_MS) {
            "Waiting: $what. Ready in " + GuardRules.waitText(GuardRules.DELAY_MS - age) + "."
        } else {
            "Ready: $what. Ask again to confirm."
        }
    }

    private fun cancelRequest(ctx: Context): String {

        val pr = p(ctx)

        if ((pr.getString("pk", "") ?: "").isEmpty()) return "There's no request waiting."

        pr.edit().remove("pk").remove("pa").remove("pt").apply()

        return "Okay. I cancelled that request."
    }

    // ---------------------------------------------------------------
    // Changing things
    // ---------------------------------------------------------------

    private fun serviceNote(ctx: Context): String =
        if (serviceEnabled(ctx)) "" else
            " But the Focus Guard service is off, so nothing is enforced yet. Say show my focus guard to switch it on."

    private fun setMaster(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("on", true).remove("off_at").apply()
            clearPendingIf(ctx, "master-off")
            return "Focus Guard is on." + serviceNote(ctx)
        }

        if (!enabled(ctx)) return "Focus Guard is already off."

        gate(ctx, "master-off", "switch Focus Guard off")?.let { return it }

        p(ctx).edit().putBoolean("on", false).putLong("off_at", System.currentTimeMillis()).apply()

        return "Focus Guard is off. Say turn on focus guard to bring it back."
    }

    private fun setShorts(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("shorts", true).apply()
            clearPendingIf(ctx, "shorts-off")
            return "Shorts blocker is on." + serviceNote(ctx)
        }

        if (!shortsOn(ctx)) return "The Shorts blocker is already off."

        gate(ctx, "shorts-off", "allow YouTube Shorts again")?.let { return it }

        p(ctx).edit().putBoolean("shorts", false).apply()

        return "Shorts are allowed again. Say block shorts to stop them."
    }

    private fun setLimit(ctx: Context, spoken: String, minutes: Int): String {

        val app = findApp(ctx, spoken) ?: return "I can't find an app called $spoken."

        if (app.pkg == ctx.packageName) return "I won't limit myself."

        val ls = limits(ctx)
        val cur = ls.firstOrNull { it.label == app.label }

        if (cur != null && cur.minutes == minutes) {
            return cap(app.label) + " is already limited to " + Num.durText(minutes) + " a day."
        }

        // A bigger limit than before loosens the rule: it waits. A new or smaller one is instant.
        if (cur != null && GuardRules.raising(cur.minutes, minutes)) {
            gate(
                ctx,
                "raise:" + app.label + ":" + minutes,
                "raise the " + cap(app.label) + " limit to " + Num.durText(minutes)
            )?.let { return it }
        }

        saveLimits(ctx, ls.filter { it.label != app.label } + Limit(app.label, minutes))

        val used = (usedMs(ctx, app.pkg) / MINUTE_MS).toInt()

        return "Okay. " + cap(app.label) + " is limited to " + Num.durText(minutes) + " a day." +
            (if (used > 0) " You've used " + Num.durText(used) + " today." else "") +
            serviceNote(ctx)
    }

    private fun removeLimit(ctx: Context, spoken: String): String {

        val q = Brain.clean(spoken)

        if (q.isEmpty() || q in setOf("my", "the", "all", "everything")) {
            return "Which app? Say, for example: remove the limit on youtube."
        }

        val ls = limits(ctx)

        val hit = ls.firstOrNull { it.label == q }
            ?: findApp(ctx, spoken)?.let { a -> ls.firstOrNull { it.label == a.label } }
            ?: return "You don't have a limit on $spoken."

        gate(ctx, "remove:" + hit.label, "remove the " + cap(hit.label) + " limit")?.let { return it }

        saveLimits(ctx, ls.filter { it.label != hit.label })

        return "Okay. The " + cap(hit.label) + " limit is gone."
    }

    // ---------------------------------------------------------------
    // The adult word filter and your own words
    // ---------------------------------------------------------------

    /** Your own entries: "w:term" (a word or phrase) or "s:term" (a site name). */
    fun userWords(ctx: Context): List<String> =
        (p(ctx).getString("xwords", "") ?: "")
            .split(";")
            .filter { it.length >= 3 && (it.startsWith("w:") || it.startsWith("s:")) }

    private fun saveWords(ctx: Context, list: List<String>) {
        p(ctx).edit().putString("xwords", list.joinToString(";")).apply()
    }

    private fun setWordFilter(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("words", true).apply()
            clearPendingIf(ctx, "words-off")
            return "The adult word filter is on. I check browser address bars, page titles and search boxes." +
                serviceNote(ctx)
        }

        if (!wordsOn(ctx)) return "The adult word filter is already off."

        gate(ctx, "words-off", "switch the adult word filter off")?.let { return it }

        p(ctx).edit().putBoolean("words", false).apply()

        return "The adult word filter is off. Say turn on the adult filter to bring it back."
    }

    private fun blockWord(ctx: Context, term: String, site: Boolean): String {

        val e = WordGuard.entry(term, site)
            ?: return if (site) {
                "That site name is too short. Use at least 5 letters, like examplesite."
            } else {
                "I didn't catch the word. Say, for example: block the word hentai."
            }

        val name = e.substring(2)

        if (WordGuard.find(name) != null) return "$name is already blocked by default."

        val list = userWords(ctx)

        if (e in list) return "$name is already on your blocked list."

        saveWords(ctx, list + e)

        return "Okay. I'll block " + (if (site) "the site " else "the word ") + name + " in browsers." +
            (if (!wordsOn(ctx)) " The adult word filter is off, so say turn on the adult filter." else "") +
            serviceNote(ctx)
    }

    private fun unblockWord(ctx: Context, term: String, site: Boolean): String {

        val e = WordGuard.entry(term, site) ?: return "I couldn't find that in your blocked words."
        val name = e.substring(2)
        val list = userWords(ctx)

        if (e !in list) {
            return if (WordGuard.find(name) != null) {
                "$name is built in, so I can't unblock it."
            } else {
                "$name isn't on your blocked list."
            }
        }

        gate(ctx, "unword:$e", "unblock $name")?.let { return it }

        saveWords(ctx, list.filter { it != e })

        return "Okay. $name is unblocked."
    }

    private fun wordListText(ctx: Context): String {

        val list = userWords(ctx)
        val head = "The adult word filter is " + (if (wordsOn(ctx)) "on. " else "off. ")

        if (list.isEmpty()) {
            return head + "I block a built-in list. You haven't added your own yet. " +
                "Say, for example: block the word hentai."
        }

        val shown = list.take(6).joinToString(", ") { it.substring(2) }

        return head + "Your own: " + shown + (if (list.size > 6) ", and ${list.size - 6} more" else "") + "."
    }

    // ---------------------------------------------------------------
    // Urge mode: ten minutes in which the tempting apps are locked
    // ---------------------------------------------------------------

    fun urgeLeftMs(ctx: Context): Long =
        (p(ctx).getLong("urge_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** During urge mode: browsers, YouTube and every app you put a limit on. */
    fun urgeBlocks(ctx: Context, pkg: String): Boolean =
        pkg in WordGuard.BROWSERS || pkg in SHORTS_APPS || limitFor(ctx, pkg) != null

    private fun startUrge(ctx: Context): String {

        val now = System.currentTimeMillis()
        val pr = p(ctx)
        val wasActive = urgeLeftMs(ctx) > 0L

        // Saying it again adds ten more minutes, up to an hour from now.
        val base = maxOf(pr.getLong("urge_until", 0L), now)
        val until = minOf(base + URGE_MIN * MINUTE_MS, now + URGE_MAX_MIN * MINUTE_MS)

        // Urge mode also switches the guard back on, if it was off.
        pr.edit().putLong("urge_until", until).putBoolean("on", true).remove("off_at").apply()
        clearPendingIf(ctx, "master-off")

        if (!wasActive) {
            bump(ctx, "urges")
            GuardLog.note(ctx, "urge")
            GuardCare.closeWindow(ctx)
        }

        val mins = ((until - now + MINUTE_MS - 1) / MINUTE_MS).toInt()

        val ideas = try {
            Routines.today(ctx).remaining.take(2).map { it.task.name }
        } catch (e: Throwable) {
            emptyList<String>()
        }

        return "I'm with you. Urge mode is on for " + Num.durText(mins) +
            ". Browsers, YouTube and your limited apps are locked. " +
            "Drink some water and breathe slowly. This will pass." +
            (if (GuardCare.settingsLockOn(ctx)) " Sanya's Accessibility and app pages are locked until it ends." else "") +
            (if (ideas.isNotEmpty()) " You could do: " + ideas.joinToString(" or ") + "." else "") +
            serviceNote(ctx)
    }

    private fun urgeStatusText(ctx: Context): String {

        val left = urgeLeftMs(ctx)

        if (left <= 0L) {
            val n = urgesToday(ctx)
            return "Urge mode is off." +
                (if (n > 0) " You've handled $n " + (if (n == 1) "urge" else "urges") + " today." else "")
        }

        val mins = ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()

        return "Urge mode: " + Num.durText(mins) + " left. You've got this."
    }

    private fun urgeEndText(ctx: Context): String {

        val left = urgeLeftMs(ctx)

        if (left <= 0L) return "Urge mode isn't on."

        val mins = ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()

        return "I won't cut urge mode short. It ends in " + Num.durText(mins) +
            ". Say urge mode again if you need more time."
    }

    /**
     * What she can offer to do instead, as a command she already understands, or "" if you are
     * already in a timer. StepRunner asks "Shall I start a pomodoro?" and runs it on a yes.
     */
    fun urgeOffer(ctx: Context): String {

        if (Pomodoro.running(ctx) || Study.running(ctx) != null) return ""

        val rem = try {
            Routines.today(ctx).remaining
        } catch (e: Throwable) {
            emptyList<Routines.Instance>()
        }

        val study = rem.firstOrNull { it.task.spec.target > 0 } ?: return "start pomodoro"

        return "start pomodoro for " + Brain.clean(study.task.name)
    }

    // ---------------------------------------------------------------
    // Watchdog: Sanya speaks up if the guard has been switched off
    // ---------------------------------------------------------------

    /**
     * (what she says, the command she runs on a yes) or null. Called by Proactive.kt, at most once
     * every 3 hours, and never at night (Proactive handles quiet hours).
     */
    fun watchdog(ctx: Context, now: Long): Pair<String, String>? {

        val pr = p(ctx)

        if (now - pr.getLong("wd", 0L) < 3 * HOUR_MS) return null

        if (!enabled(ctx)) {

            val off = pr.getLong("off_at", 0L)

            if (off == 0L || now - off < 24 * HOUR_MS) return null

            pr.edit().putLong("wd", now).apply()

            val days = ((now - off) / (24 * HOUR_MS)).toInt().coerceAtLeast(1)

            return Pair(
                "Focus Guard has been off for $days " + (if (days == 1) "day" else "days") +
                    ". Shall I switch it back on?",
                "turn on focus guard"
            )
        }

        if (!serviceEnabled(ctx)) {

            pr.edit().putLong("wd", now).apply()

            return Pair(
                "Focus Guard's Accessibility service is off, so nothing is being blocked. Shall I open it?",
                "show my focus guard"
            )
        }

        return null
    }

    // ---------------------------------------------------------------
    // Saying things
    // ---------------------------------------------------------------

    fun status(ctx: Context): String {

        val parts = ArrayList<String>()
        val on = enabled(ctx)

        parts.add(
            when {
                !on -> "Focus Guard is off."
                serviceEnabled(ctx) -> "Focus Guard is on."
                else -> "Focus Guard is on, but its Accessibility service is off, so nothing is blocked."
            }
        )

        if (on) {
            parts.add("Shorts blocker is " + (if (shortsOn(ctx)) "on." else "off."))
            parts.add("Adult word filter is " + (if (wordsOn(ctx)) "on." else "off."))
        }

        val urge = urgeLeftMs(ctx)

        if (urge > 0L) {
            parts.add("Urge mode: " + Num.durText(((urge + MINUTE_MS - 1) / MINUTE_MS).toInt()) + " left.")
        }

        val n = blocksToday(ctx)
        parts.add(if (n == 0) "No blocks today." else if (n == 1) "One block today." else "$n blocks today.")

        val ls = limits(ctx)

        if (ls.isEmpty()) {
            parts.add("No app limits yet.")
        } else {
            val installed = apps(ctx)
            parts.add(
                "Limits: " + ls.joinToString(", ") { l ->
                    val pkg = installed.firstOrNull { it.label == l.label }?.pkg
                    val used = if (pkg == null) 0 else (usedMs(ctx, pkg) / MINUTE_MS).toInt()
                    cap(l.label) + " " + Num.durText(used) + " of " + Num.durText(l.minutes)
                } + "."
            )
        }

        pendingText(ctx)?.let { parts.add(it) }

        return parts.joinToString(" ")
    }

    private fun usageText(ctx: Context, spoken: String?): String {

        val note =
            if (enabled(ctx) && serviceEnabled(ctx)) ""
            else " The Guard service is off, so this may be missing time."

        if (spoken == null) {

            val top = topApps(ctx, 3)

            if (top.isEmpty()) return "I haven't tracked any app time yet today.$note"

            return "Today: " + top.joinToString(", ") {
                cap(it.first) + " " + Num.durText((it.second / MINUTE_MS).toInt().coerceAtLeast(1))
            } + "." + note
        }

        val app = findApp(ctx, spoken) ?: return "I can't find an app called $spoken."

        val mins = (usedMs(ctx, app.pkg) / MINUTE_MS).toInt()
        val lim = limits(ctx).firstOrNull { it.label == app.label }

        return "You've used " + cap(app.label) + " for " + Num.durText(mins) + " today." +
            (if (lim != null) " Your limit is " + Num.durText(lim.minutes) + "." else "") +
            note
    }

    /** Does what the command says and returns what she should say. (Show is opened by the caller.) */
    fun run(ctx: Context, c: GuardParse.Cmd): String = when (c) {
        GuardParse.Cmd.Status -> status(ctx)
        GuardParse.Cmd.Show -> "Opening Focus Guard."
        GuardParse.Cmd.CancelRequest -> cancelRequest(ctx)
        is GuardParse.Cmd.Master -> setMaster(ctx, c.on)
        is GuardParse.Cmd.Shorts -> setShorts(ctx, c.on)
        is GuardParse.Cmd.SetLimit -> setLimit(ctx, c.app, c.minutes)
        is GuardParse.Cmd.RemoveLimit -> removeLimit(ctx, c.app)
        is GuardParse.Cmd.Usage -> usageText(ctx, c.app)
        is GuardParse.Cmd.WordFilter -> setWordFilter(ctx, c.on)
        is GuardParse.Cmd.BlockWord -> blockWord(ctx, c.term, c.site)
        is GuardParse.Cmd.UnblockWord -> unblockWord(ctx, c.term, c.site)
        GuardParse.Cmd.WordList -> wordListText(ctx)
        GuardParse.Cmd.Urge -> startUrge(ctx)
        GuardParse.Cmd.UrgeStatus -> urgeStatusText(ctx)
        GuardParse.Cmd.UrgeEnd -> urgeEndText(ctx)
        GuardParse.Cmd.Report,
        GuardParse.Cmd.SendReport,
        is GuardParse.Cmd.Partner,
        GuardParse.Cmd.PartnerOff,
        GuardParse.Cmd.PartnerWho,
        is GuardParse.Cmd.ReportReminders,
        is GuardParse.Cmd.SettingsLock,
        GuardParse.Cmd.UnlockSettings,
        GuardParse.Cmd.LockSettingsNow,
        is GuardParse.Cmd.LockAlways,
        is GuardParse.Cmd.Alerts,
        is GuardParse.Cmd.WatchDns -> GuardCare.run(ctx, c)
        is GuardParse.Cmd.Say -> c.text
    }
}
