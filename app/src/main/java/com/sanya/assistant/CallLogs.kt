package com.sanya.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract.PhoneLookup
import java.time.LocalDate
import java.time.ZoneId

/**
 * CallLogs: your call history, spoken. Reads the phone's own call log (needs the Call log
 * permission, asked when you tap Start Sanya). Nothing is copied or stored, except the time
 * of the last missed call she told you about.
 *
 *   any missed calls / did i miss any calls today / how many missed calls
 *   who called me today / show my recent calls
 *   call back / return the call           (she asks first: "Call back Ravi Kumar? Say yes or no.")
 *   redial
 *   when did ravi last call me / when did i last call ravi
 *
 * A missed call you have not returned is offered once: "You missed a call from Ravi Kumar.
 * Shall I call back?" (Proactive.kt asks missNudge(); setting | offer callback = off stops it.)
 *
 * What counts as returned: you called that number afterwards, or they called again and you answered.
 */
object CallLogs {

    data class Row(val number: String, val name: String, val type: Int, val at: Long, val seconds: Int)

    private class Target(val name: String, val number: String)

    private class Pend(val target: Target, val again: Boolean, val at: Long)

    // The call log's own numbers for the type of call.
    private const val IN = 1
    private const val OUT = 2
    private const val MISSED = 3
    private const val REJECTED = 5

    private const val PREFS = "sanya_calls"
    private const val PENDING_MS = 20_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    const val NEED = "I need call log permission. Open the Sanya app and tap Start Sanya to allow it."

    @Volatile private var pending: Pend? = null

    private val YES = Regex(
        """^(?:yes|yeah|yep|yup|ya|ok|okay|sure|correct|right|do it|please do|go ahead|confirm)\b"""
    )
    private val NO = Regex(
        """^(?:no|nope|nah|never mind|nevermind|cancel|forget it|skip|stop|dont|do not)\b"""
    )

    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------
    // Reading the log
    // ---------------------------------------------------------------

    // Newest first.
    private fun rows(ctx: Context, limit: Int): List<Row> {

        if (!allowed(ctx)) return emptyList()

        val out = ArrayList<Row>()

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(
                        Row(
                            c.getString(0) ?: "",
                            c.getString(1) ?: "",
                            c.getInt(2),
                            c.getLong(3),
                            c.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reading the call log", e)
        }

        return out
    }

    private fun lookupName(ctx: Context, number: String): String {

        if (number.isBlank()) return ""
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""

        return try {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) (it.getString(0) ?: "") else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun endingOf(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length < 4) "" else " ending " + d.takeLast(4).toCharArray().joinToString(" ")
    }

    // What to call this caller out loud.
    private fun labelOf(ctx: Context, r: Row, cache: HashMap<String, String>): String {

        if (r.name.isNotBlank()) return r.name
        if (r.number.isBlank()) return "a private number"

        val k = ContactBook.digits(r.number)
        val hit = cache.getOrPut(k) { lookupName(ctx, r.number) }

        if (hit.isNotBlank()) return hit

        return "an unknown number" + endingOf(r.number)
    }

    private fun talk(seconds: Int): String =
        if (seconds < 60) "$seconds seconds" else Num.durText((seconds + 30) / 60)

    // ---------------------------------------------------------------
    // Missed calls
    // ---------------------------------------------------------------

    // Did you ring that number back, or did they call again and you answered?
    private fun returned(all: List<Row>, miss: Row): Boolean {

        if (miss.number.isBlank()) return false

        val k = ContactBook.digits(miss.number)

        for (r in all) {
            if (r.at <= miss.at) break
            if (r.number.isBlank() || ContactBook.digits(r.number) != k) continue
            if (r.type == OUT) return true
            if (r.type == IN && r.seconds > 0) return true
        }

        return false
    }

    // Missed calls since [since], newest first. [all] is newest first too.
    private fun missed(all: List<Row>, since: Long, onlyUnreturned: Boolean): List<Row> {

        val out = ArrayList<Row>()

        for (r in all) {
            if (r.at < since) break
            if (r.type != MISSED) continue
            if (onlyUnreturned && returned(all, r)) continue
            out.add(r)
        }

        return out
    }

    private fun missedText(ctx: Context, list: List<Row>, whenWord: String, now: Long): String {

        if (list.isEmpty()) {
            return if (whenWord.isEmpty()) {
                "You haven't missed any calls recently."
            } else {
                "You didn't miss any calls $whenWord."
            }
        }

        val cache = HashMap<String, String>()
        val groups = LinkedHashMap<String, MutableList<Row>>()

        for (r in list) {
            val k = if (r.number.isBlank()) "private" else ContactBook.digits(r.number)
            groups.getOrPut(k) { ArrayList() }.add(r)
        }

        val shown = groups.values.take(4)
        val more = groups.size - shown.size

        val parts = shown.map { rs ->
            val first = rs[0]
            val times = when (rs.size) {
                1 -> ""
                2 -> " twice"
                else -> " " + rs.size + " times"
            }
            labelOf(ctx, first, cache) + times + ", " + CallParse.ago(first.at, now)
        }

        if (list.size == 1) return "You missed a call from " + parts[0] + "."

        return "You missed " + list.size + " calls" + (if (whenWord.isEmpty()) "" else " $whenWord") + ". " +
            parts.joinToString("; ") + "." +
            (if (more > 0) " And $more more " + (if (more == 1) "caller." else "callers.") else "")
    }

    private fun missedAnswer(ctx: Context, c: CallParse.Cmd.Missed, now: Long): String {

        val all = rows(ctx, 300)
        val list: List<Row>
        val whenWord: String

        if (c.span.isEmpty()) {
            list = missed(all, now - 3 * DAY_MS, true)
            whenWord = ""
        } else {
            val r = Span.range(c.span, LocalDate.now())
            val zone = ZoneId.systemDefault()
            val from = r.first.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = r.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            list = missed(all, from, false).filter { it.at < to }
            whenWord = c.span
        }

        if (c.countOnly) {
            return if (list.isEmpty()) {
                if (whenWord.isEmpty()) "You haven't missed any calls recently." else "You didn't miss any calls $whenWord."
            } else {
                "You have " + list.size + (if (list.size == 1) " missed call" else " missed calls") +
                    (if (whenWord.isEmpty()) " you haven't returned." else " $whenWord.")
            }
        }

        return missedText(ctx, list, whenWord, now)
    }

    // ---------------------------------------------------------------
    // Recent calls, and who called
    // ---------------------------------------------------------------

    private fun describe(ctx: Context, r: Row, now: Long, cache: HashMap<String, String>): String {

        val name = labelOf(ctx, r, cache)
        val ago = CallParse.ago(r.at, now)

        return when (r.type) {
            IN ->
                if (r.seconds > 0) "$name called you $ago and you talked for ${talk(r.seconds)}"
                else "$name called you $ago"
            MISSED -> "$name called you $ago and you missed it"
            REJECTED -> "$name called you $ago and you rejected it"
            OUT ->
                if (r.seconds > 0) "you called $name $ago"
                else "you called $name $ago but it wasn't answered"
            else -> "$name, $ago"
        }
    }

    private fun recentAnswer(ctx: Context, c: CallParse.Cmd.Recent, now: Long): String {

        val all = rows(ctx, 300)
        var list: List<Row> = all
        var whenWord = ""

        if (c.span.isNotEmpty()) {
            val r = Span.range(c.span, LocalDate.now())
            val zone = ZoneId.systemDefault()
            val from = r.first.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = r.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            list = all.filter { it.at in from until to }
            whenWord = c.span
        }

        if (c.callersOnly) {
            list = list.filter { it.type == IN || it.type == MISSED || it.type == REJECTED }
        }

        if (list.isEmpty()) {
            return if (c.callersOnly) {
                "Nobody called you " + (if (whenWord.isEmpty()) "recently." else "$whenWord.")
            } else {
                "I don't see any calls " + (if (whenWord.isEmpty()) "in your call log." else "$whenWord.")
            }
        }

        val cache = HashMap<String, String>()
        val shown = list.take(5)
        val more = list.size - shown.size

        val head = if (c.callersOnly) {
            if (whenWord.isEmpty()) "Recent callers: " else "Callers $whenWord: "
        } else {
            if (whenWord.isEmpty()) "Your recent calls: " else "Your calls $whenWord: "
        }

        return head + shown.joinToString("; ") { describe(ctx, it, now, cache) } + "." +
            (if (more > 0) " And $more more." else "")
    }

    /** For "who called": the newest call that came in, from the phone's own log. Null if it can't be read. */
    fun lastCallerText(ctx: Context): String? {

        if (!allowed(ctx)) return null

        val now = System.currentTimeMillis()

        val r = rows(ctx, 60).firstOrNull { it.type == IN || it.type == MISSED || it.type == REJECTED }
            ?: return null

        val how = when (r.type) {
            MISSED -> "You missed it."
            REJECTED -> "You rejected it."
            else -> "You answered it."
        }

        return "The last call was from " + labelOf(ctx, r, HashMap()) + ", " + CallParse.ago(r.at, now) + ". " + how
    }

    // ---------------------------------------------------------------
    // Calling back
    // ---------------------------------------------------------------

    private fun dial(ctx: Context, t: Target): String {

        if (t.number.isBlank()) return "That number is hidden, so I can't call it."

        return try {
            val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val uri = Uri.fromParts("tel", t.number, null)
            val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL

            ctx.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            if (canCall) "Calling ${t.name}." else "Allow phone permission so I can call directly."
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Call back", e)
            "I couldn't place that call."
        }
    }

    // Asks "Call back X?" (or calls at once if you turned the question off).
    private fun offerCall(ctx: Context, r: Row, again: Boolean, now: Long): String {

        if (r.number.isBlank()) return "That number is hidden, so I can't call it."

        val t = Target(labelOf(ctx, r, HashMap()), r.number)

        if (Brain.setting("confirm actions", "on") == "off") return dial(ctx, t)

        pending = Pend(t, again, now)

        return if (again) "Call ${t.name} again? Say yes or no." else "Call back ${t.name}? Say yes or no."
    }

    private fun callBack(ctx: Context, c: CallParse.Cmd.CallBack, now: Long): String? {

        val all = rows(ctx, 200)

        val row: Row? =
            if (c.pronoun) {
                // "call him back" only means the person who just called.
                missed(all, now - 3 * HOUR_MS, true).firstOrNull()
            } else {
                missed(all, now - 3 * DAY_MS, true).firstOrNull()
                    ?: all.firstOrNull { it.type == IN || it.type == MISSED }
            }

        if (row == null) return if (c.pronoun) null else "I don't see a recent call to return."

        return offerCall(ctx, row, false, now)
    }

    private fun redial(ctx: Context, now: Long): String {

        val last = rows(ctx, 100).firstOrNull { it.type == OUT && it.number.isNotBlank() }
            ?: return "I don't see any numbers you've called."

        return offerCall(ctx, last, true, now)
    }

    // ---------------------------------------------------------------
    // The last call with someone
    // ---------------------------------------------------------------

    private fun lastLine(r: Row, name: String, now: Long): String {

        val ago = CallParse.ago(r.at, now)

        return when (r.type) {
            OUT ->
                if (r.seconds > 0) "You last called $name $ago, and you talked for ${talk(r.seconds)}."
                else "You last called $name $ago, but it wasn't answered."
            MISSED -> "$name last called you $ago. You missed it."
            REJECTED -> "$name last called you $ago. You rejected it."
            IN ->
                if (r.seconds > 0) "$name last called you $ago, and you talked for ${talk(r.seconds)}."
                else "$name last called you $ago."
            else -> "Your last call with $name was $ago."
        }
    }

    private fun lastAnswer(ctx: Context, c: CallParse.Cmd.Last, now: Long): String? {

        if (!allowed(ctx)) return if (c.personal) NEED else null

        val all = rows(ctx, 500)

        if (c.who.isBlank()) {
            val r = when (c.dir) {
                CallParse.Dir.IN -> all.firstOrNull { it.type == IN || it.type == MISSED || it.type == REJECTED }
                CallParse.Dir.OUT -> all.firstOrNull { it.type == OUT }
                CallParse.Dir.ANY -> all.firstOrNull()
            } ?: return "I don't see any calls like that in your call log."

            return lastLine(r, labelOf(ctx, r, HashMap()), now)
        }

        val people = ContactBook.find(ctx, c.who).filter { it.score >= 50 }

        if (people.isEmpty()) return if (c.personal) "I can't find ${c.who} in your contacts." else null

        val amb = ContactBook.ambiguous(people)
        val chosen = if (amb.isNotEmpty()) amb else listOf(people[0])

        val keys = HashSet<String>()
        val nameKeys = HashSet<String>()

        for (p in chosen) {
            nameKeys.add(p.name.lowercase())
            for (e in p.entries) {
                val d = ContactBook.digits(e.number)
                if (d.isNotEmpty()) keys.add(d)
            }
        }

        val mine = all.filter { r ->
            (r.number.isNotBlank() && ContactBook.digits(r.number) in keys) ||
                (r.name.isNotBlank() && r.name.lowercase() in nameKeys)
        }

        val wanted = when (c.dir) {
            CallParse.Dir.IN -> mine.filter { it.type == IN || it.type == MISSED || it.type == REJECTED }
            CallParse.Dir.OUT -> mine.filter { it.type == OUT }
            CallParse.Dir.ANY -> mine
        }

        val r = wanted.firstOrNull()

        if (r == null) {
            val who = chosen[0].name
            return when (c.dir) {
                CallParse.Dir.IN -> "I don't see any calls from $who in your call log."
                CallParse.Dir.OUT -> "I don't see any calls to $who in your call log."
                CallParse.Dir.ANY -> "I don't see any calls with $who in your call log."
            }
        }

        // Which of the matching people owns this call?
        val k = ContactBook.digits(r.number)
        val owner = chosen.firstOrNull { p -> p.entries.any { ContactBook.digits(it.number) == k } }

        return lastLine(r, owner?.name ?: chosen[0].name, now)
    }

    // ---------------------------------------------------------------
    // A missed call she offers to return (called by Proactive.kt)
    // ---------------------------------------------------------------

    /**
     * "You missed a call from Ravi Kumar, 5 minutes ago. Shall I call back?" once per new missed
     * call you have not returned, or null. Your "yes" is read by handle().
     */
    fun missNudge(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {

        if (Brain.setting("offer callback", "on") == "off") return null
        if (!allowed(ctx)) return null

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("seen", 0L)

        // First time: only calls from now on are news.
        if (seen == 0L) {
            p.edit().putLong("seen", nowMs).apply()
            return null
        }

        val all = rows(ctx, 80)
        val fresh = missed(all, maxOf(seen + 1, nowMs - 6 * HOUR_MS), true)

        if (fresh.isEmpty()) return null

        val latest = fresh[0]

        p.edit().putLong("seen", latest.at).apply()

        val name = labelOf(ctx, latest, HashMap())
        val ago = CallParse.ago(latest.at, nowMs)

        if (latest.number.isBlank()) return "You missed a call from $name, $ago."

        pending = Pend(Target(name, latest.number), false, nowMs)

        return if (fresh.size == 1) {
            "You missed a call from $name, $ago. Shall I call back?"
        } else {
            "You missed ${fresh.size} calls. The latest was from $name, $ago. Shall I call back?"
        }
    }

    // ---------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------

    /** True while she has asked "Call back X?" and is waiting for your answer. */
    fun wantsReply(): Boolean {
        val p = pending ?: return false
        return System.currentTimeMillis() - p.at < PENDING_MS
    }

    /** Is [raw] a call-history question, or the answer to "Call back X?" */
    fun understands(ctx: Context, raw: String): Boolean {
        if (wantsReply()) return true
        return CallParse.parse(raw) != null
    }

    private fun run(ctx: Context, c: CallParse.Cmd): String? {

        val now = System.currentTimeMillis()

        if (c is CallParse.Cmd.Last) return lastAnswer(ctx, c, now)

        if (!allowed(ctx)) return if (c is CallParse.Cmd.CallBack && c.pronoun) null else NEED

        return when (c) {
            is CallParse.Cmd.Missed -> missedAnswer(ctx, c, now)
            is CallParse.Cmd.Recent -> recentAnswer(ctx, c, now)
            is CallParse.Cmd.CallBack -> callBack(ctx, c, now)
            CallParse.Cmd.Redial -> redial(ctx, now)
            is CallParse.Cmd.Last -> lastAnswer(ctx, c, now)
        }
    }

    /** Null if [raw] is not about call history. Otherwise what she should say. */
    fun handle(ctx: Context, raw: String): String? {

        Brain.setup(ctx)

        val p = pending

        if (p != null) {
            pending = null
            if (System.currentTimeMillis() - p.at < PENDING_MS) {
                val t = CallParse.norm(raw)
                if (YES.containsMatchIn(t)) return dial(ctx, p.target)
                if (NO.containsMatchIn(t)) return "Okay."
            }
        }

        val cmd = CallParse.parse(raw) ?: return null

        return try {
            run(ctx, cmd)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Call history", e)
            "I couldn't read your call log just now."
        }
    }
}
