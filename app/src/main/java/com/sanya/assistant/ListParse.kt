package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * ListParse: which spoken phrases are about your lists and tasks. No Android calls, so it is unit-tested.
 *
 *   add milk and eggs to my shopping list          -> Add (shopping, [milk, eggs])
 *   add call the bank to my list by friday         -> Add (to-do, [call the bank], due friday)
 *   add task submit report due tomorrow            -> Add (to-do, [submit report], due tomorrow)
 *   read my shopping list / what's on my list      -> Read
 *   what's due today / what's overdue              -> Due
 *   mark milk as done / i bought the eggs          -> Done (only ours if it names something on a list)
 *   remove milk from my shopping list              -> Remove
 *   clear my shopping list                         -> Clear (she asks first)
 *   create a list called packing / delete my packing list
 *   what lists do i have                           -> Show
 *
 * A list is known by a key: lower case letters, digits and spaces. The to-do list has the key "todo".
 */
object ListParse {

    const val DEFAULT = "todo"

    sealed class Cmd {
        data class Add(val list: String, val items: List<String>, val due: LocalDate?) : Cmd()
        data class Read(val list: String) : Cmd()

        /** span: today (and overdue), tomorrow, this week, next week, this month, overdue. */
        data class Due(val span: String) : Cmd()

        /** list null: look on every list. value true = finished, false = put back. */
        data class Done(val text: String, val list: String?, val value: Boolean) : Cmd()

        data class Remove(val text: String, val list: String) : Cmd()
        data class Clear(val list: String) : Cmd()
        data class ClearDone(val list: String?) : Cmd()
        data class Create(val list: String) : Cmd()
        data class Delete(val list: String) : Cmd()
        data class Say(val text: String) : Cmd()
        object Show : Cmd()
    }

    /** Lower case, polite filler removed. Commas count as "and", so "milk, eggs" is two things. */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw.replace(",", " and "), emptyList(), false).joinToString(" ")

    private val DEFAULT_NAMES =
        setOf("", "todo", "to do", "task", "tasks", "my", "the", "our", "main", "default", "list", "lists")

    private val PERIODS = setOf("daily", "weekly", "monthly", "yearly")

    /** "my shopping list" -> "shopping", "to-do" / "tasks" / nothing -> "todo". */
    fun key(name: String?): String {
        val n = Brain.clean(name ?: "")
        val k = n.removePrefix("my ").removePrefix("the ").removePrefix("our ").trim()
        return if (k in DEFAULT_NAMES) DEFAULT else k
    }

    // ---------------------------------------------------------------
    // Pieces
    // ---------------------------------------------------------------

    // "my shopping list", "the packing list", "my tasks", "my to-do list": group 1 is the name, if any.
    private const val TARGET = """(?:my |the |our )?(?:(\S+(?: \S+){0,2}) )?(?:list|tasks?|todo|to-do|to do)"""

    private const val RLEAD =
        """(?:what(?:'s| is| are)(?: the items)?(?: (?:on|in))?|what do i have (?:on|in)|read(?: me)?(?: out)?(?: all)?(?: of)?|show(?: me)?|tell me|list|check|display|give me|go through|open|whats on)"""

    // "... from my shopping list": group 1 is the list name, if any.
    private const val FROM =
        """(?: (?:from|on|in|off of|off) (?:my |the |our )?(?:(\S+(?: \S+){0,2}) )?(?:list|tasks?|todo|to-do|to do))?"""

    private const val DSPAN = "today|tonight|tomorrow|this week|next week|this month|soon"

    private val ADD_TO = Regex(
        """^(?:add|put|append|include) (.+) (?:to|on|in|into) $TARGET(?: list)?(?: (.+))?$"""
    )
    private val ADD_TASK = Regex(
        """^(?:add|create|make|new)(?: a| an)?(?: new)? (?:task|todo|to-do|to do)(?: called| named| to| that| for)? (.+)$"""
    )

    private val SHOW = Regex(
        """^(?:(?:what|which) lists do i have|(?:show|read|list|tell me|give me)(?: me)?(?: all)?(?: of)?(?: my| the)? lists|my lists|how many lists do i have)$"""
    )

    private val CREATE = Regex(
        """^(?:create|make|start|begin|set up|new)(?: me)?(?: a| an| the)?(?: new)? (?:(\S+(?: \S+){0,2}) )?list(?:(?: called| named| for| titled)? (.+))?$"""
    )
    private val DELETE_A = Regex(
        """^(?:delete|remove|scrap|drop|get rid of) (?:my |the |our )?(\S+(?: \S+){0,2}) list$"""
    )
    private val DELETE_B = Regex(
        """^(?:delete|remove|scrap|drop|get rid of) (?:the |my )?list (?:called |named )?(.+)$"""
    )

    private val CLEAR_DONE_A = Regex(
        """^(?:clear|remove|delete|clean up|tidy up)(?: all)?(?: the)? (?:done|completed|finished|checked|ticked)(?: items| tasks| things)?$FROM$"""
    )
    private val CLEAR_DONE_B = Regex(
        """^(?:clean up|tidy up|tidy) (?:my |the |our )?(?:(\S+(?: \S+){0,2}) )?(?:list|tasks?|todo|to-do|to do)$"""
    )
    private val CLEAR = Regex(
        """^(?:clear|empty|wipe|reset) (?:out )?(?:my |the |our )?(?:(\S+(?: \S+){0,2}) )?(?:list|tasks?|todo|to-do|to do)(?: list)?$"""
    )

    private val REMOVE = Regex(
        """^(?:remove|delete|take|scratch|drop) (.+?) (?:from|off of|off|out of) (?:my |the |our )?(?:(\S+(?: \S+){0,2}) )?(?:list|tasks?|todo|to-do|to do)$"""
    )

    private val DUE_E = Regex(
        """^(?:what(?:'s| is| are)|show|read|tell me|list|any|do i have any)(?: me)?(?: my| the| any)* overdue(?: tasks| things| items)?(?: do i have)?$"""
    )
    private val DUE_A = Regex(
        """^what(?:'s| is| are)?(?: the)?(?: tasks| things| items)?(?: that)?(?: are| is)? due(?: ($DSPAN))?$"""
    )
    private val DUE_B = Regex(
        """^(?:what|which) (?:tasks|things|items) (?:do i have|have i got)(?: due)?(?: ($DSPAN))?$"""
    )
    private val DUE_C = Regex("""^what do i (?:have|need) to do(?: ($DSPAN))?$""")
    private val DUE_D = Regex(
        """^$RLEAD (?:my |the |our )?(?:tasks?|todo|to-do|to do)(?: list)? ($DSPAN)$"""
    )

    private val READ = Regex("""^$RLEAD $TARGET(?: list)?(?: items)?$""")
    private val READ_BARE = Regex("""^(?:my|the|our) (\S+(?: \S+){0,2}) list$""")

    // Putting something back must be tried before finishing it ("not done" ends in "done").
    private val UNDONE_A = Regex(
        """^(?:mark|put) (.+?)(?: as| to be)? (?:not done|undone|incomplete|not finished|not completed|pending|not bought|unchecked)$FROM$"""
    )
    private val UNDONE_B = Regex("""^(?:unmark|untick|uncheck|reopen) (.+?)$FROM$""")
    private val UNDONE_C = Regex(
        """^(?:the )?(.+?) (?:is |are )?(?:not done|not finished|not completed|not bought|undone)$FROM$"""
    )

    private val DONE_A = Regex(
        """^(?:mark|tick|check|cross|strike)(?: off| out)? (.+?)(?: as| to be)? (?:done|complete|completed|finished|bought|ticked|checked|crossed off)$FROM$"""
    )
    private val DONE_B = Regex("""^(?:tick|check|cross|strike)(?: off| out) (.+?)$FROM$""")
    private val DONE_C = Regex(
        """^(?:i )?(?:have |just |already )?(?:bought|got|picked up|purchased) (?:the |my |some |a |an )?(.+?)$FROM$"""
    )
    private val DONE_D = Regex(
        """^(?:i )?(?:have |just |already )?(?:finished|completed|did|done) (?:the |my |with )?(.+?)$FROM$"""
    )
    private val DONE_E = Regex("""^(?:complete|finish) (?:the |my )?(.+?)$FROM$""")
    private val DONE_F = Regex(
        """^(?:the |my )?(.+?) (?:is |are |has been |have been )?(?:done|complete|completed|finished|bought|ticked off|checked off)$FROM$"""
    )

    private val UNDONE = listOf(UNDONE_A, UNDONE_B, UNDONE_C)
    private val DONE = listOf(DONE_A, DONE_B, DONE_C, DONE_D, DONE_E, DONE_F)

    private val PRONOUNS = setOf("it", "that", "this", "them", "those", "these")

    // "bought tea for 20" is money she should log, not something to tick off a list.
    private val MONEYISH = Regex("""\b(?:for|at|rs|inr|rupees?|bucks)\b.*\d|\d.*\b(?:rs|inr|rupees?|bucks)\b""")

    private val LEAD_JUNK = listOf("then ", "also ", "to ", "some ", "an ", "a ")

    private fun clean(s: String): String {
        var t = s.trim()
        var again = true
        while (again) {
            again = false
            for (p in LEAD_JUNK) {
                if (t.startsWith(p) && t.length > p.length) {
                    t = t.substring(p.length).trim()
                    again = true
                }
            }
        }
        return t
    }

    private fun splitItems(text: String): List<String> =
        text.split(Regex("""\s+and\s+|\s+&\s+"""))
            .map { clean(it) }
            .filter { it.isNotEmpty() }

    private fun listOrNull(group: String): String? = if (group.isEmpty()) null else key(group)

    private fun dspan(w: String): String = when (w) {
        "", "tonight" -> "today"
        "soon" -> "this week"
        else -> w
    }

    // ---------------------------------------------------------------
    // Due dates
    // ---------------------------------------------------------------

    private val IN_N = Regex(
        """^in (\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fourteen) (day|days|week|weeks)$"""
    )
    private val ORD = Regex("""^(?:the )?(\d{1,2})(?:st|nd|rd|th)$""")

    private val PREPS = listOf("due ", "by ", "before ", "on ", "for ", "until ", "till ", "at ")

    private fun stripPrep(s: String): String {
        val t = s.trim()
        for (p in PREPS) if (t.startsWith(p)) return t.substring(p.length).trim()
        return t
    }

    /** "tomorrow", "friday", "next friday", "in 3 days", "12 march", "the 15th" -> a date, or null. */
    fun dueFrom(raw: String, today: LocalDate): LocalDate? {

        val p = raw.trim()
        if (p.isEmpty()) return null

        if (p == "tonight") return today
        if (p == "next week") return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (p == "this weekend") return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

        AgendaParse.dayOf(p, today)?.let { return it }

        IN_N.matchEntire(p)?.let { m ->
            val w = m.groupValues[1]
            val n = if (w == "a" || w == "an") 1 else (Num.parse(w)?.toInt() ?: return null)
            val days = n * (if (m.groupValues[2].startsWith("week")) 7 else 1)
            return if (days in 1..366) today.plusDays(days.toLong()) else null
        }

        ORD.matchEntire(p)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day !in 1..31) return null
            var d = today
            for (k in 0..62) {
                if (d.dayOfMonth == day) return d
                d = d.plusDays(1)
            }
            return null
        }

        Mind.parseDM(p)?.let { dm ->
            for (y in today.year..today.year + 8) {
                val d = try {
                    LocalDate.of(y, dm.second, dm.first)
                } catch (e: Exception) {
                    null
                }
                if (d != null && !d.isBefore(today)) return d
            }
            return null
        }

        return null
    }

    /** "call the bank by friday" -> ("call the bank", friday). Nothing readable at the end: (text, null). */
    fun splitDue(text: String, today: LocalDate): Pair<String, LocalDate?> {

        val words = text.split(" ").filter { it.isNotEmpty() }

        for (k in minOf(4, words.size - 1) downTo 1) {
            val tail = words.subList(words.size - k, words.size).joinToString(" ")
            val d = dueFrom(stripPrep(tail), today) ?: continue
            return Pair(words.subList(0, words.size - k).joinToString(" "), d)
        }

        return Pair(text, null)
    }

    private fun makeAdd(list: String, body: String, tail: String, today: LocalDate): Cmd? {

        var due: LocalDate? = null
        var text = body

        if (tail.isNotEmpty()) {
            // Something after "list" must be a date, or this is not an "add" at all.
            due = dueFrom(stripPrep(tail), today) ?: return null
        }

        if (due == null) {
            val s = splitDue(body, today)
            text = s.first
            due = s.second
        }

        val items = splitItems(text)

        if (items.isEmpty()) return null

        // "add 45 minutes to my japanese list" is study time, and "add it" is not an item.
        if (items.all { Num.parseDuration(it) != null }) return null
        if (items.all { it in PRONOUNS }) return null

        return Cmd.Add(list, items, due)
    }

    // ---------------------------------------------------------------
    // Reading a sentence
    // ---------------------------------------------------------------

    /** Is [raw] about lists or tasks? Null if not. [today] is for testing. */
    fun parse(raw: String, today: LocalDate = LocalDate.now()): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (SHOW.matches(t)) return Cmd.Show

        CREATE.matchEntire(t)?.let { m ->
            val first = m.groupValues[1]
            val second = m.groupValues[2].removePrefix("of ").trim()
            val name = if (second.isNotEmpty()) second else first
            if (name.isEmpty()) {
                return Cmd.Say("What should I call the list? Say, for example: create a list called packing.")
            }
            val k = key(name)
            if (k in PERIODS) return null
            return Cmd.Create(k)
        }

        CLEAR_DONE_A.matchEntire(t)?.let { return Cmd.ClearDone(listOrNull(it.groupValues[1])) }
        CLEAR_DONE_B.matchEntire(t)?.let { return Cmd.ClearDone(listOrNull(it.groupValues[1])) }

        CLEAR.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[1])
            if (k in PERIODS) return null
            return Cmd.Clear(k)
        }

        REMOVE.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[2])
            if (k in PERIODS) return null
            return Cmd.Remove(clean(m.groupValues[1]), k)
        }

        DELETE_A.matchEntire(t)?.let { m ->
            val name = m.groupValues[1]
            val bad = name.split(" ").any { it == "from" || it == "off" || it == "to" || it == "on" || it == "in" }
            if (!bad) {
                val k = key(name)
                if (k in PERIODS) return null
                return Cmd.Delete(k)
            }
        }
        DELETE_B.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[1])
            if (k in PERIODS) return null
            return Cmd.Delete(k)
        }

        // ---- what is due ----

        DUE_E.matchEntire(t)?.let { return Cmd.Due("overdue") }
        DUE_A.matchEntire(t)?.let { return Cmd.Due(dspan(it.groupValues[1])) }
        DUE_B.matchEntire(t)?.let { return Cmd.Due(dspan(it.groupValues[1])) }
        DUE_C.matchEntire(t)?.let { return Cmd.Due(dspan(it.groupValues[1])) }
        DUE_D.matchEntire(t)?.let { return Cmd.Due(dspan(it.groupValues[1])) }

        // ---- reading a list ----

        READ.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[1])
            if (k in PERIODS) return null
            return Cmd.Read(k)
        }
        READ_BARE.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[1])
            if (k in PERIODS) return null
            return Cmd.Read(k)
        }

        // ---- finishing things (the caller checks that the name is really on a list) ----

        for (rx in UNDONE) {
            val m = rx.matchEntire(t) ?: continue
            return Cmd.Done(clean(m.groupValues[1]), listOrNull(m.groupValues[2]), false)
        }

        for (rx in DONE) {
            val m = rx.matchEntire(t) ?: continue
            val text = clean(m.groupValues[1])
            if (MONEYISH.containsMatchIn(text)) continue
            return Cmd.Done(text, listOrNull(m.groupValues[2]), true)
        }

        // ---- adding ----

        ADD_TASK.matchEntire(t)?.let { m ->
            val body = m.groupValues[1]
            if (body == "list" || body == "lists") return null
            return makeAdd(DEFAULT, body, "", today)
        }

        ADD_TO.matchEntire(t)?.let { m ->
            val k = key(m.groupValues[2])
            if (k in PERIODS) return null
            return makeAdd(k, m.groupValues[1], m.groupValues[3], today)
        }

        return null
    }
}
