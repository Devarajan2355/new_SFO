package com.sanya.assistant

/**
 * GuardParse: which spoken phrases are Focus Guard commands. No Android calls, so it is unit-tested.
 *
 *   focus guard status / how is my focus guard / what are my limits   -> Status
 *   show my focus guard                                              -> Show (opens the screen)
 *   turn on focus guard / turn off focus guard                       -> Master
 *   block shorts / allow shorts / turn off shorts blocker            -> Shorts
 *   limit youtube to 1 hour / limit chrome to 45 minutes a day       -> SetLimit
 *   remove the limit on youtube                                      -> RemoveLimit
 *   how long have i been on youtube / youtube usage / my screen time -> Usage
 *   cancel the guard request                                         -> CancelRequest
 *   block the word hentai / block the site examplesite               -> BlockWord
 *   unblock the word hentai / allow the site examplesite             -> UnblockWord
 *   what words are blocked                                           -> WordList
 *   turn on the adult filter / turn off the adult filter             -> WordFilter
 *   urge mode / i have an urge / i feel tempted                      -> Urge
 *   how long is left in urge mode / end urge mode                    -> UrgeStatus / UrgeEnd
 *
 * "focus mode" is deliberately NOT used: that is the Pomodoro's name.
 */
object GuardParse {

    sealed class Cmd {
        data class Master(val on: Boolean) : Cmd()
        data class Shorts(val on: Boolean) : Cmd()
        data class SetLimit(val app: String, val minutes: Int) : Cmd()
        data class RemoveLimit(val app: String) : Cmd()
        data class Usage(val app: String?) : Cmd()
        data class Say(val text: String) : Cmd()
        data class BlockWord(val term: String, val site: Boolean) : Cmd()
        data class UnblockWord(val term: String, val site: Boolean) : Cmd()
        data class WordFilter(val on: Boolean) : Cmd()
        object WordList : Cmd()
        object Urge : Cmd()
        object UrgeStatus : Cmd()
        object UrgeEnd : Cmd()
        object Status : Cmd()
        object Show : Cmd()
        object CancelRequest : Cmd()
        data class SetSchedule(val app: String, val start: Int, val end: Int) : Cmd()
        data class RemoveSchedule(val app: String) : Cmd()
        object ScheduleList : Cmd()
        object UrgePattern : Cmd()
        object Report : Cmd()
        object SendReport : Cmd()
        data class Partner(val name: String) : Cmd()
        object PartnerOff : Cmd()
        object PartnerWho : Cmd()
        data class ReportReminders(val on: Boolean) : Cmd()
        data class SettingsLock(val on: Boolean) : Cmd()
        object UnlockSettings : Cmd()
        object LockSettingsNow : Cmd()
        data class LockAlways(val on: Boolean) : Cmd()
        data class Alerts(val on: Boolean) : Cmd()
        data class WatchDns(val on: Boolean) : Cmd()
    }

    private const val G = "(?:focus guard|guard)"

    private val CANCEL = Regex(
        """^(?:cancel|forget|drop|clear) (?:the |my )?(?:pending )?${G} (?:request|change)s?$"""
    )
    private val SHOW = Regex(
        """^(?:show|open|display) (?:my |the )?${G}(?: screen| report| chart| stats| page)?$"""
    )
    private val STATUS = Regex(
        """^(?:(?:how is|how's|what is|what's|whats|check|tell me)(?: about)? )?(?:my |the )?${G}(?: status| report| progress)?$"""
    )
    private val LIMITS_LIST = Regex(
        """^(?:what are|list|show|read|tell me) (?:my )?(?:app )?(?:time )?limits$"""
    )
    private val MASTER_ON = Regex(
        """^(?:turn on|switch on|enable|start|activate) (?:my |the )?${G}$|^${G} on$"""
    )
    private val MASTER_OFF = Regex(
        """^(?:turn off|switch off|disable|stop|deactivate|pause) (?:my |the )?${G}$|^${G} off$"""
    )
    private val SHORTS_ON = Regex(
        """^(?:block|blocking) (?:the |all )?(?:youtube )?shorts$|^(?:turn on|switch on|enable|start|activate) (?:the |my )?(?:youtube )?shorts (?:blocker|blocking|block)$|^(?:youtube )?shorts (?:blocker|blocking) on$"""
    )
    private val SHORTS_OFF = Regex(
        """^(?:allow|unblock) (?:the |all )?(?:youtube )?shorts$|^(?:turn off|switch off|disable|stop|deactivate) (?:the |my )?(?:youtube )?shorts (?:blocker|blocking|block)$|^(?:youtube )?shorts (?:blocker|blocking) off$"""
    )
    private val REMOVE_A = Regex(
        """^(?:remove|delete|clear|drop|cancel) (?:the |my )?(?:daily )?(?:limit|cap) (?:for|on) (.+)$"""
    )
    private val REMOVE_B = Regex(
        """^(?:remove|delete|clear|drop) (?:the |my )?(.+?) (?:limit|cap)$"""
    )
    private val SET_A = Regex(
        """^(?:set |put |add |create )?(?:a |an |the )?(?:daily )?(?:limit|cap) (?:for|on) (.+?) (?:to|at|of) (.+?)(?: (?:a day|per day|daily|every day|each day|today))?$"""
    )
    private val SET_B = Regex(
        """^(?:limit|cap) (?:my )?(?:daily )?(?:time )?(?:on |for )?(.+?) to (.+?)(?: (?:a day|per day|daily|every day|each day))?$"""
    )
    private const val K = "(word|term|keyword|site|website|domain)"
    private const val F = "(?:adult|porn|word|keyword|browser)"

    private val BLOCK_A = Regex(
        """^(?:block|ban) (?:the |a )?${K} (.+)$"""
    )
    private val BLOCK_B = Regex(
        """^add (?:a |the )?blocked ${K} (.+)$"""
    )
    private val UNBLOCK_A = Regex(
        """^(?:unblock|allow) (?:the )?(?:blocked )?${K} (.+)$"""
    )
    private val UNBLOCK_B = Regex(
        """^(?:remove|delete|drop) (?:the |a )?blocked ${K} (.+)$"""
    )
    private val WORD_LIST = Regex(
        """^(?:what|which) (?:words|sites|terms)(?: do i have| are)? blocked$|^(?:list|show|read|tell me)(?: me)?(?: my| the)? blocked (?:words|sites|terms|list)$"""
    )
    private val FILTER_ON = Regex(
        """^(?:turn on|switch on|enable|start|activate) (?:the |my )?${F} (?:filter|filters|blocker|guard|blocking)$|^(?:adult|porn|word|keyword) (?:filter|blocker|guard) on$|^block (?:adult|porn) (?:sites|content|websites|pages)$"""
    )
    private val FILTER_OFF = Regex(
        """^(?:turn off|switch off|disable|stop|deactivate) (?:the |my )?${F} (?:filter|filters|blocker|guard|blocking)$|^(?:adult|porn|word|keyword) (?:filter|blocker|guard) off$|^allow (?:adult|porn) (?:sites|content|websites|pages)$"""
    )
    private val URGE = Regex(
        """^(?:start |begin )?urge (?:mode|timer)$|^i (?:have|feel|got|am having|am feeling) (?:an |a )?(?:urge|craving)$|^i(?: am|'m|m| feel| am feeling) (?:tempted|weak)$|^(?:help me|i need help)(?: with)?(?: my)? (?:urge|craving)$|^i feel like watching (?:porn|shorts|reels|something bad)$|^(?:watch|see) (?:porn|pornography)$"""
    )
    private val URGE_STATUS = Regex(
        """^(?:how long|how much time)(?: is)? left (?:in|on|of) (?:my |the )?urge(?: mode)?$|^urge (?:mode )?status$"""
    )
    private val URGE_END = Regex(
        """^(?:end|stop|cancel|turn off) (?:the |my )?urge (?:mode|timer)$"""
    )

    private val USAGE_ALL = Regex(
        """^(?:what is|what's|whats|show|tell me|check) (?:my )?(?:total )?(?:screen time|usage)(?: today)?$"""
    )
    private val USAGE_A = Regex(
        """^how (?:long|much time) (?:have i been|am i|have i used|did i use) (?:on |using |watching |in )?(.+?)(?: today)?$"""
    )
    private val USAGE_B = Regex(
        """^how much (?:time )?(?:have i|did i) (?:used|use|watched|watch) (.+?)(?: today)?$"""
    )
    private val USAGE_C = Regex(
        """^(.+?) (?:usage|screen time)(?: today)?$"""
    )
    private val REPORT = Regex(
        """^(?:(?:what is|what's|whats|show|read|tell me|give me|get)(?: me)? )?(?:my |the )?(?:weekly (?:focus guard|guard|focus) report|(?:focus guard|guard) weekly report|accountability report)$"""
    )
    private val SEND_REPORT = Regex(
        """^(?:send|share|prepare)(?: me)?(?: my| the)? (?:weekly )?(?:focus guard|guard|accountability) report(?: to my(?: accountability)? partner)?$|^(?:send|share|prepare)(?: my| the)? weekly report to my(?: accountability)? partner$"""
    )
    private val PARTNER_WHO = Regex(
        """^(?:who is|what is|what's|whats) my (?:accountability|guard) partner$"""
    )
    private val PARTNER_OFF = Regex(
        """^(?:remove|delete|clear|drop) (?:my |the )?(?:accountability|guard) partner$"""
    )
    private val PARTNER_A = Regex(
        """^(?:set |make |add )?(?:my |an? |the )?(?:accountability partner|guard partner)(?: to| is| as)? (.+)$"""
    )
    private val PARTNER_B = Regex("""^(.+?) is my (?:accountability|guard) partner$""")
    private val REPORT_ON = Regex(
        """^(?:turn on|enable|start) (?:the |my )?weekly (?:focus guard|guard) reports?$"""
    )
    private val REPORT_OFF = Regex(
        """^(?:turn off|disable|stop) (?:the |my )?weekly (?:focus guard|guard) reports?$"""
    )
    private val LOCK_ON = Regex(
        """^(?:turn on|enable|start|switch on) (?:the |my )?(?:urge mode )?settings lock$|^lock (?:my )?settings (?:during|in) urge mode$"""
    )
    private val LOCK_OFF = Regex(
        """^(?:turn off|disable|stop|switch off) (?:the |my )?(?:urge mode )?settings lock$|^(?:don't|dont|do not) lock (?:my )?settings (?:during|in) urge mode$"""
    )
    private val UNLOCK = Regex(
        """^unlock (?:my |the )?(?:guard|focus guard) settings$|^(?:open|start) (?:the )?guard settings window$"""
    )
    private val LOCK_NOW = Regex("""^lock (?:my |the )?(?:guard|focus guard) settings(?: again)?$""")
    private val ALWAYS_ON = Regex(
        """^(?:always lock|lock always) (?:my |the )?(?:guard|focus guard) settings$|^(?:turn on|enable) (?:the )?always lock$"""
    )
    private val ALWAYS_OFF = Regex(
        """^lock (?:my |the )?(?:guard|focus guard) settings only (?:during|in) urge mode$"""
    )
    private val ALERTS_ON = Regex(
        """^(?:turn on|enable|start) (?:the )?(?:partner|accountability) alerts?$|^tell my (?:accountability )?partner if (?:the )?guard (?:is )?(?:switched|turned) off$"""
    )
    private val ALERTS_OFF = Regex(
        """^(?:turn off|disable|stop) (?:the )?(?:partner|accountability) alerts?$|^stop telling my (?:accountability )?partner$"""
    )
    private val DNS_ON = Regex(
        """^(?:watch|monitor|protect) my private dns$|^(?:turn on|enable) (?:the )?dns watch$"""
    )
    private val DNS_OFF = Regex(
        """^(?:stop watching|unwatch) my private dns$|^(?:turn off|disable) (?:the )?dns watch$"""
    )
    private val SCHED_SET = Regex("""^block (.+?) (?:from|between) (.+?) (?:to|till|until|and) (.+)$""")
    private val SCHED_RM = Regex("""^(?:remove|delete|stop) (?:the |my )?(.+?) (?:schedule|night block)$""")
    private val SCHED_LIST = Regex(
        """^(?:what|which) apps (?:are )?(?:scheduled|blocked at night)$|^(?:list|show|read|tell me)(?: me)?(?: my)? (?:app )?schedules$"""
    )
    private val URGE_WHEN = Regex(
        """^when do i (?:get|have) (?:my )?urges$|^(?:what is|what's|whats|show|tell me) my urge pattern$"""
    )

    private fun minutesOf(clock: String?): Int? {
        if (clock == null) return null
        return clock.substring(0, 2).toInt() * 60 + clock.substring(3, 5).toInt()
    }
    private val NOT_A_NAME = setOf("is", "to", "as", "a", "an", "the", "my", "who", "what", "it", "that")
    /** Lower case, polite filler ("can you", "please", "now" ...) removed. */
    fun norm(raw: String): String =
        IntentScorer.tokens(raw, emptyList(), false).joinToString(" ")

    private fun tidy(s: String): String =
        s.trim().removePrefix("my ").removePrefix("the ").removeSuffix(" app").trim()

    private fun isSite(kind: String): Boolean =
        kind == "site" || kind == "website" || kind == "domain"

    private val NOT_AN_APP = setOf("my", "the", "all", "everything", "it", "that")

    /** Is [raw] a Focus Guard command? Null if not. */
    fun parse(raw: String): Cmd? {

        val t = norm(raw)
        if (t.isEmpty()) return null

        if (CANCEL.matches(t)) return Cmd.CancelRequest
        if (SHOW.matches(t)) return Cmd.Show
        if (STATUS.matches(t) || LIMITS_LIST.matches(t)) return Cmd.Status

        if (PARTNER_WHO.matches(t)) return Cmd.PartnerWho
        if (PARTNER_OFF.matches(t)) return Cmd.PartnerOff
        if (REPORT.matches(t)) return Cmd.Report
        if (SEND_REPORT.matches(t)) return Cmd.SendReport
        if (REPORT_ON.matches(t)) return Cmd.ReportReminders(true)
        if (REPORT_OFF.matches(t)) return Cmd.ReportReminders(false)
        if (LOCK_ON.matches(t)) return Cmd.SettingsLock(true)
        if (LOCK_OFF.matches(t)) return Cmd.SettingsLock(false)
        if (UNLOCK.matches(t)) return Cmd.UnlockSettings
        if (LOCK_NOW.matches(t)) return Cmd.LockSettingsNow
        if (ALWAYS_ON.matches(t)) return Cmd.LockAlways(true)
        if (ALWAYS_OFF.matches(t)) return Cmd.LockAlways(false)
        if (ALERTS_ON.matches(t)) return Cmd.Alerts(true)
        if (ALERTS_OFF.matches(t)) return Cmd.Alerts(false)
        if (DNS_ON.matches(t)) return Cmd.WatchDns(true)
        if (DNS_OFF.matches(t)) return Cmd.WatchDns(false)

        val partner = PARTNER_A.matchEntire(t) ?: PARTNER_B.matchEntire(t)

        if (partner != null) {
            val name = tidy(partner.groupValues[1])
            if (name.isEmpty() || name in NOT_A_NAME) {
                return Cmd.Say("Who should it be? Say, for example: set my accountability partner to Ravi.")
            }
            return Cmd.Partner(name)
        }

        if (MASTER_ON.matches(t)) return Cmd.Master(true)
        if (MASTER_OFF.matches(t)) return Cmd.Master(false)
        if (SHORTS_ON.matches(t)) return Cmd.Shorts(true)
        if (SHORTS_OFF.matches(t)) return Cmd.Shorts(false)

        if (URGE_STATUS.matches(t)) return Cmd.UrgeStatus
        if (URGE_END.matches(t)) return Cmd.UrgeEnd
        if (URGE.matches(t)) return Cmd.Urge

        if (WORD_LIST.matches(t)) return Cmd.WordList
        if (FILTER_ON.matches(t)) return Cmd.WordFilter(true)
        if (FILTER_OFF.matches(t)) return Cmd.WordFilter(false)

        val block = BLOCK_A.matchEntire(t) ?: BLOCK_B.matchEntire(t)
        if (block != null) return Cmd.BlockWord(block.groupValues[2].trim(), isSite(block.groupValues[1]))

        val unblock = UNBLOCK_A.matchEntire(t) ?: UNBLOCK_B.matchEntire(t)
        if (unblock != null) return Cmd.UnblockWord(unblock.groupValues[2].trim(), isSite(unblock.groupValues[1]))
        if (SCHED_LIST.matches(t)) return Cmd.ScheduleList
        if (URGE_WHEN.matches(t)) return Cmd.UrgePattern

        SCHED_SET.matchEntire(t)?.let { m ->
            val s = minutesOf(Mind.parseClock(m.groupValues[2]))
            val e = minutesOf(Mind.parseClock(m.groupValues[3]))
            val app = tidy(m.groupValues[1])
            if (s != null && e != null && app.isNotEmpty() && app !in NOT_AN_APP) {
                return if (s == e) {
                    Cmd.Say("Those two times are the same. Say, for example: block instagram from 10 pm to 6 am.")
                } else {
                    Cmd.SetSchedule(app, s, e)
                }
            }
        }

        SCHED_RM.matchEntire(t)?.let { m -> return Cmd.RemoveSchedule(tidy(m.groupValues[1])) }
        val rm = REMOVE_A.matchEntire(t) ?: REMOVE_B.matchEntire(t)
        if (rm != null) return Cmd.RemoveLimit(tidy(rm.groupValues[1]))

        val set = SET_A.matchEntire(t) ?: SET_B.matchEntire(t)
        if (set != null) {
            val app = tidy(set.groupValues[1])
            if (app.isEmpty() || app in NOT_AN_APP) {
                return Cmd.Say("Which app? Say, for example: limit youtube to 1 hour.")
            }
            val minutes = Num.parseDuration(set.groupValues[2])
                ?: return Cmd.Say("I didn't catch the length. Say, for example: limit $app to 1 hour.")
            return Cmd.SetLimit(app, minutes)
        }

        if (USAGE_ALL.matches(t)) return Cmd.Usage(null)

        for (rx in listOf(USAGE_A, USAGE_B, USAGE_C)) {
            val m = rx.matchEntire(t) ?: continue
            val app = tidy(m.groupValues[1])
            return Cmd.Usage(if (app.isEmpty() || app in NOT_AN_APP) null else app)
        }

        return null
    }
}
