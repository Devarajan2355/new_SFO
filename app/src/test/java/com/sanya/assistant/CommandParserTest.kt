package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    // Exactly one step, or the test fails.
    private fun only(sentence: String): Step = CommandParser.parse(sentence).single()

    // ---------------- timers (the compound-duration fix) ----------------

    @Test
    fun timerAddsEveryUnit() {
        assertEquals(Step.Timer(5400), only("set timer 1 hour 30 minutes"))
    }

    @Test
    fun timerSingleUnit() {
        assertEquals(Step.Timer(300), only("set a timer for 5 minutes"))
    }

    @Test
    fun timerSeconds() {
        assertEquals(Step.Timer(45), only("timer 45 seconds"))
        assertEquals(Step.Timer(90), only("timer 90 secs"))
    }

    @Test
    fun timerSpokenNumbers() {
        assertEquals(Step.Timer(5400), only("set timer one hour thirty minutes"))
    }

    @Test
    fun timerDecimalHours() {
        assertEquals(Step.Timer(5400), only("set timer 1.5 hours"))
    }

    @Test
    fun timerWithoutALengthIsNotUnderstood() {
        assertTrue(only("set a timer") is Step.Unknown)
    }

    // ---------------- everyday commands ----------------

    @Test
    fun openApp() {
        assertEquals(Step.OpenApp("youtube"), only("open youtube"))
    }

    @Test
    fun callAndWhatsappCall() {
        assertEquals(Step.Call("ravi", false, false), only("call ravi"))
        assertEquals(Step.Call("ravi", false, true), only("whatsapp call ravi"))
    }

    @Test
    fun andInsideAListStaysTogether() {
        assertEquals(Step.AddTodo("milk and eggs"), only("add milk and eggs to my list"))
    }

    @Test
    fun twoCommandsAreSplit() {
        assertEquals(
            listOf<Step>(Step.OpenApp("youtube"), Step.Call("ravi", false, false)),
            CommandParser.parse("open youtube and call ravi")
        )
    }

    @Test
    fun calculator() {
        assertEquals(Step.Reply("5 plus 3 is 8"), only("5 plus 3"))
    }

    @Test
    fun tanglishWordOrder() {
        assertEquals(Step.OpenApp("youtube"), only("youtube open pannu"))
        assertEquals(Step.Search("comedy songs", true), only("comedy songs podu"))
    }

    @Test
    fun teachingAWordMeaning() {
        assertEquals(Step.WordMap("thira", "open"), only("when i say thira i mean open"))
    }

    @Test
    fun nonsenseIsUnknown() {
        assertTrue(only("blah blah") is Step.Unknown)
    }
}
