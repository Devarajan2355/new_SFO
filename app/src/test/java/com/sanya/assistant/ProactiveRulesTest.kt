package com.sanya.assistant

import com.sanya.assistant.ProactiveRules.BatKind
import com.sanya.assistant.ProactiveRules.Cand
import com.sanya.assistant.ProactiveRules.Delivery
import com.sanya.assistant.ProactiveRules.Hist
import com.sanya.assistant.ProactiveRules.Presence
import com.sanya.assistant.ProactiveRules.Reply
import com.sanya.assistant.ProactiveRules.Slot
import com.sanya.assistant.ProactiveRules.Stat
import com.sanya.assistant.ProactiveRules.Type
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRulesTest {

    private val utc = ZoneId.of("UTC")
    private val min = ProactiveRules.MINUTE_MS
    private val hour = ProactiveRules.HOUR_MS
    private val day = ProactiveRules.DAY_MS

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    // ---------------- learning from your answers ----------------

    @Test
    fun twoNosMakeAKindRestForThreeDays() {
        val now = 1_000_000_000L
        var s = Stat()
        s = ProactiveRules.applyReply(s, Reply.DECLINE, now, true)
        assertEquals(2, s.strikes)
        assertEquals(0L, s.mutedUntil)
        s = ProactiveRules.applyReply(s, Reply.DECLINE, now, true)
        assertEquals(4, s.strikes)
        assertEquals(now + 3 * day, s.mutedUntil)
        assertTrue(ProactiveRules.isMuted(s, now + day))
        assertFalse(ProactiveRules.isMuted(s, now + 4 * day))
    }

    @Test
    fun aYesClearsTheStrikes() {
        val now = 1_000_000_000L
        var s = ProactiveRules.applyReply(Stat(), Reply.DECLINE, now, true)
        s = ProactiveRules.applyReply(s, Reply.ACCEPT, now, true)
        assertEquals(0, s.strikes)
        assertEquals(1, s.accepted)
    }

    @Test
    fun silenceCountsLessThanANo() {
        val now = 1_000_000_000L
        var s = Stat()
        for (i in 1..4) s = ProactiveRules.applyReply(s, Reply.IGNORE, now, true)
        assertEquals(4, s.strikes)
        assertEquals(now + 3 * day, s.mutedUntil)
    }

    @Test
    fun restingGetsLongerEachTime() {
        val now = 1_000_000_000L
        var s = Stat(strikes = 4)
        s = ProactiveRules.applyReply(s, Reply.DECLINE, now, true)
        assertEquals(now + 7 * day, s.mutedUntil)
        s = ProactiveRules.applyReply(s, Reply.DECLINE, now, true)
        assertEquals(now + 14 * day, s.mutedUntil)
    }

    @Test
    fun hardKindsAreNeverRestedAutomatically() {
        val now = 1_000_000_000L
        var s = Stat()
        for (i in 1..5) s = ProactiveRules.applyReply(s, Reply.DECLINE, now, false)
        assertEquals(10, s.strikes)
        assertEquals(0L, s.mutedUntil)
    }

    @Test
    fun ledgerSurvivesARoundTrip() {
        val m = mapOf(
            "habit" to Stat(3, 1, 1, 0, 2, 123L, 456L),
            "gap" to Stat()
        )
        assertEquals(m, ProactiveRules.decodeLedger(ProactiveRules.encodeLedger(m)))
    }

    @Test
    fun aDamagedLedgerIsIgnored() {
        assertEquals(emptyMap<String, Stat>(), ProactiveRules.decodeLedger("junk;a,b,c;,,,,,,,"))
    }

    @Test
    fun everyKindHasAUniqueKey() {
        val keys = Type.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(Type.HABIT, Type.byKey("habit"))
        assertNull(Type.byKey("nope"))
    }

    // ---------------- habits: different days, not one busy afternoon ----------------

    private val friday = ms(2026, 10, 16, 21, 5)

    private fun h(d: Int, hour: Int, mi: Int, text: String = "open youtube", ok: Boolean = true, mo: Int = 10) =
        Hist(ms(2026, mo, d, hour, mi), ok, text)

    @Test
    fun threeDifferentWeekdaysMakeAHabit() {
        val hist = listOf(h(13, 21, 10), h(14, 20, 50), h(15, 21, 30))
        assertEquals(ProactiveRules.Habit("open youtube", 3), ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun oneBusyAfternoonIsNotAHabit() {
        val hist = listOf(h(15, 21, 0), h(15, 21, 10), h(15, 21, 20), h(15, 21, 30))
        assertNull(ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun todayNeverCounts() {
        val hist = listOf(h(16, 21, 0), h(16, 20, 50), h(16, 21, 2))
        assertNull(ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun aWeekendHabitIsNotSuggestedOnAWeekday() {
        val hist = listOf(h(10, 21, 0), h(11, 21, 0), h(3, 21, 0))
        assertNull(ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun fiveMixedDaysAlsoCount() {
        val hist = listOf(h(10, 21, 0), h(11, 21, 0), h(3, 21, 0), h(4, 21, 0), h(9, 21, 0))
        assertEquals(ProactiveRules.Habit("open youtube", 5), ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun twoHoursAwayIsNotThisTimeOfDay() {
        val hist = listOf(h(13, 19, 0), h(14, 19, 0), h(15, 19, 0))
        assertNull(ProactiveRules.habit(hist, friday, utc))
    }

    @Test
    fun misunderstoodAndBoringRequestsAreIgnored() {
        val bad = listOf(h(13, 21, 0, ok = false), h(14, 21, 0, ok = false), h(15, 21, 0, ok = false))
        assertNull(ProactiveRules.habit(bad, friday, utc))
        val boring = listOf(h(13, 21, 0, "hello"), h(14, 21, 0, "hello"), h(15, 21, 0, "hello"))
        assertNull(ProactiveRules.habit(boring, friday, utc))
    }

    @Test
    fun blockedWordsAreNeverSuggested() {
        val rx = Regex("""\b(?:call|message)\b""")
        val hist = listOf(h(13, 21, 0, "call ravi"), h(14, 21, 0, "call ravi"), h(15, 21, 0, "call ravi"))
        assertNull(ProactiveRules.habit(hist, friday, utc, rx))
    }

    @Test
    fun theMindLogIsRead() {
        val lines = listOf("1700000000000|21|1|open youtube", "bad line", "x|1|1|nope", "1700000000001|9|0|call ravi")
        val hist = ProactiveRules.parseHistory(lines)
        assertEquals(2, hist.size)
        assertEquals(Hist(1700000000000L, true, "open youtube"), hist[0])
        assertFalse(hist[1].ok)
    }

    // ---------------- your calendar ----------------

    private val noon = ms(2026, 10, 16, 14, 0)

    private fun ev(b: Long, e: Long, allDay: Boolean = false, title: String = "Standup") = Slot(b, e, allDay, title)

    @Test
    fun aFreeStretchBeforeAMeeting() {
        val e = ev(noon + 60 * min, noon + 90 * min)
        assertEquals(60, ProactiveRules.freeGap(listOf(e), noon)!!.second)
        assertEquals(e, ProactiveRules.freeGap(listOf(e), noon)!!.first)
    }

    @Test
    fun gapsThatAreTooShortOrTooLongAreSkipped() {
        assertNull(ProactiveRules.freeGap(listOf(ev(noon + 20 * min, noon + 50 * min)), noon))
        assertNull(ProactiveRules.freeGap(listOf(ev(noon + 5 * hour, noon + 6 * hour)), noon))
    }

    @Test
    fun noGapWhileInAMeeting() {
        val now = ev(noon - 10 * min, noon + 30 * min)
        val later = ev(noon + 60 * min, noon + 90 * min)
        assertNull(ProactiveRules.freeGap(listOf(now, later), noon))
    }

    @Test
    fun allDayEventsDoNotBlockAGap() {
        val holiday = ev(noon - hour, noon + 8 * hour, true, "Holiday")
        val later = ev(noon + 60 * min, noon + 90 * min)
        assertEquals(60, ProactiveRules.freeGap(listOf(holiday, later), noon)!!.second)
    }

    @Test
    fun onlyARealMeetingMakesHerQuiet() {
        assertTrue(ProactiveRules.inMeeting(listOf(ev(noon - 10 * min, noon + 30 * min)), noon))
        assertFalse(ProactiveRules.inMeeting(listOf(ev(noon - hour, noon + 8 * hour, true)), noon))
        assertFalse(ProactiveRules.inMeeting(listOf(ev(noon - hour, noon + 7 * hour)), noon))
        assertFalse(ProactiveRules.inMeeting(listOf(ev(noon - 2 * min, noon + 3 * min)), noon))
    }

    @Test
    fun gapTextAndOffer() {
        assertEquals(
            "You have 55 minutes before Standup. Shall I start a pomodoro for japanese?",
            ProactiveRules.gapText(58, "Standup", "japanese")
        )
        assertEquals("You have 1 hour before your next event. Shall I start a pomodoro?", ProactiveRules.gapText(60, " ", null))
        assertEquals("start pomodoro for japanese", ProactiveRules.gapOffer("japanese"))
        assertEquals("start pomodoro", ProactiveRules.gapOffer(null))
    }

    // ---------------- an alarm for tomorrow ----------------

    private fun tomorrow(h: Int, mi: Int, title: String = "Standup") =
        ev(ms(2026, 10, 17, h, mi), ms(2026, 10, 17, h + 1, mi), false, title)

    @Test
    fun nineAmMeetingGetsAnAlarmAtSevenThirty() {
        val b = ProactiveRules.bedtime(listOf(tomorrow(9, 0)), utc, 90)!!
        assertEquals("07:30", b.alarm)
        assertEquals("Tomorrow you have Standup at 9 am. Shall I set an alarm for 7:30 am?", b.text)
    }

    @Test
    fun theAlarmRoundsDownToFiveMinutes() {
        assertEquals("07:40", ProactiveRules.bedtime(listOf(tomorrow(9, 10)), utc, 90)!!.alarm)
    }

    @Test
    fun lateOrVeryEarlyEventsGetNoOffer() {
        assertNull(ProactiveRules.bedtime(listOf(tomorrow(11, 30)), utc, 90))
        assertNull(ProactiveRules.bedtime(listOf(tomorrow(5, 0)), utc, 90))
        assertEquals("04:30", ProactiveRules.bedtime(listOf(tomorrow(6, 0)), utc, 90)!!.alarm)
    }

    @Test
    fun theEarliestEventDecidesAndAllDayEventsAreIgnored() {
        assertEquals("07:00", ProactiveRules.bedtime(listOf(tomorrow(10, 0), tomorrow(8, 0)), utc, 60)!!.alarm)
        val allDay = ev(ms(2026, 10, 17), ms(2026, 10, 18), true, "Holiday")
        assertNull(ProactiveRules.bedtime(listOf(allDay), utc, 90))
    }

    // ---------------- battery and money ----------------

    @Test
    fun batteryKinds() {
        assertEquals(BatKind.NONE, ProactiveRules.batteryKind(5, true, false, 15, false))
        assertEquals(BatKind.CRITICAL, ProactiveRules.batteryKind(7, false, true, 15, false))
        assertEquals(BatKind.NONE, ProactiveRules.batteryKind(14, false, true, 15, false))
        assertEquals(BatKind.LOW, ProactiveRules.batteryKind(15, false, false, 15, false))
        assertEquals(BatKind.NONE, ProactiveRules.batteryKind(18, false, false, 15, false))
        assertEquals(BatKind.LOW, ProactiveRules.batteryKind(18, false, false, 15, true))
        assertEquals(BatKind.NONE, ProactiveRules.batteryKind(-1, false, false, 15, true))
    }

    @Test
    fun batteryWords() {
        assertEquals("Battery is at 6 percent. Please charge now.", ProactiveRules.batteryText(BatKind.CRITICAL, 6, null, 0))
        assertEquals("Battery is at 15 percent. You might want to charge.", ProactiveRules.batteryText(BatKind.LOW, 15, null, 0))
        assertEquals(
            "Battery is at 18 percent, and you have Standup in 1 hour.",
            ProactiveRules.batteryText(BatKind.LOW, 18, "Standup", 60)
        )
    }

    @Test
    fun budgetPace() {
        assertEquals(ProactiveRules.Pace(1, 70, 40), ProactiveRules.budgetPace(70, 100, 12, 30))
        assertEquals(0, ProactiveRules.budgetPace(45, 100, 12, 30).level)
        assertEquals(0, ProactiveRules.budgetPace(55, 100, 15, 30).level)
        assertEquals(2, ProactiveRules.budgetPace(100, 100, 25, 30).level)
        assertEquals(0, ProactiveRules.budgetPace(10, 0, 1, 30).level)
        assertEquals(
            "You've used 70 percent of your monthly budget, and only 40 percent of the month has passed.",
            ProactiveRules.budgetText(ProactiveRules.Pace(1, 70, 40))
        )
    }

    @Test
    fun celebratingNeedsRealWorkAndAnEveningHour() {
        assertTrue(ProactiveRules.celebrate(3, 0, 18 * 60))
        assertFalse(ProactiveRules.celebrate(1, 0, 18 * 60))
        assertFalse(ProactiveRules.celebrate(3, 1, 18 * 60))
        assertFalse(ProactiveRules.celebrate(3, 0, 12 * 60))
        assertEquals("All 3 routine activities are done today. Nice work Dean.", ProactiveRules.celebrateText(3, " Dean"))
    }

    // ---------------- speak, write, wait or drop ----------------

    private fun presence(
        alive: Boolean = true, screen: Boolean = true, locked: Boolean = false, music: Boolean = false,
        call: Boolean = false, dnd: Boolean = false, ringer: Boolean = true, meeting: Boolean = false
    ) = Presence(alive, screen, locked, music, call, dnd, ringer, meeting)

    @Test
    fun softNudgesSpeakOnlyWhenPolite() {
        assertEquals(Delivery.SPEAK, ProactiveRules.delivery(false, false, presence(), false))
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(false, false, presence(screen = false), false))
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(false, false, presence(locked = true), false))
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(false, false, presence(music = true), false))
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(false, false, presence(meeting = true), false))
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(false, false, presence(dnd = true), false))
        assertEquals(Delivery.DROP, ProactiveRules.delivery(false, false, presence(), true))
    }

    @Test
    fun hardNudgesAlwaysArrive() {
        assertEquals(Delivery.SPEAK, ProactiveRules.delivery(true, false, presence(), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(screen = false), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(alive = false), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(dnd = true), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(ringer = false), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(call = true), false))
        assertEquals(Delivery.NOTIFY, ProactiveRules.delivery(true, false, presence(), true))
    }

    @Test
    fun theBriefWaitsForYouToPickUpThePhone() {
        assertEquals(Delivery.DEFER, ProactiveRules.delivery(true, true, presence(screen = false), false))
        assertEquals(Delivery.SPEAK, ProactiveRules.delivery(true, true, presence(), false))
    }

    // ---------------- soft nudge limits ----------------

    private val n = 10 * day

    private fun ok(
        t: Type, s: Stat = Stat(), quiet: Boolean = false, count: Int = 0, last: Long = 0L, soft: Long = 0L
    ) = ProactiveRules.softOk(t, s, n, quiet, count, 4, last, soft)

    @Test
    fun softLimits() {
        assertTrue(ok(Type.HABIT))
        assertFalse(ok(Type.MEETING))                              // hard kinds are not soft
        assertFalse(ok(Type.HABIT, quiet = true))
        assertTrue(ok(Type.BEDTIME, quiet = true))                 // the bedtime offer is for the evening
        assertFalse(ok(Type.HABIT, count = 4))
        assertFalse(ok(Type.HABIT, last = n - 10 * min))
        assertFalse(ok(Type.HABIT, soft = n - 30 * min))
        assertFalse(ok(Type.HABIT, Stat(mutedUntil = n + 1)))
        assertTrue(ok(Type.HABIT, Stat(mutedUntil = n - 1)))
        assertFalse(ok(Type.HABIT, Stat(lastShown = n - hour)))    // habit cooldown is 3 hours
        assertTrue(ok(Type.HABIT, Stat(lastShown = n - 4 * hour)))
    }

    @Test
    fun theBestCandidateWinsAndYourAnswersMatter() {
        val dates = Cand(Type.DATES, "d", "x")
        val tasks = Cand(Type.TASKS, "t", "y")
        assertEquals(dates, ProactiveRules.pick(listOf(tasks, dates), emptyMap()))
        // you keep saying no to dates: tasks now win
        assertEquals(tasks, ProactiveRules.pick(listOf(tasks, dates), mapOf("dates" to Stat(declined = 10))))
        // a habit you always accept climbs above a free-time offer you always decline
        val habit = Cand(Type.HABIT, "h", "z")
        val gap = Cand(Type.GAP, "g", "w")
        val led = mapOf("habit" to Stat(accepted = 10), "gap" to Stat(declined = 10))
        assertEquals(habit, ProactiveRules.pick(listOf(gap, habit), led))
        assertNull(ProactiveRules.pick(emptyList(), emptyMap()))
    }

    // ---------------- time helpers ----------------

    @Test
    fun wakeUpAtTheSoonestMomentButNeverTooLate() {
        val now = 1000 * min
        assertEquals(now + 5 * min, ProactiveRules.nextWake(now, listOf(now + 9 * min, now + 5 * min)))
        assertEquals(now + 15 * min, ProactiveRules.nextWake(now, listOf(now + 50 * min)))
        assertEquals(now + 15 * min, ProactiveRules.nextWake(now, emptyList()))
        assertEquals(now + 7 * min, ProactiveRules.nextWake(now, listOf(now - 5, now + 10_000L, now + 7 * min)))
        assertEquals(now + 60 * min, ProactiveRules.nextWake(now, emptyList(), 60 * min))
    }

    @Test
    fun quietHours() {
        assertTrue(ProactiveRules.inQuiet(23 * 60, "22:00-07:00"))
        assertFalse(ProactiveRules.inQuiet(12 * 60, "22:00-07:00"))
        assertTrue(ProactiveRules.inQuiet(6 * 60 + 59, "22:00-07:00"))
        assertFalse(ProactiveRules.inQuiet(7 * 60, "22:00-07:00"))
        assertTrue(ProactiveRules.inQuiet(5 * 60, "23:00-06:00"))
        assertTrue(ProactiveRules.inQuiet(23 * 60, "banana"))
    }

    @Test
    fun untilMorning() {
        assertEquals(8 * 60, ProactiveRules.minutesUntil(23 * 60, 7 * 60))
        assertEquals(2 * 60, ProactiveRules.minutesUntil(5 * 60, 7 * 60))
        assertEquals(1440, ProactiveRules.minutesUntil(7 * 60, 7 * 60))
        assertEquals(390, ProactiveRules.quietEnd("22:00-06:30"))
        assertEquals(420, ProactiveRules.quietEnd("x"))
    }

    @Test
    fun clockTimes() {
        assertEquals(600, ProactiveRules.parseClock("10:00"))
        assertNull(ProactiveRules.parseClock("25:00"))
        assertNull(ProactiveRules.parseClock("off"))
    }
}
