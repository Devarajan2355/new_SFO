package com.sanya.assistant

import com.sanya.assistant.ProactiveRules.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveParseTest {

    private fun p(s: String) = ProactiveParse.parse(s)

    @Test
    fun switchingAKindOnAndOff() {
        assertEquals(ProactiveParse.Cmd.Toggle(Type.HABIT, false), p("turn off habit suggestions"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.HABIT, true), p("turn on habit suggestions"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.BATTERY, false), p("stop battery warnings"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.MEETING, false), p("turn off meeting heads-up"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.MEETING, false), p("disable the meeting heads-up"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.BUDGET, true), p("enable my budget alerts"))
        assertEquals(ProactiveParse.Cmd.Toggle(Type.CELEBRATE, false), p("no more praise"))
    }

    @Test
    fun everyKindCanBeSwitchedBackOnByItsOwnLabel() {
        // She says "Say turn on <label> to bring them back", so that sentence must work.
        // The morning brief has its own control ("stop morning brief").
        for (t in Type.values()) {
            if (t == Type.BRIEF) continue
            assertEquals(ProactiveParse.Cmd.Toggle(t, true), p("turn on " + t.label))
        }
    }

    @Test
    fun allNudgesAtOnce() {
        assertEquals(ProactiveParse.Cmd.Master(false), p("turn off all nudges"))
        assertEquals(ProactiveParse.Cmd.Master(true), p("resume nudges"))
    }

    @Test
    fun status() {
        assertEquals(ProactiveParse.Cmd.Status, p("proactive status"))
        assertEquals(ProactiveParse.Cmd.Status, p("what nudges are on"))
    }

    @Test
    fun quietMode() {
        assertEquals(ProactiveParse.Cmd.Quiet(60, false), p("quiet mode for an hour"))
        assertEquals(ProactiveParse.Cmd.Quiet(30, false), p("leave me alone for 30 minutes"))
        assertEquals(ProactiveParse.Cmd.Quiet(120, false), p("be quiet for 2 hours"))
        assertEquals(ProactiveParse.Cmd.Quiet(0, true), p("quiet mode until tomorrow"))
        assertEquals(ProactiveParse.Cmd.Quiet(0, true), p("be quiet for today"))
        assertTrue(p("quiet mode for banana") is ProactiveParse.Cmd.Say)
        assertEquals(ProactiveParse.Cmd.QuietOff, p("quiet mode off"))
        assertEquals(ProactiveParse.Cmd.QuietOff, p("you can talk again"))
    }

    @Test
    fun feedbackOnWhatSheJustSaid() {
        assertEquals(ProactiveParse.Cmd.Stop, p("stop that"))
        assertEquals(ProactiveParse.Cmd.Stop, p("don't suggest that again"))
        assertEquals(ProactiveParse.Cmd.Helpful, p("that was helpful"))
        assertEquals(ProactiveParse.Cmd.Why, p("why did you say that"))
    }

    @Test
    fun batteryLevel() {
        assertEquals(ProactiveParse.Cmd.BatteryAt(20), p("set battery warning at 20 percent"))
        assertTrue(p("set battery warning at 90") is ProactiveParse.Cmd.Say)
    }

    @Test
    fun phrasesOtherPartsOwnAreLeftAlone() {
        assertNull(p("be quiet"))
        assertNull(p("stop suggesting"))
        assertNull(p("turn off proactive"))
        assertNull(p("stop"))
        assertNull(p("stop it"))
        assertNull(p("stop the music"))
        assertNull(p("stop routine check"))
        assertNull(p("turn off focus guard"))
        assertNull(p("turn off wifi"))
        assertNull(p("stop timer"))
        assertNull(p("call ravi"))
        assertNull(p("cancel my reminder to pay rent"))
        assertNull(p("what are my reminders"))
        assertNull(p("why"))
    }
}
