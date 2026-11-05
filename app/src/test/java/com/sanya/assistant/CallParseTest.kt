package com.sanya.assistant

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallParseTest {

    private fun p(s: String) = CallParse.parse(s)

    private val utc = ZoneId.of("UTC")

    private fun ms(day: Int, hour: Int, minute: Int, month: Int = 10): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(utc).toInstant().toEpochMilli()

    // ---------------- missed calls ----------------

    @Test
    fun missedCalls() {
        assertEquals(CallParse.Cmd.Missed(""), p("any missed calls"))
        assertEquals(CallParse.Cmd.Missed(""), p("did i miss any calls"))
        assertEquals(CallParse.Cmd.Missed(""), p("read my missed calls"))
        assertEquals(CallParse.Cmd.Missed("today"), p("did i miss any calls today"))
        assertEquals(CallParse.Cmd.Missed("yesterday"), p("what calls did i miss yesterday"))
    }

    @Test
    fun howManyMissedCalls() {
        assertEquals(CallParse.Cmd.Missed("", true), p("how many missed calls do i have"))
        assertEquals(CallParse.Cmd.Missed("today", true), p("how many calls did i miss today"))
    }

    // ---------------- who called, recent calls ----------------

    @Test
    fun whoCalledNeedsADay() {
        assertEquals(CallParse.Cmd.Recent("today", true), p("who called me today"))
        assertEquals(CallParse.Cmd.Recent("today", true), p("who called me this morning"))
        // "who called" on its own is answered by Calls.kt.
        assertNull(p("who called me"))
    }

    @Test
    fun recentCalls() {
        assertEquals(CallParse.Cmd.Recent("", false), p("show my recent calls"))
        assertEquals(CallParse.Cmd.Recent("", false), p("call history"))
    }

    // ---------------- calling back ----------------

    @Test
    fun callBack() {
        assertEquals(CallParse.Cmd.CallBack(false), p("call back"))
        assertEquals(CallParse.Cmd.CallBack(false), p("call back please"))
        assertEquals(CallParse.Cmd.CallBack(false), p("call back the last missed call"))
        assertEquals(CallParse.Cmd.CallBack(false), p("return the call"))
        assertEquals(CallParse.Cmd.CallBack(false), p("call the last caller"))
    }

    @Test
    fun callHimBackIsAPronoun() {
        assertEquals(CallParse.Cmd.CallBack(true), p("call him back"))
        assertEquals(CallParse.Cmd.CallBack(true), p("ring her back"))
    }

    @Test
    fun redial() {
        assertEquals(CallParse.Cmd.Redial, p("redial"))
        assertEquals(CallParse.Cmd.Redial, p("call the last number"))
        assertEquals(CallParse.Cmd.Redial, p("call the last number i dialed"))
    }

    // ---------------- the last call with someone ----------------

    @Test
    fun whenDidSomeoneLastCall() {
        assertEquals(
            CallParse.Cmd.Last("ravi", CallParse.Dir.IN, true),
            p("when did ravi last call me")
        )
        assertEquals(
            CallParse.Cmd.Last("ravi", CallParse.Dir.IN, false),
            p("when did ravi call")
        )
        assertEquals(
            CallParse.Cmd.Last("my mother", CallParse.Dir.IN, false),
            p("when did my mother last call")
        )
        assertEquals(
            CallParse.Cmd.Last("ravi", CallParse.Dir.IN, true),
            p("did ravi call me")
        )
    }

    @Test
    fun whenDidILastCall() {
        assertEquals(
            CallParse.Cmd.Last("ravi", CallParse.Dir.OUT, true),
            p("when did i last call ravi")
        )
        assertEquals(CallParse.Cmd.Last("", CallParse.Dir.OUT, true), p("when did i last call"))
        assertEquals(CallParse.Cmd.Last("", CallParse.Dir.OUT, true), p("who did i call last"))
        assertEquals(
            CallParse.Cmd.Last("amma", CallParse.Dir.ANY, true),
            p("when did i last speak to amma")
        )
    }

    @Test
    fun pronounsAndAnyone() {
        assertEquals(CallParse.Cmd.Last("", CallParse.Dir.IN, true), p("did she call me"))
        assertEquals(CallParse.Cmd.Recent("today", true), p("did anyone call"))
        assertNull(p("did you call me"))
    }

    @Test
    fun otherSentencesAreNotCallHistory() {
        assertNull(p("call ravi"))
        assertNull(p("call my mother"))
        assertNull(p("when is my birthday"))
        assertNull(p("what time is it"))
        assertNull(p("who is the president of india"))
        assertNull(p("when did india become independent"))
    }

    // ---------------- saying a time ----------------

    @Test
    fun agoForRecentTimes() {
        val now = ms(16, 12, 0)
        assertEquals("just now", CallParse.ago(now - 20_000L, now, utc))
        assertEquals("20 minutes ago", CallParse.ago(now - 20 * 60_000L, now, utc))
        assertEquals("3 hours ago", CallParse.ago(now - 3 * 3_600_000L, now, utc))
    }

    @Test
    fun agoForEarlierDays() {
        val now = ms(16, 20, 0)                                   // Friday 16 October, 8 pm
        assertEquals("today at 9 am", CallParse.ago(ms(16, 9, 0), now, utc))
        assertEquals("yesterday at 6:15 pm", CallParse.ago(ms(15, 18, 15), now, utc))
        assertEquals("on Monday at 8 am", CallParse.ago(ms(12, 8, 0), now, utc))
        assertEquals("on 1 October at 8 am", CallParse.ago(ms(1, 8, 0), now, utc))
    }
}
