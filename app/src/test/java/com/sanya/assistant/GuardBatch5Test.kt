package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardScheduleTest {

    private val night = GuardSchedule.Rule("instagram", 22 * 60, 6 * 60)
    private val day = GuardSchedule.Rule("chrome", 9 * 60, 17 * 60)

    @Test
    fun aWindowAcrossMidnight() {
        assertTrue(GuardSchedule.active(night, 23 * 60))
        assertTrue(GuardSchedule.active(night, 2 * 60))
        assertTrue(GuardSchedule.active(night, 6 * 60 - 1))
        assertFalse(GuardSchedule.active(night, 6 * 60))
        assertFalse(GuardSchedule.active(night, 12 * 60))
        assertTrue(GuardSchedule.active(night, 22 * 60))
    }

    @Test
    fun anOrdinaryWindow() {
        assertTrue(GuardSchedule.active(day, 9 * 60))
        assertFalse(GuardSchedule.active(day, 17 * 60))
        assertFalse(GuardSchedule.active(GuardSchedule.Rule("x", 600, 600), 600))
    }

    @Test
    fun onlyTheRightAppIsBlocked() {
        assertEquals(night, GuardSchedule.blocked(listOf(night, day), "instagram", 23 * 60))
        assertNull(GuardSchedule.blocked(listOf(night, day), "chrome", 23 * 60))
        assertNull(GuardSchedule.blocked(listOf(night), "youtube", 23 * 60))
    }

    @Test
    fun loosening() {
        val longer = GuardSchedule.Rule("instagram", 21 * 60, 7 * 60)
        val shorter = GuardSchedule.Rule("instagram", 23 * 60, 6 * 60)
        val shifted = GuardSchedule.Rule("instagram", 23 * 60, 7 * 60)
        assertFalse(GuardSchedule.loosens(night, longer))
        assertFalse(GuardSchedule.loosens(night, night))
        assertTrue(GuardSchedule.loosens(night, shorter))
        assertTrue(GuardSchedule.loosens(night, shifted))
    }

    @Test
    fun savedRulesSurviveARoundTrip() {
        val l = listOf(night, day)
        assertEquals(l, GuardSchedule.decode(GuardSchedule.encode(l)))
        assertEquals(emptyList<GuardSchedule.Rule>(), GuardSchedule.decode("junk;a=b-c;x=5-9999"))
    }

    @Test
    fun wording() {
        assertEquals("Instagram is blocked from 10 pm to 6 am", GuardSchedule.text(night))
    }
}

class GuardPatternTest {

    @Test
    fun tooFewUrgesIsNoPattern() {
        val a = GuardPattern.bump(GuardPattern.bump(IntArray(24), 22), 23)
        assertNull(GuardPattern.peak(a))
        assertEquals("I haven't logged any urges yet.", GuardPattern.text(IntArray(24)))
    }

    @Test
    fun theBusiestTwoHours() {
        var a = IntArray(24)
        for (h in listOf(22, 22, 23, 23, 23, 14)) a = GuardPattern.bump(a, h)
        assertEquals(Pair(22, 5), GuardPattern.peak(a))
        assertEquals(
            "5 of your 6 urges came between 10 pm and 12 am. " +
                "That is your hard stretch. Plan something for it, like a pomodoro or a walk.",
            GuardPattern.text(a)
        )
    }

    @Test
    fun aRoundTrip() {
        val a = GuardPattern.bump(IntArray(24), 5)
        assertEquals(a.toList(), GuardPattern.decode(GuardPattern.encode(a)).toList())
        assertEquals(24, GuardPattern.decode("").size)
    }
}

class GuardParseBatch5Test {

    private fun p(s: String) = GuardParse.parse(s)

    @Test
    fun settingASchedule() {
        assertEquals(GuardParse.Cmd.SetSchedule("instagram", 1320, 360), p("block instagram from 10 pm to 6 am"))
        assertEquals(GuardParse.Cmd.SetSchedule("youtube", 1380, 420), p("block youtube between 11 pm and 7 am"))
    }

    @Test
    fun removingAndListing() {
        assertEquals(GuardParse.Cmd.RemoveSchedule("instagram"), p("remove the instagram schedule"))
        assertEquals(GuardParse.Cmd.ScheduleList, p("list my schedules"))
        assertEquals(GuardParse.Cmd.ScheduleList, p("what apps are scheduled"))
    }

    @Test
    fun theUrgePattern() {
        assertEquals(GuardParse.Cmd.UrgePattern, p("when do i get urges"))
    }

    @Test
    fun earlierPhrasesStillMeanWhatTheyDid() {
        assertEquals(GuardParse.Cmd.Shorts(true), p("block shorts"))
        assertEquals(GuardParse.Cmd.BlockWord("hentai", false), p("block the word hentai"))
        assertEquals(GuardParse.Cmd.RemoveLimit("youtube"), p("remove youtube limit"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        assertNull(p("block the road from 5 to 6"))
        assertNull(p("remove the milk schedule from my list").takeIf { it is GuardParse.Cmd.SetSchedule })
        assertNull(p("call ravi"))
    }
}
