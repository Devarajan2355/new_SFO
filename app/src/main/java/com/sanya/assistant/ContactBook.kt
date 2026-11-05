package com.sanya.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Choose: picks one of a few spoken options from your answer. No Android calls, so it is unit-tested.
 *
 *   "the second one" / "last"        -> by position
 *   "kumar" / "mobile" / "teja"      -> by a word that only one option has
 *   "3 2 1 0"                        -> by the last digits of a number
 *   a misheard word                  -> the option that sounds closest, if it is clearly closest
 */
object Choose {

    /** key is what you get back (a name, a number, an id). words are what you can say to pick it. */
    data class Option(val label: String, val key: String, val words: List<String>)

    private val ORDINALS: Map<String, Int> = mapOf(
        "first" to 0, "1st" to 0, "1" to 0, "one" to 0,
        "second" to 1, "2nd" to 1, "2" to 1, "two" to 1,
        "third" to 2, "3rd" to 2, "3" to 2, "three" to 2,
        "fourth" to 3, "4th" to 3, "4" to 3, "four" to 3,
        "last" to -1
    )

    private val STOP = setOf(
        "the", "a", "an", "one", "please", "number", "call", "message", "text", "to", "on", "with", "it"
    )

    fun words(text: String): List<String> =
        Brain.clean(text).split(" ").filter { it.isNotEmpty() }

    /** "a, b or c" */
    fun joinOr(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        2 -> list[0] + " or " + list[1]
        else -> list.dropLast(1).joinToString(", ") + " or " + list.last()
    }

    /** The option your [answer] points at, or null if it is not clear. */
    fun pick(options: List<Option>, answer: String): Option? {

        if (options.isEmpty()) return null

        val a = Brain.clean(answer)
        if (a.isEmpty()) return null

        val toks = a.split(" ").filter { it.isNotEmpty() }

        // 1. The last digits of a number: "3 2 1 0".
        val digits = a.filter { it.isDigit() }

        if (digits.length >= 2) {
            val hit = options.filter { it.key.endsWith(digits) }
            if (hit.size == 1) return hit[0]
        }

        // 2. By position: "the second one", "last".
        if (toks.size <= 3) {
            for (t in toks) {
                val i = ORDINALS[t] ?: continue
                if (i == -1) return options.last()
                if (i < options.size) return options[i]
            }
        }

        // 3. By a word that fits only one of them.
        val said = toks.filter { it !in STOP }

        if (said.isNotEmpty()) {
            val scored = options.map { o -> Pair(o, said.count { it in o.words }) }
            val best = scored.maxOf { it.second }
            if (best > 0) {
                val top = scored.filter { it.second == best }
                return if (top.size == 1) top[0].first else null
            }
        }

        // 4. A misheard word: the one that sounds closest, if it is clearly closest.
        var bestScore = 0.0
        var second = 0.0
        var bestOption: Option? = null

        for (o in options) {
            val label = Brain.clean(o.label)
            var s = Mind.similarity(a, label)
            if (Mind.soundScore(a, o.label) >= 55) s = maxOf(s, 0.7)
            if (s > bestScore) {
                second = bestScore
                bestScore = s
                bestOption = o
            } else if (s > second) {
                second = s
            }
        }

        return if (bestOption != null && bestScore >= 0.5 && bestScore - second >= 0.1) bestOption else null
    }
}

/**
 * ContactBook: finds people in your contacts the same way she does when you say "call ravi",
 * and notices when the name fits more than one person ("Which one: Ravi Kumar, or Ravi Teja?").
 *
 * The ranking and the "is it ambiguous?" decision are pure Kotlin (unit-tested); only all() reads contacts.
 *
 * Remembering what you chose:
 *   - which person: a nickname, nick | ravi = Ravi Kumar
 *   - which number of one person: setting | number ravi kumar = 9876543210
 * Edit or delete those lines in the Brain editor to change your mind.
 */
object ContactBook {

    /** One phone number of one contact. label is "mobile", "home", "work" ... */
    data class Entry(val id: Long, val name: String, val number: String, val label: String)

    data class Person(val id: Long, val name: String, val score: Int, val entries: List<Entry>)

    /** kind: "who" (several people) or "number" (one person, several numbers). */
    class Question(val prompt: String, val kind: String, val person: String, val options: List<Choose.Option>)

    // ---------------------------------------------------------------
    // Pure part
    // ---------------------------------------------------------------

    private val NON_ALNUM = Regex("[^a-z0-9]")

    private fun squash(x: String): String = x.lowercase().replace(NON_ALNUM, "")

    /** The last 10 digits of a number, so "+91 98765 43210" and "098765 43210" are the same. */
    fun digits(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length > 10) d.takeLast(10) else d
    }

    private fun lev(a: String, b: String): Int {

        val d = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            }
        }

        return d[a.length][b.length]
    }

    /**
     * How well [contactName] fits what was said: 100 same, 90 a whole word of the name,
     * 80 starts with it, 70 contains it, 60 / 50 one or two letters off, 55 / 50 sounds alike, else 0.
     * The same marks the command runner gives, so the two always agree.
     */
    fun score(contactName: String, spoken: String): Int {

        val q = squash(spoken)
        if (q.isEmpty()) return 0

        val n = squash(contactName)
        if (n == q) return 100

        val tokens = contactName.lowercase().split(" ").map { squash(it) }

        if (q in tokens) return 90
        if (n.startsWith(q)) return 80
        if (n.contains(q)) return 70

        val d = tokens.minOfOrNull { lev(it, q) } ?: 9

        return when {
            d <= 1 && q.length > 3 -> 60
            d <= 2 && q.length > 5 -> 50
            else -> Mind.soundScore(q, contactName)
        }
    }

    /** One entry per person (their numbers together), best fit first. */
    fun rank(entries: List<Entry>, spoken: String): List<Person> {

        val byId = LinkedHashMap<Long, MutableList<Entry>>()

        for (e in entries) byId.getOrPut(e.id) { ArrayList() }.add(e)

        val out = ArrayList<Person>()

        for ((id, rows) in byId) {
            val s = score(rows[0].name, spoken)
            if (s > 0) out.add(Person(id, rows[0].name, s, rows))
        }

        return out.sortedWith(compareByDescending<Person> { it.score }.thenBy { it.name })
    }

    /**
     * The people to ask "which one?" about, or empty if the best fit is clear.
     * Only strong fits count (a whole word, the start, or inside the name), and an exact
     * full-name match always wins: "Ravi" beats "Ravi Kumar" when you say "ravi".
     */
    fun ambiguous(people: List<Person>): List<Person> {

        val top = people.firstOrNull() ?: return emptyList()

        if (top.score < 70) return emptyList()

        val close = people.filter {
            it.score >= 70 && (it.score == top.score || (top.score < 100 && top.score - it.score <= 10))
        }

        // The same name saved twice is not a "who" question.
        val different = close.map { squash(it.name) }.distinct()

        return if (close.size >= 2 && different.size >= 2) close else emptyList()
    }

    /** A person's numbers, each distinct number once. */
    fun distinctNumbers(p: Person): List<Entry> {

        val seen = HashSet<String>()
        val out = ArrayList<Entry>()

        for (e in p.entries) {
            val d = digits(e.number)
            if (d.isNotEmpty() && seen.add(d)) out.add(e)
        }

        return out
    }

    private fun last4(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length <= 4) d else d.takeLast(4)
    }

    private fun spaced(d: String): String = d.toCharArray().joinToString(" ")

    // ---------------------------------------------------------------
    // Your contacts
    // ---------------------------------------------------------------

    private var cache: List<Entry> = emptyList()
    private var cacheAt = 0L

    private fun labelOf(ctx: Context, type: Int, custom: String): String =
        try {
            Phone.getTypeLabel(ctx.resources, type, custom).toString().lowercase()
        } catch (e: Exception) {
            "number"
        }

    @Synchronized
    fun all(ctx: Context): List<Entry> {

        val now = System.currentTimeMillis()

        if (cache.isNotEmpty() && now - cacheAt < 60_000L) return cache

        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val out = ArrayList<Entry>()

        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Entry(
                            c.getLong(0),
                            c.getString(1) ?: "",
                            c.getString(2) ?: "",
                            labelOf(ctx, c.getInt(3), c.getString(4) ?: "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Reading contacts", e)
        }

        cache = out
        cacheAt = now

        return out
    }

    /** "my mother" -> "ammu" (people she knows), and your nicknames ("amma" -> "revathi"). */
    fun resolve(spoken: String): String {

        val s = Mind.resolveRelations(spoken.trim()).removePrefix("my ").trim()

        return Brain.map("nick")[Brain.clean(s)] ?: s
    }

    /** Everyone in your contacts that fits [spoken], best first. */
    fun find(ctx: Context, spoken: String): List<Person> = rank(all(ctx), resolve(spoken))

    /** The number you chose for each person who has more than one: name -> last 10 digits. */
    fun preferred(): Map<String, String> {

        val out = HashMap<String, String>()

        for ((k, v) in Brain.map("setting")) {
            if (k.startsWith("number ") && v.isNotBlank()) {
                out[k.removePrefix("number ").trim()] = digits(v)
            }
        }

        return out
    }

    /**
     * Is there something to ask before calling or messaging [spoken]? Null if not.
     * [askNumbers] is false for WhatsApp, which picks the number itself.
     */
    fun question(ctx: Context, spoken: String, askNumbers: Boolean): Question? {

        if (Brain.clean(spoken).isEmpty()) return null

        val people = find(ctx, spoken)
        if (people.isEmpty()) return null

        val amb = ambiguous(people)

        if (amb.isNotEmpty()) {
            val shown = amb.take(4)
            val opts = shown.map { Choose.Option(it.name, it.name, Choose.words(it.name)) }
            return Question("Which one: " + Choose.joinOr(shown.map { it.name }) + "?", "who", "", opts)
        }

        if (!askNumbers) return null

        val top = people[0]

        if (top.score < 70) return null
        if (preferred().containsKey(Brain.clean(top.name))) return null

        val nums = distinctNumbers(top)

        if (nums.size < 2) return null

        val opts = nums.take(4).map { e ->
            val end = last4(e.number)
            Choose.Option(
                e.label + " ending " + spaced(end),
                digits(e.number),
                Choose.words(e.label) + end
            )
        }

        val prompt = "Which number for ${top.name}: " + Choose.joinOr(opts.map { it.label }) + "?"

        return Question(prompt, "number", top.name, opts)
    }
}
