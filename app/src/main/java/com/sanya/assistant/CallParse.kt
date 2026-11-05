package com.sanya.assistant

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * CallParse: which spoken phrases ask about your call history, and how times are said.
 * No Android calls, so it is unit-tested.
 *
 *   any missed calls / did i miss any calls today     -> Missed
 *   how many missed calls do i have                   -> Missed (count only)
 *   who called me today / show my recent calls        -> Recent
 *   call back / call him back / return the call       -> CallBack
 *   redial / call the last number                     -> Redial
 *   when did ravi last call me                        -> Last ("ravi", IN)
 *   when did i last call ravi                         -> Last ("ravi", OUT)
 *
 * "who called" on its own is not here: Calls.kt answers it (from the phone's call log).
 */
object CallParse {

    enum class Dir { IN, OUT, ANY }

    sealed class Cmd {
        /** span "" means: the ones you have not returned in the last 3 days. */
        data class Missed(val span: String, val countOnly: Boolean = false) : Cmd()

        data class Recent(val span: String, val callersOnly: Boolean) : Cmd()

        /** pronoun: "call him back" (only ours if a call was missed a moment ago). */
        data class CallBack(val pronoun: Boolean) : Cmd()

        object Redial : Cmd()

        /** who "" means: anyone (the latest call in that direction). */
        data class Last(val who: String, val dir: Dir, val personal: Boolean) : Cmd()
    }

    /** Lower case, polite filler removed, punctuation gone. */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw, emptyList(), false).joinToString(" ")

    private const val SPAN = "today|yesterday|this week|last week|this morning"

    private val MISSED_COUNT = Regex(
        """^how many (?:calls (?:did i miss|have i missed)|missed calls(?: do i have| did i get| have i got)?)(?: ($SPAN))?$"""
    )
    private val MISSED_A = Regex(
        """^(?:(?:do i have|have i got|are there|did i get|did i have|show me|tell me|read me|read|show|list|check) )?(?:(?:my|any|the|all|new) )*missed calls?(?: ($SPAN))?$"""
    )
    private val MISSED_B = Regex("""^did i miss (?:any |a |some )?calls?(?: ($SPAN))?$""")
    private val MISSED_C = Regex("""^(?:what|which) calls did i miss(?: ($SPAN))?$""")

    private val CALLERS = Regex("""^who (?:called|rang|phoned|has called|has rung)(?: me)? ($SPAN)$""")
    private val RECENT = Regex(
        """^(?:(?:show|read|list|tell me|check|give me)(?: me)? )?(?:(?:my|the|all) )*(?:recent calls|call history|call log|latest calls)(?: ($SPAN))?$"""
    )

    private val BACK_A = Regex(
        """^(?:call|ring|phone|dial|return) (?:(him|her|them|that person|that caller|the caller|the last caller) )?back(?: (?:the |that |my )?(?:last |latest |recent )?(?:missed )?(?:call|caller|number))?$"""
    )
    private val BACK_B = Regex("""^return (?:the |that |my )?(?:last |latest |recent )?(?:missed )?calls?$""")
    private val BACK_C = Regex("""^call (?:the |that )?(?:last|latest|recent) (?:missed )?(?:caller|call)$""")

    private val REDIAL = Regex(
        """^(?:redial(?: (?:the )?(?:last )?(?:number|call))?|(?:call|dial) (?:the )?(?:last|same) number(?: again| i (?:dialed|dialled|called))?|call (?:the )?(?:number|person) i (?:called|dialed|dialled) (?:last|before|earlier))$"""
    )

    private val LAST_OUT_A = Regex(
        """^when did i (?:last )?(call|ring|phone|dial|talk to|speak to|speak with|talk with)(?: (.+?))?(?: last)?$"""
    )
    private val LAST_OUT_B = Regex(
        """^did i (?:call|ring|phone) (.+?)(?: (?:today|yesterday|this morning|last night|recently))?$"""
    )
    private val LAST_OUT_C = Regex("""^who did i (?:last )?(?:call|ring|phone|dial)(?: last)?$""")
    private val LAST_ANY = Regex("""^who was my last (?:call|caller)$""")
    private val LAST_PREP = Regex("""^when was (?:the )?(?:last|latest) call (from|to|with) (.+)$""")
    private val LAST_IN_A = Regex("""^when did (.+?) (?:last )?(?:call|ring|phone)(?: me)?(?: last)?$""")
    private val LAST_IN_B = Regex(
        """^(?:did|has) (.+?) (?:call|called|ring|rang|phone|phoned)(?: me)?(?: (?:today|yesterday|this morning|last night|recently))?$"""
    )

    private val HE_SHE = setOf("he", "she", "they", "him", "her", "them")
    private val ANYONE = setOf("anyone", "anybody", "someone", "somebody", "nobody", "no one")
    private val NOT_NAMES = setOf("i", "you", "we", "it", "this", "that", "there", "who", "what")

    private fun spanOf(word: String): String = if (word == "this morning") "today" else word

    // "did she call me" asks about the last caller; "did anyone call" about today's callers.
    private fun inbound(who: String, t: String): Cmd? {

        if (who.isEmpty() || who in NOT_NAMES) return null
        if (who in HE_SHE) return Cmd.Last("", Dir.IN, true)
        if (who in ANYONE) return Cmd.Recent("today", true)

        val personal = t.endsWith(" me") || t.contains(" me ")

        return Cmd.Last(who, Dir.IN, personal)
    }

    /** Null if [raw] is not about your call history. */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        MISSED_COUNT.matchEntire(t)?.let { return Cmd.Missed(spanOf(it.groupValues[1]), true) }
        MISSED_A.matchEntire(t)?.let { return Cmd.Missed(spanOf(it.groupValues[1])) }
        MISSED_B.matchEntire(t)?.let { return Cmd.Missed(spanOf(it.groupValues[1])) }
        MISSED_C.matchEntire(t)?.let { return Cmd.Missed(spanOf(it.groupValues[1])) }

        CALLERS.matchEntire(t)?.let { return Cmd.Recent(spanOf(it.groupValues[1]), true) }
        RECENT.matchEntire(t)?.let { return Cmd.Recent(spanOf(it.groupValues[1]), false) }

        BACK_A.matchEntire(t)?.let { m ->
            val w = m.groupValues[1]
            return Cmd.CallBack(w == "him" || w == "her" || w == "them" || w == "that person")
        }
        if (BACK_B.matches(t) || BACK_C.matches(t)) return Cmd.CallBack(false)

        if (REDIAL.matches(t)) return Cmd.Redial

        LAST_OUT_A.matchEntire(t)?.let { m ->
            val verb = m.groupValues[1]
            val dir = if (verb.startsWith("talk") || verb.startsWith("speak")) Dir.ANY else Dir.OUT
            return Cmd.Last(m.groupValues[2].trim(), dir, true)
        }
        LAST_OUT_B.matchEntire(t)?.let { return Cmd.Last(it.groupValues[1].trim(), Dir.OUT, true) }
        if (LAST_OUT_C.matches(t)) return Cmd.Last("", Dir.OUT, true)
        if (LAST_ANY.matches(t)) return Cmd.Last("", Dir.ANY, true)

        LAST_PREP.matchEntire(t)?.let { m ->
            val dir = when (m.groupValues[1]) {
                "from" -> Dir.IN
                "to" -> Dir.OUT
                else -> Dir.ANY
            }
            return Cmd.Last(m.groupValues[2].trim(), dir, true)
        }

        LAST_IN_A.matchEntire(t)?.let { m -> inbound(m.groupValues[1].trim(), t)?.let { return it } }
        LAST_IN_B.matchEntire(t)?.let { m -> inbound(m.groupValues[1].trim(), t)?.let { return it } }

        return null
    }

    // ---------------------------------------------------------------
    // Saying a time in the past
    // ---------------------------------------------------------------

    /**
     * "just now", "20 minutes ago", "3 hours ago", "today at 3:15 pm", "yesterday at 6 pm",
     * "on Monday at 8 am", "on 12 October at 8 am".
     */
    fun ago(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {

        val secs = (now - at) / 1000

        if (secs < 60) return "just now"

        if (secs < 3600) {
            val m = (secs / 60).toInt()
            return if (m <= 1) "a minute ago" else "$m minutes ago"
        }

        if (secs < 6 * 3600) {
            val h = ((secs + 1800) / 3600).toInt()
            return if (h <= 1) "an hour ago" else "$h hours ago"
        }

        val t = Instant.ofEpochMilli(at).atZone(zone)
        val n = Instant.ofEpochMilli(now).atZone(zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), n.toLocalDate())
        val clock = ReminderParse.clock12(t.hour * 60 + t.minute)

        return when {
            days <= 0L -> "today at $clock"
            days == 1L -> "yesterday at $clock"
            days in 2L..6L ->
                "on " + t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " at " + clock
            else ->
                "on " + t.dayOfMonth + " " + t.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " at " + clock
        }
    }
}
