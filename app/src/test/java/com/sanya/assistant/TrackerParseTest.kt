package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// ---------------------------------------------------------------------------
// Money.parse
// ---------------------------------------------------------------------------
class MoneyParseTest {

    private fun p(s: String) = Money.parse(s)

    @Test
    fun spentOnSomething() {
        assertEquals(Money.Cmd.Add(true, 12000L, "food"), p("spent 120 on food"))
    }

    @Test
    fun spentWithSpokenNumber() {
        assertEquals(Money.Cmd.Add(true, 12000L, "food"), p("spent one hundred and twenty on food"))
    }

    @Test
    fun earnedFromSomething() {
        assertEquals(Money.Cmd.Add(false, 50000L, "freelance"), p("earned 500 from freelance"))
    }

    @Test
    fun boughtFor() {
        assertEquals(Money.Cmd.Add(true, 2000L, "tea"), p("bought tea for 20"))
    }

    @Test
    fun questions() {
        assertEquals(Money.Cmd.Total("spent", "food", "this month"), p("how much did i spend on food this month"))
        assertEquals(Money.Cmd.Total("spent", null, "today"), p("how much did i spend"))
        assertEquals(Money.Cmd.Total("earned", null, "today"), p("how much did i earn today"))
        assertEquals(Money.Cmd.Total("net", null, "this month"), p("what is my balance this month"))
    }

    @Test
    fun budgetUndoAndScreen() {
        assertEquals(Money.Cmd.Budget(500000L), p("set my monthly budget to 5000"))
        assertEquals(Money.Cmd.Undo, p("undo last expense"))
        assertEquals(Money.Cmd.Show(Money.Gran.MONTH), p("show my money report"))
    }

    @Test
    fun otherSentencesAreNotMoney() {
        assertNull(p("call ravi"))
        assertNull(p("what time is it"))
    }
}

// ---------------------------------------------------------------------------
// Study.parse and Study.rangeLength
// ---------------------------------------------------------------------------
class StudyParseTest {

    private fun p(s: String) = Study.parse(s)

    @Test
    fun logAnAmountOfTime() {
        assertEquals(Study.Cmd.Log("japanese", 45, null), p("log 45 minutes for japanese"))
    }

    @Test
    fun iStudiedForAnHourAndAHalf() {
        assertEquals(Study.Cmd.Log("japanese", 90, null), p("i studied japanese for an hour and a half"))
    }

    @Test
    fun timerCommands() {
        assertEquals(Study.Cmd.Start("japanese"), p("start timer for japanese"))
        assertEquals(Study.Cmd.Stop, p("stop timer"))
    }

    @Test
    fun aCountdownIsNotAStudyTimer() {
        assertNull(p("start timer for 5 minutes"))
    }

    @Test
    fun aTimeRange() {
        val c = p("study from 6 pm to 8 pm") as Study.Cmd.Log
        assertEquals("study", c.task)
        assertEquals(120, c.minutes)
        assertNotNull(c.endMs)
    }

    @Test
    fun rangeLengthHandlesNoonAndMidnight() {
        assertEquals(120, Study.rangeLength(660, 60))       // 11 to 1
        assertEquals(240, Study.rangeLength(1260, 60))      // 9 pm to 1 am
        assertNull(Study.rangeLength(60, 1200))             // 19 hours: not a study session
    }

    @Test
    fun totals() {
        assertEquals(Study.Cmd.Total(null, "this week"), p("how long did i study this week"))
    }
}

// ---------------------------------------------------------------------------
// Pomodoro.parse and Pomodoro.locate (the clock)
// ---------------------------------------------------------------------------
class PomodoroTest {

    private val min = 60_000L

    // 25 focus, 5 break, long break 15 after 4 sessions, started at time 0.
    private val state = Pomodoro.State("study", 0L, 25, 5, 15, 4)

    private fun at(minute: Long) = Pomodoro.locate(state, minute * min)

    private fun p(s: String) = Pomodoro.parse(s)

    @Test
    fun firstFocusSession() {
        val n = at(10)
        assertEquals(Pomodoro.Phase.FOCUS, n.phase)
        assertEquals(0, n.index)
        assertEquals(25 * min, n.end)
        assertEquals(1, n.session)
    }

    @Test
    fun theBoundaryBelongsToTheNextPhase() {
        val n = at(25)
        assertEquals(Pomodoro.Phase.SHORT_BREAK, n.phase)
        assertEquals(1, n.index)
    }

    @Test
    fun fourthFocusSession() {
        val n = at(100)
        assertEquals(Pomodoro.Phase.FOCUS, n.phase)
        assertEquals(6, n.index)
        assertEquals(4, n.session)
    }

    @Test
    fun longBreakAfterFourSessions() {
        val n = at(120)
        assertEquals(Pomodoro.Phase.LONG_BREAK, n.phase)
        assertEquals(7, n.index)
    }

    @Test
    fun afterTheLongBreakTheNextRoundStarts() {
        val n = at(131)
        assertEquals(Pomodoro.Phase.FOCUS, n.phase)
        assertEquals(8, n.index)
        assertEquals(5, n.session)
    }

    @Test
    fun extendingAPhaseMovesEverythingAfterIt() {
        val longer = state.copy(extra = mapOf(0 to 5))     // first focus lasts 30 minutes
        assertEquals(Pomodoro.Phase.FOCUS, Pomodoro.locate(longer, 27 * min).phase)
        assertEquals(Pomodoro.Phase.SHORT_BREAK, Pomodoro.locate(longer, 31 * min).phase)
    }

    @Test
    fun startStopPauseResume() {
        assertEquals(Pomodoro.Cmd.Start("study"), p("start pomodoro"))
        assertEquals(Pomodoro.Cmd.Start("study"), p("pomodoro"))
        assertEquals(Pomodoro.Cmd.Start("japanese"), p("start pomodoro for japanese"))
        assertEquals(Pomodoro.Cmd.Stop, p("stop pomodoro"))
        assertEquals(Pomodoro.Cmd.Pause, p("pause pomodoro"))
        assertEquals(Pomodoro.Cmd.Resume, p("resume pomodoro"))
        assertEquals(Pomodoro.Cmd.Skip, p("skip break"))
        assertEquals(Pomodoro.Cmd.Status, p("how much time is left in my pomodoro"))
    }

    @Test
    fun extendAndSetLengths() {
        assertEquals(Pomodoro.Cmd.Extend(5), p("extend pomodoro by 5 minutes"))
        assertEquals(Pomodoro.Cmd.Length("focus", 50), p("set pomodoro focus to 50 minutes"))
        assertEquals(Pomodoro.Cmd.Length("long break", 20), p("set pomodoro long break to 20 minutes"))
    }

    @Test
    fun otherSentencesAreNotPomodoro() {
        assertNull(p("call ravi"))
    }
}
