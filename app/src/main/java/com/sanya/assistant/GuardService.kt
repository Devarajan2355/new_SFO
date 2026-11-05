package com.sanya.assistant

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * GuardService: the part of Focus Guard that watches the screen. Switched on by you, once, in
 * Settings > Accessibility > Sanya Focus Guard.
 *
 * What it looks at, and nothing more:
 *   - which app is in front (to count time and enforce limits)
 *   - while YouTube is in front, whether the Shorts player is showing (by view ids and the selected
 *     "Shorts" tab, never by reading text or video titles)
 * It stores no page content. The only things it keeps are minutes per app today and a count of blocks.
 *
 * What it does:
 *   - Shorts:  presses Back. If you come straight back three times in 12 seconds, presses Home.
 *   - Limits:  warns once at 80% (toast and voice), and at 100% sends you Home every time you open the app.
 *   - Words:   in a browser it reads only the address bar, the page title and search boxes. If one holds a
 *              blocked word it presses Back (Home if you keep coming back). Nothing it reads is stored.
 *   - Urge:    during urge mode, browsers, YouTube and your limited apps send you Home at once.
 *
 * Cheap on purpose: events from other apps are dropped at once, a Shorts check runs at most every 0.7 s,
 * the slower tab check at most every 2.5 s, and the tick that counts time runs every 15 s.
 *
 * Time is counted in milliseconds, so many short events never round to zero.
 */
class GuardService : AccessibilityService() {

    companion object {
        @Volatile var alive = false

        private const val TICK_MS = 15_000L
        private const val SCAN_GAP_MS = 700L
        private const val DEEP_GAP_MS = 2_500L
        private const val KICK_GAP_MS = 1_500L
        private const val ESCALATE_WINDOW_MS = 12_000L
        private const val MAX_ADD_MS = 180_000L
        private const val MAX_NODES = 500
        private const val WORD_GAP_MS = 1_500L
        private const val WORD_NODES = 350
        private const val URGE_TICK_MS = 4_000L
    }

    private val ui = Handler(Looper.getMainLooper())

    private var curPkg = ""
    private var curSince = 0L
    private var screenOn = true
    private var keyboards: Set<String> = emptySet()
    private var registered = false

    private var lastScan = 0L
    private var lastWordScan = 0L
    private var lastDeep = 0L
    private var lastKick = 0L
    private var lastFail = 0L
    private var lastDebug = ""
    private val hits = ArrayList<Long>()

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Throwable) {
                fail("Guard tick", e)
            }
            ui.postDelayed(this, if (Guard.urgeLeftMs(this@GuardService) > 0L) URGE_TICK_MS else TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                val now = System.currentTimeMillis()

                if (i.action == Intent.ACTION_SCREEN_OFF) {
                    flush(now)
                    screenOn = false
                    curSince = 0L
                    Guard.saveUsage(this@GuardService)
                } else if (i.action == Intent.ACTION_SCREEN_ON) {
                    screenOn = true
                    curSince = if (curPkg.isEmpty()) 0L else now
                }
            } catch (e: Throwable) {
                fail("Guard screen", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        ErrorLog.install(this)
        Brain.setup(this)

        alive = true
        GuardLog.connected(this, System.currentTimeMillis())
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

        keyboards = try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .inputMethodList.map { it.packageName }.toSet()
        } catch (e: Throwable) {
            emptySet()
        }

        if (!registered) {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_SCREEN_ON)
            registerReceiver(screenReceiver, f)
            registered = true
        }

        curSince = if (screenOn && curPkg.isNotEmpty()) System.currentTimeMillis() else 0L

        ui.removeCallbacks(tick)
        ui.postDelayed(tick, TICK_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {

        alive = false
        GuardLog.note(this, "svcoff")
        GuardCare.checkServiceLater(this)
        ui.removeCallbacksAndMessages(null)

        try {
            if (registered) unregisterReceiver(screenReceiver)
        } catch (e: Throwable) {
        }
        registered = false

        try {
            flush(System.currentTimeMillis())
            Guard.saveUsage(this)
        } catch (e: Throwable) {
        }

        super.onDestroy()
    }

    // A broken check must never flood the error list.
    private fun fail(where: String, e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastFail < 60_000L) return
        lastFail = now
        ErrorLog.add(this, where, e)
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
        }
    }

    private fun speak(text: String) {

        if (!BubbleService.alive) return

        try {
            startService(Intent(this, BubbleService::class.java).putExtra("say", text))
        } catch (e: Throwable) {
            fail("Guard voice", e)
        }
    }

    // ---------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        try {
            val pkg = event.packageName?.toString() ?: return

            if (!Guard.enabled(this)) return
            if (pkg in GuardLock.PAGES && lockSettings(pkg)) return
            if (GuardRules.ignored(pkg, packageName, keyboards)) return

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != curPkg) {
                switchTo(pkg)
            }

            if (pkg in Guard.SHORTS_APPS && Guard.shortsOn(this)) checkShorts(pkg)

            if (pkg in WordGuard.BROWSERS && Guard.wordsOn(this)) checkBrowser(pkg)

        } catch (e: Throwable) {
            fail("Guard event", e)
        }
    }

    // ---------------------------------------------------------------
    // Counting time
    // ---------------------------------------------------------------

    // Adds the time since the last flush to the app in front, if the screen is on and unlocked.
    private fun flush(now: Long) {

        if (curSince == 0L || curPkg.isEmpty()) return

        val locked = try {
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (e: Throwable) {
            false
        }

        if (screenOn && !locked) {
            val d = (now - curSince).coerceIn(0L, MAX_ADD_MS)
            if (d > 0L) Guard.addUsage(this, curPkg, d)
        }

        curSince = now
    }

    private fun switchTo(pkg: String) {

        val now = System.currentTimeMillis()

        flush(now)

        curPkg = pkg
        curSince = if (screenOn) now else 0L

        enforce(pkg)
    }

    // The window events can miss a change (for example after a call), so the tick looks again.
    private fun refreshForeground() {

        val root = rootInActiveWindow ?: return

        try {
            val p = root.packageName?.toString() ?: return

            if (p != curPkg && !GuardRules.ignored(p, packageName, keyboards)) switchTo(p)
        } finally {
            root.recycle()
        }
    }

    private fun onTick() {

        val now = System.currentTimeMillis()

        GuardLog.beat(this, now)
        GuardCare.checkDns(this, now)

        if (!Guard.enabled(this)) {
            curSince = 0L
            return
        }

        if (screenOn) refreshForeground()

        // Guard was just switched on, or the screen just came back: start counting again.
        if (curSince == 0L && screenOn && curPkg.isNotEmpty()) curSince = now

        flush(now)
        Guard.saveUsage(this)

        if (!screenOn || curPkg.isEmpty()) return

        enforce(curPkg)

        if (curPkg in Guard.SHORTS_APPS && Guard.shortsOn(this)) checkShorts(curPkg)

        if (curPkg in WordGuard.BROWSERS && Guard.wordsOn(this)) checkBrowser(curPkg)
    }

    // ---------------------------------------------------------------
    // Urge mode, then limits
    // ---------------------------------------------------------------

    private var lastLockScan = 0L
    private var lastLockCount = 0L

    // Sanya's Accessibility, app-info and uninstall pages, and the Private DNS page, are closed all the time
    // (and always during urge mode), unless you opened the 10 minute window. Only while Sanya's name or the
    // Private DNS provider option is on the page, so every other Settings page stays open.
    private fun lockSettings(pkg: String): Boolean {

        val left = Guard.urgeLeftMs(this)

        if (!GuardCare.lockActive(this, left)) return false

        val now = SystemClock.uptimeMillis()

        if (now - lastLockScan < 500L) return false
        lastLockScan = now

        val root = rootInActiveWindow ?: return false

        try {
            if (root.packageName?.toString() != pkg) return false

            val texts = ArrayList<String>()

            walk(root, 250) { n ->
                n.text?.let { texts.add(it.toString()) }
                n.contentDescription?.let { texts.add(it.toString()) }
            }

            // "guard debug = on" records what such a page says, so the words can be fixed for your phone.
            if (Guard.debug() && texts.any { it.contains("sanya", true) || it.contains("private dns", true) }) {
                ErrorLog.note(this, "Guard lock", pkg + ": " + texts.take(15).joinToString(" | "))
            }

            if (!GuardLock.isLockedPage(pkg, texts)) return false

        } finally {
            root.recycle()
        }

        performGlobalAction(GLOBAL_ACTION_HOME)
        Guard.noteBlock(this)

        // One attempt can send several events: count it once.
        if (now - lastLockCount > 3_000L) {
            lastLockCount = now
            GuardLog.note(this, "lockhit")
        }

        if (left > 0L) {
            val mins = ((left + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            toast("Urge mode: Sanya's settings are locked for " + Num.durText(mins) + ". This will pass.")
        } else {
            toast("Sanya's settings are locked. Say unlock guard settings, then wait 15 minutes.")
        }

        return true
    }
    // During urge mode the tempting apps send you straight Home. True if it did.
    private fun enforceUrge(pkg: String): Boolean {

        val left = Guard.urgeLeftMs(this)

        if (left <= 0L || !Guard.urgeBlocks(this, pkg)) return false

        val now = System.currentTimeMillis()
        if (now - lastKick < KICK_GAP_MS) return true
        lastKick = now

        val mins = ((left + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

        performGlobalAction(GLOBAL_ACTION_HOME)
        Guard.noteBlock(this)
        toast("Urge mode: " + Num.durText(mins) + " left. This will pass.")
        GuardLog.note(this, "urgekick")

        return true
    }

    private fun enforce(pkg: String) {

        if (enforceUrge(pkg)) return

        val lim = Guard.limitFor(this, pkg) ?: return

        val cap = lim.minutes * 60_000L
        val used = Guard.usedMs(this, pkg)
        val name = lim.label.replaceFirstChar { it.uppercase() }

        if (used >= cap) {

            val now = System.currentTimeMillis()
            if (now - lastKick < KICK_GAP_MS) return
            lastKick = now

            performGlobalAction(GLOBAL_ACTION_HOME)
            Guard.noteBlock(this)
            toast("$name: your limit of " + Num.durText(lim.minutes) + " is used up for today.")
            GuardLog.note(this, "limit")

        } else if (used * 5L >= cap * 4L && Guard.markWarned(this, pkg)) {

            val left = ((cap - used) / 60_000L).toInt().coerceAtLeast(1)
            val text = "$name: " + Num.durText(left) + " left today."

            toast(text)
            speak(text)
        }
    }

    // ---------------------------------------------------------------
    // Shorts
    // ---------------------------------------------------------------

    private fun checkShorts(pkg: String) {

        val now = SystemClock.uptimeMillis()

        if (now - lastScan < SCAN_GAP_MS) return
        lastScan = now

        val root = rootInActiveWindow ?: return

        try {
            if (root.packageName?.toString() != pkg) return

            var hit = false

            // 1. The fast check: ask Android for the Shorts player's view ids.
            for (id in Guard.shortsIds()) {
                val found = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                if (found != null && found.isNotEmpty()) hit = true
                found?.forEach { it.recycle() }
                if (hit) break
            }

            // 2. The slower check: is the "Shorts" tab at the bottom the selected one?
            //    (Also the place where "guard debug" records the ids it sees.)
            if (!hit && (now - lastDeep > DEEP_GAP_MS)) {
                lastDeep = now
                hit = deepScan(root)
            }

            if (hit) blockShorts()

        } finally {
            root.recycle()
        }
    }

    private fun deepScan(root: AccessibilityNodeInfo): Boolean {

        var tab = false
        val ids: LinkedHashSet<String>? = if (Guard.debug()) LinkedHashSet() else null

        walk(root, MAX_NODES) { n ->

            if (!tab && n.isSelected) {
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                if (t.equals("Shorts", true) || d.equals("Shorts", true)) tab = true
            }

            if (ids != null) {
                val id = n.viewIdResourceName
                if (id != null && (id.contains("reel", true) || id.contains("short", true))) {
                    ids.add(id.substringAfter(":id/"))
                }
            }
        }

        if (ids != null && ids.isNotEmpty()) {
            val s = ids.joinToString(", ")
            if (s != lastDebug) {
                lastDebug = s
                ErrorLog.note(this, "Guard IDs", s)
            }
        }

        return tab
    }

    // Visits up to [limit] nodes, breadth first, and recycles everything it fetched.
    private fun walk(root: AccessibilityNodeInfo, limit: Int, visit: (AccessibilityNodeInfo) -> Unit) {

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)

        var seen = 0

        while (queue.isNotEmpty()) {

            val n = queue.removeFirst()

            if (seen < limit) {
                seen++
                visit(n)
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c)
                }
            }

            if (n !== root) n.recycle()
        }
    }

    // ---------------------------------------------------------------
    // Browsers: address bar, page title and search boxes only
    // ---------------------------------------------------------------

    private fun checkBrowser(pkg: String) {

        val now = SystemClock.uptimeMillis()

        if (now - lastWordScan < WORD_GAP_MS) return
        lastWordScan = now

        val root = rootInActiveWindow ?: return

        try {
            if (root.packageName?.toString() != pkg) return

            val extra = Guard.userWords(this)
            var found: String? = null

            walk(root, WORD_NODES) { n ->

                if (found == null) {

                    val cls = n.className?.toString() ?: ""
                    val id = n.viewIdResourceName ?: ""
                    val title = cls.endsWith("WebView")

                    if (title || cls.endsWith("EditText") || WordGuard.isUrlId(id)) {

                        found = WordGuard.find(n.text?.toString() ?: "", extra)

                        // Chrome puts the page title on the web view as text or as a description.
                        if (found == null && title) {
                            found = WordGuard.find(n.contentDescription?.toString() ?: "", extra)
                        }
                    }
                }
            }

            if (found != null) kickBack("Blocked word")

        } finally {
            root.recycle()
        }
    }

    // ---------------------------------------------------------------
    // Pushing you out: Back first, Home if you keep coming back
    // ---------------------------------------------------------------

    private fun kickBack(what: String) {

        val now = System.currentTimeMillis()

        if (now - lastKick < KICK_GAP_MS) return
        lastKick = now

        hits.removeAll { now - it > ESCALATE_WINDOW_MS }
        hits.add(now)

        val home = GuardRules.goHome(hits.size)

        performGlobalAction(if (home) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)

        Guard.noteBlock(this)

        toast(if (home) "$what. Back to the home screen." else "$what.")
        GuardLog.note(this, if (what == "Blocked word") "words" else "shorts")
    }

    private fun blockShorts() = kickBack("Shorts blocked")
}
