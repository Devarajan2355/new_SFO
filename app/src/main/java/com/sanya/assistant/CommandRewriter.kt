package com.sanya.assistant

import android.content.Context
import android.content.Intent

/**
 * CommandRewriter: sits between the microphone and StepRunner.
 *
 *   0. Call history and your lists (Personal) answer first: "any missed calls", "call back",
 *      "add milk to my shopping list", and your "yes" to "Call back Ravi?".
 *   1. A pending question ("Who should I call?", "Which one?") takes your next sentence as its answer.
 *   2. Calendar questions ("what's my day", "when is my next meeting") are answered by Agenda.
 *   3. Known gaps in the fixed rules are fixed first (see prepass).
 *   4. If the rules understand the sentence, it runs as it is. A call or message to a name that fits
 *      two people ("call ravi" with Ravi Kumar and Ravi Teja) asks "Which one?" first, and a person
 *      with two numbers asks "Which number?" once. Your choice is remembered (see ContactBook).
 *   5. Otherwise IntentScorer reads it: run it, ask "Did you mean ...?", or ask for the missing piece.
 *
 * BubbleService calls decide() for every spoken command.
 */
object CommandRewriter {

    sealed class Decision {
        /** Hand this to StepRunner.handle(). */
        data class Run(val command: String) : Decision()

        /** Say [question]; "yes" runs [command]. */
        data class Ask(val question: String, val command: String) : Decision()

        /** Say [prompt] and keep listening: the next sentence is the answer. */
        data class Need(val prompt: String) : Decision()

        /** Just say [text]. */
        data class Say(val text: String) : Decision()
    }

    /**
     * template has %s where your answer goes. options is set when the answer is a choice between a
     * few things (kind "who" = which person, "number" = which number of [person]); [spoken] is the
     * name you said, which is remembered once you choose.
     */
    private class Pending(
        val template: String,
        val free: Boolean,
        val at: Long,
        val options: List<Choose.Option> = emptyList(),
        val kind: String = "",
        val spoken: String = "",
        val person: String = ""
    )

    private const val PENDING_MS = 20_000L
    private const val CONTACT_MS = 10 * 60_000L
    private const val APPS_MS = 5 * 60_000L

    private var pending: Pending? = null
    private var lastContact = ""
    private var lastContactAt = 0L
    private var appCache: List<String> = emptyList()
    private var appCacheAt = 0L

    private val CANCEL = Regex("""^(?:cancel|never mind|nevermind|forget it|stop|no|nope|nothing|leave it)$""")

    private val KNOWN_APPS = setOf(
        "youtube", "whatsapp", "instagram", "facebook", "google", "chrome", "gmail", "spotify",
        "camera", "settings", "maps", "telegram", "twitter", "snapchat"
    )

    // Intents the scorer may settle BEFORE the fixed rules, because the rules get them wrong.
    private val PRE_INTENTS = setOf("timer", "alarm", "navigate")

    // "play the next song" would be a YouTube search for "the next song" under the fixed rules.
    private val MEDIA: Map<String, String> = mapOf(
        "next one" to "next", "skip this" to "next", "skip this one" to "next", "skip it" to "next",
        "play next" to "next", "play the next" to "next", "play next song" to "next",
        "play the next song" to "next", "play next track" to "next", "play the next track" to "next",
        "change the song" to "next", "change song" to "next", "change this song" to "next",
        "another song" to "next",
        "previous one" to "previous", "play previous" to "previous", "play the previous" to "previous",
        "play previous song" to "previous", "play the previous song" to "previous",
        "play last song" to "previous", "play the last song" to "previous",
        "stop it" to "pause", "pause it" to "pause", "pause this" to "pause", "stop this" to "pause",
        "resume it" to "resume", "continue it" to "resume", "play it" to "resume"
    )

    private val PRONOUN = Regex(
        """^(call|ring|dial|phone|whatsapp call|video call|message|text|sms|msg)(?: up)? (him|her|them|back|that person|the same person|same person)(?: back)?(?: (?:saying|that says|say) (.+))?$"""
    )
    private val APP_GO = Regex("""^(?:go to|go on|switch to|take me to) (.+?)(?: app)?$""")
    private val TRACK = Regex(
        """^(?:whatsapp call|video call|call|ring|dial|phone|message|text|sms|msg)\s+(?:to\s+)?(.+?)(?:\s+(?:saying|that says|say)\b.*)?$"""
    )
    private val PRONOUNS = setOf("him", "her", "them", "back", "that person", "the same person", "same person")

    /** True while she has asked something and is waiting for your answer. */
    fun wantsReply(): Boolean {
        if (Personal.wantsReply()) return true
        val p = pending ?: return false
        return System.currentTimeMillis() - p.at < PENDING_MS
    }

    // ---------------------------------------------------------------
    // The decision
    // ---------------------------------------------------------------

    fun decide(ctx: Context, runner: StepRunner, raw: String): Decision = decide(ctx, runner, raw, 0)

    private fun decide(ctx: Context, runner: StepRunner, raw: String, depth: Int): Decision {

        val text = raw.trim()
        if (text.isEmpty() || depth > 2) return Decision.Run(text)

        val p = pending
        val waitingForBody = p != null && p.free && System.currentTimeMillis() - p.at < PENDING_MS

        // 0. Call history and your lists. Not while she waits for the words of a message:
        //    "tell ravi call me back" must not be taken as "call back".
        if (!waitingForBody) {
            Personal.handle(ctx, text)?.let { return Decision.Say(it) }
        }

        // 1. Your answer to a question she asked ("Who should I call?", "Which one?")
        if (p != null) {
            pending = null
            if (System.currentTimeMillis() - p.at < PENDING_MS) {

                if (CANCEL.matches(Brain.clean(text))) return Decision.Say("Okay.")

                if (p.options.isNotEmpty()) {
                    // A choice: "the second one", "teja", "mobile". Anything else is a new command.
                    val choice = Choose.pick(p.options, text)
                    if (choice != null) return decide(ctx, runner, answered(p, choice), depth + 1)
                } else if (
                    // A message body is taken as it is. Anything else that is a complete
                    // command of its own (or a calendar question) is a new command, not an answer.
                    p.free || !(runner.understands(text) || Agenda.understands(text) || Personal.understands(ctx, text))
                ) {
                    return decide(ctx, runner, p.template.replaceFirst("%s", text), depth + 1)
                }
            }
        }

        // 2. Calendar: "what's my day", "when is my next meeting", "am i free tomorrow at 3"
        Agenda.handle(ctx, text)?.let { return Decision.Say(it) }

        val fillers = fillers()

        // 3. Fix what the fixed rules get wrong
        val fixed = prepass(ctx, text, fillers)

        // 4. The fixed rules understand it: run it as it is
        if (runner.understands(fixed)) {
            if (depth == 0) resolveWho(ctx, fixed)?.let { return it }
            track(fixed)
            return Decision.Run(fixed)
        }

        // 5. Read it with the scorer
        val r = IntentScorer.interpret(fixed, fillers) { isApp(ctx, it) }

        return when (r) {
            is IntentScorer.Result.Sure ->
                if (runner.understands(r.command)) {
                    if (depth == 0) resolveWho(ctx, r.command)?.let { return it }
                    track(r.command)
                    Decision.Run(r.command)
                } else {
                    Decision.Run(fixed)
                }

            is IntentScorer.Result.Unsure ->
                if (runner.understands(r.command)) {
                    Decision.Ask("Did you mean ${r.command}?", r.command)
                } else {
                    Decision.Run(fixed)
                }

            is IntentScorer.Result.NeedSlot -> {
                pending = Pending(r.template, r.free, System.currentTimeMillis())
                Decision.Need(r.prompt)
            }

            IntentScorer.Result.None -> Decision.Run(fixed)
        }
    }

    /**
     * Could this sentence be understood by the calendar, your lists and calls, the prepass or the
     * scorer? Used to choose between the several guesses Google gives. Changes nothing.
     */
    fun canInterpret(ctx: Context, text: String): Boolean {
        if (Agenda.understands(text)) return true
        if (Personal.understands(ctx, text)) return true
        val fillers = fillers()
        if (prepass(ctx, text, fillers) != text) return true
        return IntentScorer.interpret(text, fillers) { isApp(ctx, it) } !is IntentScorer.Result.None
    }

    private fun fillers(): List<String> = try {
        Brain.words("filler")
    } catch (e: Exception) {
        emptyList()
    }

    // ---------------------------------------------------------------
    // Which person? Which number?
    // ---------------------------------------------------------------

    /**
     * "call ravi" when two contacts fit: ask which one, and keep the command so your answer completes
     * it. The first time a person with two numbers is called, ask which number. Null if nothing to ask.
     */
    private fun resolveWho(ctx: Context, cmd: String): Decision? {

        val c = cmd.trim().lowercase()
        val m = TRACK.matchEntire(c) ?: return null
        val g = m.groups[1] ?: return null
        val spoken = g.value.trim()

        if (spoken.isEmpty() || spoken in PRONOUNS) return null

        // WhatsApp picks the number itself, so only ask which person.
        val plain = !(c.startsWith("whatsapp") || c.startsWith("video"))

        val q: ContactBook.Question? = try {
            ContactBook.question(ctx, spoken, plain)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Which contact", e)
            null
        }

        if (q == null) return null

        val template = c.substring(0, g.range.first) + "%s" + c.substring(g.range.last + 1)

        pending = Pending(template, false, System.currentTimeMillis(), q.options, q.kind, spoken, q.person)

        return Decision.Need(q.prompt)
    }

    // The command to run once you have chosen, after remembering your choice.
    private fun answered(p: Pending, choice: Choose.Option): String {

        if (p.kind == "who") {
            // "ravi" now means this person, for every verb (call, message ...).
            Brain.link("nick", p.spoken, choice.key)
            return p.template.replaceFirst("%s", choice.key)
        }

        // Which number of one person: remember it, then say the same command again.
        Brain.put("setting", "number " + Brain.clean(p.person), choice.key)

        return p.template.replaceFirst("%s", p.spoken)
    }

    // ---------------------------------------------------------------
    // Fixes that must happen BEFORE the fixed rules
    // ---------------------------------------------------------------

    private fun prepass(ctx: Context, text: String, fillers: Collection<String>): String {

        val plain = IntentScorer.tokens(text, fillers, false).joinToString(" ")
        if (plain.isEmpty()) return text

        val c = IntentScorer.tokens(text, fillers).joinToString(" ")

        // "play the next song" -> next
        MEDIA[plain]?.let { return it }

        // "call her back", "message him saying hi" -> the last person you named
        PRONOUN.matchEntire(c)?.let { m ->
            if (lastContact.isNotEmpty() && System.currentTimeMillis() - lastContactAt < CONTACT_MS) {
                val body = m.groupValues[3]
                return m.groupValues[1] + " " + lastContact + (if (body.isEmpty()) "" else " saying $body")
            }
        }

        // "go to youtube" opens the app instead of searching Maps for "youtube"
        APP_GO.matchEntire(plain)?.let { m ->
            val name = Brain.clean(m.groupValues[1])
            if (name.isNotEmpty() && isApp(ctx, name)) return "open $name"
        }

        // Timers, alarm times and "how do I get to ...": the scorer reads them correctly
        // ("1 hour 30 minutes", "alarm 7 30", "wake me up at six thirty").
        val r = IntentScorer.interpret(text, fillers) { isApp(ctx, it) }
        if (r is IntentScorer.Result.Sure && r.intent in PRE_INTENTS) return r.command

        return text
    }

    // ---------------------------------------------------------------
    // Context: who you talked about last
    // ---------------------------------------------------------------

    private fun track(cmd: String) {
        val m = TRACK.matchEntire(cmd.trim().lowercase()) ?: return
        val who = m.groupValues[1].trim()
        if (who.isEmpty() || who in PRONOUNS) return
        lastContact = who
        lastContactAt = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------
    // Installed apps (cached)
    // ---------------------------------------------------------------

    private fun appLabels(ctx: Context): List<String> {

        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < APPS_MS) return appCache

        val list = try {
            val pm = ctx.packageManager
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            ).map { Brain.clean(it.loadLabel(pm).toString()) }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

        appCache = list
        appCacheAt = now

        return list
    }

    private fun isApp(ctx: Context, name: String): Boolean {
        val n = Brain.clean(name)
        if (n.isEmpty()) return false
        if (n in KNOWN_APPS) return true
        if (Brain.map("nick").containsKey(n)) return true
        return appLabels(ctx).any { it == n }
    }
}
