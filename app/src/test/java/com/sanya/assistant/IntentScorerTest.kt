package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentScorerTest {

    private fun read(text: String) = IntentScorer.interpret(text)

    // ---------------- cleaning ----------------

    @Test
    fun stripsWakeWordAndPoliteFiller() {
        assertEquals(
            listOf("call", "ravi"),
            IntentScorer.tokens("Hey Sanya, could you call Ravi?")
        )
    }

    @Test
    fun stripsPoliteEndings() {
        assertEquals(
            listOf("open", "youtube"),
            IntentScorer.tokens("can you please open youtube for me")
        )
    }

    // ---------------- calls and messages ----------------

    @Test
    fun ringUpOnWhatsappBecomesWhatsappCall() {
        assertEquals(
            IntentScorer.Result.Sure("call", "whatsapp call preethi"),
            read("ring up preethi on whatsapp")
        )
    }

    @Test
    fun callWithoutNameAsksWho() {
        assertEquals(
            IntentScorer.Result.NeedSlot("call", "Who should I call?", "call %s", false),
            read("call")
        )
    }

    @Test
    fun textWithoutSeparatorAsksToConfirm() {
        assertEquals(
            IntentScorer.Result.Unsure("message", "message ravi saying i will be late"),
            read("text ravi i will be late")
        )
    }

    // ---------------- apps, lists, volume ----------------

    @Test
    fun openApp() {
        assertEquals(
            IntentScorer.Result.Sure("open", "open youtube"),
            read("can you please open youtube for me")
        )
    }

    @Test
    fun shoppingListBecomesTodo() {
        assertEquals(
            IntentScorer.Result.Sure("todo", "add milk to my list"),
            read("add milk to my shopping list")
        )
    }

    @Test
    fun turnItUpIsVolumeUp() {
        assertEquals(
            IntentScorer.Result.Sure("volume", "volume up"),
            read("turn it up")
        )
    }

    // ---------------- timers and alarms ----------------

    @Test
    fun anHourAndAHalfIsNinetyMinutes() {
        assertEquals(
            IntentScorer.Result.Sure("timer", "set timer 90 minutes"),
            read("set a timer for an hour and a half")
        )
    }

    @Test
    fun wakeMeUpBecomesAlarm() {
        assertEquals(
            IntentScorer.Result.Sure("alarm", "alarm at 06:30"),
            read("wake me up at six thirty")
        )
    }

    @Test
    fun clockExtraction() {
        assertEquals("18:30", IntentScorer.extractClock("at six thirty pm"))
    }

    // ---------------- not an intent ----------------

    @Test
    fun unknownPhraseIsNotGuessed() {
        assertTrue(read("movie time") is IntentScorer.Result.None)
    }
}

class NumTest {

    @Test
    fun wordsToNumbers() {
        assertEquals(120.0, Num.parse("one hundred and twenty")!!, 0.0001)
        assertEquals(1500.0, Num.parse("1.5k")!!, 0.0001)
        assertEquals(500000.0, Num.parse("five lakh")!!, 0.0001)
    }

    @Test
    fun durations() {
        assertEquals(90, Num.parseDuration("1 hour 30 minutes"))
        assertEquals(90, Num.parseDuration("an hour and a half"))
        assertEquals(150, Num.parseDuration("two hours thirty minutes"))
        assertEquals(45, Num.parseDuration("45 mins"))
        assertEquals(90, Num.parseDuration("1h30m"))
    }
}

class BrainCleanTest {

    @Test
    fun latinIsLowercasedAndPunctuationRemoved() {
        assertEquals("hello world", Brain.clean("Hello,  World!"))
    }

    @Test
    fun tamilTextSurvives() {
        assertEquals("வணக்கம்", Brain.clean("வணக்கம்!"))
    }
}
