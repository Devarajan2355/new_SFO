package com.sanya.assistant

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * ProactiveRules: the pure decisions behind Sanya speaking first. No Android calls, so they are
 * unit-tested (ProactiveRulesTest). Proactive.kt gathers the facts and acts on the answers.
 *
 * The ideas, in plain words:
 *
 *   HARD nudges   things you asked for or that cannot wait: meeting heads-up, routine at its time,
 *                 study break, missed call, critical battery. They ALWAYS arrive: spoken if she can
 *                 speak, otherwise as a notification.
 *   SOFT nudges   things that are only helpful: a free gap, tasks due, a habit, an alarm for
 *                 tomorrow, budget pace. They only speak when it is polite, and wait otherwise.
 *
 *   Polite means: screen on and unlocked, no call, no music, not in Do Not Disturb, ringer on, not in
 *   a meeting, not in your quiet hours, and not too soon after the last nudge.
 *
 *   Learning: every offer she makes is answered yes, no, or ignored. Yes lifts that kind. No, and
 *   silence, add strikes. Enough strikes and that kind rests for 3, 7 or 14 days. Hard kinds are
 *   never rested automatically (you can switch them off yourself).
 */
object ProactiveRules {

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** No soft nudge within this long of ANY nudge. */
    const val ANY_GAP_MS = 20 * MINUTE_MS

    /** No soft nudge within this long of the last SOFT nudge. */
    const val SOFT_GAP_MS = 45 * MINUTE_MS

    /** At or below this the battery warning cannot be silenced by quiet hours. */
    const val CRITICAL_BATTERY = 8

    // ---------------------------------------------------------------
    // The kinds of nudge
    // ---------------------------------------------------------------

    /** key is used in settings ("nudge battery = off"); label is what she says aloud. */
    enum class Type(
        val key: String,
        val label: String,
        val hard: Boolean,
        val base: Int,
        val cooldownMs: Long
    ) {
        BATTERY("battery", "battery warnings", true, 90, 0L),
        MEETING("meeting", "meeting alerts", true, 100, 0L),
        ROUTINE("routine", "routine reminders", true, 80, 0L),
        STUDY("study", "study break reminders", true, 70, 0L),
        BRIEF("brief", "the morning brief", true, 95, 0L),
        MISSED("missed", "missed call offers", true, 85, 0L),
        GUARD("guard", "focus guard warnings", true, 60, 0L),
        DATES("dates", "birthday and date alerts", false, 75, 0L),
        TASKS("tasks", "task reminders", false, 65, 20 * HOUR_MS),
        BUDGET("budget", "budget warnings", false, 55, 5 * DAY_MS),
        GAP("gap", "free time suggestions", false, 50, 90 * MINUTE_MS),
        BEDTIME("bedtime", "alarm suggestions", false, 60, 20 * HOUR_MS),
        HABIT("habit", "habit suggestions", false, 40, 3 * HOUR_MS),
        CELEBRATE("celebrate", "well done messages", false, 20, 20 * HOUR_MS);

        companion object {
            fun byKey(k: String): Type? = values().firstOrNull { it.key == k }
        }
    }

    // ---------------------------------------------------------------
    // Learning from your answers
    // ---------------------------------------------------------------

    data class Stat(
        val shown: Int = 0,
        val accepted: Int = 0,
        val declined: Int = 0,
        val ignored: Int = 0,
        val strikes: Int = 0,
        val lastShown: Long = 0L,
        val mutedUntil: Long = 0L
    )

    enum class Reply { ACCEPT, DECLINE, IGNORE }

    /** How long a kind rests after this many strikes. A "no" is 2 strikes, silence is 1. */
    fun muteDays(strikes: Int): Int = when {
        strikes >= 8 -> 14
        strikes >= 6 -> 7
        strikes >= 4 -> 3
        else -> 0
    }

    fun applyReply(s: Stat, r: Reply, now: Long, canMute: Boolean): Stat {

        val next = when (r) {
            Reply.ACCEPT -> s.copy(accepted = s.accepted + 1, strikes = 0, mutedUntil = 0L)
            Reply.DECLINE -> s.copy(declined = s.declined + 1, strikes = s.strikes + 2)
            Reply.IGNORE -> s.copy(ignored = s.ignored + 1, strikes = s.strikes + 1)
        }

        if (r == Reply.ACCEPT || !canMute) return next

        val days = muteDays(next.strikes)

        return if (days > 0) next.copy(mutedUntil = now + days * DAY_MS) else next
    }

    fun isMuted(s: Stat, now: Long): Boolean = s.mutedUntil > now

    fun encodeLedger(m: Map<String, Stat>): String =
        m.entries.joinToString(";") { e ->
            val s = e.value
            listOf(e.key, s.shown, s.accepted, s.declined, s.ignored, s.strikes, s.lastShown, s.mutedUntil)
                .joinToString(",")
        }

    fun decodeLedger(raw: String): Map<String, Stat> {

        val out = LinkedHashMap<String, Stat>()

        for (part in raw.split(";")) {

            val p = part.split(",")
            if (p.size < 8 || p[0].isEmpty()) continue

            val shown = p[1].toIntOrNull() ?: continue
            val accepted = p[2].toIntOrNull() ?: continue
            val declined = p[3].toIntOrNull() ?: continue
            val ignored = p[4].toIntOrNull() ?: continue
            val strikes = p[5].toIntOrNull() ?: continue
            val last = p[6].toLongOrNull() ?: continue
            val mute = p[7].toLongOrNull() ?: continue

            out[p[0]] = Stat(shown, accepted, declined, ignored, strikes, last, mute)
        }

        return out
    }

    // ---------------------------------------------------------------
    // Candidates and choosing one
    // ---------------------------------------------------------------

    /** One thing she could say. [id] is unique for the occasion ("gap:29012345"), so it is said once. */
    class Cand(
        val type: Type,
        val id: String,
        val text: String,
        val offer: String = "",
        val run: String = "",
        val urgency: Int = 0,
        val why: String = ""
    )

    /** Base weight of the kind, plus urgency, plus what you have taught her (up to +/- 15). */
    fun score(c: Cand, s: Stat): Int {
        val bonus = minOf(15, s.accepted * 3) - minOf(15, s.declined * 3)
        return c.type.base + c.urgency + bonus
    }

    /** The best candidate. On a tie the first one listed wins. */
    fun pick(cands: List<Cand>, ledger: Map<String, Stat>): Cand? =
        cands.maxByOrNull { score(it, ledger[it.type.key] ?: Stat()) }

    /**
     * May a SOFT nudge of this kind be said right now? (Politeness about the device is separate:
     * see delivery().) The bedtime alarm offer is allowed during quiet hours on purpose: that is
     * exactly when it is useful.
     */
    fun softOk(
        type: Type,
        s: Stat,
        now: Long,
        quiet: Boolean,
        count: Int,
        limit: Int,
        lastAny: Long,
        lastSoft: Long
    ): Boolean {

        if (type.hard) return false
        if (quiet && type != Type.BEDTIME) return false
        if (count >= limit) return false
        if (now - lastAny < ANY_GAP_MS) return false
        if (now - lastSoft < SOFT_GAP_MS) return false
        if (isMuted(s, now)) return false

        return now - s.lastShown >= type.cooldownMs
    }

    // ---------------------------------------------------------------
    // Speak, write, wait or drop
    // ---------------------------------------------------------------

    class Presence(
        val serviceAlive: Boolean,
        val screenOn: Boolean,
        val locked: Boolean,
        val music: Boolean,
        val inCall: Boolean,
        val dnd: Boolean,
        val ringerNormal: Boolean,
        val inMeeting: Boolean
    )

    enum class Delivery { SPEAK, NOTIFY, DEFER, DROP }

    fun voiceOk(p: Presence): Boolean =
        p.serviceAlive && p.screenOn && !p.locked && !p.music && !p.inCall &&
            !p.dnd && p.ringerNormal && !p.inMeeting

    /**
     * hard      always gets through: spoken if possible, else a notification.
     * voiceOnly (the morning brief) is only worth saying out loud: it waits for you to pick up the phone.
     * snoozed   "quiet mode": soft nudges are dropped, hard ones become silent notifications.
     */
    fun delivery(hard: Boolean, voiceOnly: Boolean, p: Presence, snoozed: Boolean): Delivery {

        val voice = voiceOk(p) && !snoozed

        if (hard) {
            return if (voice) Delivery.SPEAK else if (voiceOnly) Delivery.DEFER else Delivery.NOTIFY
        }

        if (snoozed) return Delivery.DROP

        return if (voice) Delivery.SPEAK else Delivery.DEFER
    }

    // ---------------------------------------------------------------
    // Your calendar as she sees it
    // ---------------------------------------------------------------

    data class Slot(val begin: Long, val end: Long, val allDay: Boolean, val title: String)

    /**
     * True during a real meeting. All-day events, blips under 10 minutes and long blocks over 4 hours
     * ("Work 9 to 5") do not count, or she would be silent all day.
     */
    fun inMeeting(events: List<Slot>, now: Long): Boolean =
        events.any {
            !it.allDay && it.begin <= now && it.end > now &&
                (it.end - it.begin) in (10 * MINUTE_MS)..(4 * HOUR_MS)
        }

    fun nextTimed(events: List<Slot>, now: Long): Slot? =
        events.filter { !it.allDay && it.begin > now }.minByOrNull { it.begin }

    /**
     * Free now, and the next timed event is [minMin] to [maxMin] minutes away: that is a useful gap.
     * Returns the event and the minutes until it, or null.
     */
    fun freeGap(events: List<Slot>, now: Long, minMin: Int = 35, maxMin: Int = 180): Pair<Slot, Int>? {

        if (events.any { !it.allDay && it.begin <= now && it.end > now }) return null

        val next = nextTimed(events, now) ?: return null
        val mins = ((next.begin - now) / MINUTE_MS).toInt()

        return if (mins in minMin..maxMin) Pair(next, mins) else null
    }

    fun gapText(mins: Int, title: String, task: String?): String {

        val free = Num.durText(mins / 5 * 5)
        val t = title.trim().ifEmpty { "your next event" }.take(60)
        val ask = if (task == null) "Shall I start a pomodoro?" else "Shall I start a pomodoro for $task?"

        return "You have $free before $t. $ask"
    }

    fun gapOffer(task: String?): String =
        if (task == null) "start pomodoro" else "start pomodoro for $task"

    /** [text] is what she says; [alarm] is "HH:MM" for the command "alarm at HH:MM". */
    data class Bed(val alarm: String, val text: String)

    /**
     * Tomorrow's first timed event, if it is before 11 am: suggest an alarm [leadMin] minutes earlier
     * (rounded down to 5 minutes). Nothing before 4 am, and nothing for late events.
     */
    fun bedtime(tomorrow: List<Slot>, zone: ZoneId, leadMin: Int): Bed? {

        val first = tomorrow.filter { !it.allDay }.minByOrNull { it.begin } ?: return null
        val z = Instant.ofEpochMilli(first.begin).atZone(zone)
        val start = z.hour * 60 + z.minute

        if (start >= 11 * 60) return null

        val alarm = (start - leadMin) / 5 * 5

        if (alarm < 4 * 60) return null

        val title = first.title.trim().ifEmpty { "an event" }.take(60)
        val hh = (alarm / 60).toString().padStart(2, '0')
        val mm = (alarm % 60).toString().padStart(2, '0')

        return Bed(
            "$hh:$mm",
            "Tomorrow you have $title at ${ReminderParse.clock12(start)}. " +
                "Shall I set an alarm for ${ReminderParse.clock12(alarm)}?"
        )
    }

    // ---------------------------------------------------------------
    // Battery
    // ---------------------------------------------------------------

    enum class BatKind { NONE, LOW, CRITICAL }

    /**
     * Critical (8% or less) is always said. Low is said at [warnAt] percent, or at 20 when a meeting is
     * within 3 hours, and never in quiet hours. A charging phone is never warned.
     */
    fun batteryKind(level: Int, charging: Boolean, quiet: Boolean, warnAt: Int, eventSoon: Boolean): BatKind {

        if (level < 0 || charging) return BatKind.NONE
        if (level <= CRITICAL_BATTERY) return BatKind.CRITICAL
        if (quiet) return BatKind.NONE

        val limit = if (eventSoon) maxOf(warnAt, 20) else warnAt

        return if (level <= limit) BatKind.LOW else BatKind.NONE
    }

    fun batteryText(kind: BatKind, level: Int, nextTitle: String?, minsToEvent: Int): String = when (kind) {
        BatKind.CRITICAL -> "Battery is at $level percent. Please charge now."
        BatKind.LOW ->
            if (nextTitle != null && minsToEvent in 0..180) {
                "Battery is at $level percent, and you have ${nextTitle.take(60)} in " +
                    Num.durText(maxOf(minsToEvent, 1)) + "."
            } else {
                "Battery is at $level percent. You might want to charge."
            }
        BatKind.NONE -> ""
    }

    // ---------------------------------------------------------------
    // Money pace
    // ---------------------------------------------------------------

    /** level 0 fine, 1 spending faster than the month is passing, 2 budget used up. */
    data class Pace(val level: Int, val spentPct: Int, val timePct: Int)

    fun budgetPace(spent: Long, budget: Long, day: Int, daysInMonth: Int): Pace {

        if (budget <= 0L || daysInMonth <= 0) return Pace(0, 0, 0)

        val sp = (spent * 100 / budget).toInt()
        val tp = day * 100 / daysInMonth

        val level = when {
            sp >= 100 -> 2
            sp >= 50 && sp - tp >= 20 -> 1
            else -> 0
        }

        return Pace(level, sp, tp)
    }

    fun budgetText(p: Pace): String =
        if (p.level >= 2) {
            "You've used ${p.spentPct} percent of your monthly budget."
        } else {
            "You've used ${p.spentPct} percent of your monthly budget, " +
                "and only ${p.timePct} percent of the month has passed."
        }

    // ---------------------------------------------------------------
    // Small ones
    // ---------------------------------------------------------------

    fun celebrate(total: Int, remaining: Int, minutesNow: Int): Boolean =
        total >= 2 && remaining == 0 && minutesNow >= 17 * 60

    /** [name] has a leading space (" Dean") or is "". */
    fun celebrateText(total: Int, name: String): String =
        "All $total routine activities are done today. Nice work$name."

    private val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")
    private val RANGE = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$""")

    /** "07:30" -> minutes after midnight, or null. */
    fun parseClock(raw: String): Int? {
        val m = CLOCK.find(raw.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) null else h * 60 + mi
    }

    /** [raw] is "22:00-07:00". Anything unreadable means 22:00 to 07:00. */
    fun inQuiet(minutes: Int, raw: String): Boolean {

        val m = RANGE.find(raw.trim()) ?: return minutes >= 22 * 60 || minutes < 7 * 60

        val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        val end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()

        return if (start <= end) minutes in start until end else minutes >= start || minutes < end
    }

    /** The minute quiet hours end, for "quiet until tomorrow". */
    fun quietEnd(raw: String): Int {
        val m = RANGE.find(raw.trim()) ?: return 7 * 60
        return m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
    }

    /** Minutes from [nowMin] to the next [targetMin] (a full day if it is exactly now). */
    fun minutesUntil(nowMin: Int, targetMin: Int): Int {
        val d = (targetMin - nowMin + 1440) % 1440
        return if (d == 0) 1440 else d
    }

    /**
     * When to wake next: the soonest time in [times] that is more than 20 seconds away, but never later
     * than [maxGapMs] from now, so a changed calendar is noticed.
     */
    fun nextWake(now: Long, times: List<Long>, maxGapMs: Long = 15 * MINUTE_MS): Long {

        val soon = times.filter { it > now + 20_000L }.minOrNull()
        val cap = now + maxGapMs

        return if (soon == null || soon > cap) cap else soon
    }

    // ---------------------------------------------------------------
    // Habits: said on different DAYS around this time, not three times in one afternoon
    // ---------------------------------------------------------------

    data class Hist(val ts: Long, val ok: Boolean, val text: String)

    data class Habit(val text: String, val days: Int)

    private val BORING = Regex(
        """^(?:again|hello|hi|hey|hai|thanks|thank you|yes|no|ok|okay|stop|exit|good morning|good afternoon|good evening|good night|yes\s?\w*)$"""
    )

    fun meaningful(text: String): Boolean = text.length >= 4 && !BORING.matches(text)

    /** Reads files/mind.log: timestamp|hour|ok|text */
    fun parseHistory(lines: List<String>): List<Hist> {

        val out = ArrayList<Hist>()

        for (line in lines) {
            val p = line.split("|", limit = 4)
            if (p.size < 4) continue
            val ts = p[0].toLongOrNull() ?: continue
            out.add(Hist(ts, p[2] == "1", p[3]))
        }

        return out
    }

    private fun weekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

    /**
     * What you usually say around this time of day. Only EARLIER days count (today never does), within
     * the last 28 days and within an hour of now. It needs 3 different days of the same kind (weekday or
     * weekend), or 5 different days of any kind. [blocked] words (call, delete ...) are never suggested.
     */
    fun habit(history: List<Hist>, now: Long, zone: ZoneId, blocked: Regex? = null): Habit? {

        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZ.toLocalDate()
        val hourNow = nowZ.hour
        val weekendNow = weekend(nowZ.dayOfWeek)
        val from = today.minusDays(28)

        val same = HashMap<String, HashSet<LocalDate>>()
        val other = HashMap<String, HashSet<LocalDate>>()

        for (h in history) {

            if (!h.ok || !meaningful(h.text)) continue
            if (blocked != null && blocked.containsMatchIn(h.text)) continue

            val z = Instant.ofEpochMilli(h.ts).atZone(zone)
            val d = z.toLocalDate()

            if (!d.isBefore(today) || d.isBefore(from)) continue

            val diff = abs(z.hour - hourNow)
            if (minOf(diff, 24 - diff) > 1) continue

            val bucket = if (weekend(z.dayOfWeek) == weekendNow) same else other

            bucket.getOrPut(h.text) { HashSet() }.add(d)
        }

        var best: Habit? = null
        var bestSame = -1
        var bestAll = -1

        for (text in (same.keys + other.keys).sorted()) {

            val s = same[text]?.size ?: 0
            val all = s + (other[text]?.size ?: 0)

            if (!(s >= 3 || all >= 5)) continue

            if (s > bestSame || (s == bestSame && all > bestAll)) {
                best = Habit(text, if (s >= 3) s else all)
                bestSame = s
                bestAll = all
            }
        }

        return best
    }
}
