package com.sanya.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaParseTest {

    private val today = LocalDate.of(2026, 10, 16)      // a Friday

    private fun p(s: String) = AgendaParse.parse(s, today)

    @Test
    fun calendarTomorrow() {
        assertEquals(AgendaParse.Cmd.Day(LocalDate.of(2026, 10, 17)), p("what's on my calendar tomorrow"))
    }

    @Test
    fun calendarWithoutADayMeansToday() {
        assertEquals(AgendaParse.Cmd.Day(today), p("what is on my calendar"))
    }

    @Test
    fun anyMeetingsToday() {
        assertEquals(AgendaParse.Cmd.Day(today), p("do i have any meetings today"))
    }

    @Test
    fun whatDoIHaveOnMonday() {
        assertEquals(AgendaParse.Cmd.Day(LocalDate.of(2026, 10, 19)), p("what do i have on monday"))
    }

    @Test
    fun whatIsMyScheduleForTomorrow() {
        assertEquals(AgendaParse.Cmd.Day(LocalDate.of(2026, 10, 17)), p("what is my schedule for tomorrow"))
    }

    @Test
    fun myDay() {
        assertEquals(AgendaParse.Cmd.MyDay, p("what's my day"))
        assertEquals(AgendaParse.Cmd.MyDay, p("my agenda"))
    }

    @Test
    fun nextMeeting() {
        assertEquals(AgendaParse.Cmd.Next, p("when is my next meeting"))
        assertEquals(AgendaParse.Cmd.Next, p("what's my next event"))
    }

    @Test
    fun amIFree() {
        assertEquals(AgendaParse.Cmd.Free("tomorrow at 3"), p("am i free tomorrow at 3"))
        assertEquals(AgendaParse.Cmd.Day(LocalDate.of(2026, 10, 17)), p("am i free tomorrow"))
    }

    @Test
    fun followUp() {
        assertEquals(AgendaParse.Cmd.Follow(LocalDate.of(2026, 10, 17)), p("what about tomorrow"))
        assertEquals(AgendaParse.Cmd.Follow(LocalDate.of(2026, 10, 19)), p("monday"))
    }

    @Test
    fun dayOfNextWeekday() {
        assertEquals(LocalDate.of(2026, 10, 23), AgendaParse.dayOf("next friday", today))
        assertEquals(LocalDate.of(2026, 10, 18), AgendaParse.dayOf("sunday", today))
    }

    @Test
    fun otherSentencesAreNotCalendarQuestions() {
        assertNull(p("open calendar"))
        assertNull(p("call ravi"))
        assertNull(p("schedule dentist tomorrow at 5"))
        assertNull(p("what is the event horizon"))
        assertNull(p("what are my reminders"))
    }
}
