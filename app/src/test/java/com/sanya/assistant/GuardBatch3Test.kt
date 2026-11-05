package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardLockTest {

    private val settings = "com.android.settings"

    @Test
    fun theAccessibilityPageForSanyaIsClosed() {
        assertTrue(GuardLock.isGuardPage(settings, listOf("Accessibility", "Sanya Focus Guard", "On")))
        assertTrue(GuardLock.isGuardPage(settings, listOf("ACCESSIBILITY", "sanya focus guard")))
    }

    @Test
    fun theAppInfoAndUninstallPagesAreClosed() {
        assertTrue(GuardLock.isGuardPage(settings, listOf("App info", "Sanya", "Uninstall", "Force stop")))
        assertTrue(GuardLock.isGuardPage("com.miui.securitycenter", listOf("Sanya", "Force stop", "Clear data")))
        assertTrue(
            GuardLock.isGuardPage(
                "com.google.android.packageinstaller",
                listOf("Do you want to uninstall this app?", "Sanya")
            )
        )
    }

    @Test
    fun everythingElseStaysOpen() {
        assertFalse(GuardLock.isGuardPage(settings, listOf("Wi-Fi", "Home network")))
        assertFalse(GuardLock.isGuardPage(settings, listOf("Accessibility", "TalkBack")))      // no Sanya
        assertFalse(GuardLock.isGuardPage(settings, listOf("Sanya", "Battery")))               // no keyword
        assertFalse(GuardLock.isGuardPage(settings, listOf("Accessibility", "Sanyal Ltd")))    // other name
        assertFalse(GuardLock.isGuardPage("com.android.chrome", listOf("Sanya", "Uninstall"))) // other app
    }

    @Test
    fun itOnlyLocksDuringUrgeModeAndWhileTheLockIsOn() {
        val page = listOf("Accessibility", "Sanya Focus Guard")
        assertTrue(GuardLock.shouldLock(5_000L, true, settings, page))
        assertFalse(GuardLock.shouldLock(0L, true, settings, page))
        assertFalse(GuardLock.shouldLock(5_000L, false, settings, page))
    }
}

class GuardReportTest {

    private fun e(d: Int): Long = LocalDate.of(2026, 10, d).toEpochDay()      // Mon 12 .. Sun 18 October 2026

    private val cfg = GuardReport.Config(true, true, true, true, 2)

    private fun sampleWeek(): List<GuardReport.Day> {
        val m = mapOf(
            e(12) to GuardReport.Day(e(12), shorts = 2),
            e(14) to GuardReport.Day(e(14), shorts = 1, words = 1),
            e(15) to GuardReport.Day(e(15), urgeKicks = 1, urges = 1),
            e(16) to GuardReport.Day(e(16), shorts = 3, words = 1, limits = 1, urges = 1, loosened = 1),
            e(17) to GuardReport.Day(e(17), svcOff = 1, offMin = 130)
        )
        return GuardReport.lastDays(m, e(18))
    }

    // ---------------- the counters ----------------

    @Test
    fun countersAddUp() {
        var m: Map<Long, GuardReport.Day> = emptyMap()
        m = GuardReport.bump(m, e(18), "shorts")
        m = GuardReport.bump(m, e(18), "shorts", 2)
        m = GuardReport.bump(m, e(18), "offmin", 130)
        assertEquals(3, m.getValue(e(18)).shorts)
        assertEquals(130, m.getValue(e(18)).offMin)
        assertEquals(m, GuardReport.bump(m, e(18), "nonsense"))
    }

    @Test
    fun blocksAreEverythingThatWasStopped() {
        val d = GuardReport.Day(1L, shorts = 1, words = 2, limits = 3, urgeKicks = 4, urges = 9, loosened = 9)
        assertEquals(10, d.blocks)
    }

    @Test
    fun countersSurviveARoundTrip() {
        val m = sampleWeek().associateBy { it.day }
        assertEquals(m, GuardReport.decode(GuardReport.encode(m)))
    }

    @Test
    fun aDamagedLogIsIgnored() {
        assertEquals(
            emptyMap<Long, GuardReport.Day>(),
            GuardReport.decode("junk;1,2,3;x,1,1,1,1,1,1,1,1;5,a,1,1,1,1,1,1,1")
        )
    }

    @Test
    fun oldDaysAreForgotten() {
        val m = mapOf(e(18) to GuardReport.Day(e(18), shorts = 1), LocalDate.of(2026, 8, 1).toEpochDay() to GuardReport.Day(1L))
        assertEquals(setOf(e(18)), GuardReport.prune(m, e(18)).keys)
    }

    @Test
    fun aMissingDayIsAllZeros() {
        val days = GuardReport.lastDays(emptyMap(), e(18))
        assertEquals(7, days.size)
        assertEquals(e(12), days.first().day)
        assertEquals(e(18), days.last().day)
        assertEquals(0, days.sumOf { it.blocks })
    }

    @Test
    fun timeNotRunning() {
        assertEquals(0, GuardReport.offGap(0L, 1_000_000_000L))
        assertEquals(0, GuardReport.offGap(1_000_000L, 1_000_000L + 5 * 60_000L))
        assertEquals(10, GuardReport.offGap(1_000_000L, 1_000_000L + 10 * 60_000L))
        assertEquals(1440, GuardReport.offGap(1_000_000L, 1_000_000L + 3 * 86_400_000L))
        assertEquals(0, GuardReport.offGap(2_000_000L, 1_000_000L))
    }

    // ---------------- the week ----------------

    @Test
    fun theWeekAddsUp() {
        val s = GuardReport.summarize(sampleWeek())
        assertEquals(LocalDate.of(2026, 10, 12), s.from)
        assertEquals(LocalDate.of(2026, 10, 18), s.to)
        assertEquals(10, s.blocks)
        assertEquals(6, s.shorts)
        assertEquals(2, s.words)
        assertEquals(1, s.limits)
        assertEquals(1, s.urgeKicks)
        assertEquals(2, s.urges)
        assertEquals(1, s.loosened)
        assertEquals(1, s.svcOff)
        assertEquals(130, s.offMin)
        assertEquals(5, s.noWordDays)
        assertEquals(listOf(2, 0, 2, 1, 5, 0, 0), s.perDay)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), s.dayNames)
    }

    @Test
    fun theMessageForYourPartner() {
        assertEquals(
            "Hi Ravi, my Focus Guard report for 12 Oct to 18 Oct:\n" +
                "Blocks: 10 (Shorts 6, adult words 2, limits 1, urge mode 1)\n" +
                "Urges handled: 2\n" +
                "Days with no adult-word block: 5 of 7\n" +
                "Safeguards loosened: 1\n" +
                "Guard not running: 2h 10m (includes phone off). Guard service stopped: 1 (includes restarts)\n" +
                "Per day: Mon 2, Tue 0, Wed 2, Thu 1, Fri 5, Sat 0, Sun 0\n" +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.partnerText(GuardReport.summarize(sampleWeek()), cfg, "Ravi")
        )
    }

    @Test
    fun aQuietWeekWithTheGuardOffIsStillShownHonestly() {
        val s = GuardReport.summarize(GuardReport.lastDays(emptyMap(), e(18)))
        assertEquals(
            "My Focus Guard report for 12 Oct to 18 Oct:\n" +
                "Blocks: 0\n" +
                "Urges handled: 0\n" +
                "Days with no adult-word block: 7 of 7\n" +
                "Safeguards loosened: 0\n" +
                "Guard not running: none\n" +
                "Per day: Mon 0, Tue 0, Wed 0, Thu 0, Fri 0, Sat 0, Sun 0\n" +
                "Right now: guard OFF, Shorts blocker OFF, adult filter on, 1 app limit.",
            GuardReport.partnerText(s, GuardReport.Config(false, false, true, false, 1), "")
        )
    }

    @Test
    fun whatSheSaysToYou() {
        assertEquals(
            "This week: 10 blocks. You handled 2 urges. That takes effort. 5 of 7 days with no adult-word block. " +
                "A safeguard was loosened once. The guard service stopped once (that includes restarts). " +
                "It wasn't running for 2 hours 10 minutes. " +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.spoken(GuardReport.summarize(sampleWeek()), cfg)
        )
        val quiet = GuardReport.summarize(GuardReport.lastDays(emptyMap(), e(18)))
        assertEquals(
            "This week: no blocks. 7 of 7 days with no adult-word block. " +
                "Right now: guard on but its Accessibility service is OFF, Shorts blocker on, adult filter on, 0 app limits.",
            GuardReport.spoken(quiet, GuardReport.Config(true, true, true, false, 0))
        )
    }

    @Test
    fun dateRanges() {
        assertEquals("30 Oct to 5 Nov", GuardReport.rangeText(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 5)))
    }

    // ---------------- when to offer it ----------------

    private val sunday = LocalDate.of(2026, 10, 18)
    private val tuesday = LocalDate.of(2026, 10, 20)

    @Test
    fun offeredOnTheReportDayFromTheSetTime() {
        val sun = DayOfWeek.SUNDAY
        assertFalse(GuardReport.due(sunday, 1100, 0L, sun, 1200))                          // too early
        assertTrue(GuardReport.due(sunday, 1230, 0L, sun, 1200))                           // first time
        assertFalse(GuardReport.due(sunday, 1230, sunday.toEpochDay(), sun, 1200))         // already today
        assertTrue(GuardReport.due(sunday, 1230, sunday.toEpochDay() - 7, sun, 1200))      // a week later
        assertTrue(GuardReport.due(sunday, 1230, sunday.toEpochDay() - 6, sun, 1200))
        assertFalse(GuardReport.due(sunday, 1230, sunday.toEpochDay() - 3, sun, 1200))
    }

    @Test
    fun aMissedWeekIsOfferedOnceLaterInsteadOfNever() {
        val sun = DayOfWeek.SUNDAY
        assertTrue(GuardReport.due(tuesday, 1230, LocalDate.of(2026, 10, 11).toEpochDay(), sun, 1200))
        assertFalse(GuardReport.due(tuesday, 1230, LocalDate.of(2026, 10, 12).toEpochDay(), sun, 1200))
        assertFalse(GuardReport.due(tuesday, 1230, 0L, sun, 1200))                          // never started
    }

    @Test
    fun dayNames() {
        assertEquals(DayOfWeek.SUNDAY, GuardReport.dayOf("Sunday"))
        assertEquals(DayOfWeek.FRIDAY, GuardReport.dayOf(" friday "))
        assertNull(GuardReport.dayOf("someday"))
    }
}

class GuardParseBatch3Test {

    private fun p(s: String) = GuardParse.parse(s)

    @Test
    fun readingTheWeeklyReport() {
        assertEquals(GuardParse.Cmd.Report, p("weekly focus guard report"))
        assertEquals(GuardParse.Cmd.Report, p("show my weekly guard report"))
        assertEquals(GuardParse.Cmd.Report, p("focus guard weekly report"))
        assertEquals(GuardParse.Cmd.Report, p("read my accountability report"))
    }

    @Test
    fun gettingItReadyToSend() {
        assertEquals(GuardParse.Cmd.SendReport, p("send my focus guard report"))
        assertEquals(GuardParse.Cmd.SendReport, p("share my weekly guard report"))
        assertEquals(GuardParse.Cmd.SendReport, p("send my weekly focus guard report"))
        assertEquals(GuardParse.Cmd.SendReport, p("prepare the accountability report"))
        assertEquals(GuardParse.Cmd.SendReport, p("send my focus guard report to my accountability partner"))
    }

    @Test
    fun thePartner() {
        assertEquals(GuardParse.Cmd.Partner("ravi"), p("set my accountability partner to ravi"))
        assertEquals(GuardParse.Cmd.Partner("ravi kumar"), p("my accountability partner is ravi kumar"))
        assertEquals(GuardParse.Cmd.Partner("ravi"), p("ravi is my accountability partner"))
        assertEquals(GuardParse.Cmd.Partner("amma"), p("add guard partner amma"))
        assertTrue(p("my accountability partner is") is GuardParse.Cmd.Say)
        assertEquals(GuardParse.Cmd.PartnerWho, p("who is my accountability partner"))
        assertEquals(GuardParse.Cmd.PartnerWho, p("what is my guard partner"))
        assertEquals(GuardParse.Cmd.PartnerOff, p("remove my accountability partner"))
    }

    @Test
    fun theReminderAndTheLockSwitches() {
        assertEquals(GuardParse.Cmd.ReportReminders(false), p("turn off weekly guard reports"))
        assertEquals(GuardParse.Cmd.ReportReminders(true), p("turn on weekly focus guard reports"))
        assertEquals(GuardParse.Cmd.SettingsLock(false), p("turn off settings lock"))
        assertEquals(GuardParse.Cmd.SettingsLock(true), p("turn on the urge mode settings lock"))
        assertEquals(GuardParse.Cmd.SettingsLock(true), p("lock my settings during urge mode"))
        assertEquals(GuardParse.Cmd.SettingsLock(false), p("don't lock my settings in urge mode"))
    }

    @Test
    fun earlierPhrasesStillMeanWhatTheyDid() {
        assertEquals(GuardParse.Cmd.Status, p("focus guard report"))
        assertEquals(GuardParse.Cmd.Status, p("focus guard status"))
        assertEquals(GuardParse.Cmd.Show, p("show my focus guard"))
        assertEquals(GuardParse.Cmd.Show, p("show my focus guard report"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        assertNull(p("weekly report"))
        assertNull(p("my weekly routine report"))
        assertNull(p("who is ravi"))
        assertNull(p("ravi is my friend"))
        assertNull(p("ravi is my partner"))            // a relationship, handled elsewhere
        assertNull(p("send a message to ravi"))
        assertNull(p("share my location"))
        assertNull(p("lock my phone"))
        assertNull(p("turn off weekly reports"))
        assertNull(p("call ravi"))
        assertNull(p("set my monthly budget to 5000"))
        assertNull(p("limit youtube to 1 hour"))
    }
}
