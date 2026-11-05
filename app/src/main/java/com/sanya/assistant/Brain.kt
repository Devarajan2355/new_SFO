package com.sanya.assistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream

// Sanya's editable memory: one plain-text file, edited by hand (in the app) and by Sanya herself.
//
// The file is read once and kept in memory. Every change goes to a temp file first and is then
// renamed over brain.txt, so a crash or kill in the middle of a save can never leave a half-written
// (or empty) brain. All access is synchronized.
object Brain {
    private var file: File? = null
    private var lines: List<String> = emptyList()
    private var loaded = false
    private var rowsCache: List<Row>? = null
    private val mapCache = HashMap<String, Map<String, String>>()

    private val STARTER = """
# SANYA BRAIN - edit freely. One rule per line. Lines starting with # are ignored.
# Sanya also edits this file herself when you teach her by voice.
#
#   nick    | name1, name2 = real contact or app name     e.g. ammu, amma = revathi
#   slang   | phrase1, phrase2 = what to do               e.g. movie time = open youtube and search comedy
#   word    | your word = my word                         e.g. thira = open
#   filler  | words to ignore                             e.g. da, bro, machan
#   fact    | what = answer                               e.g. favorite color = blue
#   setting | wake words / language / name = value
#   todo    | text        note | text
setting | wake words = sanya, saniya, sunya, sonya, sonia
setting | language = en-IN
filler | da, bro, machan, macha, dude, buddy
nick | yt = youtube
nick | insta = instagram
nick | gpay = google pay
""".trimIndent() + "\n"

    // ------------------------------------------------------------------
    // Loading and saving
    // ------------------------------------------------------------------

    @Synchronized
    fun setup(c: Context) {
        if (file == null) file = File(c.filesDir, "brain.txt")
        if (loaded) return
        val f = file!!
        if (f.exists()) {
            lines = splitLines(f.readText())
            invalidate()
        } else {
            setLines(splitLines(STARTER))
        }
        loaded = true
    }

    private fun splitLines(s: String): List<String> {
        val l = s.split("\n").map { it.removeSuffix("\r") }
        return if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l
    }

    private fun invalidate() {
        rowsCache = null
        mapCache.clear()
    }

    // Replace the whole brain: update memory, then write atomically.
    private fun setLines(next: List<String>) {
        if (next == lines) return
        lines = next
        invalidate()
        persist()
    }

    private fun persist() {
        val f = file ?: return
        val text = lines.joinToString("\n") + "\n"
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            try {
                f.writeText(text)
            } catch (x: Exception) {
            }
        }
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    @Synchronized
    fun save(t: String) {
        setLines(splitLines(if (t.endsWith("\n")) t else t + "\n"))
    }

    // Letters and digits of any language (Latin, Tamil, Hindi ...), lower case, single spaces.
    private val CLEAN_DROP = Regex("[^\\p{L}\\p{M}\\p{N} ]")
    private val SPACES = Regex("\\s+")

    fun clean(x: String) = x.lowercase().replace(CLEAN_DROP, "").replace(SPACES, " ").trim()

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private data class Row(val type: String, val left: String, val right: String)

    private fun parse(line: String): Row? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val bar = t.indexOf('|')
        if (bar < 0) return null
        val type = t.substring(0, bar).trim().lowercase()
        val rest = t.substring(bar + 1)
        val eq = rest.indexOf('=')
        return if (eq < 0) Row(type, rest.trim(), "") else Row(type, rest.substring(0, eq).trim(), rest.substring(eq + 1).trim())
    }

    private fun allRows(): List<Row> {
        rowsCache?.let { return it }
        val r = lines.mapNotNull { parse(it) }
        rowsCache = r
        return r
    }

    private fun rows(type: String): List<Row> = allRows().filter { it.type == type }

    private fun aliases(s: String) = s.split(",").map { clean(it) }.filter { it.isNotEmpty() }

    // alias -> value, for nick / slang / word / fact
    @Synchronized
    fun map(type: String): Map<String, String> {
        mapCache[type]?.let { return it }
        val m = LinkedHashMap<String, String>()
        for (r in rows(type)) for (a in aliases(r.left)) m[a] = r.right
        mapCache[type] = m
        return m
    }

    @Synchronized
    fun words(type: String): List<String> = rows(type).flatMap { aliases(it.left) }

    @Synchronized
    fun items(type: String): List<String> =
        rows(type).map { if (it.right.isEmpty()) it.left else it.left + " = " + it.right }

    @Synchronized
    fun setting(key: String, def: String): String =
        rows("setting").firstOrNull { clean(it.left) == key }?.right?.takeIf { it.isNotBlank() } ?: def

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    // alias -> value. Aliases with the same value share one line. An alias lives on only one line.
    @Synchronized
    fun link(type: String, alias: String, value: String) {
        val a = clean(alias)
        if (a.isEmpty()) return
        val out = mutableListOf<String>()
        for (ln in lines) {
            val r = parse(ln)
            if (r != null && r.type == type && r.right.isNotEmpty()) {
                val keep = r.left.split(",").map { it.trim() }.filter { it.isNotEmpty() && clean(it) != a }
                if (keep.isNotEmpty()) out += "$type | ${keep.joinToString(", ")} = ${r.right}"
            } else out += ln
        }
        val i = out.indexOfFirst { val r = parse(it); r != null && r.type == type && r.right.equals(value, true) }
        if (i >= 0) {
            val r = parse(out[i])!!
            out[i] = "$type | ${r.left}, $a = ${r.right}"
        } else out += "$type | $a = $value"
        setLines(out)
    }

    // one value per key (settings)
    @Synchronized
    fun put(type: String, key: String, value: String) {
        val k = clean(key)
        val out = lines.filterNot { val r = parse(it); r != null && r.type == type && clean(r.left) == k }.toMutableList()
        out += "$type | $k = $value"
        setLines(out)
    }

    @Synchronized
    fun unlink(alias: String): Boolean {
        val a = clean(alias)
        var found = false
        val out = mutableListOf<String>()
        for (ln in lines) {
            val r = parse(ln)
            if (r != null && r.right.isNotEmpty() && r.type in listOf("nick", "slang", "word", "fact", "heard") && aliases(r.left).contains(a)) {
                found = true
                val keep = r.left.split(",").map { it.trim() }.filter { it.isNotEmpty() && clean(it) != a }
                if (keep.isNotEmpty()) out += "${r.type} | ${keep.joinToString(", ")} = ${r.right}"
            } else out += ln
        }
        if (found) setLines(out)
        return found
    }

    @Synchronized
    fun add(type: String, text: String) {
        setLines(lines + "$type | $text")
    }

    @Synchronized
    fun drop(type: String, contains: String) {
        setLines(lines.filterNot { val r = parse(it); r != null && r.type == type && r.left.contains(contains, true) })
    }

    @Synchronized
    fun clear(type: String) {
        setLines(lines.filterNot { parse(it)?.type == type })
    }

    // ------------------------------------------------------------------
    // For the Brain editor screen (BrainActivity): row-level reading and editing.
    // Every change touches exactly ONE line of the current brain, so the editor can never
    // overwrite something Sanya learned while the screen was open.
    // ------------------------------------------------------------------

    /** One rule: its parts, plus the exact line it came from (used to find it again). */
    data class Item(val type: String, val left: String, val right: String, val line: String)

    // Types whose whole text is one field (no "left = right").
    private val SINGLE = setOf("filler", "todo", "note")

    fun isSingle(type: String): Boolean = type in SINGLE

    fun format(type: String, left: String, right: String): String =
        if (right.isEmpty()) "$type | $left" else "$type | $left = $right"

    /** Every rule of [type], in file order. */
    @Synchronized
    fun entries(type: String): List<Item> {

        val out = ArrayList<Item>()

        for (ln in lines) {

            val r = parse(ln) ?: continue
            if (r.type != type) continue

            if (type in SINGLE) {
                out.add(Item(type, ln.substring(ln.indexOf('|') + 1).trim(), "", ln))
            } else {
                out.add(Item(type, r.left, r.right, ln))
            }
        }

        return out
    }

    /** Lines that are not a known rule type (typos, damaged lines), so nothing is invisible. */
    @Synchronized
    fun stray(known: Set<String>): List<Item> {

        val out = ArrayList<Item>()

        for (ln in lines) {

            val t = ln.trim()
            if (t.isEmpty() || t.startsWith("#")) continue

            val r = parse(ln)
            if (r == null || r.type !in known) out.add(Item("other", t, "", ln))
        }

        return out
    }

    /**
     * Replace (or, with null, delete) one exact line.
     * Returns false if that line is gone because the file changed in the meantime.
     */
    @Synchronized
    fun replaceLine(old: String, new: String?): Boolean {

        val ml = lines.toMutableList()
        val i = ml.indexOf(old)

        if (i < 0) return false

        if (new == null) ml.removeAt(i) else ml[i] = new

        setLines(ml)

        return true
    }

    /** Add one raw line at the end. */
    @Synchronized
    fun appendLine(line: String) {
        setLines(lines + line)
    }

    // One-step undo for "Restore": the brain as it was just before.
    private fun safetyFile() = File(file!!.parentFile, "brain.before-restore.txt")

    @Synchronized
    fun keepSafetyCopy() {
        safetyFile().writeText(text())
    }

    @Synchronized
    fun hasSafetyCopy(): Boolean = safetyFile().exists()

    @Synchronized
    fun undoRestore(): Boolean {
        val b = safetyFile()
        if (!b.exists()) return false
        setLines(splitLines(b.readText()))
        b.delete()
        return true
    }

    // Words the speech recognizer should expect (wake words, nicknames, slang)
    @Synchronized
    fun hints(): ArrayList<String> = ArrayList<String>().apply {
        addAll(setting("wake words", "sanya").split(",").map { it.trim() }.filter { it.isNotEmpty() })
        addAll(map("nick").keys.take(40))
        addAll(map("slang").keys.take(40))
    }
}
