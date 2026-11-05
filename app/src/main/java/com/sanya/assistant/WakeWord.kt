package com.sanya.assistant

/**
 * WakeWord: the pure logic behind the two-stage wake word.
 *
 *   stage 1  a recogniser limited to a few words (grammar) listens all the time: cheap
 *   stage 2  when it hears a wake word, the audio is replayed through the full recogniser,
 *            and that transcript must contain a wake-like word (by sound) too
 *
 * No Android calls here, so it can be unit-tested.
 */
object WakeWord {

    private const val MAX_WORDS = 12

    /** Wake words as the grammar wants them: lower case, letters and single spaces only. */
    fun cleanWords(raw: List<String>): List<String> =
        raw.map { it.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_WORDS)

    /** The JSON grammar Vosk expects: ["sanya","sonia","[unk]"]. */
    fun grammar(words: List<String>): String =
        "[" + words.joinToString(",") { "\"$it\"" } + ",\"[unk]\"]"

    /** The wake word the grammar recogniser heard in [text], or null. */
    fun detect(text: String, words: List<String>): String? {
        val set = words.toSet()
        return text.lowercase().split(" ").firstOrNull { it in set }
    }

    /**
     * Builds the command text from the full transcript. Looks for a word that SOUNDS like a wake
     * word in the first three words and replaces it with [detected], so the normal wake-word
     * handling recognises it. Null means the full transcript did not confirm the wake word.
     * With [verify] false, an unconfirmed transcript is accepted with [detected] put in front.
     */
    fun rebuild(fullText: String, detected: String, words: List<String>, verify: Boolean): String? {

        val toks = fullText.lowercase().split(" ").filter { it.isNotEmpty() }

        if (toks.isEmpty()) return if (verify) null else detected

        val keys = words.map { Mind.soundKey(it) }.filter { it.length >= 2 }.toSet()

        for (i in 0 until minOf(3, toks.size)) {
            if (Mind.soundKey(toks[i]) in keys) {
                return (detected + " " + toks.drop(i + 1).joinToString(" ")).trim()
            }
        }

        return if (verify) null else (detected + " " + toks.joinToString(" ")).trim()
    }

    /** Cuts the silence off the start of [pcm], keeping 0.3 s before the first sound. */
    fun trimLeading(pcm: ShortArray, rate: Int = 16000): ShortArray {

        val win = rate / 50                       // 20 ms
        if (pcm.size < win * 2) return pcm

        var i = 0

        while (i + win <= pcm.size) {

            var s = 0.0
            for (k in i until i + win) {
                val v = pcm[k].toDouble()
                s += v * v
            }

            if (Math.sqrt(s / win) > 300.0) {
                val start = maxOf(0, i - rate * 3 / 10)
                return pcm.copyOfRange(start, pcm.size)
            }

            i += win
        }

        return pcm
    }
}
