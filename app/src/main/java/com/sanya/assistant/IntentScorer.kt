package com.sanya.assistant

/**
 * IntentScorer: understands phrasings the fixed rules in CommandParser do not.
 * 100% on-device, no model, no network: a vocabulary of verbs and sentence frames, a confidence
 * for each, and slot extraction (who / what / when).
 *
 *   "ring up preethi on whatsapp"      -> Sure     "whatsapp call preethi"
 *   "call"                             -> NeedSlot "Who should I call?"
 *   "text ravi i will be late"         -> Unsure   "message ravi saying i will be late"
 *   "set a timer for an hour and a half" -> Sure   "set timer 90 minutes"
 *   "add milk to my shopping list"     -> Sure     "add milk to my list"
 *   "turn it up"                       -> Sure     "volume up"
 *
 * The output is always a command the existing rules already understand, so the normal
 * confirmations (calls, deleting lists) and learning still apply.
 *
 * Only one-way evidence is used: a sentence needs a recognised verb/frame to score at all, so a
 * phrase she should ask to be taught ("movie time") still goes to the teaching flow.
 */
object IntentScorer {

    sealed class Result {
        data class Sure(val intent: String, val command: String) : Result()
        data class Unsure(val intent: String, val command: String) : Result()

        /** She needs one more piece. [template] has %s where the answer goes. */
        data class NeedSlot(
            val intent: String,
            val prompt: String,
            val template: String,
            val free: Boolean = false      // true: the answer is free text (a message body), never a command
        ) : Result()

        object None : Result()
    }

    const val SURE = 0.85
    const val MAYBE = 0.55

    private sealed class Built(val score: Double) {
        class Cmd(val command: String, score: Double) : Built(score)
        class Need(val prompt: String, val template: String, val free: Boolean, score: Double) : Built(score)
    }

    private class Lead(val words: List<String>, val weight: Double)

    private fun leads(vararg p: Pair<String, Double>): List<Lead> =
        p.map { Lead(it.first.split(" "), it.second) }.sortedByDescending { it.words.size }

    // ---------------------------------------------------------------
    // Cleaning: strip polite filler from both ends, rewrite common frames
    // ---------------------------------------------------------------

    private val LEAD_TOKENS: List<List<String>> = listOf(
        "hey", "hi", "hello", "ok", "okay", "so", "um", "uh", "well", "now", "then", "and", "also",
        "sanya", "saniya", "sania", "sonya", "sonia", "sunya",
        "please", "pls", "plz", "kindly", "just", "quickly", "actually",
        "can you", "could you", "would you", "will you", "can u", "could u",
        "i want you to", "i want to", "i wanna", "i need to", "i need you to",
        "i would like to", "i would like you to", "i'd like to", "i'd like you to",
        "you can", "lets", "let's", "let us", "go ahead and", "try to", "help me",
        "i am going to", "i will"
    ).map { it.split(" ") }.sortedByDescending { it.size }

    private val TRAIL_TOKENS: List<List<String>> = listOf(
        "please", "pls", "plz", "for me", "now", "right now", "quickly", "thanks", "thank you",
        "okay", "ok", "will you", "can you", "if you can", "immediately", "asap"
    ).map { it.split(" ") }.sortedByDescending { it.size }

    // Frames that mean a simple verb: "make a call to X" -> "call X".
    private val NORMALIZERS: List<Pair<Regex, String>> = listOf(
        Regex("""^(?:make|give|place|do) (?:a |an )?((?:(?:whatsapp|video|voice|phone) )*)call(?: to)?(?: |$)""") to "$1call ",
        Regex("""^(?:send|write|drop|shoot|type)(?: a| an)?(?: whatsapp| quick| short)? (?:text|message|sms|msg)(?: to)?(?: |$)""") to "message ",
        Regex("""^(?:set|start|put|make|create)(?: me)?(?: a| an| the)? (?:timer|countdown)(?: for| of| to)?(?: |$)""") to "timer ",
        Regex("""^(?:set|create|put|make)(?: me)?(?: a| an| the)? alarm(?: for| at)?(?: |$)""") to "alarm ",
        Regex("""^wake me(?: up)?(?: at| by| for)?(?: |$)""") to "alarm ",
        Regex("""^(?:jot|write|note) down(?: that)?(?: |$)""") to "note ",
        Regex("""^(?:make|take) a note(?: of| that| saying)?(?: |$)""") to "note ",
        Regex("""^(?:take|bring|drive|guide|lead) me (?:to|towards)(?: |$)""") to "navigate to ",
        Regex("""^(?:show me the way to|how do i get to|how to get to|how can i get to|how to reach|directions to|route to)(?: |$)""") to "navigate to "
    )

    private fun stripEdges(list: List<String>): List<String> {
        var t = list
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (f in LEAD_TOKENS) {
                if (t.size > f.size && t.subList(0, f.size) == f) {
                    t = t.drop(f.size)
                    changed = true
                    break
                }
            }
        }
        changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (f in TRAIL_TOKENS) {
                if (t.size > f.size && t.subList(t.size - f.size, t.size) == f) {
                    t = t.dropLast(f.size)
                    changed = true
                    break
                }
            }
        }
        return t
    }
    private val RX_PUNCT = Regex("[,!?;\"]")
    private val RX_DOT = Regex("(?<!\\d)\\.|\\.(?!\\d)")
    private val RX_SPACES = Regex("\\s+")
    

    /**
     * The words of [text] with polite filler removed (and, if [normalize], common frames rewritten).
     * [fillers] are your own filler words from the Brain file ("da", "bro" ...).
     */
    fun tokens(text: String, fillers: Collection<String> = emptyList(), normalize: Boolean = true): List<String> {

        val flat = text.lowercase()
            .replace('\u2019', '\'')
            .replace(RX_PUNCT, " ")
            .replace(RX_DOT, "")
            .replace(RX_SPACES, " ")
            .trim()

        if (flat.isEmpty()) return emptyList()

        val skip = HashSet<String>()
        for (f in fillers) {
            val x = f.lowercase().trim()
            if (x.isNotEmpty() && ' ' !in x) skip.add(x)
        }

        var t = flat.split(" ").filter { it.isNotEmpty() && it !in skip }
        t = stripEdges(t)

        if (t.isEmpty() || !normalize) return t

        var s = t.joinToString(" ")

        for ((rx, rep) in NORMALIZERS) {
            if (rx.containsMatchIn(s)) {
                s = rx.replaceFirst(s, rep).trim()
                break
            }
        }

        return s.split(" ").filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------

    private fun editDistance(a: String, b: String): Int {
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

    // The lead phrase at the start of [t] (and how many words it used), or null.
    // A single long verb one letter off ("mesage", "serch") also counts, at a lower confidence.
    private fun matchLead(t: List<String>, ls: List<Lead>): Pair<Lead, Int>? {
        for (l in ls) {
            val n = l.words.size
            if (t.size >= n && t.subList(0, n) == l.words) return Pair(l, n)
        }
        if (t.isEmpty()) return null
        for (l in ls) {
            if (l.words.size != 1) continue
            val w = l.words[0]
            if (w.length < 5 || t[0].length < 4 || t[0] == w) continue
            if (editDistance(t[0], w) == 1) return Pair(Lead(l.words, l.weight * 0.8), 1)
        }
        return null
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "at six thirty pm" -> "18:30". Null if there is no clock time in [slot]. */
    fun extractClock(slot: String): String? {

        val s = Mind.numWords(slot.lowercase())
            .replace(Regex("\\b([ap])\\.? ?m\\.?(?=\\s|$)"), "$1m")
            .replace(Regex("\\s+"), " ")
            .trim()

        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?").find(s) ?: return null

        var h = m.groupValues[1].toInt()
        val min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
        var ap = m.groupValues[3]

        if (ap.isEmpty()) {
            val part = Regex("\\b(morning|afternoon|evening|night)\\b").find(s)?.value
            ap = when (part) {
                "morning" -> "am"
                "afternoon", "evening" -> "pm"
                "night" -> if (h == 12 || h < 6) "am" else "pm"
                else -> ""
            }
        }

        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0

        if (h > 23 || min > 59) return null

        return two(h) + ":" + two(min)
    }

    // ---------------------------------------------------------------
    // Vocabulary
    // ---------------------------------------------------------------

    private val CALL_LEADS = leads(
        "call" to 1.0, "ring" to 1.0, "dial" to 1.0, "phone" to 0.95, "buzz" to 0.8,
        "video call" to 1.0, "voice call" to 1.0, "whatsapp call" to 1.0, "phone call" to 1.0,
        "whatsapp video call" to 1.0, "whatsapp voice call" to 1.0
    )
    private val MSG_LEADS = leads("message" to 1.0, "text" to 0.95, "sms" to 1.0, "msg" to 1.0)
    private val OPEN_LEADS = leads(
        "open" to 1.0, "launch" to 1.0, "bring up" to 0.95, "pull up" to 0.95, "fire up" to 0.95, "switch to" to 0.9
    )
    private val SEARCH_LEADS = leads(
        "search" to 1.0, "google" to 0.9, "look up" to 1.0, "look for" to 0.9,
        "find out" to 0.85, "find" to 0.8, "browse" to 0.8
    )
    private val PLAY_LEADS = leads(
        "play" to 1.0, "listen to" to 0.95, "watch" to 0.9, "put on" to 0.8, "stream" to 0.8
    )
    private val NAV_LEADS = leads("navigate" to 1.0, "navigation" to 0.9)
    private val TIMER_LEADS = leads("timer" to 1.0, "countdown" to 0.9)

    private val CALL_BLOCK = setOf(
        "tone", "tones", "settings", "volume", "battery", "number", "numbers", "history",
        "log", "logs", "app", "screen", "list", "book"
    )
    private val MSG_BLOCK = setOf("size", "editor", "app", "apps", "settings", "tone", "history")
    private val DANGLING = setOf("on", "in", "via", "using", "through", "over", "by", "with", "to")
    private val NO_NAME = setOf("someone", "somebody", "anyone", "anybody", "a friend", "a person")
    private val PRON_CTX = setOf("him", "her", "them", "back")
    private val PRON_BLOCK = setOf(
        "me", "us", "you", "a", "an", "the", "about", "my", "your", "him", "her", "them",
        "it", "something", "everyone", "all"
    )

    private val DEVICES: List<Pair<String, String>> = listOf(
        "mobile data" to "data", "cellular data" to "data",
        "airplane mode" to "airplane mode", "aeroplane mode" to "airplane mode", "flight mode" to "airplane mode",
        "flash light" to "flashlight", "flashlight" to "flashlight", "torch" to "flashlight",
        "wi-fi" to "wifi", "wi fi" to "wifi", "wifi" to "wifi",
        "bluetooth" to "bluetooth", "location" to "location", "gps" to "gps", "nfc" to "nfc", "data" to "data"
    ).sortedByDescending { it.first.length }

    private val ON_WORDS = setOf("turn on", "switch on", "enable", "activate", "start", "power on", "put on", "on")
    private val OFF_WORDS = setOf(
        "turn off", "switch off", "disable", "deactivate", "kill", "stop", "power off", "shut off", "shut down", "put off", "off"
    )

    private val P_GIVE_CALL = Regex("""^(?:give|make) (.+?) a (?:call|ring|buzz)$""")
    private val P_TALK = Regex("""^(?:talk|speak) to (.+)$""")
    private val P_TELL_THAT = Regex("""^(?:tell|inform|let) (.+?) (?:know )?that (.+)$""")
    private val P_TELL = Regex("""^tell (\S+) (.+)$""")
    private val P_SEND_X = Regex(
        """^send (.+?) (?:a |an )(?:text|message|sms|msg)(?: (?:saying|that says|say|with|that))?(?: (.+))?$"""
    )
    private val P_TODO = Regex(
        """^(?:add|put) (.+?) (?:to|on|in|into) (?:my |the |our )?(?:(?:shopping|grocery|groceries|task|tasks|todo|to do|to-do) )?list$"""
    )
    private val P_DUR_TIMER = Regex("""^(?:set |start )?(.+?) (?:timer|countdown)$""")

    private val VOL_UP = listOf(
        Regex("""^(?:turn|crank|pump|bring|raise|put)(?: it| the sound| the music| the volume)? up$"""),
        Regex("""^(?:i )?(?:can't|cant|cannot|can not) hear(?: anything| you| it| this)?$"""),
        Regex("""^(?:(?:it is|its|it's|that is|thats|that's) )?too (?:quiet|low|soft|silent)$"""),
        Regex("""^(?:speak|talk) louder$""")
    )
    private val VOL_DOWN = listOf(
        Regex("""^(?:turn|bring|tone|put|take)(?: it| the sound| the music| the volume)? down$"""),
        Regex("""^(?:(?:it is|its|it's|that is|thats|that's) )?too (?:loud|high|noisy)$"""),
        Regex("""^(?:speak|talk) (?:softer|quieter)$"""),
        Regex("""^(?:lower|reduce) (?:it|the sound|the music)$""")
    )

    private val BATTERY_A = Regex("""\b(?:charge|charged|charging|juice|power)\b""")
    private val BATTERY_B = Regex("""\b(?:left|level|percent|percentage|much|remaining|status)\b""")
    private val TIME_Q = Regex("""^how late(?: is it)?(?: now)?$""")
    private val DATE_Q = Regex("""^(?:(?:which|what) date(?: is it| is today| today)?|date today|today date)$""")

    private val REL_WORDS = Regex("""\b(?:hour|hours|minute|minutes|min|mins|hr|hrs)\b""")

    // ---------------------------------------------------------------
    // Reading a sentence
    // ---------------------------------------------------------------

    /**
     * What does [text] mean? [fillers] are your own filler words; [isApp] says whether a name is an
     * installed app (used for the weaker "open" phrasings).
     */
    fun interpret(
        text: String,
        fillers: Collection<String> = emptyList(),
        isApp: (String) -> Boolean = { true }
    ): Result {

        val t = tokens(text, fillers)
        if (t.isEmpty()) return Result.None

        val joined = t.joinToString(" ")
        val cands = ArrayList<Pair<String, Built>>()

        fun add(x: Pair<String, Built>?) {
            if (x != null) cands.add(x)
        }

        add(patterns(joined))
        add(deviceIntent(joined))
        add(callIntent(t))
        add(messageIntent(t))
        add(openIntent(t, isApp))
        add(searchIntent(t))
        add(playIntent(t))
        add(navIntent(t))
        add(timerIntent(t))
        add(alarmIntent(t))
        add(noteIntent(t))

        val best = cands.maxByOrNull { it.second.score } ?: return Result.None

        return when (val b = best.second) {
            is Built.Cmd ->
                when {
                    b.score >= SURE -> Result.Sure(best.first, b.command)
                    b.score >= MAYBE -> Result.Unsure(best.first, b.command)
                    else -> Result.None
                }
            is Built.Need ->
                if (b.score >= SURE) Result.NeedSlot(best.first, b.prompt, b.template, b.free) else Result.None
        }
    }

    // ---------------------------------------------------------------
    // Whole-sentence frames
    // ---------------------------------------------------------------

    private fun patterns(s: String): Pair<String, Built>? {

        P_GIVE_CALL.matchEntire(s)?.let { m ->
            val who = m.groupValues[1]
            if (who !in PRON_BLOCK) return Pair("call", Built.Cmd("call $who", 0.95))
        }

        P_TALK.matchEntire(s)?.let { m ->
            val who = m.groupValues[1]
            if (who !in PRON_BLOCK) return Pair("call", Built.Cmd("call $who", 0.7))
        }

        P_TELL_THAT.matchEntire(s)?.let { m ->
            val who = m.groupValues[1]
            if (who !in PRON_BLOCK) return Pair("message", Built.Cmd("message $who saying ${m.groupValues[2]}", 0.9))
        }

        P_SEND_X.matchEntire(s)?.let { m ->
            val who = m.groupValues[1]
            val body = m.groupValues[2]
            if (who !in PRON_BLOCK) {
                return if (body.isEmpty()) {
                    Pair("message", Built.Need("What should I say to $who?", "message $who saying %s", true, 0.95))
                } else {
                    Pair("message", Built.Cmd("message $who saying $body", 0.95))
                }
            }
        }

        P_TELL.matchEntire(s)?.let { m ->
            val who = m.groupValues[1]
            if (who !in PRON_BLOCK) return Pair("message", Built.Cmd("message $who saying ${m.groupValues[2]}", 0.6))
        }

        P_TODO.matchEntire(s)?.let { m ->
            return Pair("todo", Built.Cmd("add ${m.groupValues[1]} to my list", 0.95))
        }

        P_DUR_TIMER.matchEntire(s)?.let { m ->
            val d = Num.parseDuration(m.groupValues[1])
            if (d != null) return Pair("timer", Built.Cmd("set timer $d minutes", 0.9))
        }

        for (r in VOL_UP) if (r.matches(s)) return Pair("volume", Built.Cmd("volume up", 0.9))
        for (r in VOL_DOWN) if (r.matches(s)) return Pair("volume", Built.Cmd("volume down", 0.9))

        if (BATTERY_A.containsMatchIn(s) && BATTERY_B.containsMatchIn(s)) {
            return Pair("info", Built.Cmd("battery", 0.9))
        }
        if (TIME_Q.matches(s)) return Pair("info", Built.Cmd("what time is it", 0.9))
        if (DATE_Q.matches(s)) return Pair("info", Built.Cmd("what is the date", 0.9))

        return null
    }

    // "kill bluetooth", "wifi off", "activate the torch"
    private fun deviceIntent(joined: String): Pair<String, Built>? {

        val padded = " $joined "

        for ((phrase, canon) in DEVICES) {

            if (!padded.contains(" $phrase ")) continue

            val rest = padded.replace(" $phrase ", " ")
                .trim()
                .split(" ")
                .filter { it.isNotEmpty() && it != "the" && it != "my" }
                .joinToString(" ")

            if (rest in ON_WORDS) return Pair("device", Built.Cmd("turn on $canon", 0.9))
            if (rest in OFF_WORDS) return Pair("device", Built.Cmd("turn off $canon", 0.9))

            return null
        }

        return null
    }

    // ---------------------------------------------------------------
    // Verb-led intents
    // ---------------------------------------------------------------

    private fun callIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, CALL_LEADS) ?: return null
        var rest = t.drop(m.second)

        if (rest.isNotEmpty() && rest[0] in CALL_BLOCK) return null

        val video = "video" in t
        val wa = "whatsapp" in t || video

        while (rest.isNotEmpty() && (rest[0] == "up" || rest[0] == "to")) rest = rest.drop(1)
        rest = rest.filter { it != "whatsapp" && it != "video" && it != "voice" && it != "call" }
        while (rest.isNotEmpty() && rest.last() in DANGLING) rest = rest.dropLast(1)

        val name = rest.joinToString(" ")
        val prefix = if (video) "video call " else if (wa) "whatsapp call " else "call "
        val w = m.first.weight

        if (name.isEmpty() || name in NO_NAME || name in PRON_CTX) {
            return if (w >= SURE) Pair("call", Built.Need("Who should I call?", prefix + "%s", false, w)) else null
        }

        if (name in PRON_BLOCK) return null

        val penalty = if (rest.size > 3) 0.3 else 0.0

        return Pair("call", Built.Cmd(prefix + name, w - penalty))
    }

    private fun messageIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, MSG_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && (rest[0] == "to" || rest[0] == "up")) rest = rest.drop(1)

        if (rest.isNotEmpty() && rest[0] in MSG_BLOCK) return null

        val w = m.first.weight
        val sep = rest.indexOfFirst { it == "saying" || it == "say" || it == "says" || it == "that" }

        if (sep >= 0) {

            val who = rest.subList(0, sep).joinToString(" ")
            var body = rest.drop(sep + 1)
            if (rest[sep] == "that" && body.firstOrNull() == "says") body = body.drop(1)
            val text = body.joinToString(" ")

            if (who.isEmpty()) {
                return if (w >= SURE && text.isNotEmpty()) {
                    Pair("message", Built.Need("Who should I message?", "message %s saying $text", false, w))
                } else {
                    null
                }
            }

            if (text.isEmpty()) {
                return if (w >= SURE) {
                    Pair("message", Built.Need("What should I say to $who?", "message $who saying %s", true, w))
                } else {
                    null
                }
            }

            return Pair("message", Built.Cmd("message $who saying $text", w))
        }

        val who = rest.joinToString(" ")

        return when {
            rest.isEmpty() ->
                if (w >= SURE) Pair("message", Built.Need("Who should I message?", "message %s", false, w)) else null

            rest.size <= 2 ->
                if (w >= SURE) {
                    Pair("message", Built.Need("What should I say to $who?", "message $who saying %s", true, w))
                } else {
                    null
                }

            // "text ravi i will be late": the first word is probably the person. Check first.
            else ->
                Pair("message", Built.Cmd("message ${rest[0]} saying ${rest.drop(1).joinToString(" ")}", minOf(w, 0.6)))
        }
    }

    private fun openIntent(t: List<String>, isApp: (String) -> Boolean): Pair<String, Built>? {

        val m = matchLead(t, OPEN_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && (rest[0] == "up" || rest[0] == "the" || rest[0] == "app")) rest = rest.drop(1)
        while (rest.isNotEmpty() && (rest.last() == "app" || rest.last() == "application")) rest = rest.dropLast(1)

        val name = rest.joinToString(" ")
        val w = m.first.weight

        if (name.isEmpty()) {
            return if (w >= 0.95) Pair("open", Built.Need("Which app should I open?", "open %s", false, w)) else null
        }

        // A weaker phrasing (or a one-letter slip) only counts if the name really is an app.
        if (w < 0.9 && !isApp(name)) return null

        return Pair("open", Built.Cmd("open $name", w))
    }

    private fun searchIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, SEARCH_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && (rest[0] == "for" || rest[0] == "about" || rest[0] == "on" || rest[0] == "up")) {
            rest = rest.drop(1)
        }

        var q = rest.joinToString(" ")
        val youtube = Regex("\\s(?:on|in) youtube$").containsMatchIn(q)

        q = q.replace(Regex("\\s(?:on|in) youtube$"), "")
            .replace(Regex("\\s(?:on|in) (?:google|the web|the internet|internet)$"), "")
            .replace(Regex("\\sonline$"), "")
            .trim()

        val w = m.first.weight

        if (q.isEmpty()) {
            return if (w >= SURE) Pair("search", Built.Need("What should I search for?", "search %s", false, w)) else null
        }

        return Pair("search", Built.Cmd((if (youtube) "play " else "search ") + q, w))
    }

    private fun playIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, PLAY_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && (rest[0] == "some" || rest[0] == "the" || rest[0] == "a" || rest[0] == "me")) {
            rest = rest.drop(1)
        }

        val q = rest.joinToString(" ")
        val w = m.first.weight

        if (q.isEmpty()) {
            return if (w >= 0.9) Pair("play", Built.Need("What should I play?", "play %s", false, w)) else null
        }

        return Pair("play", Built.Cmd("play $q", w))
    }

    private fun navIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, NAV_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && rest[0] == "to") rest = rest.drop(1)

        val place = rest.joinToString(" ")
        val w = m.first.weight

        if (place.isEmpty()) {
            return Pair("navigate", Built.Need("Where do you want to go?", "navigate to %s", false, w))
        }

        return Pair("navigate", Built.Cmd("navigate to $place", w))
    }

    private fun timerIntent(t: List<String>): Pair<String, Built>? {

        val m = matchLead(t, TIMER_LEADS) ?: return null
        var rest = t.drop(m.second)

        while (rest.isNotEmpty() && (rest[0] == "for" || rest[0] == "of" || rest[0] == "to")) rest = rest.drop(1)

        val slot = rest.joinToString(" ")
        val w = m.first.weight

        if (slot.isEmpty()) {
            return Pair("timer", Built.Need("For how long?", "timer for %s", false, w))
        }

        val d = Num.parseDuration(slot)
        if (d != null) return Pair("timer", Built.Cmd("set timer $d minutes", w))

        val sm = Regex("^(.+?) seconds?$").matchEntire(slot)
        val secs = sm?.let { Num.parse(it.groupValues[1])?.toInt() }
        if (secs != null && secs in 1..86400) return Pair("timer", Built.Cmd("set timer $secs seconds", w))

        return null
    }

    private fun alarmIntent(t: List<String>): Pair<String, Built>? {

        if (t.isEmpty() || t[0] != "alarm") return null

        var rest = t.drop(1)

        while (rest.isNotEmpty() && (rest[0] == "for" || rest[0] == "at" || rest[0] == "on")) rest = rest.drop(1)

        val slot = rest.joinToString(" ")

        if (slot.isEmpty()) {
            return Pair("alarm", Built.Need("What time should I set the alarm for?", "alarm at %s", false, 1.0))
        }

        // "wake me in 20 minutes" is really a timer.
        if (REL_WORDS.containsMatchIn(slot)) {
            val d = Num.parseDuration(slot.removePrefix("in ")) ?: return null
            return Pair("timer", Built.Cmd("set timer $d minutes", 0.95))
        }

        val clock = extractClock(slot) ?: return null

        return Pair("alarm", Built.Cmd("alarm at $clock", 1.0))
    }

    private fun noteIntent(t: List<String>): Pair<String, Built>? {

        if (t.isEmpty() || t[0] != "note") return null

        val text = t.drop(1).joinToString(" ")

        if (text.isEmpty()) {
            return Pair("note", Built.Need("What should I note down?", "note %s", true, 0.95))
        }

        return Pair("note", Built.Cmd("note $text", 0.95))
    }
}
