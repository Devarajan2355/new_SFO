package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardParseTest {

    private fun p(s: String) = GuardParse.parse(s)

    @Test
    fun statusAndScreen() {
        assertEquals(GuardParse.Cmd.Status, p("focus guard status"))
        assertEquals(GuardParse.Cmd.Status, p("how is my focus guard"))
        assertEquals(GuardParse.Cmd.Status, p("what are my limits"))
        assertEquals(GuardParse.Cmd.Show, p("show my focus guard"))
    }

    @Test
    fun masterSwitch() {
        assertEquals(GuardParse.Cmd.Master(true), p("turn on focus guard"))
        assertEquals(GuardParse.Cmd.Master(false), p("turn off the guard"))
        assertEquals(GuardParse.Cmd.Master(false), p("guard off"))
    }

    @Test
    fun shortsSwitch() {
        assertEquals(GuardParse.Cmd.Shorts(true), p("block shorts"))
        assertEquals(GuardParse.Cmd.Shorts(true), p("block youtube shorts"))
        assertEquals(GuardParse.Cmd.Shorts(false), p("allow shorts"))
        assertEquals(GuardParse.Cmd.Shorts(false), p("turn off shorts blocker"))
    }

    @Test
    fun setLimits() {
        assertEquals(GuardParse.Cmd.SetLimit("youtube", 60), p("limit youtube to 1 hour"))
        assertEquals(GuardParse.Cmd.SetLimit("chrome", 45), p("limit chrome to 45 minutes a day"))
        assertEquals(
            GuardParse.Cmd.SetLimit("crunchyroll", 90),
            p("set a daily limit for crunchyroll to an hour and a half")
        )
        assertEquals(GuardParse.Cmd.SetLimit("youtube", 30), p("limit my youtube to half an hour"))
    }

    @Test
    fun aLimitWithoutALengthAsksAgain() {
        assertTrue(p("limit youtube to banana") is GuardParse.Cmd.Say)
    }

    @Test
    fun removeLimits() {
        assertEquals(GuardParse.Cmd.RemoveLimit("youtube"), p("remove the limit on youtube"))
        assertEquals(GuardParse.Cmd.RemoveLimit("youtube"), p("remove youtube limit"))
    }

    @Test
    fun usageQuestions() {
        assertEquals(GuardParse.Cmd.Usage("youtube"), p("how long have i been on youtube"))
        assertEquals(GuardParse.Cmd.Usage("youtube"), p("youtube usage today"))
        assertEquals(GuardParse.Cmd.Usage(null), p("what is my screen time"))
    }

    @Test
    fun cancelRequest() {
        assertEquals(GuardParse.Cmd.CancelRequest, p("cancel the guard request"))
    }

    @Test
    fun blockAndUnblockWords() {
        assertEquals(GuardParse.Cmd.BlockWord("hentai", false), p("block the word hentai"))
        assertEquals(GuardParse.Cmd.BlockWord("examplesite", true), p("block the site examplesite"))
        assertEquals(GuardParse.Cmd.BlockWord("dirty", false), p("add a blocked word dirty"))
        assertEquals(GuardParse.Cmd.UnblockWord("dirty", false), p("unblock the word dirty"))
        assertEquals(GuardParse.Cmd.UnblockWord("examplesite", true), p("allow the site examplesite"))
        assertEquals(GuardParse.Cmd.UnblockWord("dirty", false), p("remove the blocked word dirty"))
        assertEquals(GuardParse.Cmd.WordList, p("what words are blocked"))
    }

    @Test
    fun adultFilterSwitch() {
        assertEquals(GuardParse.Cmd.WordFilter(true), p("turn on the adult filter"))
        assertEquals(GuardParse.Cmd.WordFilter(true), p("block adult sites"))
        assertEquals(GuardParse.Cmd.WordFilter(false), p("adult filter off"))
        assertEquals(GuardParse.Cmd.WordFilter(false), p("allow adult sites"))
    }

    @Test
    fun urgeMode() {
        assertEquals(GuardParse.Cmd.Urge, p("urge mode"))
        assertEquals(GuardParse.Cmd.Urge, p("i have an urge"))
        assertEquals(GuardParse.Cmd.Urge, p("i feel tempted"))
        assertEquals(GuardParse.Cmd.Urge, p("i feel like watching porn"))
        assertEquals(GuardParse.Cmd.UrgeStatus, p("how long is left in urge mode"))
        assertEquals(GuardParse.Cmd.UrgeEnd, p("end urge mode"))
    }

    @Test
    fun listsAndShortsAreNotWordCommands() {
        assertNull(p("add the word milk to my list"))
        assertNull(p("remove the word milk from my list"))
        assertNull(p("ignore the word da"))
        assertNull(p("i feel like watching a movie"))
        assertEquals(GuardParse.Cmd.Shorts(true), p("block shorts"))
    }

    @Test
    fun otherSentencesAreNotGuardCommands() {
        assertNull(p("call ravi"))
        assertNull(p("set pomodoro focus to 50 minutes"))
        assertNull(p("turn on focus mode"))
        assertNull(p("how long did i study today"))
        assertNull(p("how much time did i spend on japanese this week"))
        assertNull(p("set my monthly budget to 5000"))
        assertNull(p("cancel my reminder to pay rent"))
    }
}

class GuardRulesTest {

    private val delay = GuardRules.DELAY_MS

    @Test
    fun shortsEscalatesFromBackToHome() {
        assertFalse(GuardRules.goHome(1))
        assertFalse(GuardRules.goHome(2))
        assertTrue(GuardRules.goHome(3))
    }

    @Test
    fun firstRequestStartsTheWait() {
        assertEquals(GuardRules.Verdict.Start, GuardRules.check("", 0L, "master-off", 5_000L))
    }

    @Test
    fun aDifferentRequestRestartsTheWait() {
        assertEquals(GuardRules.Verdict.Start, GuardRules.check("shorts-off", 1_000L, "master-off", 2_000L))
    }

    @Test
    fun askingTooEarlyShowsTheTimeLeft() {
        assertEquals(
            GuardRules.Verdict.Wait(delay - 60_000L),
            GuardRules.check("master-off", 1_000L, "master-off", 1_000L + 60_000L)
        )
    }

    @Test
    fun afterTheWaitItApplies() {
        assertEquals(
            GuardRules.Verdict.Apply,
            GuardRules.check("master-off", 1_000L, "master-off", 1_000L + delay)
        )
    }

    @Test
    fun anOldReadyRequestExpires() {
        assertEquals(
            GuardRules.Verdict.Start,
            GuardRules.check("master-off", 1_000L, "master-off", 1_000L + GuardRules.EXPIRE_MS + 1L)
        )
    }

    @Test
    fun raisingAndLowering() {
        assertTrue(GuardRules.raising(60, 90))
        assertFalse(GuardRules.raising(60, 30))
        assertFalse(GuardRules.raising(60, 60))
    }

    @Test
    fun waitTextRoundsUp() {
        assertEquals("9 minutes", GuardRules.waitText(8 * 60_000L + 1L))
        assertEquals("1 minute", GuardRules.waitText(5_000L))
    }

    @Test
    fun ignoredWindows() {
        val own = "com.sanya.assistant"
        val kb = setOf("com.google.android.inputmethod.latin")
        assertTrue(GuardRules.ignored("com.android.systemui", own, kb))
        assertTrue(GuardRules.ignored(own, own, kb))
        assertTrue(GuardRules.ignored("com.google.android.inputmethod.latin", own, kb))
        assertFalse(GuardRules.ignored("com.google.android.youtube", own, kb))
    }
}

class WordGuardTest {

    @Test
    fun distinctiveNamesAreCaughtAnywhere() {
        assertEquals("porn", WordGuard.find("https://www.pornhub.com/video"))
        assertEquals("xvideos", WordGuard.find("xvideos.com"))
    }

    @Test
    fun shortWordsMustStandAlone() {
        assertEquals("sex", WordGuard.find("sex education for teens"))
        assertNull(WordGuard.find("Sussex County Council"))
        assertNull(WordGuard.find("Essex"))
        assertNull(WordGuard.find("XXXL shirt size"))
        assertNull(WordGuard.find("popcorn recipe"))
        assertNull(WordGuard.find("google.com"))
    }

    @Test
    fun yourOwnWordsAndSites() {
        assertEquals("examplesite", WordGuard.find("visit examplesite.com now", listOf("s:examplesite")))
        assertEquals("dirty word", WordGuard.find("my dirty word list", listOf("w:dirty word")))
        assertNull(WordGuard.find("the dirty laundry", listOf("w:dirty word")))
        assertEquals("cars", WordGuard.find("cars for sale", listOf("w:cars")))
        assertNull(WordGuard.find("scarsdale", listOf("w:cars")))
    }

    @Test
    fun entriesAreCleanedAndSafe() {
        assertEquals("w:dirty word", WordGuard.entry("Dirty  Word!", false))
        assertEquals("s:examplesite", WordGuard.entry("Example-Site", true))
        assertNull(WordGuard.entry("abc", true))      // a site name needs 5 or more letters
        assertNull(WordGuard.entry("a", false))
    }

    @Test
    fun addressBarIds() {
        assertTrue(WordGuard.isUrlId("com.android.chrome:id/url_bar"))
        assertTrue(WordGuard.isUrlId("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"))
        assertFalse(WordGuard.isUrlId("com.android.chrome:id/menu_button"))
    }

    @Test
    fun browserList() {
        assertTrue("com.android.chrome" in WordGuard.BROWSERS)
        assertFalse("com.google.android.youtube" in WordGuard.BROWSERS)
    }
}
