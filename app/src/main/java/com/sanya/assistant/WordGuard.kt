package com.sanya.assistant

import java.util.Locale

/**
 * WordGuard: the pure part of the adult-word filter. No Android calls, so it is unit-tested.
 *
 * GuardService hands it a few short pieces of text from a browser: the address bar, the page title
 * and any search box. Nothing else on the page is read, and nothing it sees is ever stored.
 *
 * Two built-in lists (fixed in the code, so they cannot be edited away from the Brain file):
 *   SUBSTRINGS  distinctive names that are blocked wherever they appear ("pornhub.com" -> "porn")
 *   WORDS       short words that must stand alone ("sex" is blocked, "Essex" is not)
 *
 * Your own additions come from Guard (stored as "w:term" for a word or phrase, "s:term" for a site):
 *   w:  a whole word or phrase:      "dirty word" matches "my dirty word list", not "dirty laundry"
 *   s:  a site name, letters and digits only, at least 5 long:  "examplesite" matches "examplesite.com"
 *
 * The built-in lists are English. Add your own words for other languages (block the word ...).
 */
object WordGuard {

    val SUBSTRINGS: List<String> = listOf(
        "porn", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "hentai",
        "onlyfans", "brazzers", "chaturbate", "spankbang", "camgirl", "nsfw"
    )

    val WORDS: Set<String> = setOf(
        "xxx", "sex", "nude", "nudes", "naked", "milf", "erotic", "erotica", "topless", "bdsm"
    )

    /** Browsers (and the Google app's search box) that GuardService checks. */
    val BROWSERS: Set<String> = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.mi.globalbrowser",
        "com.android.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
        "com.UCMobile.intl", "com.google.android.googlequicksearchbox"
    )

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case words and numbers only: "https://www.Site.com/a-b" -> [https, www, site, com, a, b]. */
    fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(SPLIT).filter { it.isNotEmpty() }

    /** Letters and digits only, joined: "examplesite.com" -> "examplesitecom". */
    fun squash(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** True for the ids browsers give their address bar and search box ("url_bar", "omnibox" ...). */
    fun isUrlId(id: String): Boolean {
        val l = id.lowercase(Locale.ROOT)
        return l.contains("url") || l.contains("omnibox") || l.contains("search_box") ||
            l.contains("searchbox") || l.contains("search_edit")
    }

    /** Your own entries are stored as "w:term" or "s:term". Null if [raw] cannot be a safe entry. */
    fun entry(raw: String, site: Boolean): String? {

        val term =
            if (site) squash(raw) else tokens(raw).joinToString(" ")

        if (site && term.length < 5) return null
        if (!site && (term.length < 3 || term.length > 40)) return null
        if (term.length > 40) return null

        return (if (site) "s:" else "w:") + term
    }

    /** The word that matched, or null. [extra] = your own entries ("w:..." / "s:..."). */
    fun find(text: String, extra: List<String> = emptyList()): String? {

        if (text.isBlank()) return null

        val low = text.lowercase(Locale.ROOT)

        for (s in SUBSTRINGS) if (low.contains(s)) return s

        val toks = tokens(text)

        for (t in toks) if (t in WORDS) return t

        if (extra.isEmpty()) return null

        val sq = squash(text)
        val joined = " " + toks.joinToString(" ") + " "

        for (e in extra) {

            if (e.length < 3) continue

            val kind = e[0]
            val term = e.substring(2)

            if (term.isEmpty()) continue

            if (kind == 's') {
                if (term.length >= 5 && sq.contains(term)) return term
            } else if (term.contains(' ')) {
                if (joined.contains(" $term ")) return term
            } else if (term in toks) {
                return term
            }
        }

        return null
    }
}
