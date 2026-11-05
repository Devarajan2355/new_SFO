package com.sanya.assistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Lists: named lists (shopping, packing ...) and tasks with due dates, all by voice.
 *
 *   add milk and eggs to my shopping list          read my shopping list
 *   add call the bank to my list by friday         what's due today / what's overdue
 *   mark milk as done / i bought the eggs          remove milk from my shopping list
 *   clear my shopping list                         create a list called packing
 *
 * Kept in files/lists.txt (on the phone only, and in "Back up everything"), one line each:
 *     L <tab> list key                                         a list that exists (even when empty)
 *     I <tab> id <tab> list <tab> text <tab> due <tab> done <tab> created <tab> done at
 * The to-do list is the list with the key "todo". Anything found in the Brain file's old
 * "todo | ..." lines (the To-do section of the Brain editor) is moved here the next time you use a list.
 *
 * Clearing a list or deleting one asks first ("Say yes or no"), unless you turned that off with
 * setting | confirm actions = off.
 */
object Lists {

    data class Entry(
        val id: Long,
        val list: String,
        val text: String,
        val due: LocalDate?,
        val done: Boolean,
        val created: Long,
        val doneAt: Long
    )

    private const val FILE = "lists.txt"
    private const val PENDING_MS = 20_000L
    private const val SPEAK_MAX = 8

    private val items = ArrayList<Entry>()
    private val names = ArrayList<String>()
    private var loaded = false

    private sealed class Pend(val at: Long) {
        class Clear(val list: String, t: Long) : Pend(t)
        class Delete(val list: String, t: Long) : Pend(t)

        /** Several items fit: action is "done", "undone" or "remove"; ids are the candidates. */
        class Pick(val action: String, val ids: List<Long>, t: Long) : Pend(t)
    }

    @Volatile private var pending: Pend? = null

    private val YES = Regex(
        """^(?:yes|yeah|yep|yup|ya|ok|okay|sure|correct|right|do it|please do|go ahead|confirm)\b"""
    )
    private val NO = Regex(
        """^(?:no|nope|nah|never mind|nevermind|cancel|forget it|skip|stop|dont|do not)\b"""
    )

    // ---------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context) {

        if (loaded) return
        loaded = true

        items.clear()
        names.clear()

        try {
            val f = file(ctx)

            if (f.exists()) {
                for (line in f.readLines()) {

                    val p = line.split("\t")

                    if (p[0] == "L" && p.size >= 2 && p[1].isNotEmpty()) {

                        if (p[1] !in names) names.add(p[1])

                    } else if (p[0] == "I" && p.size >= 8) {

                        val id = p[1].toLongOrNull()
                        val created = p[6].toLongOrNull()
                        val doneAt = p[7].toLongOrNull()

                        if (id != null && created != null && doneAt != null && p[3].isNotEmpty()) {
                            val due = if (p[4].isEmpty()) {
                                null
                            } else {
                                try {
                                    LocalDate.parse(p[4])
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            items.add(Entry(id, p[2], p[3], due, p[5] == "1", created, doneAt))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Reading lists", e)
        }
    }

    // Written to a temp file and renamed, so a kill in the middle cannot damage the lists.
    private fun persist(ctx: Context) {

        val sb = StringBuilder()

        for (n in names) sb.append("L\t").append(n).append('\n')

        for (e in items) {
            sb.append("I\t").append(e.id)
                .append('\t').append(e.list)
                .append('\t').append(e.text)
                .append('\t').append(e.due?.toString() ?: "")
                .append('\t').append(if (e.done) "1" else "0")
                .append('\t').append(e.created)
                .append('\t').append(e.doneAt)
                .append('\n')
        }

        val text = sb.toString()
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")

        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Saving lists", e)
        }
    }

    /** After a restore: read the file again next time. */
    @Synchronized
    fun reload() {
        loaded = false
        items.clear()
        names.clear()
        pending = null
    }

    private fun clip(s: String): String = s.replace('\t', ' ').replace('\n', ' ').trim().take(200)

    private fun exists(key: String): Boolean = key in names || items.any { it.list == key }

    private fun ensureName(key: String) {
        if (key !in names) names.add(key)
    }

    private fun nextId(): Long {
        var id = System.currentTimeMillis()
        val top = items.maxOfOrNull { it.id } ?: 0L
        if (id <= top) id = top + 1
        return id
    }

    private fun addItem(key: String, text: String, due: LocalDate?) {
        items.add(Entry(nextId(), key, text, due, false, System.currentTimeMillis(), 0L))
    }

    // Things you typed in the Brain editor's To-do section (or an older command wrote) join the to-do list.
    private fun absorbLegacy(ctx: Context) {

        val legacy = Brain.entries("todo")
        if (legacy.isEmpty()) return

        ensureName(ListParse.DEFAULT)

        for (e in legacy) {
            val text = clip(e.left)
            if (text.isNotEmpty()) addItem(ListParse.DEFAULT, text, null)
            Brain.replaceLine(e.line, null)
        }

        persist(ctx)
    }

    private fun begin(ctx: Context) {
        Brain.setup(ctx)
        load(ctx)
        absorbLegacy(ctx)
    }

    // ---------------------------------------------------------------
    // Saying things
    // ---------------------------------------------------------------

    /** "to-do list", "shopping list" */
    fun label(key: String): String =
        if (key == ListParse.DEFAULT) "to-do list" else key.removeSuffix(" list") + " list"

    private fun joinSpoken(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> parts[0] + " and " + parts[1]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun joinOr(parts: List<String>): String = Choose.joinOr(parts)

    private fun dueWord(d: LocalDate, today: LocalDate): String {

        val diff = ChronoUnit.DAYS.between(today, d)

        return when {
            diff == 0L -> "today"
            diff == 1L -> "tomorrow"
            diff == -1L -> "yesterday"
            diff < -1L -> "${-diff} days ago"
            diff in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> d.dayOfMonth.toString() + " " + d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
    }

    // "call the bank, due Friday" / "submit report, overdue"
    private fun phrase(e: Entry, today: LocalDate): String {

        val d = e.due ?: return e.text

        return if (d.isBefore(today)) e.text + ", overdue" else e.text + ", due " + dueWord(d, today)
    }

    private fun overdueBy(e: Entry, today: LocalDate): String {

        val d = e.due ?: return e.text
        val n = ChronoUnit.DAYS.between(d, today)

        return e.text + (if (n <= 1L) ", due yesterday" else ", due $n days ago")
    }

    // Soonest due first, things without a date last, then in the order you added them.
    private fun ordered(list: List<Entry>): List<Entry> =
        list.sortedWith(compareBy<Entry>({ it.due == null }, { it.due }, { it.created }))

    private fun openIn(key: String): List<Entry> = ordered(items.filter { it.list == key && !it.done })

    // ---------------------------------------------------------------
    // Finding an item by what you said
    // ---------------------------------------------------------------

    private fun matches(pool: List<Entry>, query: String): List<Entry> {

        val q = Brain.clean(query)
        if (q.length < 2) return emptyList()

        val exact = pool.filter { Brain.clean(it.text) == q }
        if (exact.isNotEmpty()) return exact

        val inside = pool.filter {
            val t = Brain.clean(it.text)
            t.contains(q) || (t.length >= 3 && q.contains(t))
        }
        if (inside.isNotEmpty()) return inside

        // Spelt or heard a little differently.
        val scored = pool.map {
            val t = Brain.clean(it.text)
            var s = Mind.similarity(q, t)
            if (Mind.soundScore(q, t) >= 55) s = maxOf(s, 0.7)
            Pair(it, s)
        }

        val best = scored.maxOfOrNull { it.second } ?: 0.0

        if (best < 0.6) return emptyList()

        return scored.filter { it.second >= best - 0.001 }.map { it.first }
    }

    private fun candidates(text: String, list: String?, wantDone: Boolean): List<Entry> =
        matches(items.filter { it.done == wantDone && (list == null || it.list == list) }, text)

    private fun sameThing(found: List<Entry>): Boolean =
        found.map { it.list + "|" + Brain.clean(it.text) }.distinct().size <= 1

    private fun askWhich(action: String, found: List<Entry>): String {

        val shown = found.take(4)

        pending = Pend.Pick(action, shown.map { it.id }, System.currentTimeMillis())

        val manyLists = shown.map { it.list }.distinct().size > 1
        val said = shown.map { if (manyLists) it.text + " on your " + label(it.list) else it.text }

        return "I found " + joinOr(said) + ". Which one?"
    }

    private fun mark(e: Entry, value: Boolean) {
        val i = items.indexOfFirst { it.id == e.id }
        if (i >= 0) items[i] = e.copy(done = value, doneAt = if (value) System.currentTimeMillis() else 0L)
    }

    private fun afterDone(key: String, text: String, value: Boolean): String {

        val lab = label(key)

        if (!value) return "Put $text back on your $lab."

        val left = items.count { it.list == key && !it.done }

        return if (left == 0) {
            "Marked $text as done. That's everything on your $lab."
        } else {
            "Marked $text as done. $left left on your $lab."
        }
    }

    // ---------------------------------------------------------------
    // The commands
    // ---------------------------------------------------------------

    private fun doAdd(ctx: Context, c: ListParse.Cmd.Add): String {

        val key = c.list
        val today = LocalDate.now()
        val existed = exists(key)
        val added = ArrayList<String>()
        val dupes = ArrayList<String>()

        for (raw in c.items) {

            val text = clip(raw)
            if (text.isEmpty()) continue

            val same = Brain.clean(text)
            val dup = items.any { it.list == key && !it.done && Brain.clean(it.text) == same }

            if (dup) {
                dupes.add(text)
            } else {
                addItem(key, text, c.due)
                added.add(text)
            }
        }

        ensureName(key)
        persist(ctx)

        val lab = label(key)

        if (added.isEmpty()) {
            return if (dupes.isEmpty()) {
                "I didn't catch what to add."
            } else {
                joinSpoken(dupes) + (if (dupes.size == 1) " is" else " are") + " already on your $lab."
            }
        }

        val due = c.due
        val head = if (!existed && key != ListParse.DEFAULT) "Created your $lab. " else ""
        val dueText = if (due != null) ", due " + dueWord(due, today) else ""
        val extra =
            if (dupes.isEmpty()) "" else " " + joinSpoken(dupes) + (if (dupes.size == 1) " was" else " were") + " already there."

        return head + "Added " + joinSpoken(added) + " to your $lab" + dueText + "." + extra
    }

    private fun readText(key: String): String {

        val lab = label(key)

        if (!exists(key)) return "You don't have a $lab yet. Say, for example: add milk to my shopping list."

        val today = LocalDate.now()
        val open = openIn(key)
        val doneCount = items.count { it.list == key && it.done }

        if (open.isEmpty()) {
            return "Your $lab is empty." + (if (doneCount > 0) " $doneCount done." else "")
        }

        val shown = open.take(SPEAK_MAX)
        val head = if (open.size == 1) "Your $lab has one item: " else "Your $lab has ${open.size} items: "

        return head + joinSpoken(shown.map { phrase(it, today) }) + "." +
            (if (open.size > SPEAK_MAX) " And ${open.size - SPEAK_MAX} more." else "")
    }

    private fun inSpan(e: Entry, span: String, today: LocalDate): Boolean {

        val d = e.due ?: return false

        return when (span) {
            "overdue" -> d.isBefore(today)
            "today" -> !d.isAfter(today)
            "tomorrow" -> d == today.plusDays(1)
            "this week" -> !d.isBefore(today) && !d.isAfter(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
            "next week" -> {
                val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                !d.isBefore(monday) && !d.isAfter(monday.plusDays(6))
            }
            "this month" -> !d.isBefore(today) && !d.isAfter(today.withDayOfMonth(today.lengthOfMonth()))
            else -> false
        }
    }

    private fun dueText(span: String): String {

        val today = LocalDate.now()
        val open = ordered(items.filter { !it.done && it.due != null })
        val hit = open.filter { inSpan(it, span, today) }

        if (span == "overdue") {
            if (hit.isEmpty()) return "Nothing is overdue."
            return "You have ${hit.size} overdue " + (if (hit.size == 1) "item: " else "items: ") +
                joinSpoken(hit.take(SPEAK_MAX).map { overdueBy(it, today) }) + "."
        }

        if (span == "today") {

            val dueToday = hit.filter { it.due == today }
            val late = hit.filter { val d = it.due; d != null && d.isBefore(today) }

            if (dueToday.isEmpty() && late.isEmpty()) return "Nothing is due today."

            val sb = StringBuilder()

            if (dueToday.isNotEmpty()) {
                sb.append("Today you have ").append(dueToday.size)
                    .append(if (dueToday.size == 1) " thing due: " else " things due: ")
                    .append(joinSpoken(dueToday.take(SPEAK_MAX).map { it.text }))
                    .append('.')
            } else {
                sb.append("Nothing is due today.")
            }

            if (late.isNotEmpty()) {
                sb.append(" Overdue: ").append(joinSpoken(late.take(4).map { overdueBy(it, today) })).append('.')
            }

            return sb.toString()
        }

        if (hit.isEmpty()) return "Nothing is due $span."

        if (span == "tomorrow") {
            return "Tomorrow you have ${hit.size} " + (if (hit.size == 1) "thing" else "things") + " due: " +
                joinSpoken(hit.take(SPEAK_MAX).map { it.text }) + "."
        }

        return "Due $span: " + joinSpoken(hit.take(SPEAK_MAX).map { phrase(it, today) }) + "."
    }

    // Null when there is nothing by that name and you did not say which list: let the other rules try.
    private fun doDone(ctx: Context, c: ListParse.Cmd.Done): String? {

        val found = candidates(c.text, c.list, !c.value)

        if (found.isEmpty()) {
            val list = c.list ?: return null
            return "I couldn't find ${c.text} on your ${label(list)}."
        }

        if (!sameThing(found)) return askWhich(if (c.value) "done" else "undone", found)

        for (e in found) mark(e, c.value)
        persist(ctx)

        return afterDone(found[0].list, found[0].text, c.value)
    }

    private fun doRemove(ctx: Context, c: ListParse.Cmd.Remove): String {

        val lab = label(c.list)

        if (!exists(c.list)) return "You don't have a $lab yet."

        val found = matches(items.filter { it.list == c.list }, c.text)

        if (found.isEmpty()) return "I couldn't find ${c.text} on your $lab."

        if (!sameThing(found)) return askWhich("remove", found)

        items.removeAll { e -> found.any { it.id == e.id } }
        persist(ctx)

        return "Removed ${found[0].text} from your $lab."
    }

    private fun doClear(ctx: Context, key: String): String {
        items.removeAll { it.list == key }
        persist(ctx)
        return "Cleared your ${label(key)}."
    }

    private fun askClear(ctx: Context, key: String): String {

        if (!exists(key)) return "You don't have a ${label(key)} yet."

        if (items.none { it.list == key }) return "Your ${label(key)} is already empty."

        if (Brain.setting("confirm actions", "on") == "off") return doClear(ctx, key)

        pending = Pend.Clear(key, System.currentTimeMillis())

        return "Clear your whole ${label(key)}? Say yes or no."
    }

    private fun doDelete(ctx: Context, key: String): String {
        items.removeAll { it.list == key }
        names.remove(key)
        persist(ctx)
        return "Deleted your ${label(key)}."
    }

    private fun askDelete(ctx: Context, key: String): String {

        // The to-do list is always there: deleting it means emptying it.
        if (key == ListParse.DEFAULT) return askClear(ctx, key)

        if (!exists(key)) return "I couldn't find a list called $key."

        val n = items.count { it.list == key }

        if (n == 0 || Brain.setting("confirm actions", "on") == "off") return doDelete(ctx, key)

        pending = Pend.Delete(key, System.currentTimeMillis())

        return "Delete your ${label(key)} and its $n " + (if (n == 1) "item" else "items") + "? Say yes or no."
    }

    private fun doClearDone(ctx: Context, key: String?): String {

        val n = items.count { it.done && (key == null || it.list == key) }

        if (n == 0) {
            return if (key == null) "Nothing is marked done." else "Nothing is marked done on your ${label(key)}."
        }

        items.removeAll { it.done && (key == null || it.list == key) }
        persist(ctx)

        return "Cleared $n done " + (if (n == 1) "item" else "items") +
            (if (key == null) "." else " from your ${label(key)}.")
    }

    private fun doCreate(ctx: Context, key: String): String {

        val lab = label(key)

        if (key == ListParse.DEFAULT) {
            ensureName(key)
            persist(ctx)
            return "Your to-do list is ready. Say, for example: add call the bank to my list."
        }

        if (exists(key)) return "You already have a $lab."

        ensureName(key)
        persist(ctx)

        return "Created your $lab. Say, for example: add something to my $lab."
    }

    private fun showLists(): String {

        val keys = ArrayList<String>()

        for (n in names) if (n !in keys) keys.add(n)
        for (e in items) if (e.list !in keys) keys.add(e.list)

        if (keys.isEmpty()) return "You don't have any lists yet. Say, for example: add milk to my shopping list."

        val parts = keys.take(6).map { k ->
            val open = items.count { it.list == k && !it.done }
            (if (k == ListParse.DEFAULT) "to-do" else k) + " with " + open + (if (open == 1) " open item" else " open items")
        }

        return "You have ${keys.size} " + (if (keys.size == 1) "list: " else "lists: ") + joinSpoken(parts) + "."
    }

    private fun execute(ctx: Context, cmd: ListParse.Cmd): String? {

        begin(ctx)

        return when (cmd) {
            is ListParse.Cmd.Add -> doAdd(ctx, cmd)
            is ListParse.Cmd.Read -> readText(cmd.list)
            is ListParse.Cmd.Due -> dueText(cmd.span)
            is ListParse.Cmd.Done -> doDone(ctx, cmd)
            is ListParse.Cmd.Remove -> doRemove(ctx, cmd)
            is ListParse.Cmd.Clear -> askClear(ctx, cmd.list)
            is ListParse.Cmd.ClearDone -> doClearDone(ctx, cmd.list)
            is ListParse.Cmd.Create -> doCreate(ctx, cmd.list)
            is ListParse.Cmd.Delete -> askDelete(ctx, cmd.list)
            is ListParse.Cmd.Say -> cmd.text
            ListParse.Cmd.Show -> showLists()
        }
    }

    // ---------------------------------------------------------------
    // Your answer to a question
    // ---------------------------------------------------------------

    private fun pick(ctx: Context, p: Pend.Pick, raw: String): String? {

        val pool = p.ids.mapNotNull { id -> items.firstOrNull { it.id == id } }
        if (pool.isEmpty()) return null

        val options = pool.map { Choose.Option(it.text, it.id.toString(), Choose.words(it.text + " " + it.list)) }
        val choice = Choose.pick(options, raw) ?: return null
        val e = pool.firstOrNull { it.id.toString() == choice.key } ?: return null

        return when (p.action) {
            "remove" -> {
                items.removeAll { it.id == e.id }
                persist(ctx)
                "Removed ${e.text} from your ${label(e.list)}."
            }
            "undone" -> {
                mark(e, false)
                persist(ctx)
                afterDone(e.list, e.text, false)
            }
            else -> {
                mark(e, true)
                persist(ctx)
                afterDone(e.list, e.text, true)
            }
        }
    }

    private fun answer(ctx: Context, p: Pend, raw: String): String? {

        val t = ListParse.norm(raw)

        return when (p) {
            is Pend.Clear ->
                when {
                    YES.containsMatchIn(t) -> doClear(ctx, p.list)
                    NO.containsMatchIn(t) -> "Okay. I'll keep it."
                    else -> null
                }
            is Pend.Delete ->
                when {
                    YES.containsMatchIn(t) -> doDelete(ctx, p.list)
                    NO.containsMatchIn(t) -> "Okay. I'll keep it."
                    else -> null
                }
            is Pend.Pick ->
                if (NO.containsMatchIn(t)) "Okay." else pick(ctx, p, raw)
        }
    }

    // ---------------------------------------------------------------
    // For the rest of the app
    // ---------------------------------------------------------------

    /** True while she has asked something (yes or no? which one?) and is waiting for your answer. */
    fun wantsReply(): Boolean {
        val p = pending ?: return false
        return System.currentTimeMillis() - p.at < PENDING_MS
    }

    /** Is [raw] about lists, or the answer to a question she asked? */
    @Synchronized
    fun understands(ctx: Context, raw: String): Boolean {

        if (wantsReply()) return true

        val cmd = ListParse.parse(raw) ?: return false

        // "mark gym as done" is only ours if gym is on one of your lists.
        if (cmd is ListParse.Cmd.Done) {
            begin(ctx)
            return candidates(cmd.text, cmd.list, !cmd.value).isNotEmpty()
        }

        return true
    }

    /** Null if [raw] is not about lists. Otherwise what she should say. */
    @Synchronized
    fun handle(ctx: Context, raw: String): String? {

        val p = pending

        if (p != null) {
            pending = null
            if (System.currentTimeMillis() - p.at < PENDING_MS) {
                begin(ctx)
                val r = answer(ctx, p, raw)
                if (r != null) return r
            }
        }

        val cmd = ListParse.parse(raw) ?: return null

        return try {
            execute(ctx, cmd)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Lists", e)
            "I couldn't do that with your lists."
        }
    }

    private fun shortNames(list: List<Entry>): List<String> {
        val shown = list.take(3).map { it.text }
        return if (list.size > 3) shown + "${list.size - 3} more" else shown
    }

    /** One or two sentences for the morning brief and "what's my day", or "" if nothing is open. */
    @Synchronized
    fun briefLine(ctx: Context, today: LocalDate = LocalDate.now()): String {

        begin(ctx)

        val open = items.filter { !it.done }

        if (open.isEmpty()) return ""

        val dueToday = ordered(open.filter { it.due == today })
        val late = ordered(open.filter { val d = it.due; d != null && d.isBefore(today) })

        val sb = StringBuilder()

        sb.append("You have ").append(open.size)
            .append(if (open.size == 1) " open item" else " open items")
            .append(" on your lists.")

        if (dueToday.isNotEmpty()) sb.append(" Due today: ").append(joinSpoken(shortNames(dueToday))).append('.')
        if (late.isNotEmpty()) sb.append(" Overdue: ").append(joinSpoken(shortNames(late))).append('.')

        return sb.toString()
    }
}
