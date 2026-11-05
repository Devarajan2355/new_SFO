package com.sanya.assistant

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderParseTest {

    private val now = LocalDateTime.of(2026, 10, 16, 10, 0)     // a Friday

    private fun p(s: String) = ReminderParse.parse(s, now, 540)

    private fun add(y: Int, mo: Int, d: Int, h: Int, mi: Int, repeat: String, text: String) =
        ReminderParse.Cmd.Add(
            ReminderParse.Spec(LocalDateTime.of(y, mo, d, h, mi), repeat, h * 60 + mi),
            text
        )

    // ---------------- one-off times ----------------

    @Test
    fun tomorrowAtNine() {
        assertEquals(add(2026, 10, 17, 9, 0, "", "pay rent"), p("remind me tomorrow at 9 to pay rent"))
    }

    @Test
    fun textFirstThenTime() {
        assertEquals(add(2026, 10, 16, 17, 0, "", "call ravi"), p("remind me to call ravi at 5 pm"))
    }

    @Test
    fun spokenNumbers() {
        assertEquals(add(2026, 10, 17, 9, 0, "", "pay rent"), p("remind me tomorrow at nine to pay rent"))
        assertEquals(add(2026, 10, 16, 19, 30, "", "call mom"), p("remind me at 7 30 pm to call mom"))
    }

    @Test
    fun noon() {
        assertEquals(add(2026, 10, 16, 12, 0, "", "eat"), p("remind me at noon to eat"))
    }

    @Test
    fun nineWithoutAmPmTakesTheNextOneAhead() {
        // it is 10:00, so 9 means tonight
        assertEquals(add(2026, 10, 16, 21, 0, "", "call ravi"), p("remind me at 9 to call ravi"))
    }

    @Test
    fun inTwoHours() {
        assertEquals(add(2026, 10, 16, 12, 0, "", "drink water"), p("remind me in 2 hours to drink water"))
    }

    @Test
    fun inAnHourAndAHalf() {
        assertEquals(add(2026, 10, 16, 11, 30, "", "call mom"), p("remind me in an hour and a half to call mom"))
    }

    @Test
    fun weekdayWithTime() {
        assertEquals(add(2026, 10, 19, 18, 0, "", "go to the gym"), p("remind me on monday at 6 pm to go to the gym"))
    }

    @Test
    fun dateWithoutTimeUsesTheDefault() {
        assertEquals(add(2027, 3, 12, 9, 0, "", "pay insurance"), p("remind me to pay insurance on 12 march"))
    }

    @Test
    fun partOfDay() {
        assertEquals(add(2026, 10, 16, 21, 0, "", "lock the door"), p("remind me tonight to lock the door"))
        assertEquals(add(2026, 10, 17, 8, 0, "", "call ravi"), p("remind me tomorrow morning to call ravi"))
    }

    @Test
    fun setAReminderForm() {
        assertEquals(add(2026, 10, 17, 9, 0, "", "pay rent"), p("set a reminder for tomorrow at 9 to pay rent"))
    }

    // ---------------- repeats ----------------

    @Test
    fun everyDay() {
        assertEquals(add(2026, 10, 17, 8, 0, "daily", "take medicine"), p("remind me every day at 8 am to take medicine"))
    }

    @Test
    fun everyMondayAndWednesday() {
        assertEquals(
            add(2026, 10, 19, 19, 0, "weekly:1,3", "go to the gym"),
            p("remind me every monday and wednesday at 7 pm to go to the gym")
        )
    }

    @Test
    fun nextAfterMonthlyClampsToTheLastDay() {
        assertEquals(
            LocalDateTime.of(2026, 2, 28, 9, 0),
            ReminderParse.nextAfter("monthly:31", 540, LocalDateTime.of(2026, 2, 10, 10, 0))
        )
    }

    // ---------------- missing pieces ----------------

    @Test
    fun missingTimeAsksWhen() {
        assertEquals(ReminderParse.Cmd.NeedTime("pay rent"), p("remind me to pay rent"))
    }

    @Test
    fun missingTextAsksWhat() {
        assertEquals(
            ReminderParse.Cmd.NeedText(ReminderParse.Spec(LocalDateTime.of(2026, 10, 17, 9, 0), "", 540)),
            p("remind me tomorrow at 9")
        )
    }

    @Test
    fun aTimeThatHasPassedIsRefused() {
        assertEquals(
            ReminderParse.Cmd.Say("That time has already passed today."),
            p("remind me today at 9 am to stretch")
        )
    }

    @Test
    fun parseWhenReadsAnAnswer() {
        assertEquals(
            ReminderParse.Spec(LocalDateTime.of(2026, 10, 17, 9, 0), "", 540),
            ReminderParse.parseWhen("tomorrow at 9", now, 540)
        )
        assertNull(ReminderParse.parseWhen("banana", now, 540))
    }

    // ---------------- managing ----------------

    @Test
    fun snooze() {
        assertEquals(ReminderParse.Cmd.Snooze(10), p("snooze for 10 minutes"))
        assertEquals(ReminderParse.Cmd.Snooze(null), p("snooze"))
        assertEquals(ReminderParse.Cmd.Snooze(5), p("remind me again in 5 minutes"))
    }

    @Test
    fun listingAndCancelling() {
        assertEquals(ReminderParse.Cmd.Show, p("what are my reminders"))
        assertEquals(ReminderParse.Cmd.Show, p("do i have any reminders"))
        assertEquals(ReminderParse.Cmd.Next, p("what is my next reminder"))
        assertEquals(ReminderParse.Cmd.Cancel("pay rent"), p("cancel my reminder to pay rent"))
        assertEquals(ReminderParse.Cmd.CancelAll, p("cancel all my reminders"))
    }

    @Test
    fun otherSentencesAreNotReminders() {
        assertNull(p("call ravi"))
        assertNull(p("what time is it"))
    }

    // ---------------- saying things ----------------

    @Test
    fun whenText() {
        assertEquals("tomorrow at 9 am", ReminderParse.whenText(LocalDateTime.of(2026, 10, 17, 9, 0), "", now))
        assertEquals("in 30 minutes", ReminderParse.whenText(LocalDateTime.of(2026, 10, 16, 10, 30), "", now))
        assertEquals("Monday at 6 pm", ReminderParse.whenText(LocalDateTime.of(2026, 10, 19, 18, 0), "", now))
        assertEquals("every day at 8 am", ReminderParse.whenText(LocalDateTime.of(2026, 10, 17, 8, 0), "daily", now))
        assertEquals(
            "every Monday and Wednesday at 7 pm",
            ReminderParse.whenText(LocalDateTime.of(2026, 10, 19, 19, 0), "weekly:1,3", now)
        )
    }

    @Test
    fun flipAndClean() {
        assertEquals("pay your rent", ReminderParse.flip("pay my rent"))
        assertEquals("you have a meeting", ReminderParse.flip("i have a meeting"))
        assertEquals("the meeting", ReminderParse.cleanText("about the meeting"))
    }
}
