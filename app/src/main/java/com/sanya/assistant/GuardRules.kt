package com.sanya.assistant

/**
 * GuardRules: the pure decisions behind Focus Guard. No Android calls, so they are unit-tested.
 *
 *   goHome()   how hard to push you out of Shorts (Back first, Home if you keep coming back)
 *   check()    the friction lock: loosening a rule needs a 15 minute wait, then asking again
 *   ignored()  which windows are NOT "the app you are using" (keyboard, status bar, Sanya's own bubble)
 */
object GuardRules {

    // ---------------------------------------------------------------
    // Shorts: Back, Back, then Home
    // ---------------------------------------------------------------

    const val HOME_AFTER = 3

    /** [recentBlocks] = blocks inside the last few seconds, including this one. */
    fun goHome(recentBlocks: Int): Boolean = recentBlocks >= HOME_AFTER

    // ---------------------------------------------------------------
    // The friction lock
    // ---------------------------------------------------------------

    const val DELAY_MIN = 15L
    const val DELAY_MS = DELAY_MIN * 60_000L

    // A request that is ready stays usable for 3 hours, then it must be started again.
    const val EXPIRE_MS = 3L * 60L * 60_000L

    sealed class Verdict {
        /** First time asking (or the old request expired): note it and start the wait. */
        object Start : Verdict()

        /** Asked again too early. */
        data class Wait(val msLeft: Long) : Verdict()

        /** The wait is over: do it now. */
        object Apply : Verdict()
    }

    /**
     * [pendKey] / [pendAt] = the request already waiting ("" and 0 if none).
     * [key] = what is being asked for now, for example "master-off" or "raise:youtube:90".
     */
    fun check(pendKey: String, pendAt: Long, key: String, now: Long): Verdict {

        if (pendKey != key || pendAt <= 0L) return Verdict.Start

        val age = now - pendAt

        return when {
            age < 0L -> Verdict.Start
            age < DELAY_MS -> Verdict.Wait(DELAY_MS - age)
            age > EXPIRE_MS -> Verdict.Start
            else -> Verdict.Apply
        }
    }

    /** True if [now] minutes is a bigger limit than [old]: that loosens the rule. */
    fun raising(old: Int, now: Int): Boolean = now > old

    /** "9 minutes", "1 minute". Always rounds up, never says "0 minutes". */
    fun waitText(ms: Long): String {
        val m = ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return if (m == 1) "1 minute" else "$m minutes"
    }

    // ---------------------------------------------------------------
    // Which window is "the app you are using"
    // ---------------------------------------------------------------

    private val IGNORED = setOf(
        "android",
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    fun ignored(pkg: String, own: String, keyboards: Set<String>): Boolean =
        pkg.isEmpty() ||
            pkg == own ||
            pkg in IGNORED ||
            pkg in keyboards ||
            pkg.contains("inputmethod")
}
