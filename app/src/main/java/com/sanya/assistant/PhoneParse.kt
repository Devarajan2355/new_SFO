package com.sanya.assistant

/**
 * PhoneParse: which spoken phrases mean a call command or a notification command.
 * Pure Kotlin (no Android calls), so it is unit-tested.
 */
object PhoneParse {

    sealed class CallCmd {
        data class Answer(val speaker: Boolean) : CallCmd()
        object Reject : CallCmd()
        object Hangup : CallCmd()
        object Who : CallCmd()
        data class Announce(val on: Boolean) : CallCmd()
    }

    sealed class NoticeCmd {
        data class Read(val all: Boolean, val messagesOnly: Boolean) : NoticeCmd()
        object Clear : NoticeCmd()
        object Enable : NoticeCmd()
        data class Announce(val on: Boolean) : NoticeCmd()
    }

    /** Lower case, polite filler ("can you", "please", "now" ...) removed. */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw, emptyList(), false).joinToString(" ")

    // ---------------- calls ----------------

    private val ANSWER = Regex(
        """^(?:answer|accept|pick up|pickup|take|receive)(?: the| this| that| it| my)*(?: call| phone)?(?: (?:on|with) (?:the )?(speaker|speakerphone|loudspeaker))?$"""
    )
    private val REJECT = Regex(
        """^(?:reject|decline|ignore|cut|dismiss|silence|refuse)(?: the| this| that| it| my)*(?: call| phone)?$"""
    )
    private val HANGUP = Regex(
        """^(?:hang up|hangup)(?: the| this| that| my)*(?: call| phone)?$|^(?:end|disconnect|finish)(?: the| this| that| my)* (?:call|phone)$"""
    )
    private val WHO = Regex(
        """^who (?:called|is calling|called me|just called|rang|was calling|was that)(?: me)?(?: just now| earlier| last)?$"""
    )
    private val CALL_ON = Regex("""^(?:announce|start announcing)(?: my| incoming)* calls$""")
    private val CALL_OFF = Regex(
        """^(?:stop announcing|do not announce|don't announce|dont announce)(?: my| incoming)* calls$"""
    )

    fun call(raw: String): CallCmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (CALL_OFF.matches(t)) return CallCmd.Announce(false)
        if (CALL_ON.matches(t)) return CallCmd.Announce(true)
        if (WHO.matches(t)) return CallCmd.Who
        if (HANGUP.matches(t)) return CallCmd.Hangup
        if (REJECT.matches(t)) return CallCmd.Reject

        ANSWER.matchEntire(t)?.let { return CallCmd.Answer(it.groupValues[1].isNotEmpty()) }

        return null
    }

    // ---------------- notifications ----------------

    private val READ = Regex(
        """^(?:read(?: out)?|tell me|check|what are|what is|what's|whats|do i have(?: any)?|have i got(?: any)?|any|show me|show)(?: my| the| me| any| new| unread| all| latest| recent)*(?: (whatsapp|text|sms|telegram))? (notifications?|messages?|alerts?|texts?)(?: again| today)?$"""
    )
    private val ALL = Regex("""\ball\b""")
    private val CLEAR = Regex("""^(?:clear|dismiss|forget|delete)(?: all| my| the)* notifications?$""")
    private val ENABLE = Regex(
        """^(?:enable|allow|turn on|set up|setup|give you)(?: my| the| you)* notification (?:access|reading)$"""
    )
    private val MSG_ON = Regex(
        """^(?:announce|start announcing)(?: new| incoming| my)* messages(?: automatically| as they (?:come|arrive))?$"""
    )
    private val MSG_OFF = Regex(
        """^(?:stop announcing|stop reading out|stop reading|do not announce|don't announce|dont announce|do not read|don't read|dont read)(?: new| incoming| my)* messages(?: automatically| as they (?:come|arrive))?$"""
    )

    fun notice(raw: String): NoticeCmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (MSG_OFF.matches(t)) return NoticeCmd.Announce(false)
        if (MSG_ON.matches(t)) return NoticeCmd.Announce(true)
        if (ENABLE.matches(t)) return NoticeCmd.Enable
        if (CLEAR.matches(t)) return NoticeCmd.Clear

        READ.matchEntire(t)?.let { m ->
            val kind = m.groupValues[2]
            val messagesOnly = m.groupValues[1].isNotEmpty() || kind.startsWith("message") || kind.startsWith("text")
            return NoticeCmd.Read(ALL.containsMatchIn(t), messagesOnly)
        }

        return null
    }
}
