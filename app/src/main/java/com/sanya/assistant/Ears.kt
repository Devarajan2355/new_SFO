package com.sanya.assistant

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

// Offline voice model: downloaded once, stored inside the app.
object ModelStore {
    const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    // Trained on Indian English speakers (36 MB); usually better for Indian accents.
    const val INDIAN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    // The voice chosen in the brain file ("setting | voice model url = ...").
    fun currentUrl(): String = Brain.setting("voice model url", DEFAULT_URL)

    // Every voice lives in its own folder, so switching never deletes the other one
    // and never needs a second download.
    private fun idOf(url: String): String =
        url.substringAfterLast('/')
            .removeSuffix(".zip")
            .filter { it.isLetterOrDigit() || it == '-' || it == '.' }
            .ifEmpty { "custom" }

    private fun dirFor(c: Context, url: String) = File(c.filesDir, "voice-" + idOf(url))

    fun label(url: String): String = when (url) {
        DEFAULT_URL -> "US English"
        INDIAN_URL -> "Indian English"
        else -> idOf(url)
    }

    fun isInstalled(c: Context, url: String): Boolean {
        migrate(c)
        return File(dirFor(c, url), "am/final.mdl").exists()
    }

    fun dir(c: Context) = dirFor(c, currentUrl())
    fun ready(c: Context) = isInstalled(c, currentUrl())

    // Older versions kept a single model in "vosk-model". Move it into its own
    // folder once, so nobody has to download it again.
    private fun migrate(c: Context) {
        val old = File(c.filesDir, "vosk-model")
        if (!File(old, "am/final.mdl").exists()) return
        val marker = File(c.filesDir, "vosk-model.url")
        val url = try {
            if (marker.exists()) marker.readText().trim().ifEmpty { DEFAULT_URL } else DEFAULT_URL
        } catch (e: Exception) {
            DEFAULT_URL
        }
        val target = dirFor(c, url)
        if (File(target, "am/final.mdl").exists()) {
            old.deleteRecursively()
        } else {
            target.deleteRecursively()
            old.renameTo(target)
        }
        marker.delete()
    }

    // Returns null on success, or a short error message.
    fun download(c: Context, onProgress: (Int) -> Unit): String? {
        return try {
            val url = currentUrl()
            val tmp = File(c.cacheDir, "voice-model.zip")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            val dest = File(c.filesDir, "vosk-model-new")
            dest.deleteRecursively()
            dest.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val rel = e.name.substringAfter('/')
                    if (rel.isNotEmpty()) {
                        val f = File(dest, rel)
                        if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("bad zip")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                    e = z.nextEntry
                }
            }
            tmp.delete()
            if (!File(dest, "am/final.mdl").exists()) {
                dest.deleteRecursively()
                "model files missing after unzip (your other voice is untouched)"
            } else {
                val live = dirFor(c, url)
                live.deleteRecursively()
                if (dest.renameTo(live)) {
                    null
                } else {
                    "could not install the new voice"
                }
            }
        } catch (e: Exception) {
            e.message ?: "download failed"
        }
    }
}

// A rolling buffer of the most recent audio (used to replay the wake-word utterance).
private class Pcm(private val cap: Int) {

    private val data = ShortArray(cap)
    var size = 0
        private set

    fun add(b: ShortArray, n: Int) {
        val len = minOf(n, cap)
        if (size + len > cap) {
            val drop = size + len - cap
            System.arraycopy(data, drop, data, 0, size - drop)
            size -= drop
        }
        System.arraycopy(b, 0, data, size, len)
        size += len
    }

    fun clear() { size = 0 }

    fun tail(k: Int): ShortArray = data.copyOfRange(maxOf(0, size - k), size)

    fun keepTail(k: Int) {
        if (size > k) {
            System.arraycopy(data, size - k, data, 0, k)
            size = k
        }
    }

    fun all(): ShortArray = data.copyOf(size)
}

/**
 * Microphone loop: feeds Vosk and reports every finished sentence. No beeps, no internet.
 *
 * Two ways of listening:
 *   - normal (full recogniser all the time): used after a tap, while she waits for your answer,
 *     and whenever "wake mode" is not "grammar"
 *   - wake mode (setting | wake mode = grammar, and wakeOnly() is true): a grammar recogniser that
 *     knows only the wake words listens; when it hears one, the utterance is replayed through the
 *     full recogniser, checked for a wake-like word, and reported through onFinal.
 *
 * Other settings: "wake gate" (on/off) skips silence, "wake verify" (on/off) is the full-transcript check.
 */
class Ears(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val wakeWords: () -> List<String> = { listOf("sanya") },
    private val wakeOnly: () -> Boolean = { false }
) {
    @Volatile var paused = false          // true while Sanya is speaking, so she doesn't hear herself
    @Volatile private var running = false
    @Volatile private var gen = 0
    private var model: Model? = null
    private var thread: Thread? = null

    private companion object {
        const val RATE = 16000
        const val BUFFER_CAP = RATE * 12          // 12 s kept for the replay
        const val PREROLL = 2048 * 3              // audio fed to the grammar when the gate opens
        const val HANG_BLOCKS = 12                // blocks (about 1.5 s) kept open after sound
        const val IDLE_KEEP = RATE * 2
        const val IDLE_TRIM_AT = RATE * 3
    }

    fun load(): Boolean {
        if (model != null) return true
        if (!ModelStore.ready(ctx)) return false
        return try { model = Model(ModelStore.dir(ctx).absolutePath); true } catch (e: Throwable) { false }
    }

    private fun closeQuiet(r: Recognizer?) {
        try { (r as? java.lang.AutoCloseable)?.close() } catch (e: Throwable) {}
    }

    private fun makeWakeRecognizer(m: Model, words: List<String>): Recognizer? {
        if (words.isEmpty()) return null
        return try {
            Recognizer(m, RATE.toFloat(), WakeWord.grammar(words))
        } catch (e: Throwable) {
            ErrorLog.note(ctx, "Wake grammar", "Could not start: " + (e.message ?: e.javaClass.simpleName) + ". Using full listening.")
            null
        }
    }

    private fun level(b: ShortArray, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) {
            val v = b[i].toDouble()
            s += v * v
        }
        return sqrt(s / maxOf(n, 1))
    }

    // Runs recorded audio through the full recogniser and returns what it heard.
    private fun transcribe(full: Recognizer, pcm: ShortArray): String {

        full.reset()

        val sb = StringBuilder()
        var i = 0

        while (i < pcm.size) {
            val len = minOf(4096, pcm.size - i)
            val chunk = pcm.copyOfRange(i, i + len)
            if (full.acceptWaveForm(chunk, len)) {
                sb.append(' ').append(JSONObject(full.result).optString("text"))
            }
            i += len
        }

        sb.append(' ').append(JSONObject(full.finalResult).optString("text"))

        full.reset()

        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun start() {
        if (running) return
        val m = model ?: return
        try { if (thread?.isAlive == true) thread?.join(400) } catch (e: Throwable) {}
        val my = ++gen
        running = true
        thread = Thread {
            var rec: AudioRecord? = null
            var full: Recognizer? = null
            var wakeRec: Recognizer? = null
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 4)
                rec = a
                if (a.state != AudioRecord.STATE_INITIALIZED) { onProblem("Microphone is busy or not allowed"); return@Thread }
                val f = Recognizer(m, RATE.toFloat())
                full = f
                a.startRecording()
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val buf = ShortArray(2048)
                val utt = Pcm(BUFFER_CAP)

                var idleBefore = true
                var saver = false
                var gramOn = false
                var gateOn = true
                var verify = true
                var gramKey = ""
                var words: List<String> = emptyList()
                var checkAt = 0L
                var lastWake = false
                var floor = 100.0
                var hang = 0

                while (running && my == gen) {
                    val n = a.read(buf, 0, buf.size)
                    if (n < 0) { onProblem("Microphone error $n"); break }
                    if (n == 0) continue
                    val now = System.currentTimeMillis()

                    // Settings are checked every few seconds, not on every block.
                    if (now > checkAt) {
                        saver = Brain.setting("screen off listening", "on") == "off"
                        gramOn = Brain.setting("wake mode", "grammar") == "grammar"
                        gateOn = Brain.setting("wake gate", "on") != "off"
                        verify = Brain.setting("wake verify", "on") != "off"

                        if (gramOn) {
                            val w = WakeWord.cleanWords(wakeWords())
                            val key = w.joinToString(",")
                            if (key != gramKey) {
                                closeQuiet(wakeRec)
                                wakeRec = makeWakeRecognizer(m, w)
                                gramKey = key
                                words = w
                            }
                        } else if (wakeRec != null || gramKey.isNotEmpty()) {
                            closeQuiet(wakeRec)
                            wakeRec = null
                            gramKey = ""
                        }

                        checkAt = now + 3000
                    }

                    val idle = paused || (saver && !pm.isInteractive)
                    if (idle) { idleBefore = true; continue }

                    val wake = wakeOnly() && wakeRec != null

                    // Starting, or switching between the two ways of listening: start clean.
                    if (idleBefore || wake != lastWake) {
                        f.reset()
                        wakeRec?.reset()
                        utt.clear()
                        hang = 0
                        idleBefore = false
                        lastWake = wake
                    }

                    // ---------- normal listening: the full recogniser ----------
                    if (!wake) {
                        if (f.acceptWaveForm(buf, n)) {
                            val t = JSONObject(f.result).optString("text")
                            if (t.isNotBlank()) onFinal(t)
                        } else {
                            val p = JSONObject(f.partialResult).optString("partial")
                            if (p.isNotEmpty()) onPartial(p)
                        }
                        continue
                    }

                    // ---------- wake mode: only the wake words ----------
                    val w = wakeRec ?: continue

                    val lv = level(buf, n)
                    val thr = maxOf(250.0, floor * 2.5)
                    val loud = lv > thr
                    if (!loud) floor = minOf(600.0, floor * 0.98 + lv * 0.02)

                    // The gate opens: give the grammar the moment just before the sound.
                    if (gateOn && loud && hang <= 0) {
                        val pre = utt.tail(PREROLL)
                        if (pre.isNotEmpty()) w.acceptWaveForm(pre, pre.size)
                    }

                    utt.add(buf, n)

                    if (gateOn) {
                        if (loud) hang = HANG_BLOCKS
                        if (hang <= 0) {
                            if (utt.size > IDLE_TRIM_AT) utt.keepTail(IDLE_KEEP)
                            continue                    // silence: nothing to feed
                        }
                        hang--
                    }

                    if (w.acceptWaveForm(buf, n)) {

                        val heard = JSONObject(w.result).optString("text")
                        val detected = WakeWord.detect(heard, words)

                        if (detected != null) {
                            onPartial(detected)

                            val text = transcribe(f, WakeWord.trimLeading(utt.all(), RATE))
                            val command = WakeWord.rebuild(text, detected, words, verify)

                            if (command != null) {
                                onFinal(command)
                            } else {
                                ErrorLog.note(ctx, "Wake word", "Heard a wake word but the full transcript did not confirm it: '$text'")
                            }
                        }

                        utt.clear()
                        hang = 0
                    }
                }
            } catch (e: Throwable) {
                onProblem("Ears stopped: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { rec?.stop() } catch (e: Throwable) {}
                try { rec?.release() } catch (e: Throwable) {}
                closeQuiet(wakeRec)
                closeQuiet(full)
                if (my == gen) running = false
            }
        }.also { it.start() }
    }

    fun stop() { running = false; gen++ }

    fun close() {
        stop()
        try { thread?.join(1500) } catch (e: Throwable) {}
        try { (model as? java.lang.AutoCloseable)?.close() } catch (e: Throwable) {}
        model = null
    }
}

// Direct Wi-Fi / Bluetooth switching. Works on Android 10 because Sanya targets API 28.
// Returns false if the phone refuses, and then the settings page opens instead.
fun directToggle(ctx: Context, s: Step.Toggle, say: (String) -> Unit): Boolean = try {
    when {
        s.what.contains("bluetooth") -> {
            val b = BluetoothAdapter.getDefaultAdapter()
            val ok = b != null && (if (s.on) b.enable() else b.disable())
            if (ok) say("Bluetooth ${if (s.on) "on" else "off"}")
            ok
        }
        s.what.contains("wi") -> {
            val w = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ok = w.setWifiEnabled(s.on)
            if (ok) say("Wi-Fi ${if (s.on) "on" else "off"}")
            ok
        }
        else -> false
    }
} catch (e: Throwable) { false }

// Brings Sanya back after the phone restarts (Xiaomi also needs Autostart switched on).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val on = c.getSharedPreferences("sanya", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (i.action == Intent.ACTION_BOOT_COMPLETED && on && Settings.canDrawOverlays(c)) {
            try { c.startForegroundService(Intent(c, BubbleService::class.java)) } catch (e: Throwable) {}
        }
    }
}
