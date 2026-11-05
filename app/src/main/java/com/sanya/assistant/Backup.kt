package com.sanya.assistant

import android.content.Context
import java.io.File
import java.util.Base64

/**
 * Backup: everything Sanya knows about you, in ONE text file.
 *
 * Included: the Brain file (rules, settings, to-dos, notes, routines), reminders, the money log,
 * the study log, the routine history, her learned model of how you speak, and her memory stats.
 *
 * NOT included:
 *   - secrets saved with "remember my password is ...". They are encrypted with a key that never
 *     leaves this phone's Keystore, so they cannot be restored anywhere (not even here after a
 *     reinstall).
 *   - voice models (big, and re-downloadable), the answer cache and the error log.
 *
 * File format (plain text, one record per line):
 *   SANYA-BACKUP 1
 *   F <tab> file name <tab> base64 of the file
 *   P <tab> key <tab> I or L <tab> number          (her memory stats)
 *
 * Restore replaces your current data. The current data is first saved to
 * files/before-restore.sanyabackup, so one "Undo last restore" is always possible.
 *
 * encode() and decode() are pure Kotlin and unit-tested (BackupTest).
 */
object Backup {

    private const val HEADER = "SANYA-BACKUP 1"
    private const val SNAPSHOT = "before-restore.sanyabackup"
    private const val MIND = "sanya_mind"

    /** The only files a backup may contain or write. A backup can never touch any other path. */
    val FILES = listOf(
        "brain.txt", "reminders.txt", "money.log", "time.log",
        "routine.log", "learner.txt", "mind.log","lists.txt"
    )

    private val MIND_KEYS = mapOf("total" to 'I', "first" to 'L', "last" to 'L')

    data class Pref(val key: String, val type: Char, val value: Long)

    class Decoded(val files: Map<String, ByteArray>, val prefs: List<Pref>)

    // ---------------------------------------------------------------
    // The format (pure, testable)
    // ---------------------------------------------------------------

    fun encode(files: Map<String, ByteArray>, prefs: List<Pref>): String {

        val enc = Base64.getEncoder()
        val sb = StringBuilder()

        sb.append(HEADER).append('\n')

        for ((name, bytes) in files) {
            sb.append("F\t").append(name).append('\t').append(enc.encodeToString(bytes)).append('\n')
        }

        for (p in prefs) {
            sb.append("P\t").append(p.key).append('\t').append(p.type).append('\t').append(p.value).append('\n')
        }

        return sb.toString()
    }

    /** Null if [text] is not a Sanya backup, or is damaged. Unknown file names are ignored. */
    fun decode(text: String): Decoded? {

        val dec = Base64.getDecoder()
        val lines = text.replace("\r", "").split("\n")

        if (lines.isEmpty() || lines[0].trim() != HEADER) return null

        val files = LinkedHashMap<String, ByteArray>()
        val prefs = ArrayList<Pref>()

        for (line in lines.drop(1)) {

            if (line.isBlank() || line.startsWith("#")) continue

            val p = line.split("\t")

            when (p[0]) {

                "F" -> {
                    if (p.size < 3) return null
                    val bytes = try {
                        dec.decode(p[2].trim())
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                    if (p[1] in FILES) files[p[1]] = bytes
                }

                "P" -> {
                    if (p.size < 4) return null
                    val type = MIND_KEYS[p[1]]
                    val value = p[3].trim().toLongOrNull()
                    if (type != null && type == p[2].singleOrNull() && value != null) {
                        prefs.add(Pref(p[1], type, value))
                    }
                }
            }
        }

        return if (files.isEmpty()) null else Decoded(files, prefs)
    }

    // ---------------------------------------------------------------
    // Making a backup
    // ---------------------------------------------------------------

    fun export(ctx: Context): String {

        Brain.setup(ctx)
        Learner.flush(ctx)                     // make sure her learned model is on disk

        val files = LinkedHashMap<String, ByteArray>()

        for (name in FILES) {
            if (name == "brain.txt") {
                files[name] = Brain.text().toByteArray(Charsets.UTF_8)
                continue
            }
            val f = File(ctx.filesDir, name)
            if (f.exists()) files[name] = f.readBytes()
        }

        val p = ctx.getSharedPreferences(MIND, Context.MODE_PRIVATE)
        val prefs = ArrayList<Pref>()

        for ((key, type) in MIND_KEYS) {
            if (!p.contains(key)) continue
            val v = if (type == 'I') p.getInt(key, 0).toLong() else p.getLong(key, 0L)
            prefs.add(Pref(key, type, v))
        }

        return encode(files, prefs)
    }

    // ---------------------------------------------------------------
    // Restoring
    // ---------------------------------------------------------------

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.writeBytes(bytes)
            tmp.delete()
        }
    }

    // Puts a backup in place of the current data, then makes every in-memory copy reload.
    private fun apply(ctx: Context, d: Decoded) {

        Brain.setup(ctx)

        for (name in FILES) {

            val bytes = d.files[name]

            if (name == "brain.txt") {
                if (bytes != null) Brain.save(String(bytes, Charsets.UTF_8))
                continue
            }

            val f = File(ctx.filesDir, name)

            // A file that was not in the backup did not exist then: remove it, so the result matches.
            if (bytes == null) f.delete() else writeAtomic(f, bytes)
        }

        val ed = ctx.getSharedPreferences(MIND, Context.MODE_PRIVATE).edit().clear()

        for (p in d.prefs) {
            if (p.type == 'I') ed.putInt(p.key, p.value.toInt()) else ed.putLong(p.key, p.value)
        }

        ed.commit()

        Routines.reload()
        Lists.reload()
        Learner.reload()
        Reminders.reload(ctx)
        SanyaWidget.refresh(ctx)
    }

    fun snapshotFile(ctx: Context) = File(ctx.filesDir, SNAPSHOT)

    fun hasSnapshot(ctx: Context): Boolean = snapshotFile(ctx).exists()

    /** What she says / shows. Saves your current data first, so [undoRestore] works. */
    fun restore(ctx: Context, text: String): String {

        val d = decode(text) ?: return "That is not a Sanya backup file, so nothing was changed."

        try {
            snapshotFile(ctx).writeText(export(ctx))
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Backup safety copy", e)
            return "I couldn't save a safety copy of your current data first, so nothing was changed."
        }

        return try {
            apply(ctx, d)
            "Restored " + d.files.size + " files. Tap Undo last restore if that was a mistake."
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Restore", e)
            "Restore stopped part-way (" + (e.message ?: e.javaClass.simpleName) + "). Tap Undo last restore."
        }
    }

    fun undoRestore(ctx: Context): String {

        val f = snapshotFile(ctx)

        if (!f.exists()) return "Nothing to undo."

        val d = decode(f.readText()) ?: return "The safety copy is damaged, so I left everything as it is."

        return try {
            apply(ctx, d)
            f.delete()
            "Brought back the data from before the restore."
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Undo restore", e)
            "Undo failed: " + (e.message ?: e.javaClass.simpleName)
        }
    }
}
