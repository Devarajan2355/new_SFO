package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneParseTest {

    @Test
    fun answerPhrases() {
        assertEquals(PhoneParse.CallCmd.Answer(false), PhoneParse.call("pick up the call"))
        assertEquals(PhoneParse.CallCmd.Answer(true), PhoneParse.call("answer on speaker"))
    }

    @Test
    fun rejectAndHangup() {
        assertEquals(PhoneParse.CallCmd.Reject, PhoneParse.call("can you reject it"))
        assertEquals(PhoneParse.CallCmd.Hangup, PhoneParse.call("hang up"))
        assertEquals(PhoneParse.CallCmd.Hangup, PhoneParse.call("end the call"))
    }

    @Test
    fun whoCalled() {
        assertEquals(PhoneParse.CallCmd.Who, PhoneParse.call("who called"))
    }

    @Test
    fun otherSentencesAreNotCallCommands() {
        assertNull(PhoneParse.call("answer the milk question"))
        assertNull(PhoneParse.call("call ravi"))
    }

    @Test
    fun readNotifications() {
        assertEquals(PhoneParse.NoticeCmd.Read(false, false), PhoneParse.notice("read my notifications"))
        assertEquals(PhoneParse.NoticeCmd.Read(false, true), PhoneParse.notice("do i have any new messages"))
        assertEquals(PhoneParse.NoticeCmd.Read(true, true), PhoneParse.notice("read all my whatsapp messages"))
    }

    @Test
    fun announceMessages() {
        assertEquals(PhoneParse.NoticeCmd.Announce(true), PhoneParse.notice("announce messages"))
        assertEquals(PhoneParse.NoticeCmd.Announce(false), PhoneParse.notice("stop announcing messages"))
    }

    @Test
    fun notesAreNotNotifications() {
        assertNull(PhoneParse.notice("read my notes"))
    }
}
