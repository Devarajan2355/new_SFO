package com.sanya.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardLockAlwaysTest {

    private val settings = "com.android.settings"

    @Test
    fun theLockIsOnAllTheTimeUntilYouOpenAWindow() {
        assertTrue(GuardLock.active(0L, true, true, false))      // the default: always closed
        assertFalse(GuardLock.active(0L, true, true, true))      // you asked, waited, and opened a window
        assertTrue(GuardLock.active(5_000L, true, true, true))   // urge mode closes it whatever the window says
        assertFalse(GuardLock.active(0L, true, false, false))    // "only during urge mode" and no urge
        assertTrue(GuardLock.active(5_000L, true, false, false))
        assertFalse(GuardLock.active(5_000L, false, true, false)) // the lock is switched off
    }

    @Test
    fun thePrivateDnsPageIsClosedButNotTheListThatMentionsIt() {
        assertTrue(
            GuardLock.isDnsPage(
                settings,
                listOf("Private DNS", "Off", "Automatic", "Private DNS provider hostname", "Hostname of Private DNS provider")
            )
        )
        // A list page shows "Private DNS" with its value, so hotspot, VPN and Wi-Fi stay usable.
        assertFalse(GuardLock.isDnsPage(settings, listOf("Connection & sharing", "Wi-Fi hotspot", "Private DNS", "Automatic", "VPN")))
        assertFalse(GuardLock.isDnsPage(settings, listOf("Private DNS", "family-filter-dns.cleanbrowsing.org")))
        assertFalse(GuardLock.isDnsPage("com.android.chrome", listOf("Private DNS", "hostname")))
        assertFalse(GuardLock.isDnsPage(settings, listOf("Wi-Fi", "Home network")))
    }

    @Test
    fun aLockedPageIsEitherSanyasOrTheDnsOne() {
        assertTrue(GuardLock.isLockedPage(settings, listOf("Accessibility", "Sanya Focus Guard")))
        assertTrue(GuardLock.isLockedPage(settings, listOf("Private DNS", "Private DNS provider hostname")))
        assertFalse(GuardLock.isLockedPage(settings, listOf("Display", "Brightness")))
    }
}

class GuardDnsTest {

    private val host = "family-filter-dns.cleanbrowsing.org"

    @Test
    fun onlyAProviderHostnameCanBeWatched() {
        assertTrue(GuardDns.canWatch("hostname", host))
        assertFalse(GuardDns.canWatch("opportunistic", null))
        assertFalse(GuardDns.canWatch("hostname", "  "))
        assertFalse(GuardDns.canWatch("hostname", null))
        assertFalse(GuardDns.canWatch("off", host))
    }

    @Test
    fun aChangeIsNoticed() {
        assertFalse(GuardDns.changed("hostname", host, host))
        assertFalse(GuardDns.changed("hostname", "Family-Filter-DNS.cleanbrowsing.org", host))
        assertTrue(GuardDns.changed("off", null, host))
        assertTrue(GuardDns.changed("opportunistic", null, host))
        assertTrue(GuardDns.changed("hostname", "dns.google", host))
    }

    @Test
    fun nothingIsWatchedUntilYouAskForIt() {
        assertFalse(GuardDns.changed("off", null, ""))
    }
}

class GuardReportAlertsTest {

    private fun e(d: Int): Long = LocalDate.of(2026, 10, d).toEpochDay()

    @Test
    fun theNewCountersAdd() {
        var m: Map<Long, GuardReport.Day> = emptyMap()
        m = GuardReport.bump(m, e(18), "dnsoff", 2)
        m = GuardReport.bump(m, e(18), "alert")
        assertEquals(2, m.getValue(e(18)).dnsOff)
        assertEquals(1, m.getValue(e(18)).alerts)
        assertEquals(0, m.getValue(e(18)).blocks)        // they are not blocks
    }

    @Test
    fun theNewCountersSurviveARoundTrip() {
        val m = mapOf(5L to GuardReport.Day(5L, shorts = 1, dnsOff = 2, alerts = 1))
        assertEquals(m, GuardReport.decode(GuardReport.encode(m)))
    }

    @Test
    fun anOlderLogStillLoads() {
        assertEquals(
            mapOf(7L to GuardReport.Day(7L, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0)),
            GuardReport.decode("7,1,2,3,4,5,6,7,8")
        )
    }

    private fun week(): List<GuardReport.Day> {
        val m = mapOf(
            e(13) to GuardReport.Day(e(13), loosened = 1),
            e(15) to GuardReport.Day(e(15), dnsOff = 2),
            e(17) to GuardReport.Day(e(17), alerts = 1, svcOff = 1, offMin = 45)
        )
        return GuardReport.lastDays(m, e(18))
    }

    private val cfg = GuardReport.Config(true, true, true, true, 2)

    @Test
    fun theReportShowsDnsChangesAndAlerts() {
        assertEquals(
            "Hi Ravi, my Focus Guard report for 12 Oct to 18 Oct:\n" +
                "Blocks: 0\n" +
                "Urges handled: 0\n" +
                "Days with no adult-word block: 7 of 7\n" +
                "Safeguards loosened: 1\n" +
                "Guard not running: 45m (includes phone off). Guard service stopped: 1 (includes restarts)\n" +
                "Private DNS filter changed: 2\n" +
                "Automatic alerts sent to you: 1\n" +
                "Per day: Mon 0, Tue 0, Wed 0, Thu 0, Fri 0, Sat 0, Sun 0\n" +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.partnerText(GuardReport.summarize(week()), cfg, "Ravi")
        )
    }

    @Test
    fun whatSheSaysAboutIt() {
        assertEquals(
            "This week: no blocks. 7 of 7 days with no adult-word block. A safeguard was loosened once. " +
                "The guard service stopped once (that includes restarts). It wasn't running for 45 minutes. " +
                "The Private DNS filter was changed 2 times. I texted your partner once. " +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.spoken(GuardReport.summarize(week()), cfg)
        )
    }

    @Test
    fun theOneTextToYourPartnerIsShortAndPlain() {
        val at = 23 * 60 + 42
        assertEquals(
            "Hi Ravi, automatic message from my Focus Guard: my Focus Guard was switched off at 11:42 pm.",
            GuardReport.alertText("Ravi", "guard", at)
        )
        assertEquals(
            "Hi Ravi, automatic message from my Focus Guard: my adult word filter was switched off at 11:42 pm.",
            GuardReport.alertText("Ravi", "filter", at)
        )
        assertEquals(
            "Hi Ravi, automatic message from my Focus Guard: my Focus Guard's Accessibility service was switched off at 11:42 pm.",
            GuardReport.alertText("Ravi", "service", at)
        )
        assertEquals(
            "Hi Ravi, automatic message from my Focus Guard: my Private DNS filter was changed at 11:42 pm.",
            GuardReport.alertText("Ravi", "dns", at)
        )
        assertEquals(
            "Hi Ravi, automatic message from my Focus Guard: I turned off these alerts at 11:42 pm.",
            GuardReport.alertText("Ravi", "alerts", at)
        )
        assertEquals(
            "Automatic message from my Focus Guard: my Focus Guard was switched off at 6 am.",
            GuardReport.alertText("", "guard", 6 * 60)
        )
        // One SMS is 160 characters: every message must fit.
        for (w in listOf("guard", "filter", "service", "dns", "alerts", "other")) {
            assertTrue(GuardReport.alertText("Ravi Kumar", w, at).length <= 160)
        }
    }
}

class GuardParseBatch4Test {

    private fun p(s: String) = GuardParse.parse(s)

    @Test
    fun openingAndClosingTheSettingsWindow() {
        assertEquals(GuardParse.Cmd.UnlockSettings, p("unlock guard settings"))
        assertEquals(GuardParse.Cmd.UnlockSettings, p("unlock my focus guard settings"))
        assertEquals(GuardParse.Cmd.UnlockSettings, p("open the guard settings window"))
        assertEquals(GuardParse.Cmd.LockSettingsNow, p("lock guard settings"))
        assertEquals(GuardParse.Cmd.LockSettingsNow, p("lock my focus guard settings again"))
    }

    @Test
    fun alwaysOrOnlyDuringUrgeMode() {
        assertEquals(GuardParse.Cmd.LockAlways(true), p("always lock guard settings"))
        assertEquals(GuardParse.Cmd.LockAlways(false), p("lock guard settings only during urge mode"))
    }

    @Test
    fun partnerAlerts() {
        assertEquals(GuardParse.Cmd.Alerts(true), p("turn on partner alerts"))
        assertEquals(GuardParse.Cmd.Alerts(true), p("tell my partner if the guard is switched off"))
        assertEquals(GuardParse.Cmd.Alerts(false), p("turn off accountability alerts"))
        assertEquals(GuardParse.Cmd.Alerts(false), p("stop telling my partner"))
    }

    @Test
    fun theDnsWatch() {
        assertEquals(GuardParse.Cmd.WatchDns(true), p("watch my private dns"))
        assertEquals(GuardParse.Cmd.WatchDns(true), p("turn on dns watch"))
        assertEquals(GuardParse.Cmd.WatchDns(false), p("stop watching my private dns"))
    }

    @Test
    fun earlierPhrasesStillMeanWhatTheyDid() {
        assertEquals(GuardParse.Cmd.SettingsLock(true), p("lock my settings during urge mode"))
        assertEquals(GuardParse.Cmd.SettingsLock(false), p("turn off settings lock"))
        assertEquals(GuardParse.Cmd.SendReport, p("send my focus guard report"))
        assertEquals(GuardParse.Cmd.Show, p("show my focus guard"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        assertNull(p("lock my phone"))
        assertNull(p("lock the screen"))
        assertNull(p("unlock my phone"))
        assertNull(p("turn off alerts"))
        assertNull(p("turn on alerts"))
        assertNull(p("turn off wifi"))
        assertNull(p("watch youtube"))
        assertNull(p("open settings"))
        assertNull(p("call ravi"))
    }
}

class GuardLockHitsTest {

    private fun e(d: Int): Long = LocalDate.of(2026, 10, d).toEpochDay()

    private val cfg = GuardReport.Config(true, true, true, true, 2)

    private fun week(): List<GuardReport.Day> = GuardReport.lastDays(
        mapOf(
            e(14) to GuardReport.Day(e(14), lockHits = 3),
            e(16) to GuardReport.Day(e(16), lockHits = 2, dnsOff = 1)
        ),
        e(18)
    )

    @Test
    fun attemptsToOpenTheLockedSettingsAreCounted() {
        var m: Map<Long, GuardReport.Day> = emptyMap()
        m = GuardReport.bump(m, e(18), "lockhit")
        m = GuardReport.bump(m, e(18), "lockhit")
        assertEquals(2, m.getValue(e(18)).lockHits)
        assertEquals(0, m.getValue(e(18)).blocks)
        assertEquals(m, GuardReport.decode(GuardReport.encode(m)))
    }

    @Test
    fun anOlderLogWithoutThemStillLoads() {
        assertEquals(
            mapOf(7L to GuardReport.Day(7L, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
            GuardReport.decode("7,1,2,3,4,5,6,7,8,9,10")
        )
    }

    @Test
    fun theReportShowsHowOftenYouTriedToGetIn() {
        assertEquals(
            "Hi Ravi, my Focus Guard report for 12 Oct to 18 Oct:\n" +
                "Blocks: 0\n" +
                "Urges handled: 0\n" +
                "Days with no adult-word block: 7 of 7\n" +
                "Safeguards loosened: 0\n" +
                "Guard not running: none\n" +
                "Tried to open Sanya's locked settings: 5\n" +
                "Private DNS filter changed: 1\n" +
                "Per day: Mon 0, Tue 0, Wed 0, Thu 0, Fri 0, Sat 0, Sun 0\n" +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.partnerText(GuardReport.summarize(week()), cfg, "Ravi")
        )
        assertEquals(
            "This week: no blocks. 7 of 7 days with no adult-word block. " +
                "You tried to open Sanya's locked settings 5 times. " +
                "The Private DNS filter was changed once. " +
                "Right now: guard on, Shorts blocker on, adult filter on, 2 app limits.",
            GuardReport.spoken(GuardReport.summarize(week()), cfg)
        )
    }
}
