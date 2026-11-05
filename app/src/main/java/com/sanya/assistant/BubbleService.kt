package com.sanya.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class BubbleService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: OrbView
    private lateinit var caption: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var runner: StepRunner
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("sanya", MODE_PRIVATE) }
    private val muted = mutableListOf<Int>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null        // Google voice (backup)
    private var ears: Ears? = null                  // offline Vosk voice
    private var voskReady = false
    private var hearingMode = ""                   // "google" or "offline", as last applied

    // Microphone trouble: retry with a growing delay instead of switching hands-free off.
    private var earsFailures = 0
    private var lastEarsFail = 0L
    private val retryEars = Runnable { retryListening() }

    // Incoming calls: the phone-state watcher, and whether WE are the reason she is listening.
    private var phoneListener: PhoneStateListener? = null
    private var callAwait = false
    private val ringTask = Runnable { announceCall() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // Which ears are in charge. "setting | hearing = google" makes Google's recogniser
    // do all the listening (much better with Indian names and accents).
    private fun googleHearing() = Brain.setting("hearing", "offline") == "google"
    private fun offlineEars() = voskReady && !googleHearing()

    // Called after any command, and on Start: switch the ears if the setting changed.
    private fun applyHearing() {
        val now = if (googleHearing()) "google" else "offline"
        if (now == hearingMode) return
        hearingMode = now
        if (now == "google") {
            ears?.stop()
            releaseLock()
            if (handsFree) ui.postDelayed({ resume() }, 2500)    // after she says "Okay…"
        } else {
            sr?.destroy(); sr = null; listening = false; unmuteBeep()
            if (handsFree && voskReady) { takeLock(); ears?.paused = false; ears?.start() }
        }
    }
    private var loading = false
    private var wakeLock: PowerManager.WakeLock? = null

    // handsFree and awaiting are read by the Ears thread (wake mode), so they are @Volatile.
    @Volatile private var handsFree = false   // always listening for "Sanya" (long-press the bubble)
    @Volatile private var awaiting = false    // the next sentence is a command, no wake word needed
    private var listening = false   // Google voice is active
    private var pending = 0         // spoken sentences still queued
    private var size = 0
    private var lookColor = IDLE
    private var wakeRx = Regex("sanya")
    private val hideCaption = Runnable { caption.visibility = View.GONE }
    private val giveUp = Runnable {
        awaiting = false
        if (!handsFree) ears?.stop()
        look(if (handsFree) HANDS_FREE else IDLE, false)
    }

    companion object {
        const val IDLE = 0xFF6C5CE7.toInt()
        const val LISTENING = 0xFF00B894.toInt()
        const val HANDS_FREE = 0xFFE17055.toInt()

        // True while the service is running, so a pomodoro alarm knows it can ask her to speak.
        @Volatile var alive = false
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif() = Notification.Builder(this, "sanya")
        .setContentTitle("Sanya is ready")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()

    override fun onCreate() {
        ErrorLog.install(this)
        super.onCreate()
        alive = true
        Brain.setup(this)
        wakeRx = wake()
        hearingMode = if (googleHearing()) "google" else "offline"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sanya", "Sanya", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, notif())
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                ttsReady = true
                val r = tts?.setLanguage(Locale.forLanguageTag(Brain.setting("language", "en-IN")))
                if (r == null || r < 0) tts?.setLanguage(Locale.getDefault())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ui.post { pending = maxOf(0, pending - 1); settle(); resume() } }
                    override fun onError(id: String?) { ui.post { pending = maxOf(0, pending - 1); settle(); resume() } }
                })
            }
        }
        runner = StepRunner(this, { say(it) }, { show(it, 8000L) })
        ears = Ears(this,
            onPartial = { p -> ui.post { if (awaiting || wakeRx.containsMatchIn(p)) show("🎤 $p") } },
            onFinal = { t -> ui.post { earsFailures = 0; heard(listOf(t)) } },
            onProblem = { m ->
                ErrorLog.note(this, "Hearing", m)
                ui.post { earsProblem(m) }
            },
            wakeWords = { wakeWordList() },
            wakeOnly = { handsFree && !awaiting })
        Notices.listener = { n -> ui.post { announceMessage(n) } }
        showBubble()
        makeCaption()
        loadEars()
        watchCalls()
        Reminders.schedule(this)        // make sure the alarm for the next reminder is set
        Proactive.schedule(this)        // exact wake-ups for the next meeting, routine and brief
        registerReceiver(nudgeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_LOW)
        })
        ui.postDelayed(tick, 45_000L)
    }
    // She looks around when you unlock the phone and when the battery gets low,
    // instead of only polling every minute.
    private val nudgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val reason = if (i.action == Intent.ACTION_BATTERY_LOW) "battery" else "unlock"
            ui.postDelayed({
                try {
                    proactiveCheck(reason)
                } catch (e: Throwable) {
                    ErrorLog.add(this@BubbleService, "Proactive trigger", e)
                }
            }, if (reason == "unlock") 3000L else 500L)
        }
    }
    // ---------- Proactive Sanya: speaks first when something matters ----------
    private val tick = object : Runnable {
        override fun run() {
            // Backup for the reminder alarm: delivers anything due, then sets the next alarm.
            try { Reminders.deliver(this@BubbleService) } catch (e: Throwable) { ErrorLog.add(this@BubbleService, "Reminders", e) }
            try { proactiveCheck() } catch (e: Throwable) { ErrorLog.add(this@BubbleService, "Proactive check", e) }
            try { keepWidgetFresh() } catch (e: Throwable) { ErrorLog.add(this@BubbleService, "Widget refresh", e) }
            ui.postDelayed(this, 60_000L)
        }
    }

    // The home-screen widget ticks its own clock, but routines you mark by voice and
    // pomodoro phase changes need a redraw. Only while the screen is on, to save battery.
    private fun keepWidgetFresh() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) SanyaWidget.refresh(this)
    }

    private fun proactiveCheck(reason: String = "tick") {
        // Never interrupt her own talking or listening. The next check tries again.
        if (pending > 0 || listening || awaiting || runner.wantsReply() ||
            CommandRewriter.wantsReply() || Reminders.wantsReply()
        ) return

        // A pomodoro cue is something you asked for, so it is spoken even with the screen off
        // or music playing (you study with the phone down). It also logs finished focus sessions.
        if (!(inCall() || Calls.ringing)) {
            val cue = Pomodoro.due(this)
            if (cue != null) {
                say(cue)
                Pomodoro.notify(this, cue)
                SanyaWidget.refresh(this)
                return
            }
        }

        // Everything else is decided by Proactive: it speaks (returned here), writes a
        // notification itself, or waits for a better moment.
        val n = Proactive.check(this, reason, true) ?: return

        when {
            n.run.isNotEmpty() -> execute(n.run)
            n.offer.isNotEmpty() -> {
                runner.offer(n.text, n.offer)
                awaiting = true          // listen for yes / no, no wake word needed
            }
            n.text.isNotEmpty() -> say(n.text)
        }
    }
    private fun inCall() =
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    // Called again each time you tap Start in the app, so a freshly downloaded voice gets loaded
    // and a phone permission you just allowed starts working.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif())
        loadEars()
        applyHearing()
        applyLook()
        watchCalls()
        // A pomodoro or reminder alarm asks her to say the cue out loud. A reminder also opens a
        // short listening window, so you can answer "snooze" without the wake word.
        intent?.getStringExtra("say")?.let { text ->
            val listenAfter = intent.getBooleanExtra("listen", false)
            val offerCmd = intent.getStringExtra("offer") ?: ""
            ui.post {
                if (offerCmd.isNotEmpty()) {
                    runner.offer(text, offerCmd)
                    awaiting = true
                } else {
                    if (listenAfter) awaiting = true
                    say(text)
                }
            }
        }
        // A button on a nudge notification ("Yes") asks her to run a command.
        intent?.getStringExtra("run")?.let { cmd -> ui.post { execute(cmd) } }
        // The exact alarm woke the app while she is running: let her decide if now is a good moment.
        intent?.getStringExtra("proactive")?.let { why ->
            ui.post {
                try {
                    proactiveCheck(why)
                } catch (e: Throwable) {
                    ErrorLog.add(this, "Proactive wake", e)
                }
            }
        }
        return START_STICKY
    }

    private fun loadEars() {
        val e = ears ?: return
        if (voskReady || loading || !ModelStore.ready(this)) return
        loading = true
        Thread {
            val ok = e.load()
            ui.post {
                loading = false
                voskReady = ok
                if (ok) {
                    show("Offline voice ready")
                    if (prefs.getBoolean("handsfree", false)) setHandsFree(true, false)
                } else show("Offline voice failed to load")
            }
        }.start()
    }

    // ---------- Incoming calls ----------
    @Suppress("DEPRECATION")
    private fun watchCalls() {
        if (phoneListener != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val l = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    ui.post { callChanged(state, incomingNumber) }
                }
            }
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            phoneListener = l
        } catch (e: Throwable) {
            ErrorLog.add(this, "Call watcher", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun unwatchCalls() {
        val l = phoneListener ?: return
        try {
            (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(l, PhoneStateListener.LISTEN_NONE)
        } catch (e: Throwable) {
        }
        phoneListener = null
    }

    private fun callChanged(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!Calls.ringing) Calls.rangStart()
                if (!number.isNullOrBlank()) Calls.number = number
                // Android often reports the ring twice (the number comes with the second one).
                if (!Calls.announced) {
                    ui.removeCallbacks(ringTask)
                    ui.postDelayed(ringTask, 600L)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                ui.removeCallbacks(ringTask)
                if (Calls.ringing) Calls.rangEnd(true)
                endCallListening()
            }
            else -> {
                ui.removeCallbacks(ringTask)
                if (Calls.ringing){
                    Calls.rangEnd(false)
                    ui.postDelayed({ proactiveCheck()},4000L)  //missed it? offer to call back
                }
                endCallListening()
            }
        }
    }

    private fun endCallListening() {
        if (callAwait) {
            callAwait = false
            awaiting = false
        }
    }

    private fun announceCall() {
        if (!Calls.ringing || Calls.announced) return
        Calls.announced = true
        val text = Calls.ringText(this)                 // also remembers the caller for "who called"
        if (Brain.setting("announce calls", "on") == "off") return
        tts?.stop(); pending = 0                        // a call is urgent: interrupt what she was saying
        callAwait = true
        awaiting = true                                 // listen for answer / reject, no wake word
        say(text)
    }

    // New message notification, spoken only if you asked for it and she isn't busy.
    private fun announceMessage(n: Notices.Note) {
        if (Brain.setting("announce messages", "off") != "on") return
        if (!n.messaging) return
        if (pending > 0 || listening || awaiting || inCall() || Calls.ringing) return
        say(Notices.spokenOne(n))
    }

    // Answer / reject / who called / read my notifications. Null if it is not one of those.
    private fun phoneCommand(c: String): String? =
        try {
            Calls.handle(this, c) ?: Notices.handle(this, c)
        } catch (e: Throwable) {
            ErrorLog.add(this, "Phone command", e)
            null
        }

    private fun phoneUnderstands(c: String): Boolean =
        PhoneParse.call(c) != null || PhoneParse.notice(c) != null

    // Set a reminder / list / cancel / snooze, or the answer to "When should I remind you?".
    // Null if it is not about reminders.
    private fun reminderCommand(c: String): String? =
        try {
            Reminders.handle(this, c)
        } catch (e: Throwable) {
            ErrorLog.add(this, "Reminder command", e)
            null
        }

    // The microphone failed (a call, another app using it, ...). Hands-free is NOT switched off:
    // try again after a growing delay. Only a missing permission stops it.
    private fun earsProblem(m: String) {

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopEars("Microphone permission is missing. Open the Sanya app to allow it.")
            return
        }

        ears?.stop()
        ui.removeCallbacks(retryEars)

        // During a call the microphone belongs to the call: just wait.
        if (inCall()) {
            ui.postDelayed(retryEars, 8_000L)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastEarsFail > 120_000L) earsFailures = 0
        lastEarsFail = now
        earsFailures++

        val wait = if (earsFailures <= 5) 1_500L shl (earsFailures - 1) else 30_000L

        if (earsFailures == 1) show("Microphone problem. Retrying…")
        if (earsFailures == 6) say("I can't reach the microphone. I'll keep trying.")

        ui.postDelayed(retryEars, wait)
    }

    private fun retryListening() {
        if (!offlineEars() || !(handsFree || awaiting)) return
        if (inCall() || pending > 0) {
            ui.postDelayed(retryEars, 8_000L)
            return
        }
        ears?.paused = false
        ears?.start()
    }

    private fun stopEars(msg: String) {
        handsFree = false; awaiting = false
        prefs.edit().putBoolean("handsfree", false).apply()
        ears?.stop(); releaseLock()
        look(IDLE, false)
        say(msg)
    }

    // The wake words: yours from the brain file, plus the usual ways the phone mishears "Sanya".
    private fun wakeWordList(): List<String> =
        (Brain.setting("wake words", "sanya").split(",").map { it.trim().lowercase() } +
            listOf("sanya", "saniya", "sania", "sunya", "sonya", "sonia", "sanja", "suniya", "shanya"))
            .filter { it.isNotEmpty() }.distinct()

    private fun wake(): Regex {
        val w = wakeWordList()
        return Regex("^\\s*(?:(?:hey|hi|ok|okay|uh|um|so)\\s+)?(?:" + w.joinToString("|") { Regex.escape(it) } + ")\\b[ ,.]*")
    }

    private fun takeLock() {
        if (Brain.setting("screen off listening", "on") == "off") return
        val w = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sanya:ears").also { wakeLock = it }
        if (!w.isHeld) w.acquire()
    }

    private fun releaseLock() { wakeLock?.let { if (it.isHeld) it.release() } }

    // ---------- Bubble (the orb) ----------
    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        size = dp(80)
        bubble = OrbView(this).apply { contentDescription = "Sanya" }
        applyLook()
        lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            var moved = false; var longPressed = false
            val longPress = Runnable { longPressed = true; buzz(); setHandsFree(!handsFree) }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                        moved = false; longPressed = false
                        bubble.press(true)
                        ui.postDelayed(longPress, 700)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (abs(dx) > 12 || abs(dy) > 12) { moved = true; ui.removeCallbacks(longPress) }
                        if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(bubble, lp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longPress)
                        bubble.press(false)
                        if (moved) snap() else if (!longPressed) tap()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(longPress)
                        bubble.press(false)
                    }
                }
                return true
            }
        })
        wm.addView(bubble, lp)
    }

    private fun snap() {
        val w = resources.displayMetrics.widthPixels
        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
        wm.updateViewLayout(bubble, lp)
    }

    private fun buzz() { bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    // setting | bubble animation = full / active / off
    private fun applyLook() {
        if (::bubble.isInitialized) bubble.anim = Brain.setting("bubble animation", "active")
    }

    // The orb follows what she is doing: speaking beats everything, then listening, hands-free, idle.
    private fun look(color: Int, pulseOn: Boolean) {
        lookColor = color
        if (!::bubble.isInitialized) return
        bubble.mode = when {
            pending > 0 -> OrbView.Mode.SPEAKING
            color == LISTENING -> OrbView.Mode.LISTENING
            color == HANDS_FREE -> OrbView.Mode.HANDS_FREE
            else -> OrbView.Mode.IDLE
        }
    }

    // Back to the right look after she finishes talking.
    private fun settle() = look(lookColor, false)

    // ---------- Caption pill (top of screen) ----------
    private fun makeCaption() {
        caption = TextView(this).apply {
            textSize = 15f
            setTextColor(Ui.TEXT)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEE0E1426.toInt())
                setStroke(dp(1), 0x6618D2FF)
            }
            visibility = View.GONE
        }
        val cp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 140 }
        wm.addView(caption, cp)
    }

    private fun show(text: String, ms: Long = 3500L) {
        caption.text = text; caption.visibility = View.VISIBLE
        ui.removeCallbacks(hideCaption); ui.postDelayed(hideCaption, ms)
    }

    // ---------- Beep control (only needed for the Google voice backup) ----------
    private fun muteBeep() {
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
            if (st == AudioManager.STREAM_MUSIC && am.isMusicActive) continue   // never cut your video audio
            if (muted.contains(st) || am.isStreamMute(st)) continue
            runCatching {
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                if (am.isStreamMute(st)) muted.add(st)
            }
        }
    }

    private fun unmuteBeep() {
        for (st in muted) runCatching { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) }
        muted.clear()
    }

    // ---------- Speaking ----------
    private fun say(s: String) {
        show(s)
        unmuteBeep()
        sr?.let { if (listening) { it.cancel(); finish(it) } }   // never listen to my own voice
        val t = tts ?: return
        if (!ttsReady) return
        ears?.paused = true                                       // offline ears also pause while I talk
        pending++
        settle()                                                  // the orb shows she is speaking
        if (t.speak(s, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime()) == TextToSpeech.ERROR) {
            pending = maxOf(0, pending - 1); settle(); resume()
        }
    }

    // After she finishes talking, go back to listening if hands-free (or waiting for a command)
    private fun resume() {
        // she asked something (even later, from a step): listen for the answer
        if (runner.wantsReply() || CommandRewriter.wantsReply() || Reminders.wantsReply()) awaiting = true
        if (offlineEars()) {
            ui.postDelayed({
                if (pending > 0) return@postDelayed
                if (handsFree || awaiting) {
                    ears?.paused = false
                    ears?.start()
                    if (awaiting) {
                        look(LISTENING, true)
                        ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
                    }
                } else ears?.stop()
            }, 450)
            return
        }
        if ((handsFree || awaiting) && !listening && pending == 0) {
            ui.postDelayed({ if ((handsFree || awaiting) && !listening && pending == 0) listen() }, 500)
        }
    }

    // ---------- Listening ----------
    private fun tap() {
        buzz()
        if (pending > 0) { tts?.stop(); pending = 0; settle() }   // tap = stop talking
        awaiting = true                                  // a tap means: no wake word needed
        if (offlineEars() && Brain.setting("tap voice", "offline") != "google") {
            ears?.paused = false
            ears?.start()
            look(LISTENING, true)
            show("Listening…")
            ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
        } else {
            ears?.stop()                      // free the microphone for Google's recogniser
            if (listening) sr?.stopListening() else listen()
        }
    }

    // Google voice backup (online first, offline pack if there is no internet)
    private fun listen(offline: Boolean = false) {
        if (listening || pending > 0) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("No speech service on this phone"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        listening = true
        look(LISTENING, true)
        if (handsFree) muteBeep()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: Bundle?) {
                finish(r)
                val list = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (list.isNullOrEmpty()) resume() else heard(list)
            }
            override fun onError(code: Int) {
                finish(r)
                if (!offline && (code == 1 || code == 2 || code == 11)) {   // no internet: try the offline pack
                    show("Offline mode")
                    ui.post { listen(true) }
                    return
                }
                awaiting = false
                if (handsFree && (code == 6 || code == 7 || code == 8)) {   // silence or busy: keep waiting
                    ui.postDelayed({ resume() }, if (code == 8) 1500L else 300L); return
                }
                if (handsFree) handsFree = false                              // real error: stop looping
                look(IDLE, false)
                say(explain(code))
            }
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { show("🎤 $it") }
            }
            override fun onReadyForSpeech(p: Bundle?) { if (!handsFree) show(if (offline) "Listening (offline)…" else "Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Brain.setting("language", "en-IN"))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, Brain.hints())
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(i)
    }

    private fun finish(r: SpeechRecognizer) {
        listening = false
        unmuteBeep()
        look(if (handsFree) HANDS_FREE else IDLE, false)
        if (sr === r) sr = null
        ui.post { r.destroy() }
    }

    private fun explain(c: Int) = when (c) {
        1, 2 -> "No internet and no offline speech pack. Check your connection."
        3 -> "Microphone problem"
        6 -> "I didn't hear anything"
        7 -> "I didn't catch that"
        8 -> "Speech service is busy, try again"
        9 -> "Microphone permission is missing"
        12, 13 -> "Your language isn't supported for speech here"
        else -> "Speech error $c"
    }

    // ---------- Understanding ----------
    // Google gives up to 5 guesses, Vosk gives 1. Pick the one Sanya actually understands.
    private fun heard(all: List<String>) {
        val lows = all.map { it.lowercase() }
        val pool = if (handsFree && !awaiting) {
            lows.filter { wakeRx.containsMatchIn(it) }
        } else {
            lows
        }

        if (pool.isEmpty()) {
            if (!offlineEars()) resume()
            return
        }

        ears?.paused = true
        ui.removeCallbacks(giveUp)
        look(if (handsFree) HANDS_FREE else IDLE, false)

        // One guess (the offline voice): there is nothing to choose between, so skip the slow checks.
        // Several guesses (Google): of the ones she understands, prefer the one that sounds most like YOU.
        val pick = if (pool.size == 1) {
            pool[0]
        } else {
            val understood = pool.filter {
                val c = wakeRx.replace(it, "").trim()
                c.isNotEmpty() && (
                    runner.understands(c) ||
                        CommandRewriter.canInterpret(this, c) ||
                        phoneUnderstands(c) ||
                        Reminders.understands(this, c)
                    )
            }

            when {
                understood.size > 1 -> {
                    val stripped = understood.map { wakeRx.replace(it, "").trim() }
                    understood[
                        stripped.indexOf(Learner.choose(this, stripped)).coerceAtLeast(0)
                    ]
                }
                understood.size == 1 -> understood[0]
                else -> pool.first()
            }
        }

        show("“$pick”")
        val cmd = wakeRx.replace(pick, "").trim()
        if (cmd.isNotEmpty()) Proactive.noteReply(this, cmd)    // yes / no / ignored: she learns
        awaiting = false

        if (cmd.isEmpty()) {
            awaiting = true
            buzz()
            say("Yes?")
        } else {
            execute(cmd)
        }
    }

    private fun execute(c: String) {

        // Reminders first: "remind me tomorrow at 9 to pay rent", "snooze", "cancel my reminder ...",
        // and the answer to "When should I remind you?".
        val reminder = reminderCommand(c)
        if (reminder != null) {
            say(reminder)
            if (Reminders.wantsReply()) awaiting = true     // she asked: keep listening, no wake word
            ui.postDelayed({ resume() }, 2000L)
            return
        }

        // Answer / reject / who called / read my notifications come next.
        val phone = phoneCommand(c)
        if (phone != null) {
            endCallListening()
            if (phone.isNotEmpty()) say(phone) else show("📞 Done", 2000L)
            ui.postDelayed({ resume() }, 2000L)
            return
        }

        when {
            Regex("^(?:exit|quit|close|shut down)$").containsMatchIn(c) -> {
                prefs.edit().putBoolean("autostart", false).putBoolean("handsfree", false).apply()
                say("Bye"); ui.postDelayed({ stopSelf() }, 1500)
            }
            Regex("stop listening|hands.?free off").containsMatchIn(c) -> setHandsFree(false)
            Regex("hands.?free on|hands.?free mode|start listening").containsMatchIn(c) -> setHandsFree(true)
            else -> {
                when (val d = CommandRewriter.decide(this, runner, c)) {

                    is CommandRewriter.Decision.Run -> {
                        val n = runner.handle(d.command)
                        // she asked a question: keep listening, no wake word
                        if (runner.wantsReply() || CommandRewriter.wantsReply() || Reminders.wantsReply()) awaiting = true
                        wakeRx = wake()
                        applyHearing()
                        applyLook()
                        ui.postDelayed({ resume() }, n * 2500L + 1500L)
                    }

                    is CommandRewriter.Decision.Ask -> {
                        runner.offer(d.question, d.command)     // "Did you mean ...?" then yes / no
                        awaiting = true
                        ui.postDelayed({ resume() }, 3000L)
                    }

                    is CommandRewriter.Decision.Need -> {
                        awaiting = true                          // "Who should I call?" then the name
                        say(d.prompt)
                        ui.postDelayed({ resume() }, 3000L)
                    }

                    is CommandRewriter.Decision.Say -> {
                        say(d.text)
                        ui.postDelayed({ resume() }, 2000L)
                    }
                }
            }
        }
    }

    private fun setHandsFree(on: Boolean, announce: Boolean = true) {
        handsFree = on; awaiting = false
        prefs.edit().putBoolean("handsfree", on).apply()
        if (!on) { sr?.destroy(); sr = null; listening = false; unmuteBeep(); ears?.stop(); releaseLock() }
        look(if (on) HANDS_FREE else IDLE, false)
        if (on && offlineEars()) { takeLock(); ears?.paused = false; ears?.start() }
        if (announce) {
            say(if (on) {
                if (offlineEars()) "Hands free on. Just say Sanya, then your command."
                else if (googleHearing()) "Hands free on, using Google voice."
                else "Hands free on, using Google voice. For no beeps, download offline voice in the Sanya app."
            } else "Hands free off")
        } else show(if (on) "Hands free is on" else "Hands free off")
        if (on && !offlineEars()) resume()
    }

    override fun onDestroy() {
        alive = false
        Notices.listener = null
        unwatchCalls()
        try { unregisterReceiver(nudgeReceiver) } catch (e: Throwable) { }
        Learner.flush(this)
        ui.removeCallbacksAndMessages(null)
        unmuteBeep()
        releaseLock()
        if (::bubble.isInitialized) { bubble.animate().cancel(); wm.removeView(bubble) }
        if (::caption.isInitialized) wm.removeView(caption)
        sr?.destroy(); tts?.shutdown()
        val e = ears
        ears = null
        e?.stop()
        Thread { e?.close() }.start()
        super.onDestroy()
    }
}
