package com.sanya.assistant

/**
 * GuardLock: the pure decision behind the urge-mode settings lock. No Android calls, so it is unit-tested.
 *
 * GuardService closes the pages where Sanya's Accessibility service can be switched off, the app removed, or
 * the Private DNS filter changed. By default that is ALL THE TIME, because switching the guard off in Android's
 * own settings is the easiest way round it, and nobody starts urge mode just before doing that. To get in you
 * say "unlock guard settings", wait 15 minutes, and say it again: the pages then open for 10 minutes. During
 * urge mode they stay closed whatever you do. It only closes a page when ALL of this holds:
 *
 *   - the lock is on and active (always, or urge mode), and no unlock window is open (you can turn the lock off,
 *     but that waits 15 minutes like every other loosening, and cannot happen during urge mode)
 *   - the window belongs to Settings, the Security app or the package installer
 *   - the page shows Sanya's name together with a word like accessibility, uninstall or force stop
 *
 * Every other Settings page (Wi-Fi, battery, sound ...) stays open, and so do the phone and emergency calls.
 *
 * What this is NOT: a lock you cannot get past. When urge mode ends everything opens again, and a restart in
 * safe mode or removing the app from a computer still works. It is a speed bump for the worst ten minutes.
 */
object GuardLock {

    /** Settings, the MIUI Security app (app info lives there) and the package installers (uninstall dialog). */
    val PAGES: Set<String> = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.appmanager",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    private val SANYA = Regex("""\bsanya\b""")

    private val KEYWORDS = listOf(
        "accessibility", "focus guard", "uninstall", "force stop", "app info",
        "clear data", "clear storage", "disable", "deactivate"
    )

    /** [texts] are the visible texts and descriptions on the page. */
    fun isGuardPage(pkg: String, texts: Collection<String>): Boolean {

        if (pkg !in PAGES) return false

        val joined = texts.joinToString(" ").lowercase()

        if (!SANYA.containsMatchIn(joined)) return false

        return KEYWORDS.any { joined.contains(it) }
    }

    fun shouldLock(urgeLeftMs: Long, lockOn: Boolean, pkg: String, texts: Collection<String>): Boolean =
        urgeLeftMs > 0L && lockOn && isGuardPage(pkg, texts)

    private val PRIVATE_DNS = Regex("""\bprivate dns\b""")

    /**
     * The Private DNS settings page itself (not the list that merely mentions it): it shows "Private DNS" and
     * the "provider hostname" option. Changing it would switch off the system-wide adult filter.
     */
    fun isDnsPage(pkg: String, texts: Collection<String>): Boolean {

        if (pkg != "com.android.settings") return false

        val joined = texts.joinToString(" ").lowercase()

        return PRIVATE_DNS.containsMatchIn(joined) && joined.contains("hostname")
    }

    fun isLockedPage(pkg: String, texts: Collection<String>): Boolean =
        isGuardPage(pkg, texts) || isDnsPage(pkg, texts)

    /**
     * Is the lock on right now?
     *   lockOn      the lock is switched on at all
     *   always      closed all the time (the default), not only in urge mode
     *   windowOpen  you asked to open Sanya's settings and waited: a 10 minute window
     * Urge mode closes the pages whatever the window says.
     */
    fun active(urgeLeftMs: Long, lockOn: Boolean, always: Boolean, windowOpen: Boolean): Boolean =
        lockOn && (urgeLeftMs > 0L || (always && !windowOpen))
}
