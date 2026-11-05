package com.sanya.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {

    private val words = listOf("sanya", "sonia", "sunya")

    @Test
    fun grammarIsJson() {
        assertEquals("[\"sanya\",\"sonia\",\"[unk]\"]", WakeWord.grammar(listOf("sanya", "sonia")))
    }

    @Test
    fun cleanWordsKeepsLettersAndRemovesDuplicates() {
        assertEquals(
            listOf("sanya", "hey there"),
            WakeWord.cleanWords(listOf("Sanya!", "hey  there", "", "sanya"))
        )
    }

    @Test
    fun detectFindsTheWakeWord() {
        assertEquals("sonia", WakeWord.detect("[unk] sonia [unk]", words))
        assertNull(WakeWord.detect("[unk]", words))
    }

    @Test
    fun rebuildKeepsTheCommand() {
        assertEquals("sanya call ravi", WakeWord.rebuild("sunya call ravi", "sanya", words, true))
    }

    @Test
    fun rebuildRejectsAnUnconfirmedTranscript() {
        assertNull(WakeWord.rebuild("hello world", "sanya", words, true))
        assertEquals("sanya hello world", WakeWord.rebuild("hello world", "sanya", words, false))
    }

    @Test
    fun trimLeadingRemovesInitialSilence() {
        val pcm = ShortArray(32000)
        for (i in 16000 until 32000) pcm[i] = 2000
        val out = WakeWord.trimLeading(pcm)
        assertEquals(20800, out.size)
    }

    @Test
    fun trimLeadingKeepsAllSilenceUnchanged() {
        val pcm = ShortArray(8000)
        assertArrayEquals(pcm, WakeWord.trimLeading(pcm))
    }
}
