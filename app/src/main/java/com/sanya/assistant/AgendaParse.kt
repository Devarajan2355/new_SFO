package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * AgendaParse: which spoken phrases are calendar questions. No Android calls, so it is unit-tested.
 *
 *   what's on my calendar tomorrow      -> Day (tomorrow)
 *   do i have any meetings today        -> Day (today)
 *   what do i have on monday            -> Day (next monday)
 *   what's my day                       -> MyDay
 *   when is my next meeting             -> Next
 *   am i free tomorrow at 3             -> Free ("tomorrow at 3")
 *   what about tomorrow                 -> Follow (only used just after another agenda answer)
 */
object AgendaParse {

    sealed class Cmd {
        data class Day(val date: LocalDate) : Cmd()
        data class Follow(val date: LocalDate) : Cmd()
        data class Free(val whenText: String) : Cmd()
        object Next : Cmd()
        object MyDay : Cmd()
    }

    private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val DAY = "day after tomorrow|today|tomorrow|(?:(?:on|this|next) )?(?:$WEEKDAYS)"

    private val MYDAY = Regex(
        """^(?:(?:what|what's|whats|what is|how is|how's|tell me|give me|show me|read me|read)(?: about)? )?my (?:day|agenda|day ahead)(?: look like| today)?$"""
    )
    private val NEXT_A = Regex(
        """^(?:(?:what is|what's|whats|when is|when's|tell me|show me|read me)(?: about)? )?(?:my )?next (?:meeting|event|appointment)$"""
    )
    private val NEXT_B = Regex("""^(?:what is|what's|whats) next(?: on| in)?(?: my)?(?: calendar| schedule)?$""")
    private val FREE = Regex("""^(?:am i|are we) (?:free|busy|available)(?: (?:on|at|for))? (.+)$""")
    private val DAYQ = Regex(
        """^(?:what do i have|what have i got|what am i doing|what is planned|what do we have|what is on|what's on|whats on)(?: on| for)? ($DAY)$"""
    )
    private val FOLLOW = Regex("""^(?:(?:what|how) about )?($DAY)$""")
    private val DAY_ONLY = Regex("""^($DAY)$""")

    private val ASK = Regex(
        """^(?:what|what's|whats|which|do i have|have i got|am i|is there|are there|any|anything|show|read|tell me|check|list|give me|how is|how's)\b"""
    )
    private val NOUN = Regex("""\b(?:calendar|schedule|meetings?|events?|appointments?|diary)\b""")
    private val PERSONAL = Regex("""\b(?:my|calendar|schedule|diary|any|anything)\b|do i have|have i got""")
    private val DAYIN = Regex("""\b($DAY)\b""")

    /** "tomorrow", "monday", "next monday", "day after tomorrow" -> a date. */
    fun dayOf(s: String, today: LocalDate): LocalDate? {

        val t = s.trim()

        if (t == "today") return today
        if (t == "tomorrow") return today.plusDays(1)
        if (t == "day after tomorrow") return today.plusDays(2)

        val name = t.removePrefix("on ").removePrefix("this ").removePrefix("next ")
        val dow = DayOfWeek.values().firstOrNull { it.name.equals(name, true) } ?: return null

        var d = today.with(TemporalAdjusters.nextOrSame(dow))
        if (t.startsWith("next ") && d == today) d = d.plusWeeks(1)

        return d
    }

    /** Null if [raw] is not about the calendar. */
    fun parse(raw: String, today: LocalDate = LocalDate.now()): Cmd? {

        val t = IntentScorer.tokens(raw, emptyList(), false).joinToString(" ")
        if (t.isEmpty()) return null

        if (MYDAY.matches(t)) return Cmd.MyDay

        if (NEXT_A.matches(t) || NEXT_B.matches(t)) return Cmd.Next

        FREE.matchEntire(t)?.let { m ->
            val g = m.groupValues[1].trim()
            // "am i free tomorrow" asks about the day, not about one time
            if (DAY_ONLY.matches(g)) {
                dayOf(g, today)?.let { d -> return Cmd.Day(d) }
            }
            return Cmd.Free(g)
        }

        DAYQ.matchEntire(t)?.let { m ->
            dayOf(m.groupValues[1], today)?.let { d -> return Cmd.Day(d) }
        }

        FOLLOW.matchEntire(t)?.let { m ->
            dayOf(m.groupValues[1], today)?.let { d -> return Cmd.Follow(d) }
        }

        if (ASK.containsMatchIn(t) && NOUN.containsMatchIn(t) && PERSONAL.containsMatchIn(t)) {
            val d = DAYIN.find(t)?.let { dayOf(it.groupValues[1], today) } ?: today
            return Cmd.Day(d)
        }

        return null
    }
}
