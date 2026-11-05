package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Mind: sound keys, similarity, corrections, dates, clock times, conversation kinds
// ---------------------------------------------------------------------------
class MindTest {

    // ---------------- hearing names by sound ----------------

    @Test
    fun soundKeysMatchForNamesThatSoundAlike() {
        assertEquals("PRT", Mind.soundKey("preethi"))
        assertEquals("PRT", Mind.soundKey("pretty"))
        assertEquals(Mind.soundKey("karthik"), Mind.soundKey("kartik"))
    }

    @Test
    fun soundScore() {
        assertEquals(55, Mind.soundScore("pretty", "preethi"))
        assertEquals(0, Mind.soundScore("kerala", "karnataka"))
    }

    @Test
    fun similarity() {
        assertEquals(1.0, Mind.similarity("same words", "same words"), 0.0001)
        assertTrue(Mind.similarity("movie time", "movy time") > 0.4)
        assertTrue(Mind.similarity("call ravi", "movie time") < 0.3)
    }

    // ---------------- learning from corrections ----------------

    @Test
    fun correctionPhrases() {
        assertEquals(Mind.Correction("preethi"), Mind.parseCorrection("no i said preethi", "call pretty"))
        assertEquals(Mind.Correction(""), Mind.parseCorrection("wrong", "call pretty"))
        assertEquals(Mind.Correction("preethi", "pretty"), Mind.parseCorrection("not pretty preethi", "call pretty"))
    }

    @Test
    fun ordinarySentencesAreNotCorrections() {
        assertNull(Mind.parseCorrection("i said that i was late", "call pretty"))
        assertNull(Mind.parseCorrection("hello there", "call pretty"))
    }

    @Test
    fun diffFixPinsDownTheMisheardWord() {
        val fix = Mind.diffFix("call pretty", Mind.Correction("preethi")) { false }!!
        assertEquals("pretty", fix.heardSeg)
        assertEquals("preethi", fix.intendedSeg)
        assertEquals("call preethi", fix.fixedText)
        assertEquals("call pretty", fix.key)
        assertEquals("call preethi", fix.value)
    }

    // ---------------- clock times and dates ----------------

    @Test
    fun clockTimes() {
        assertEquals("19:30", Mind.parseClock("7 30 pm"))
        assertEquals("07:30", Mind.parseClock("seven thirty"))
        assertEquals("00:00", Mind.parseClock("12 am"))
        assertEquals("12:00", Mind.parseClock("12 pm"))
        assertNull(Mind.parseClock("25:00"))
    }

    @Test
    fun spokenDates() {
        assertEquals("21 of march", Mind.numWords("twenty first of march"))
        assertEquals(Pair(12, 3), Mind.parseDM("12 march"))
        assertEquals(Pair(12, 3), Mind.parseDM("twelfth of march"))
        assertEquals(Pair(5, 3), Mind.parseDM("march 5"))
        assertNull(Mind.parseDM("31 february"))
    }

    // ---------------- which kind of conversation ----------------

    @Test
    fun conversationKinds() {
        assertEquals("greet", Mind.kind("hello"))
        assertEquals("mood", Mind.kind("how are you"))
        assertEquals("person_set", Mind.kind("ammu is my mother"))
        assertEquals("fact_set", Mind.kind("chief minister of tamil nadu is joseph vijay"))
    }

    @Test
    fun ordinarySentencesAreNotConversationKinds() {
        assertNull(Mind.kind("call ravi"))
        assertNull(Mind.kind("my friend is late"))
        assertNull(Mind.kind("my friend is calling me"))
    }
}

// ---------------------------------------------------------------------------
// Routines: schedule text, due days, voice commands
// ---------------------------------------------------------------------------
class RoutinesTest {

    private val daily = Routines.Period.DAILY
    private val weekly = Routines.Period.WEEKLY

    // ---------------- reading the schedule text ----------------

    @Test
    fun parseSpecForEachPeriod() {
        assertEquals(Routines.Spec(daily, minutes = 390), Routines.parseSpec("daily 06:30"))

        assertEquals(
            Routines.Spec(weekly, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), minutes = 1080),
            Routines.parseSpec("weekly mon wed 18:00")
        )

        assertEquals(
            Routines.Spec(Routines.Period.MONTHLY, dayOfMonth = 1),
            Routines.parseSpec("monthly 1")
        )

        assertEquals(
            Routines.Spec(Routines.Period.YEARLY, month = 3, day = 12),
            Routines.parseSpec("yearly 12 march")
        )
    }

    @Test
    fun parseSpecWithATimeTarget() {
        assertEquals(
            Routines.Spec(daily, minutes = 1140, target = 120),
            Routines.parseSpec("daily 19:00 for 2h")
        )
    }

    @Test
    fun weeklyWithNoDaysIsOpenEnded() {
        assertTrue(Routines.parseSpec("weekly")!!.openEnded)
    }

    @Test
    fun badSchedulesAreRefused() {
        assertNull(Routines.parseSpec("sometimes"))
        assertNull(Routines.parseSpec("daily banana"))
        assertNull(Routines.parseSpec("daily 25:00"))
    }

    @Test
    fun specTextReadsBackTheSame() {
        for (text in listOf("weekly mon wed 18:00", "daily 19:00 for 2h", "yearly 12 march", "monthly 1", "daily 06:30")) {
            assertEquals(text, Routines.specText(Routines.parseSpec(text)!!))
        }
    }

    @Test
    fun whenTextIsSpeakable() {
        assertEquals("every day at 6:30 am", Routines.whenText(Routines.Spec(daily, minutes = 390)))
    }

    // ---------------- which days an activity is due ----------------

    @Test
    fun weeklyOnFixedDaysOnlyLivesOnThoseDays() {
        val spec = Routines.parseSpec("weekly mon wed")!!
        val task = Routines.Task("gym", spec, "")

        val friday = LocalDate.of(2026, 10, 16)
        val monday = LocalDate.of(2026, 10, 19)

        assertTrue(Routines.activeOn(task, friday).isEmpty())

        val live = Routines.activeOn(task, monday)
        assertEquals(1, live.size)
        assertEquals("2026-10-19", live[0].key)
    }

    @Test
    fun openEndedWeeklyIsOneItemForTheWholeWeek() {
        val task = Routines.Task("clean room", Routines.parseSpec("weekly")!!, "")

        val live = Routines.activeOn(task, LocalDate.of(2026, 10, 16))

        assertEquals(1, live.size)
        assertEquals("2026-W42", live[0].key)
        assertEquals(LocalDate.of(2026, 10, 12), live[0].due)      // that week's Monday
    }

    // ---------------- voice commands ----------------

    @Test
    fun addByVoice() {
        assertEquals(
            Routines.Cmd.Add("morning walk", "daily 06:30"),
            Routines.parse("add morning walk to my daily routine at 6 30 am")
        )
        assertEquals(
            Routines.Cmd.Add("japanese", "daily for 2h"),
            Routines.parse("add japanese to my daily routine for 2 hours")
        )
    }

    @Test
    fun remainingQuestion() {
        assertEquals(Routines.Cmd.Remaining, Routines.parse("what are the remaining activities"))
    }

    @Test
    fun otherSentencesAreNotRoutines() {
        assertNull(Routines.parse("call ravi"))
    }
}
