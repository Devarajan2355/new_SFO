package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun everythingSurvivesARoundTrip() {

        val files = mapOf(
            "brain.txt" to bytes("nick | ammu = revathi\nnote | வணக்கம்\n"),
            "money.log" to bytes("1\tS\t12000\tfood\ttea\n"),
            "reminders.txt" to bytes("")
        )
        val prefs = listOf(
            Backup.Pref("total", 'I', 42L),
            Backup.Pref("first", 'L', 1_700_000_000_000L)
        )

        val d = Backup.decode(Backup.encode(files, prefs))!!

        assertEquals(3, d.files.size)
        assertEquals("nick | ammu = revathi\nnote | வணக்கம்\n", String(d.files.getValue("brain.txt"), Charsets.UTF_8))
        assertEquals("1\tS\t12000\tfood\ttea\n", String(d.files.getValue("money.log"), Charsets.UTF_8))
        assertEquals(0, d.files.getValue("reminders.txt").size)
        assertEquals(prefs, d.prefs)
    }

    @Test
    fun somethingThatIsNotABackupIsRefused() {
        assertNull(Backup.decode("hello world"))
        assertNull(Backup.decode(""))
        assertNull(Backup.decode("SANYA-BACKUP 1\n"))       // no files at all
    }

    @Test
    fun aDamagedFileRefusesTheWholeBackup() {
        assertNull(Backup.decode("SANYA-BACKUP 1\nF\tbrain.txt\t!!!not-base64!!!\n"))
        assertNull(Backup.decode("SANYA-BACKUP 1\nF\tbrain.txt\n"))
    }

    @Test
    fun aBackupCanNeverWriteOutsideTheKnownFiles() {

        val evil = Backup.encode(
            mapOf("../evil.txt" to bytes("x"), "brain.txt" to bytes("ok\n")),
            emptyList()
        )

        val d = Backup.decode(evil)!!

        assertEquals(setOf("brain.txt"), d.files.keys)
    }

    @Test
    fun unknownPrefKeysAreIgnored() {

        val text = "SANYA-BACKUP 1\n" +
            Backup.encode(mapOf("brain.txt" to bytes("a\n")), emptyList()).lines().drop(1).joinToString("\n") +
            "P\tautostart\tI\t1\n" +
            "P\ttotal\tI\t7\n"

        val d = Backup.decode(text)!!

        assertTrue(d.prefs.none { it.key == "autostart" })
        assertEquals(listOf(Backup.Pref("total", 'I', 7L)), d.prefs)
    }
}
