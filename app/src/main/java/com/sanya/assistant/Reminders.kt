package com.sanya.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reminders: "remind me tomorrow at 9 to pay rent".
 *
 *  - kept in files/reminders.txt (on the phone only), one line each:
 *        id <tab> time (ms) <tab> repeat <tab> time of day (minutes) <tab> text
 *    written to a temp file and renamed, so a kill in the middle cannot damage the list
 *  - ONE exact alarm is always set for the earliest reminder (AlarmManager), so the cue comes on
 *    time even if Sanya is not running. It posts a notification with sound and a Snooze button
 *    and speaks the reminder if she is running (and you are not on a call)
 *  - BubbleService's once-a-minute check is the backup if an alarm is delayed; whoever gets there
 *    first delivers it, the other finds nothing left
 *  - after a reboot or an app update the receiver fires anything that came due and sets the alarm again
 *
 * Settings (Brain file): reminder default time (09:00), snooze minutes (10),
 * reminder listen (on/off), reminder alarm (exact/clock).
 */
object Reminders {

    data class Reminder(
        val id: Long,
        val at: Long,
        val repeat: String,
        val tod: Int,
        val text: String
    )

    data class Fired(val reminder: Reminder, val spoken: String)

    const val ACTION_DUE = "com.sanya.assistant.REMINDER_DUE"
    const val ACTION_SNOOZE = "com.sanya.assistant.REMINDER_SNOOZE"

    private const val FILE = "reminders.txt"
    private const val PREFS = "sanya_reminders"
    private const val CHANNEL = "sanya_reminders"
    private const val ALARM_CODE = 11
    private const val MAX = 100
    private const val PENDING_MS = 20_000L
    private const val LATE_MS = 120_000L
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private var cache: MutableList<Reminder>? = null

    // ---------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------

    private fun toMs(t: LocalDateTime): Long =
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun toLdt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun defaultTod(): Int {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(Brain.setting("reminder default time", "09:00").trim())
            ?: return 540
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) 540 else h * 60 + mi
    }
    @Synchronized
    fun reload(ctx: Context) {
        cache = null
        schedule(ctx)
    }

    private fun snoozeMinutes(): Int =
        (Brain.setting("snooze minutes", "10").trim().toIntOrNull() ?: 10).coerceIn(1, 240)

    // ---------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun load(ctx: Context): MutableList<Reminder> {

        cache?.let { return it }

        val out = ArrayList<Reminder>()

        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readLines()) {
                    val p = line.split("\t", limit = 5)
                    if (p.size < 5) continue
                    val id = p[0].toLongOrNull() ?: continue
                    val at = p[1].toLongOrNull() ?: continue
                    val tod = p[3].toIntOrNull() ?: continue
                    out.add(Reminder(id, at, p[2], tod, p[4]))
                }
            }
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Reading reminders", e)
        }

        cache = out

        return out
    }

    private fun persist(ctx: Context, list: List<Reminder>) {

        val f = file(ctx)
        val text = list.joinToString("") { "${it.id}\t${it.at}\t${it.repeat}\t${it.tod}\t${it.text}\n" }
        val tmp = File(f.parentFile, f.name + ".tmp")

        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Saving reminders", e)
        }
    }

    /** Earliest first. */
    @Synchronized
    fun all(ctx: Context): List<Reminder> = load(ctx).sortedBy { it.at }

    @Synchronized
    fun add(ctx: Context, spec: ReminderParse.Spec, text: String): Reminder {

        val list = load(ctx)

        var id = System.currentTimeMillis()
        val top = list.maxOfOrNull { it.id } ?: 0L
        if (id <= top) id = top + 1

        val r = Reminder(
            id,
            toMs(spec.at),
            spec.repeat,
            spec.tod,
            text.replace('\t', ' ').replace('\n', ' ').trim().take(300)
        )

        list.add(r)
        persist(ctx, list)

        return r
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val list = load(ctx)
        if (list.removeAll { it.id == id }) persist(ctx, list)
    }

    @Synchronized
    fun clearAll(ctx: Context) {
        val list = load(ctx)
        list.clear()
        persist(ctx, list)
    }

    // ---------------------------------------------------------------
    // The alarm
    // ---------------------------------------------------------------

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ALARM_CODE,
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_DUE),
            FLAGS
        )

    /** Sets (or cancels) the one alarm for the earliest reminder. Safe to call any time. */
    fun schedule(ctx: Context) {

        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(ctx)
            val next = all(ctx).firstOrNull()

            if (next == null) {
                am.cancel(pi)
                return
            }

            val at = maxOf(next.at, System.currentTimeMillis() + 1000L)

            if (Brain.setting("reminder alarm", "exact") == "clock") {
                val show = PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java), FLAGS)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                return
            }

            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reminder alarm", e)
        }
    }

    // ---------------------------------------------------------------
    // Firing
    // ---------------------------------------------------------------

    private fun spokenFor(r: Reminder, nowMs: Long): String {

        val text = ReminderParse.flip(r.text)

        if (nowMs - r.at <= LATE_MS) return "Reminder: $text"

        val whenWas = ReminderParse.whenText(toLdt(r.at), "", toLdt(nowMs))

        return "Missed reminder from $whenWas: $text"
    }

    /**
     * Takes every reminder that is due out of the list (a repeating one moves to its next time)
     * and returns what to say. Nothing is returned twice.
     */
    @Synchronized
    fun fireDue(ctx: Context): List<Fired> {

        val list = load(ctx)
        val nowMs = System.currentTimeMillis()
        val due = list.filter { it.at <= nowMs + 500L }.sortedBy { it.at }

        if (due.isEmpty()) return emptyList()

        val out = ArrayList<Fired>()

        for (r in due) {

            list.remove(r)

            if (r.repeat.isNotEmpty()) {
                val next = ReminderParse.nextAfter(r.repeat, r.tod, toLdt(maxOf(nowMs, r.at)))
                if (next != null) list.add(r.copy(at = toMs(next)))
            }

            out.add(Fired(r, spokenFor(r, nowMs)))
        }

        persist(ctx, list)

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_text", due.last().text)
            .putLong("last_at", nowMs)
            .apply()

        return out
    }

    private fun nidOf(r: Reminder): Int = ((r.id % 1_000_000L).toInt()) + 100

    private fun notify(ctx: Context, f: Fired) {

        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)

            val ch = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)

            val nid = nidOf(f.reminder)
            val mins = snoozeMinutes()

            val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java), FLAGS)

            val snoozeIntent = Intent(ctx, ReminderReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .setData(Uri.parse("sanya://reminder/" + f.reminder.id))
                .putExtra("text", f.reminder.text)
                .putExtra("nid", nid)

            val snooze = PendingIntent.getBroadcast(ctx, 0, snoozeIntent, FLAGS)

            val action = Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_lock_idle_alarm),
                "Snooze $mins min",
                snooze
            ).build()

            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Reminder")
                .setContentText(f.spoken)
                .setStyle(Notification.BigTextStyle().bigText(f.spoken))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(action)
                .build()

            nm.notify(nid, n)

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reminder notification", e)
        }
    }

    // Spoken only if Sanya is running and you are not on a call.
    private fun speak(ctx: Context, text: String) {

        if (!BubbleService.alive) return

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return

            ctx.startService(
                Intent(ctx, BubbleService::class.java)
                    .putExtra("say", text)
                    .putExtra("listen", Brain.setting("reminder listen", "on") != "off")
            )
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reminder cue", e)
        }
    }

    /** Fires everything that is due (notification, and her voice if she is running), then sets the next alarm. */
    fun deliver(ctx: Context) {

        val fired = fireDue(ctx)

        for (f in fired) {
            notify(ctx, f)
            speak(ctx, f.spoken)
        }

        schedule(ctx)
    }

    /** The Snooze button on the notification. */
    fun snoozeFromNotification(ctx: Context, text: String, nid: Int) {

        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(nid)
        } catch (e: Throwable) {
        }

        if (text.isBlank()) return

        val dt = LocalDateTime.now().plusMinutes(snoozeMinutes().toLong()).withNano(0)

        add(ctx, ReminderParse.Spec(dt, "", dt.hour * 60 + dt.minute), text)
        schedule(ctx)
    }

    // ---------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------

    private sealed class Pend(val at: Long) {
        class Time(val text: String, t: Long) : Pend(t)
        class Text(val spec: ReminderParse.Spec, t: Long) : Pend(t)
        class Cancel(t: Long) : Pend(t)
        class ClearAll(t: Long) : Pend(t)
    }

    @Volatile private var pending: Pend? = null

    private val CANCEL_WORDS = Regex("""^(?:cancel|never mind|nevermind|forget it|stop|no|nope|nothing|leave it|skip)$""")
    private val YES = Regex("""^(?:yes|yeah|yep|yup|ya|ok|okay|sure|do it|go ahead|confirm)\b""")

    /** True while she has asked something (when? about what? which one?) and is waiting for your answer. */
    fun wantsReply(): Boolean {
        val p = pending ?: return false
        return System.currentTimeMillis() - p.at < PENDING_MS
    }

    /** Is [raw] a reminder command, or the answer to a question she asked? */
    fun understands(ctx: Context, raw: String): Boolean {
        if (wantsReply()) return true
        return ReminderParse.parse(raw, LocalDateTime.now(), defaultTod()) != null
    }

    /** Null if [raw] is not about reminders. Otherwise what she should say. */
    fun handle(ctx: Context, raw: String): String? {

        Brain.setup(ctx)

        val p = pending

        if (p != null) {
            pending = null
            if (System.currentTimeMillis() - p.at < PENDING_MS) {
                val r = answer(ctx, p, raw)
                if (r != null) return r
            }
        }

        val cmd = ReminderParse.parse(raw, LocalDateTime.now(), defaultTod()) ?: return null

        return execute(ctx, cmd)
    }

    private fun answer(ctx: Context, p: Pend, raw: String): String? {

        val t = ReminderParse.norm(raw)

        return when (p) {

            is Pend.ClearAll ->
                when {
                    YES.containsMatchIn(t) -> {
                        val n = all(ctx).size
                        clearAll(ctx)
                        schedule(ctx)
                        "Okay. I've deleted all $n reminders."
                    }
                    CANCEL_WORDS.matches(t) -> "Okay. I'll keep them."
                    else -> null
                }

            is Pend.Cancel ->
                if (CANCEL_WORDS.matches(t)) "Okay." else cancel(ctx, t)

            is Pend.Time ->
                if (CANCEL_WORDS.matches(t)) {
                    "Okay."
                } else {
                    val spec = ReminderParse.parseWhen(raw, LocalDateTime.now(), defaultTod()) ?: return null
                    if (p.text.isEmpty()) {
                        pending = Pend.Text(spec, System.currentTimeMillis())
                        "What should I remind you about?"
                    } else {
                        confirm(ctx, spec, p.text)
                    }
                }

            is Pend.Text ->
                if (CANCEL_WORDS.matches(t)) {
                    "Okay."
                } else {
                    val text = ReminderParse.cleanText(raw)
                    if (text.isEmpty()) null else confirm(ctx, p.spec, text)
                }
        }
    }

    private fun execute(ctx: Context, cmd: ReminderParse.Cmd): String =
        when (cmd) {

            is ReminderParse.Cmd.Add -> confirm(ctx, cmd.spec, cmd.text)

            is ReminderParse.Cmd.NeedTime -> {
                pending = Pend.Time(cmd.text, System.currentTimeMillis())
                cmd.prompt
            }

            is ReminderParse.Cmd.NeedText -> {
                pending = Pend.Text(cmd.spec, System.currentTimeMillis())
                "What should I remind you about?"
            }

            is ReminderParse.Cmd.Say -> cmd.text

            ReminderParse.Cmd.Show -> listText(ctx)

            ReminderParse.Cmd.Next -> nextText(ctx)

            is ReminderParse.Cmd.Cancel -> cancel(ctx, cmd.text)

            ReminderParse.Cmd.CancelAll -> {
                val n = all(ctx).size
                if (n == 0) {
                    "You have no reminders."
                } else {
                    pending = Pend.ClearAll(System.currentTimeMillis())
                    "Delete all $n reminders? Say yes or no."
                }
            }

            is ReminderParse.Cmd.Snooze -> snooze(ctx, cmd.minutes)
        }

    private fun confirm(ctx: Context, spec: ReminderParse.Spec, text: String): String {

        if (all(ctx).size >= MAX) return "You already have $MAX reminders. Cancel some first."

        val r = add(ctx, spec, text)
        schedule(ctx)

        return "Okay. I'll remind you " +
            ReminderParse.whenText(toLdt(r.at), r.repeat, LocalDateTime.now()) + ": " + r.text + "."
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun listText(ctx: Context): String {

        val l = all(ctx)

        if (l.isEmpty()) return "You have no reminders."

        val now = LocalDateTime.now()
        val shown = l.take(5)
        val more = l.size - shown.size

        val head = if (l.size == 1) "You have one reminder. " else "You have ${l.size} reminders. "

        return head + shown.joinToString(". ") {
            cap(ReminderParse.whenText(toLdt(it.at), it.repeat, now)) + ": " + it.text
        } + "." + (if (more > 0) " And $more more." else "")
    }

    private fun nextText(ctx: Context): String {

        val r = all(ctx).firstOrNull() ?: return "You have no reminders."

        return "Your next reminder is " +
            ReminderParse.whenText(toLdt(r.at), r.repeat, LocalDateTime.now()) + ": " + r.text + "."
    }

    private fun cancel(ctx: Context, text: String): String {

        val l = all(ctx)

        if (l.isEmpty()) return "You have no reminders."

        val q = Brain.clean(text)

        if (q.isEmpty()) {

            if (l.size == 1) {
                remove(ctx, l[0].id)
                schedule(ctx)
                return "Okay. I've cancelled your reminder: ${l[0].text}."
            }

            pending = Pend.Cancel(System.currentTimeMillis())

            return "Which one? You have ${l.size}. For example: " +
                l.take(3).joinToString(", ") { it.text } + ". Say a few words from it."
        }

        var hits = l.filter {
            val c = Brain.clean(it.text)
            c.isNotEmpty() && (c.contains(q) || q.contains(c))
        }

        if (hits.isEmpty()) {
            val best = l.maxByOrNull { Mind.similarity(q, Brain.clean(it.text)) }
            if (best != null && Mind.similarity(q, Brain.clean(best.text)) >= 0.5) hits = listOf(best)
        }

        return when {

            hits.isEmpty() -> "I couldn't find a reminder like that."

            hits.size == 1 -> {
                remove(ctx, hits[0].id)
                schedule(ctx)
                "Okay. I've cancelled your reminder: ${hits[0].text}."
            }

            else ->
                "I found ${hits.size} reminders like that: " + hits.take(3).joinToString(", ") { it.text } +
                    ". Say a few more words of the one you want."
        }
    }

    private fun snooze(ctx: Context, minutes: Int?): String {

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = p.getString("last_text", "") ?: ""
        val at = p.getLong("last_at", 0L)

        if (text.isEmpty() || System.currentTimeMillis() - at > 60 * 60_000L) {
            return "I haven't reminded you of anything recently."
        }

        val m = (minutes ?: snoozeMinutes()).coerceIn(1, 1440)
        val dt = LocalDateTime.now().plusMinutes(m.toLong()).withNano(0)

        add(ctx, ReminderParse.Spec(dt, "", dt.hour * 60 + dt.minute), text)
        schedule(ctx)

        return "Okay. I'll remind you again in ${Num.durText(m)}: $text."
    }

    /** One sentence for the morning brief, or "". */
    fun briefLine(ctx: Context): String {

        val now = LocalDateTime.now()

        val today = all(ctx).filter {
            val d = toLdt(it.at)
            d.toLocalDate() == now.toLocalDate() && d.isAfter(now)
        }

        if (today.isEmpty()) return ""

        val parts = today.take(3).map {
            val d = toLdt(it.at)
            it.text + " at " + ReminderParse.clock12(d.hour * 60 + d.minute)
        }

        return "Reminders today: " + parts.joinToString(", ") +
            (if (today.size > 3) ", and ${today.size - 3} more" else "") + "."
    }
}

/**
 * Fires when a reminder is due (an exact alarm), after a reboot or an app update (to deliver
 * anything that came due and set the alarm again), and for the Snooze button.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {

        Brain.setup(ctx)

        try {
            if (intent.action == Reminders.ACTION_SNOOZE) {
                Reminders.snoozeFromNotification(
                    ctx,
                    intent.getStringExtra("text") ?: "",
                    intent.getIntExtra("nid", -1)
                )
            } else {
                Reminders.deliver(ctx)
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reminder alarm", e)
        }

        Reminders.schedule(ctx)
    }
}
