package com.sanya.assistant

import com.sanya.assistant.ProactiveRules.Type

/**
 * ProactiveParse: which spoken phrases control her proactive side. No Android calls, so it is unit-tested.
 *
 *   turn off habit suggestions / turn on meeting alerts / stop battery warnings   -> Toggle
 *   turn off all nudges / resume nudges                                          -> Master
 *   quiet mode for an hour / leave me alone for 30 minutes                       -> Quiet
 *   quiet mode until tomorrow                                                    -> Quiet (until morning)
 *   quiet mode off / you can talk again                                          -> QuietOff
 *   stop that / don't suggest that again      (only just after she said something) -> Stop
 *   that was helpful / good suggestion        (only just after she said something) -> Helpful
 *   why did you say that                      (only just after she said something) -> Why
 *   set battery warning at 20 percent                                            -> BatteryAt
 *   proactive status / what nudges are on                                        -> Status
 *
 * Phrases that other parts already own are left alone: "be quiet", "stop suggesting",
 * "turn off proactive" (Mind), "stop routine check" (Routines), "stop it" (music).
 */
object ProactiveParse {

    sealed class Cmd {
        object Status : Cmd()
        data class Toggle(val type: Type, val on: Boolean) : Cmd()
        data class Master(val on: Boolean) : Cmd()
        data class Quiet(val minutes: Int, val untilMorning: Boolean) : Cmd()
        object QuietOff : Cmd()
        object Stop : Cmd()
        object Helpful : Cmd()
        object Why : Cmd()
        data class BatteryAt(val percent: Int) : Cmd()
        data class Say(val text: String) : Cmd()
    }

    /** Lower case, polite filler removed, hyphens read as spaces ("heads-up" = "heads up"). */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw.replace("-", " "), emptyList(), false).joinToString(" ")

    private val WORDS: Map<String, Type> = HashMap<String, Type>().also { m ->

        fun add(t: Type, vararg words: String) {
            for (w in words) m[w] = t
        }

        add(Type.BATTERY, "battery warnings", "battery warning", "battery alerts", "battery nudges",
            "low battery warnings", "low battery alerts")
        add(Type.MEETING, "meeting alerts", "meeting warnings", "meeting heads up", "meeting reminders",
            "calendar alerts", "event alerts")
        add(Type.ROUTINE, "routine reminders", "routine nudges")
        add(Type.STUDY, "study break reminders", "break reminders", "study breaks")
        add(Type.MISSED, "missed call offers", "call back offers", "callback offers")
        add(Type.DATES, "birthday and date alerts", "birthday reminders", "birthday alerts",
            "date alerts", "date reminders")
        add(Type.TASKS, "task reminders", "task alerts", "task nudges", "due task alerts")
        add(Type.BUDGET, "budget warnings", "budget alerts", "budget nudges", "spending warnings")
        add(Type.GAP, "free time suggestions", "free time nudges", "gap suggestions",
            "pomodoro suggestions", "focus suggestions")
        add(Type.BEDTIME, "alarm suggestions", "bedtime suggestions", "bedtime alarms", "bedtime nudges")
        add(Type.CELEBRATE, "well done messages", "praise", "celebrations", "celebration messages")
        add(Type.HABIT, "habit suggestions", "habit nudges", "habit offers")
        add(Type.GUARD, "focus guard warnings", "guard warnings")
    }

    private val MASTER = setOf(
        "nudges", "all nudges", "proactive nudges", "proactive features", "all proactive features",
        "suggestions", "all suggestions"
    )

    private const val QW =
        """(?:quiet mode|be quiet|leave me alone|no nudges|no suggestions|do not disturb me|don't disturb me|dont disturb me|stop nudging me|mute nudges|mute suggestions)"""

    private val STATUS = Regex(
        """^(?:proactive|nudge|nudges|suggestion) (?:status|settings|list|report)$|^what (?:proactive|nudges?)(?: features| things| alerts)?(?: are| do you have)?(?: on| active| enabled| turned on)?$|^what (?:are you watching|do you watch) for me$"""
    )
    private val WHY = Regex(
        """^(?:why did you (?:say|tell me|suggest|mention) (?:that|this)(?: just now)?|why (?:that|this)|why was that|why do you keep (?:saying|telling me) that)$"""
    )
    private val HELPFUL = Regex(
        """^(?:that was helpful|that is helpful|thats helpful|that's helpful|that helps|that helped|good suggestion|good call|nice suggestion|nice one|good one|more of these|more like that|keep doing that)$"""
    )
    private val STOP = Regex(
        """^(?:stop that|stop these|stop telling me that|stop telling me this|stop telling me these|stop suggesting that|stop suggesting this|don't suggest that again|dont suggest that again|do not suggest that again|don't tell me that again|dont tell me that again|never suggest that again|never tell me that again|no more of these|no more of that|i don't need that|i dont need that)$"""
    )
    private val QUIET_OFF = Regex(
        """^(?:quiet mode off|end quiet mode|stop quiet mode|cancel quiet mode|you can talk again|talk again|nudges back on|stop being quiet)$"""
    )
    private val QUIET_DAY = Regex(
        """^$QW (?:for the rest of the day|for today|until tomorrow|till tomorrow|until morning|till morning|today|tonight)$"""
    )
    private val QUIET_FOR = Regex("""^$QW for (.+)$""")
    private val BATTERY_AT = Regex(
        """^set (?:my |the )?(?:low )?battery (?:warning|warnings|alert|alerts|nudge) (?:at|to) (\d{1,2})(?: ?percent| ?%)?$"""
    )
    private val OFF = Regex(
        """^(?:turn off|switch off|disable|stop|mute|silence|no more|cancel) (?:my |the |all |your )*(.+)$"""
    )
    private val ON = Regex(
        """^(?:turn on|switch on|enable|start|unmute|resume|bring back|allow) (?:my |the |all |your )*(.+)$"""
    )

    const val MAX_QUIET_MIN = 720

    /** Is [raw] a proactive control command? Null if not. */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (STATUS.matches(t)) return Cmd.Status
        if (WHY.matches(t)) return Cmd.Why
        if (HELPFUL.matches(t)) return Cmd.Helpful
        if (STOP.matches(t)) return Cmd.Stop
        if (QUIET_OFF.matches(t)) return Cmd.QuietOff

        if (QUIET_DAY.matches(t)) return Cmd.Quiet(0, true)

        QUIET_FOR.matchEntire(t)?.let { m ->
            val mins = Num.parseDuration(m.groupValues[1])
                ?: return Cmd.Say("I didn't catch how long. Say, for example: quiet mode for an hour.")
            return Cmd.Quiet(minOf(mins, MAX_QUIET_MIN), false)
        }

        BATTERY_AT.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            return if (n in 5..50) {
                Cmd.BatteryAt(n)
            } else {
                Cmd.Say("Pick a number between 5 and 50. For example: set battery warning at 20 percent.")
            }
        }

        for (pair in listOf(Pair(OFF, false), Pair(ON, true))) {

            val m = pair.first.matchEntire(t) ?: continue
            val g = m.groupValues[1].trim()

            if (g in MASTER) return Cmd.Master(pair.second)

            val type = WORDS[g]
            if (type != null) return Cmd.Toggle(type, pair.second)
        }

        return null
    }
}
