package com.sanya.assistant

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * ReminderParse: turns a spoken reminder into a schedule. No Android calls, so it is unit-tested.
 *
 *   remind me tomorrow at 9 to pay rent            -> Add (tomorrow 09:00, "pay rent")
 *   remind me to call ravi at 5 pm                 -> Add (today 17:00)
 *   remind me in an hour and a half to call mom    -> Add (now + 90 min)
 *   remind me every monday and wednesday at 7 pm to go to the gym
 *   remind me to pay rent                          -> NeedTime  ("When should I remind you?")
 *   remind me tomorrow at 9                        -> NeedText  ("What should I remind you about?")
 *
 * repeat is "" (once), "daily", "weekdays", "weekly:1,3" (ISO weekday numbers) or "monthly:5".
 * tod is the time of day in minutes after midnight (repeating reminders keep their local time).
 */
object ReminderParse {

    data class Spec(val at: LocalDateTime, val repeat: String, val tod: Int)

    sealed class Cmd {
        data class Add(val spec: Spec, val text: String) : Cmd()
        data class NeedTime(val text: String, val prompt: String = "When should I remind you?") : Cmd()
        data class NeedText(val spec: Spec) : Cmd()
        data class Say(val text: String) : Cmd()
        data class Cancel(val text: String) : Cmd()
        data class Snooze(val minutes: Int?) : Cmd()
        object Show : Cmd()
        object Next : Cmd()
        object CancelAll : Cmd()
    }

    private class Clock(val hour: Int, val minute: Int, val known: Boolean)

    // ---------------------------------------------------------------
    // Vocabulary
    // ---------------------------------------------------------------

    private val DAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY
    )

    private val PARTS = setOf("morning", "afternoon", "evening", "night")

    private val PART_TOD: Map<String, Int> = mapOf(
        "morning" to 8 * 60, "afternoon" to 15 * 60, "evening" to 18 * 60,
        "night" to 21 * 60, "tonight" to 21 * 60
    )

    private val PREP = setOf("on", "for", "by")

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"
    )

    private val LEAD_JUNK = setOf("to", "that", "about", "for", "and", "me", "also", "then")
    private val TRAIL_JUNK = setOf("to", "at", "on", "in", "for", "and", "by", "then", "also")

    private val NUM12 = Regex("""\d{1,2}""")
    private val ORD_SUFFIX = Regex("""(?:st|nd|rd|th)$""")

    private val PREFIXES = listOf(
        Regex("""^remind (?:me|us)\b\s*"""),
        Regex("""^(?:set|add|create|make|put|schedule|save)(?: me| us)?(?: a| an| the| my| another| new)* reminder\b\s*"""),
        Regex("""^reminder\b\s*""")
    )

    // ---------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------

    /** Lower case, polite filler removed, punctuation gone. */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw, emptyList(), false).joinToString(" ")

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** 540 -> "9 am", 1110 -> "6:30 pm" */
    fun clock12(m: Int): String {
        val h = m / 60
        val mi = m % 60
        val ap = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (mi == 0) "$h12 $ap" else "$h12:${two(mi)} $ap"
    }

    private fun dayName(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun joinNames(l: List<String>): String = when (l.size) {
        0 -> ""
        1 -> l[0]
        2 -> l[0] + " and " + l[1]
        else -> l.dropLast(1).joinToString(", ") + " and " + l.last()
    }

    /** Reminder text said back to you: "pay my rent" -> "pay your rent". */
    fun flip(t: String): String = t
        .replace(Regex("""\bmyself\b"""), "yourself")
        .replace(Regex("""\bmy\b"""), "your")
        .replace(Regex("""\bi'm\b"""), "you're")
        .replace(Regex("""\bi am\b"""), "you are")
        .replace(Regex("""\bi'll\b"""), "you'll")
        .replace(Regex("""\bi have\b"""), "you have")
        .replace(Regex("""\bi\b"""), "you")
        .replace(Regex("""\bmine\b"""), "yours")

    private fun trimText(w: List<String>): String {
        var l = w
        while (l.isNotEmpty() && l[0] in LEAD_JUNK) l = l.drop(1)
        while (l.isNotEmpty() && l.last() in TRAIL_JUNK) l = l.dropLast(1)
        return l.joinToString(" ")
    }

    /** What you said when she asked "What should I remind you about?" */
    fun cleanText(raw: String): String =
        trimText(norm(raw).split(" ").filter { it.isNotEmpty() })

    // ---------------------------------------------------------------
    // Saying a time
    // ---------------------------------------------------------------

    /**
     * "in 30 minutes", "today at 5 pm", "tomorrow at 9 am", "Monday at 6 pm", "12 March at 9 am",
     * "every day at 8 am", "every Monday and Wednesday at 7 pm".
     */
    fun whenText(at: LocalDateTime, repeat: String, now: LocalDateTime): String {

        val clock = clock12(at.hour * 60 + at.minute)

        when {
            repeat == "daily" -> return "every day at $clock"
            repeat == "weekdays" -> return "every weekday at $clock"
            repeat.startsWith("weekly:") -> {
                val days = repeat.removePrefix("weekly:").split(",")
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it in 1..7 }
                    .map { dayName(DayOfWeek.of(it)) }
                return "every " + joinNames(days) + " at " + clock
            }
            repeat.startsWith("monthly:") ->
                return "on day " + repeat.removePrefix("monthly:") + " of every month at " + clock
        }

        val secs = Duration.between(now, at).seconds

        if (secs in 0L..10800L) {
            val m = ((secs + 30) / 60).toInt()
            return if (m < 1) "in less than a minute" else "in " + Num.durText(m)
        }

        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())

        val day = when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days in 2L..6L -> dayName(at.dayOfWeek)
            else -> at.dayOfMonth.toString() + " " + MONTH_NAMES[at.monthValue - 1] +
                (if (at.year != now.year) " " + at.year else "")
        }

        return "$day at $clock"
    }

    // ---------------------------------------------------------------
    // Repeats
    // ---------------------------------------------------------------

    private fun matches(repeat: String, d: LocalDate): Boolean = when {
        repeat == "daily" -> true
        repeat == "weekdays" -> d.dayOfWeek.value in 1..5
        repeat.startsWith("weekly:") ->
            d.dayOfWeek.value in repeat.removePrefix("weekly:").split(",").mapNotNull { it.toIntOrNull() }
        repeat.startsWith("monthly:") -> {
            val dom = repeat.removePrefix("monthly:").toIntOrNull() ?: 0
            dom > 0 && d.dayOfMonth == minOf(dom, d.lengthOfMonth())
        }
        else -> false
    }

    /** The first time after [after] that a repeating reminder is due. */
    fun nextAfter(repeat: String, tod: Int, after: LocalDateTime): LocalDateTime? {

        val time = LocalTime.of((tod / 60).coerceIn(0, 23), (tod % 60).coerceIn(0, 59))
        var d = after.toLocalDate()

        for (k in 0..800) {
            if (matches(repeat, d)) {
                val c = LocalDateTime.of(d, time)
                if (c.isAfter(after)) return c
            }
            d = d.plusDays(1)
        }

        return null
    }

    // ---------------------------------------------------------------
    // Reading a spoken reminder
    // ---------------------------------------------------------------

    private val SNOOZE_A = Regex("""^snooze(?: it| that| this| the reminder)?(?: for| by)?(?: (.+))?$""")
    private val SNOOZE_B = Regex("""^remind me again(?: in (.+))?$""")
    private val SNOOZE_C = Regex("""^remind me later$""")

    private val SHOW_A = Regex(
        """^(?:what are|what is|what's|whats|list|show|read|read out|tell me|check|display|give me)(?: me)?(?: all)?(?: of)?(?: my| the| upcoming| pending| current)* reminders?(?: do i have| i have| are there| left| set)?$"""
    )
    private val SHOW_B = Regex("""^(?:what|which) reminders? (?:do i have|have i set|are set|are there|are pending|are left)$""")
    private val SHOW_C = Regex("""^(?:do i have|have i got|are there) any(?: upcoming| pending)? reminders?$""")
    private val SHOW_D = Regex("""^(?:my )?reminders?$""")
    private val NEXT = Regex("""^(?:what is|what's|whats|when is|when's) (?:my )?next reminder$""")

    private val CANCEL_ALL = Regex(
        """^(?:cancel|delete|remove|clear|erase|forget)(?: all| every)?(?: of)?(?: my| the)* reminders$"""
    )
    private val CANCEL_A = Regex(
        """^(?:cancel|delete|remove|forget|drop)(?: my| the| that| this| a)* reminder(?: (?:to |about |for |of |that )?(.+))?$"""
    )
    private val CANCEL_B = Regex("""^(?:cancel|delete|remove|forget|drop) (?:my |the |that )?(.+?) reminder$""")
    private val CANCEL_C = Regex("""^(?:cancel|delete|remove|forget) (.+?) from (?:my )?reminders$""")

    private fun snoozeCmd(g: String): Cmd {
        if (g.isEmpty()) return Cmd.Snooze(null)
        val m = Num.parseDuration(g) ?: g.toIntOrNull()?.takeIf { it in 1..1440 }
        return if (m == null) {
            Cmd.Say("I didn't catch how long. Say, for example: snooze for 10 minutes.")
        } else {
            Cmd.Snooze(m)
        }
    }

    private fun manage(t: String): Cmd? {

        SNOOZE_A.matchEntire(t)?.let { return snoozeCmd(it.groupValues[1]) }
        SNOOZE_B.matchEntire(t)?.let { return snoozeCmd(it.groupValues[1]) }
        if (SNOOZE_C.matches(t)) return Cmd.Snooze(null)

        if (NEXT.matches(t)) return Cmd.Next

        if (SHOW_A.matches(t) || SHOW_B.matches(t) || SHOW_C.matches(t) || SHOW_D.matches(t)) return Cmd.Show

        if (CANCEL_ALL.matches(t)) return Cmd.CancelAll

        CANCEL_A.matchEntire(t)?.let { return Cmd.Cancel(it.groupValues[1].trim()) }

        CANCEL_B.matchEntire(t)?.let { m ->
            val x = m.groupValues[1].trim()
            if (x.isNotEmpty() && x != "all" && x != "every") return Cmd.Cancel(x)
        }

        CANCEL_C.matchEntire(t)?.let { return Cmd.Cancel(it.groupValues[1].trim()) }

        return null
    }

    /** Is [raw] about reminders? Null if not. [now] and [defTod] (default time of day) are for testing. */
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now(), defTod: Int = 540): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        manage(t)?.let { return it }

        var body: String? = null

        for (rx in PREFIXES) {
            val m = rx.find(t)
            if (m != null) {
                body = t.substring(m.range.last + 1).trim()
                break
            }
        }

        val b = body ?: return null

        if (b.isEmpty()) {
            return Cmd.Say("Tell me what and when. For example: remind me tomorrow at 9 to pay rent.")
        }

        return build(b.split(" ").filter { it.isNotEmpty() }, now, defTod)
    }

    /** Only the time part of an answer like "tomorrow at 9" or "in 10 minutes". Null if there is none. */
    fun parseWhen(raw: String, now: LocalDateTime = LocalDateTime.now(), defTod: Int = 540): Spec? {
        val c = parse("remind me $raw to x", now, defTod)
        return when (c) {
            is Cmd.Add -> c.spec
            is Cmd.NeedText -> c.spec
            else -> null
        }
    }

    // ---------------------------------------------------------------
    // Pieces of a time
    // ---------------------------------------------------------------

    // (days, seconds) for "2 hours", "an hour and a half", "3 days", "a week", "30 seconds".
    private fun relative(p: String): Pair<Int, Int>? {

        Regex("""^(\d+|[a-z]+) (day|days|week|weeks)$""").matchEntire(p)?.let { m ->
            val w = m.groupValues[1]
            val n = if (w == "a" || w == "an") 1 else Num.parse(w)?.toInt() ?: return null
            val days = n * (if (m.groupValues[2].startsWith("week")) 7 else 1)
            return if (days in 1..366) Pair(days, 0) else null
        }

        Regex("""^(.+?) (?:seconds?|secs?)$""").matchEntire(p)?.let { m ->
            val s = Num.parse(m.groupValues[1])?.toInt() ?: return null
            return if (s in 1..86400) Pair(0, s) else null
        }

        return Num.parseDuration(p)?.let { Pair(0, it * 60) }
    }

    private fun hourWord(t: String): Int? = Mind.numWords(t).toIntOrNull()?.takeIf { it in 1..12 }

    private fun isNum(t: String): Boolean = NUM12.matches(t) || NUM12.matches(Mind.numWords(t))

    // hour is as spoken (1..12) unless known.
    private fun fin(h0: Int, mi: Int, ap: String, twoDigit: Boolean): Clock? {

        if (mi !in 0..59) return null

        if (ap.isNotEmpty()) {
            if (h0 !in 1..12) return null
            return Clock(h0 % 12 + (if (ap == "pm") 12 else 0), mi, true)
        }

        if (h0 == 0 || h0 > 12 || twoDigit) {
            return if (h0 in 0..23) Clock(h0, mi, true) else null
        }

        return Clock(h0, mi, false)
    }

    // "5 pm", "7 30", "9:30 am", "noon", "half past six", "quarter to five", "nine o clock"
    private fun clock(w: List<String>): Clock? {

        val s = w.joinToString(" ")
            .replace(Regex("""\bo'?\s?clock\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (s.isEmpty()) return null
        if (s == "noon" || s == "midday") return Clock(12, 0, true)
        if (s == "midnight") return Clock(0, 0, true)

        Regex("""^half past (\S+)(?: (am|pm))?$""").matchEntire(s)?.let { m ->
            val h = hourWord(m.groupValues[1]) ?: return null
            return fin(h, 30, m.groupValues[2], false)
        }

        Regex("""^quarter past (\S+)(?: (am|pm))?$""").matchEntire(s)?.let { m ->
            val h = hourWord(m.groupValues[1]) ?: return null
            return fin(h, 15, m.groupValues[2], false)
        }

        Regex("""^quarter to (\S+)(?: (am|pm))?$""").matchEntire(s)?.let { m ->
            val h = hourWord(m.groupValues[1]) ?: return null
            return fin(if (h == 1) 12 else h - 1, 45, m.groupValues[2], false)
        }

        val t = Mind.numWords(s).replace(Regex("""\b([ap]) ?m\b"""), "$1m")

        val m = Regex("""^(\d{1,2})(?:[: ](\d{2}))?(?: ?(am|pm))?$""").matchEntire(t) ?: return null

        val g1 = m.groupValues[1]
        val mi = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()

        return fin(g1.toInt(), mi, m.groupValues[3], g1.length == 2 && g1.startsWith("0"))
    }

    // A clock time that can safely start without "at": "5 pm", "9:30", "noon", "half past six".
    private fun strongStart(toks: List<String>, s: Int): Boolean {

        val t = toks[s]

        if (t == "noon" || t == "midnight" || t == "midday") return true
        if (Regex("""\d{1,2}:\d{2}(?:am|pm)?""").matches(t)) return true
        if (Regex("""\d{1,2}(?:am|pm)""").matches(t)) return true

        if ((t == "half" || t == "quarter") && s + 1 < toks.size && (toks[s + 1] == "past" || toks[s + 1] == "to")) {
            return true
        }

        if (isNum(t) && s + 1 < toks.size && (toks[s + 1] == "am" || toks[s + 1] == "pm")) return true

        return false
    }

    // The first clock time in the words: (from, to exclusive, the time). Longest phrase wins.
    private fun findClock(toks: List<String>, used: BooleanArray): Triple<Int, Int, Clock>? {

        val n = toks.size

        for (i in 0 until n) {

            if (used[i]) continue

            val viaAt = toks[i] == "at"
            val s = if (viaAt) i + 1 else i

            if (s >= n || used[s]) continue
            if (!viaAt && !strongStart(toks, s)) continue

            var k = minOf(6, n - s)

            while (k >= 1) {
                if ((s until s + k).none { used[it] }) {
                    val c = clock(toks.subList(s, s + k))
                    if (c != null) return Triple(if (viaAt) i else s, s + k, c)
                }
                k--
            }
        }

        return null
    }

    // "at 5" without am or pm, said with a part of the day: "tomorrow evening at 6" -> 18:00.
    private fun applyPart(c: Clock, part: String): Clock {

        val h = c.hour

        val hh = when (part) {
            "morning" -> h % 12
            "afternoon", "evening" -> h % 12 + 12
            else -> if (h == 12 || h < 6) h % 12 else h % 12 + 12
        }

        return Clock(hh, c.minute, true)
    }

    // Without am or pm on a day that is not today: 7 to 11 is morning, 12 is noon, 1 to 6 is afternoon.
    private fun guess24(h: Int): Int = if (h == 12) 12 else if (h in 1..6) h + 12 else h

    // Today: the next 9 o'clock (am or pm) that is still ahead. Another day: the guess above.
    private fun pickHour(c: Clock, d: LocalDate, now: LocalDateTime): LocalDateTime? {

        if (c.known) return LocalDateTime.of(d, LocalTime.of(c.hour, c.minute))

        if (d == now.toLocalDate()) {
            val h12 = c.hour % 12
            val am = LocalDateTime.of(d, LocalTime.of(h12, c.minute))
            val pm = LocalDateTime.of(d, LocalTime.of(h12 + 12, c.minute))
            return if (am.isAfter(now)) am else if (pm.isAfter(now)) pm else null
        }

        return LocalDateTime.of(d, LocalTime.of(guess24(c.hour), c.minute))
    }

    // "the 15th", "the twenty first": a day of the month, or null.
    private fun ordinal(w: List<String>): Int? {
        if (!ORD_SUFFIX.containsMatchIn(w.last())) return null
        val v = Mind.numWords(w.joinToString(" ")).replace(Regex("""(?<=\d)(?:st|nd|rd|th)$"""), "")
        return v.toIntOrNull()
    }

    // ---------------------------------------------------------------
    // Putting it together
    // ---------------------------------------------------------------

    private fun build(toks: List<String>, now: LocalDateTime, defTod: Int): Cmd {

        val n = toks.size
        val used = BooleanArray(n)

        fun use(from: Int, to: Int) {
            for (k in from until to) used[k] = true
        }

        var repKind = ""
        val repDays = LinkedHashSet<DayOfWeek>()
        var repPart: String? = null
        var relDays = -1
        var relSecs = 0
        var dateKind = ""
        var offset = 0
        var weekday: DayOfWeek? = null
        var nextWord = false
        var dm: Pair<Int, Int>? = null
        var dom = 0
        var part: String? = null
        var clock: Clock? = null

        // ---- A. repeats: every day / every monday and wednesday / weekdays / monthly ----

        var i = 0

        while (i < n && repKind.isEmpty()) {

            val w = toks[i]

            if ((w == "every" || w == "each") && i + 1 < n) {

                val x = toks[i + 1]

                if (x == "day") {
                    repKind = "daily"; use(i, i + 2)
                } else if (x in PARTS) {
                    repKind = "daily"; repPart = x; use(i, i + 2)
                } else if (x == "weekday" || x == "weekdays") {
                    repKind = "weekdays"; use(i, i + 2)
                } else if (x == "week") {
                    repKind = "weekly"; use(i, i + 2)
                } else if (x == "month") {
                    repKind = "monthly"; use(i, i + 2)
                } else if (DAYS.containsKey(x)) {
                    repKind = "weekly"
                    use(i, i + 1)
                    var j = i + 1
                    while (j < n) {
                        val d = DAYS[toks[j]]
                        if (d != null) {
                            repDays.add(d); used[j] = true; j++
                        } else if ((toks[j] == "and" || toks[j] == "&") && j + 1 < n && DAYS.containsKey(toks[j + 1])) {
                            used[j] = true; j++
                        } else {
                            break
                        }
                    }
                }

            } else if (w == "daily" || w == "everyday") {
                repKind = "daily"; use(i, i + 1)
            } else if (w == "weekdays") {
                repKind = "weekdays"; use(i, i + 1)
            } else if (w == "weekly") {
                repKind = "weekly"; use(i, i + 1)
            } else if (w == "monthly") {
                repKind = "monthly"; use(i, i + 1)
            }

            if (repKind.isNotEmpty() && i > 0 && toks[i - 1] in PREP && !used[i - 1]) used[i - 1] = true

            i++
        }

        // "every evening at 7": the part of the day also settles am or pm.
        if (repPart != null) part = repPart

        // ---- B. relative: in 2 hours / in an hour and a half / in 3 days ----

        i = 0

        while (i < n && relDays < 0) {

            if (toks[i] == "in" && !used[i]) {

                var k = minOf(8, n - i - 1)

                while (k >= 1) {
                    if ((i + 1 until i + 1 + k).none { used[it] }) {
                        val r = relative(toks.subList(i + 1, i + 1 + k).joinToString(" "))
                        if (r != null) {
                            relDays = r.first
                            relSecs = r.second
                            use(i, i + 1 + k)
                            break
                        }
                    }
                    k--
                }
            }

            i++
        }

        // ---- C. a day: today / tomorrow / monday / 12 march / the 15th ----

        i = 0

        while (i < n && dateKind.isEmpty()) {

            if (used[i]) { i++; continue }

            val w = toks[i]
            var consumed = 0

            when {
                w == "today" -> { dateKind = "today"; consumed = 1 }
                w == "tonight" -> { dateKind = "today"; part = "tonight"; consumed = 1 }
                w == "tomorrow" -> { dateKind = "offset"; offset = 1; consumed = 1 }
                w == "day" && i + 2 < n && toks[i + 1] == "after" && toks[i + 2] == "tomorrow" -> {
                    dateKind = "offset"; offset = 2; consumed = 3
                }
                w == "this" && i + 1 < n &&
                    (toks[i + 1] == "morning" || toks[i + 1] == "afternoon" || toks[i + 1] == "evening") -> {
                    dateKind = "today"; part = toks[i + 1]; consumed = 2
                }
                DAYS.containsKey(w) -> {
                    dateKind = "weekday"
                    weekday = DAYS[w]
                    consumed = 1
                    if (i > 0 && toks[i - 1] == "next") nextWord = true
                }
            }

            if (consumed > 0) {

                if (part == null && (dateKind == "today" || dateKind == "offset") &&
                    i + consumed < n && toks[i + consumed] in PARTS
                ) {
                    part = toks[i + consumed]
                    consumed++
                }

                use(i, i + consumed)

                if (i > 0 && !used[i - 1] &&
                    (toks[i - 1] in PREP || (dateKind == "weekday" && (toks[i - 1] == "next" || toks[i - 1] == "this")))
                ) {
                    used[i - 1] = true
                }
            }

            i++
        }

        // a date: "12 march", "march 12", "twelfth of march"
        if (dateKind.isEmpty()) {

            var s = 0

            scan@ while (s < n) {
                if (!used[s]) {
                    for (k in 4 downTo 2) {
                        if (s + k > n) continue
                        if ((s until s + k).any { used[it] }) continue
                        val r = Mind.parseDM(toks.subList(s, s + k).joinToString(" "))
                        if (r != null) {
                            dateKind = "dm"
                            dm = r
                            use(s, s + k)
                            if (s > 0 && !used[s - 1] && toks[s - 1] in PREP) used[s - 1] = true
                            break@scan
                        }
                    }
                }
                s++
            }
        }

        // "on the 15th"
        if (dateKind.isEmpty()) {

            var s = 0

            scan@ while (s + 2 < n) {
                if (!used[s] && toks[s] == "on" && toks[s + 1] == "the") {
                    for (k in 2 downTo 1) {
                        if (s + 2 + k > n) continue
                        if ((s until s + 2 + k).any { used[it] }) continue
                        val d = ordinal(toks.subList(s + 2, s + 2 + k))
                        if (d != null && d in 1..31) {
                            dateKind = "dom"
                            dom = d
                            use(s, s + 2 + k)
                            break@scan
                        }
                    }
                }
                s++
            }
        }

        // "in the morning", "at night"
        if (part == null) {

            var s = 0

            while (s < n - 1) {
                if (!used[s] && !used[s + 1]) {
                    if (toks[s] == "in" && s + 2 < n && toks[s + 1] == "the" && toks[s + 2] in PARTS && !used[s + 2]) {
                        part = toks[s + 2]; use(s, s + 3); break
                    }
                    if (toks[s] == "at" && toks[s + 1] == "night") {
                        part = "night"; use(s, s + 2); break
                    }
                }
                s++
            }
        }

        // ---- D. a clock time ----

        val found = findClock(toks, used)

        if (found != null) {
            use(found.first, found.second)
            clock = found.third
        }

        val c0 = clock
        val p0 = part

        if (c0 != null && !c0.known && p0 != null) clock = applyPart(c0, p0)

        // ---- the words that are left are what to remind you about ----

        val text = trimText((0 until n).filter { !used[it] }.map { toks[it] })

        val fClock = clock
        val fPart = part
        val fWeekday = weekday
        val fDm = dm

        val anyTime = repKind.isNotEmpty() || relDays >= 0 || dateKind.isNotEmpty() || fClock != null || fPart != null

        if (!anyTime) {
            return if (text.isEmpty()) {
                Cmd.Say("I didn't catch that. Say, for example: remind me tomorrow at 9 to pay rent.")
            } else {
                Cmd.NeedTime(text)
            }
        }

        val today = now.toLocalDate()
        val defTime = LocalTime.of(defTod / 60, defTod % 60)

        fun timeOn(d: LocalDate): LocalDateTime? {
            if (fClock != null) return pickHour(fClock, d, now)
            if (fPart != null) {
                val m = PART_TOD.getValue(fPart)
                return LocalDateTime.of(d, LocalTime.of(m / 60, m % 60))
            }
            return LocalDateTime.of(d, defTime)
        }

        val hasTime = fClock != null || fPart != null

        fun passedToday(): Cmd =
            if (hasTime) Cmd.Say("That time has already passed today.") else Cmd.NeedTime(text, "At what time today?")

        fun finish(at: LocalDateTime, repeat: String, tod: Int): Cmd {
            val spec = Spec(at, repeat, tod)
            return if (text.isEmpty()) Cmd.NeedText(spec) else Cmd.Add(spec, text)
        }

        // ---- repeating ----

        if (repKind.isNotEmpty()) {

            val tod = when {
                fClock != null ->
                    if (fClock.known) fClock.hour * 60 + fClock.minute else guess24(fClock.hour) * 60 + fClock.minute
                fPart != null -> PART_TOD.getValue(fPart)
                else -> defTod
            }

            val repeat = when (repKind) {
                "daily" -> "daily"
                "weekdays" -> "weekdays"
                "weekly" -> {
                    val days: Set<DayOfWeek> = when {
                        repDays.isNotEmpty() -> repDays
                        fWeekday != null -> setOf(fWeekday)
                        else -> setOf(now.dayOfWeek)
                    }
                    "weekly:" + days.map { it.value }.sorted().joinToString(",")
                }
                else -> "monthly:" + (if (dom > 0) dom else fDm?.first ?: now.dayOfMonth)
            }

            val first = nextAfter(repeat, tod, now) ?: return Cmd.Say("I couldn't work out that schedule.")

            return finish(first, repeat, tod)
        }

        // ---- in 2 hours / in 3 days ----

        if (relDays >= 0) {

            val at: LocalDateTime =
                if (relDays > 0 && hasTime) {
                    timeOn(today.plusDays(relDays.toLong())) ?: return Cmd.Say("I couldn't work out that time.")
                } else {
                    now.plusDays(relDays.toLong()).plusSeconds(relSecs.toLong()).withNano(0)
                }

            return finish(at, "", at.hour * 60 + at.minute)
        }

        // ---- a day and / or a time ----

        var at: LocalDateTime? = null

        when (dateKind) {

            "today" -> {
                val c = timeOn(today)
                if (c == null || !c.isAfter(now)) return passedToday()
                at = c
            }

            "offset" -> at = timeOn(today.plusDays(offset.toLong()))

            "weekday" -> {
                val dow = fWeekday ?: return Cmd.Say("I couldn't work out that day.")
                var d = today.with(TemporalAdjusters.nextOrSame(dow))
                if (nextWord && d == today) d = d.plusWeeks(1)
                var c = timeOn(d)
                if (c == null || !c.isAfter(now)) {
                    d = d.plusWeeks(1)
                    c = timeOn(d)
                }
                at = c
            }

            "dm" -> {
                val pair = fDm ?: return Cmd.Say("I couldn't work out that date.")
                val dd = pair.first
                val mm = pair.second

                fun mk(y: Int): LocalDate? = try {
                    LocalDate.of(y, mm, dd)
                } catch (e: Exception) {
                    null
                }

                var d = mk(today.year)
                if (d == null || d.isBefore(today)) d = (today.year + 1..today.year + 8).firstNotNullOfOrNull { mk(it) }
                if (d == null) return Cmd.Say("That date doesn't exist.")

                if (d == today) {
                    val c = timeOn(today)
                    if (c == null || !c.isAfter(now)) return passedToday()
                    at = c
                } else {
                    at = timeOn(d)
                }
            }

            "dom" -> {
                var f: LocalDateTime? = null
                for (k in 0..62) {
                    val d = today.plusDays(k.toLong())
                    if (d.dayOfMonth != dom) continue
                    val c = timeOn(d)
                    if (c != null && c.isAfter(now)) {
                        f = c
                        break
                    }
                }
                at = f
            }

            else -> {
                var c = timeOn(today)
                if (c == null || !c.isAfter(now)) c = timeOn(today.plusDays(1))
                at = c
            }
        }

        val result = at ?: return Cmd.Say("I couldn't work out that time.")

        return finish(result, "", result.hour * 60 + result.minute)
    }
}
