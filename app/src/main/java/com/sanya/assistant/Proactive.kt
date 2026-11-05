package com.sanya.assistant

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.sanya.assistant.ProactiveRules.BatKind
import com.sanya.assistant.ProactiveRules.Cand
import com.sanya.assistant.ProactiveRules.Delivery
import com.sanya.assistant.ProactiveRules.Reply
import com.sanya.assistant.ProactiveRules.Stat
import com.sanya.assistant.ProactiveRules.Type
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Proactive: Sanya speaking first, the way a good assistant would. 100% on-device, rule based.
 * The decisions live in ProactiveRules.kt (unit-tested); this file gathers the facts and acts.
 *
 * WHAT SHE NOTICES
 *   battery (smarter with a meeting coming)   meeting heads-up   routine at its time + evening check
 *   study break   morning brief   missed call offer   Focus Guard watchdog       (HARD: always arrive)
 *   birthdays and dates   tasks due   budget pace   a free gap before a meeting
 *   an alarm for tomorrow's early meeting   a habit   all routines done          (SOFT: only when polite)
 *
 * HOW SHE DELIVERS
 *   spoken      screen on and unlocked, no call, no music, not Do Not Disturb, ringer on, not in a meeting
 *   notification  hard nudges when she cannot speak. With offers it has Yes / Not now / Stop these buttons
 *   wait        soft nudges that cannot speak right now try again at your next unlock
 *
 * WHEN SHE LOOKS (not once a minute any more)
 *   you unlock the phone   battery low   an exact alarm for the next meeting / routine / brief
 *   a call ends   and a cheap check every minute from BubbleService. The calendar and call log are
 *   only read at most every 5 minutes unless one of the events above woke her.
 *
 * HOW SHE LEARNS
 *   Every offer is answered yes, no or ignored. Yes lifts that kind. No and silence add strikes, and
 *   enough strikes make that kind rest for 3 / 7 / 14 days. "Stop that" switches a kind off for good,
 *   "why did you say that" explains it, "that was helpful" lifts it.
 *
 * Settings (Brain file, or by voice):
 *   setting | proactive = on / off            setting | nudge battery = off   (one per kind: battery, meeting,
 *   setting | quiet hours = 22:00-07:00                routine, study, brief, missed, guard, dates, tasks,
 *   setting | proactive limit = 4                      budget, gap, bedtime, habit, celebrate)
 *   setting | battery warn = 15              setting | tasks time = 10:00
 *   setting | bedtime nudge = 21:00 / off    setting | bedtime lead = 90   (minutes before the first event)
 *   setting | event heads up = 15 / off      setting | morning brief = 07:30 / off
 */
object Proactive {

    /**
     * text  = just say it
     * offer = say text, then do this command if the answer is yes
     * run   = run this command now (like the morning brief)
     * type and key say which kind it was and which occasion (used for learning).
     */
    data class Nudge(
        val text: String = "",
        val offer: String = "",
        val run: String = "",
        val type: String = "",
        val key: String = ""
    )

    const val ACTION_ALARM = "com.sanya.assistant.PROACTIVE_ALARM"
    const val ACTION_ACCEPT = "com.sanya.assistant.NUDGE_ACCEPT"
    const val ACTION_DECLINE = "com.sanya.assistant.NUDGE_DECLINE"
    const val ACTION_MUTE = "com.sanya.assistant.NUDGE_MUTE"

    private const val PREFS = "sanya_proactive"
    private const val CHANNEL = "sanya_nudges"
    private const val NOTIFY_BASE = 1_000_200      // above every reminder notification id
    private const val ALARM_CODE = 21
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private const val PENDING_REPLY_MS = 25_000L
    private const val RECENT_MS = 3 * 60_000L
    private const val EXPENSIVE_GAP_MS = 5 * 60_000L

    // Never suggest these on her own: people, money/secrets, or anything destructive.
    private val NO_SUGGEST = Regex(
        """\b(?:call|message|text|sms|whatsapp|send|pin|password|otp|secret|delete|clear|remove|forget)\b"""
    )

    private val YES = Regex(
        """^(?:yes|yeah|yep|yup|ya|ok|okay|sure|correct|right|do it|please do|go ahead|confirm)\b"""
    )
    private val NO = Regex(
        """^(?:no|nope|nah|never mind|nevermind|cancel|forget it|skip|stop|dont|do not)\b"""
    )

    // Today's calendar, kept for a few minutes so she does not ask the phone every minute.
    @Volatile private var todayEvents: List<ProactiveRules.Slot> = emptyList()
    @Volatile private var eventsAt = 0L
    @Volatile private var eventsDay = -1L
    @Volatile private var lastExpensive = 0L

    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(type: Type): Boolean = Brain.setting("nudge " + type.key, "on") != "off"

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun who(): String =
        Brain.setting("name", "").let {
            if (it.isEmpty()) "" else " " + it.replaceFirstChar { c -> c.uppercase() }
        }

    // ---------------------------------------------------------------
    // The one entry point
    // ---------------------------------------------------------------

    /**
     * Look around and decide. Returns what BubbleService should SAY, or null. Anything that has to be
     * written instead (a notification) is posted here. [reason] is tick, unlock, battery, call or alarm.
     * [serviceAlive] is false when called from an alarm while BubbleService is not running.
     */
    @Synchronized
    fun check(ctx: Context, reason: String, serviceAlive: Boolean): Nudge? =
        try {
            evaluate(ctx, reason, serviceAlive)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Proactive", e)
            null
        }

    /** The old name: BubbleService's minute check. */
    fun next(ctx: Context): Nudge? = check(ctx, "tick", true)

    private fun evaluate(ctx: Context, reason: String, serviceAlive: Boolean): Nudge? {

        Brain.setup(ctx)

        if (Brain.setting("proactive", "on") == "off") return null

        val pr = p(ctx)
        val now = System.currentTimeMillis()
        val minutes = nowMinutes()
        val today = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

        if (pr.getInt("day", 0) != today) {
            pr.edit().putInt("day", today).putInt("count", 0).apply()
        }

        settleIgnored(ctx, now)

        // The calendar and call log are only asked at most every 5 minutes, unless something woke her.
        val expensive = reason != "tick" || now - lastExpensive >= EXPENSIVE_GAP_MS

        if (expensive) {
            lastExpensive = now
            refreshEvents(ctx, now)
        }

        val snoozed = now < pr.getLong("snooze", 0L)
        val quiet = ProactiveRules.inQuiet(minutes, Brain.setting("quiet hours", "22:00-07:00"))
        val presence = presence(ctx, serviceAlive, ProactiveRules.inMeeting(todayEvents, now))

        val hardWay = ProactiveRules.delivery(true, false, presence, snoozed)
        val voiceWay = ProactiveRules.delivery(true, true, presence, snoozed)
        val softWay = ProactiveRules.delivery(false, false, presence, snoozed)

        // ---------------------------------------------------------
        // HARD nudges: they always arrive (spoken, or as a notification)
        // ---------------------------------------------------------

        // 1. Battery
        val bat = battery(ctx)
        val level = bat?.first ?: -1
        val next = ProactiveRules.nextTimed(todayEvents, now)
        val soon = next != null && next.begin - now <= 3 * ProactiveRules.HOUR_MS
        val minsToEvent = if (next == null) 0 else ((next.begin - now) / ProactiveRules.MINUTE_MS).toInt()
        val warnAt = (Brain.setting("battery warn", "15").trim().toIntOrNull() ?: 15).coerceIn(5, 50)

        val kind =
            if (bat == null) BatKind.NONE
            else ProactiveRules.batteryKind(level, bat.second, quiet, warnAt, soon)

        if (kind == BatKind.CRITICAL && enabled(Type.BATTERY) &&
            now - pr.getLong("bat_crit", 0L) > ProactiveRules.HOUR_MS
        ) {
            pr.edit().putLong("bat_crit", now).putLong("bat", now).apply()
            return deliver(
                ctx, Type.BATTERY,
                Nudge(text = ProactiveRules.batteryText(kind, level, null, 0)),
                hardWay, "Your battery is almost empty and the phone is not charging.", now
            )
        }

        // 2. Meeting heads-up (it is on your calendar, so it ignores quiet hours and the daily limit)
        if (expensive && enabled(Type.MEETING)) {
            val head = Agenda.headsUp(ctx, now)
            if (head != null) {
                return deliver(ctx, Type.MEETING, Nudge(text = head), hardWay,
                    "A calendar event is about to start.", now)
            }
        }

        // 3. Routines: an activity's time has come, or the evening check
        if (enabled(Type.ROUTINE)) {

            val due = Routines.reminder(ctx, minutes)

            if (due != null) {
                Routines.markReminded(ctx, due)
                return deliver(ctx, Type.ROUTINE, Nudge(text = "Time for ${due.task.name}."), hardWay,
                    "A routine activity of yours is due now.", now)
            }

            if (pr.getInt("routine", 0) != today) {
                val evening = Routines.eveningDue(ctx, minutes)
                if (evening != null) {
                    pr.edit().putInt("routine", today).apply()
                    return deliver(ctx, Type.ROUTINE, Nudge(text = evening), hardWay,
                        "It is time for your evening routine check.", now)
                }
            }
        }

        // 4. Study break: you started the timer yourself. Once per timer.
        if (enabled(Type.STUDY)) {
            val br = Study.breakDue(ctx)
            if (br != null) {
                return deliver(ctx, Type.STUDY, Nudge(text = br), hardWay,
                    "Your study timer has been running a long time.", now)
            }
        }

        // 5. Morning brief: only worth saying aloud, so it waits for you to pick up the phone
        val briefAt = briefMinutes()

        if (enabled(Type.BRIEF) && briefAt != null && pr.getInt("brief", 0) != today &&
            minutes >= briefAt && minutes < briefAt + 90 && voiceWay == Delivery.SPEAK
        ) {
            pr.edit().putInt("brief", today).apply()
            return deliver(ctx, Type.BRIEF, Nudge(run = "good morning"), Delivery.SPEAK,
                "You asked for a morning brief at this time.", now)
        }

        // 6. A missed call you have not returned
        if (expensive && !quiet && enabled(Type.MISSED)) {
            val miss = CallLogs.missNudge(ctx, now)
            if (miss != null) {
                val text = if (hardWay == Delivery.SPEAK) miss else miss.replace(" Shall I call back?", "")
                return deliver(ctx, Type.MISSED, Nudge(text = text), hardWay,
                    "You missed a call and have not returned it.", now)
            }
        }

        // 7. Focus Guard watchdog
        if (!quiet && enabled(Type.GUARD)) {
            val wd = Guard.watchdog(ctx, now) ?: GuardCare.reportNudge(ctx, now)
            if (wd != null) {
                return deliver(ctx, Type.GUARD, Nudge(text = wd.first, offer = wd.second), hardWay,
                    "Focus Guard has been switched off.", now)
            }
        }

        // 8. Low battery (a meeting coming raises the warning level to 20)
        if (kind == BatKind.LOW && enabled(Type.BATTERY) && now - pr.getLong("bat", 0L) > 2 * ProactiveRules.HOUR_MS) {
            pr.edit().putLong("bat", now).apply()
            return deliver(
                ctx, Type.BATTERY,
                Nudge(text = ProactiveRules.batteryText(kind, level, next?.title, minsToEvent)),
                hardWay, "Your battery is low and the phone is not charging.", now
            )
        }

        // ---------------------------------------------------------
        // SOFT nudges: only when it is polite, one at a time, the best one
        // ---------------------------------------------------------

        if (softWay != Delivery.SPEAK || !expensive) return null

        val led = ledger(ctx)
        val count = pr.getInt("count", 0)
        val lastAny = pr.getLong("last", 0L)
        val lastSoft = pr.getLong("soft", 0L)
        val limit = (Brain.setting("proactive limit", "4").trim().toIntOrNull() ?: 4).coerceIn(1, 20)
        val done = doneToday(pr, "soft", today)
        val cands = ArrayList<Cand>()

        fun ok(t: Type): Boolean =
            enabled(t) && ProactiveRules.softOk(t, led[t.key] ?: Stat(), now, quiet, count, limit, lastAny, lastSoft)

        // Birthdays and other dates (today and tomorrow)
        if (ok(Type.DATES)) {
            for ((label, days) in Mind.dueDates()) {
                val id = "date:$label"
                if (id in done) continue
                cands.add(Cand(Type.DATES, id, Mind.dateSentence(label, days),
                    urgency = if (days == 0) 20 else 8,
                    why = "Someone's special date is today or tomorrow."))
                break
            }
        }

        // Tasks due today or overdue, once, from the time you choose
        if (ok(Type.TASKS) && "tasks" !in done && !Lists.wantsReply()) {
            val at = ProactiveRules.parseClock(Brain.setting("tasks time", "10:00")) ?: 600
            if (minutes >= at && minutes < at + 8 * 60) {
                val t = Lists.handle(ctx, "what is due today")
                if (!t.isNullOrBlank() && !t.startsWith("Nothing")) {
                    cands.add(Cand(Type.TASKS, "tasks", t,
                        urgency = if (t.contains("Overdue")) 15 else 5,
                        why = "You have tasks due today or overdue."))
                }
            }
        }

        // Spending faster than the month is passing
        if (ok(Type.BUDGET)) {
            val budget = Money.budgetPaise()
            if (budget > 0L) {
                val td = LocalDate.now()
                val spent = Money.entries(ctx, td.withDayOfMonth(1), td).filter { it.spent }.sumOf { it.paise }
                val pace = ProactiveRules.budgetPace(spent, budget, td.dayOfMonth, td.lengthOfMonth())
                if (pace.level > 0) {
                    cands.add(Cand(Type.BUDGET, "budget:${td.monthValue}:${pace.level}",
                        ProactiveRules.budgetText(pace),
                        urgency = if (pace.level >= 2) 20 else 5,
                        why = "You are spending faster than the month is passing."))
                }
            }
        }

        // A free stretch before a meeting, and something left to do
        if (ok(Type.GAP) && minutes in (8 * 60) until (21 * 60) &&
            !Pomodoro.running(ctx) && Study.running(ctx) == null
        ) {
            val gap = ProactiveRules.freeGap(todayEvents, now)
            if (gap != null) {
                val left = Routines.today(ctx).remaining
                if (left.isNotEmpty()) {
                    val task = left.firstOrNull { it.task.spec.target > 0 }?.let { Brain.clean(it.task.name) }
                    cands.add(Cand(Type.GAP, "gap:" + gap.first.begin / ProactiveRules.MINUTE_MS,
                        ProactiveRules.gapText(gap.second, gap.first.title, task),
                        offer = ProactiveRules.gapOffer(task),
                        urgency = minOf(gap.second, 120) / 12,
                        why = "You have a free stretch before your next event and something left to do."))
                }
            }
        }

        // Tomorrow starts early: offer an alarm (allowed in the evening even inside quiet hours)
        if (ok(Type.BEDTIME) && Agenda.allowed(ctx)) {
            val raw = Brain.setting("bedtime nudge", "21:00").trim()
            if (raw != "off") {
                val start = ProactiveRules.parseClock(raw) ?: 1260
                if (minutes >= start && minutes < start + 90) {
                    val tomorrow = LocalDate.now().plusDays(1)
                    val lead = (Brain.setting("bedtime lead", "90").trim().toIntOrNull() ?: 90).coerceIn(15, 240)
                    val evs = Agenda.eventsOn(ctx, tomorrow)
                        .map { ProactiveRules.Slot(it.begin, it.end, it.allDay, it.title) }
                    val bed = ProactiveRules.bedtime(evs, ZoneId.systemDefault(), lead)
                    if (bed != null) {
                        cands.add(Cand(Type.BEDTIME, "bed:$tomorrow", bed.text,
                            offer = "alarm at " + bed.alarm, urgency = 10,
                            why = "Tomorrow starts early and you have not set an alarm with me."))
                    }
                }
            }
        }

        // A habit: said on different days around this time
        if (ok(Type.HABIT)) {
            val lines = try {
                File(ctx.filesDir, "mind.log").readLines()
            } catch (e: Exception) {
                emptyList<String>()
            }
            val h = ProactiveRules.habit(ProactiveRules.parseHistory(lines), now, ZoneId.systemDefault(), NO_SUGGEST)
            if (h != null && "habit:${h.text}" !in done && !Mind.didRecently(ctx, h.text, 4 * ProactiveRules.HOUR_MS)) {
                cands.add(Cand(Type.HABIT, "habit:${h.text}",
                    "Around now you usually say ${h.text}. Shall I do that?",
                    offer = h.text, urgency = minOf(h.days, 10),
                    why = "You have said that around this time on ${h.days} different days."))
            }
        }

        // Everything done
        if (ok(Type.CELEBRATE) && "celebrate" !in done) {
            val t = Routines.today(ctx)
            val total = t.finished.size + t.remaining.size
            if (ProactiveRules.celebrate(total, t.remaining.size, minutes)) {
                cands.add(Cand(Type.CELEBRATE, "celebrate", ProactiveRules.celebrateText(total, who()),
                    why = "You finished all your routine activities today."))
            }
        }

        val best = ProactiveRules.pick(cands, led) ?: return null

        pr.edit().putInt("count", count + 1).putLong("soft", now).apply()
        markDone(pr, "soft", today, done + best.id)

        return deliver(
            ctx, best.type,
            Nudge(text = best.text, offer = best.offer, run = best.run, key = best.id),
            Delivery.SPEAK, best.why, now
        )
    }

    // ---------------------------------------------------------------
    // Delivering
    // ---------------------------------------------------------------

    // Records it, then either hands it back to be spoken or posts it as a notification.
    private fun deliver(ctx: Context, type: Type, n: Nudge, way: Delivery, why: String, now: Long): Nudge? {

        val full = n.copy(type = type.key, key = if (n.key.isEmpty()) type.key + ":" + now else n.key)
        val pr = p(ctx)

        markShown(ctx, type, now)

        pr.edit()
            .putLong("last", now)
            .putString("lastn", type.key + "|" + now + "|" + why.replace('\n', ' '))
            .apply()

        return when (way) {

            Delivery.SPEAK -> {
                if (full.offer.isNotEmpty()) {
                    // So the next thing you say can be read as yes, no or something else.
                    pr.edit().putString("pend", type.key + "|" + now + "|" + full.key + "|" + full.offer).apply()
                }
                full
            }

            Delivery.NOTIFY -> {
                notify(ctx, full)
                null
            }

            else -> null
        }
    }

    private fun presence(ctx: Context, serviceAlive: Boolean, inMeeting: Boolean): ProactiveRules.Presence {

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val locked = try {
            km.isKeyguardLocked
        } catch (e: Throwable) {
            false
        }

        val dnd = try {
            val f = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            f == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                f == NotificationManager.INTERRUPTION_FILTER_NONE ||
                f == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } catch (e: Throwable) {
            false
        }

        val inCall = am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            Calls.ringing

        return ProactiveRules.Presence(
            serviceAlive,
            pm.isInteractive,
            locked,
            am.isMusicActive,
            inCall,
            dnd,
            am.ringerMode == AudioManager.RINGER_MODE_NORMAL,
            inMeeting
        )
    }

    private fun refreshEvents(ctx: Context, now: Long) {

        todayEvents =
            if (Agenda.allowed(ctx)) {
                try {
                    Agenda.eventsOn(ctx, LocalDate.now())
                        .map { ProactiveRules.Slot(it.begin, it.end, it.allDay, it.title) }
                } catch (e: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        eventsAt = now
        eventsDay = LocalDate.now().toEpochDay()
    }

    private fun briefMinutes(): Int? {
        val raw = Brain.setting("morning brief", "off").trim()
        return ProactiveRules.parseClock(raw)
    }

    /** (percent, isCharging) or null if the phone does not say. */
    private fun battery(ctx: Context): Pair<Int, Boolean>? {

        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null

        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        if (level < 0 || scale <= 0) return null

        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(level * 100 / scale, charging)
    }

    // ---------------------------------------------------------------
    // Notifications (when she cannot speak)
    // ---------------------------------------------------------------

    private fun action(ctx: Context, act: String, n: Nudge, label: String): Notification.Action {

        val i = Intent(ctx, ProactiveReceiver::class.java)
            .setAction(act)
            .setData(Uri.parse("sanya://nudge/" + act + "/" + Uri.encode(n.key)))
            .putExtra("type", n.type)
            .putExtra("key", n.key)
            .putExtra("cmd", if (n.offer.isNotEmpty()) n.offer else n.run)

        val pi = PendingIntent.getBroadcast(ctx, 0, i, FLAGS)

        return Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_lock_idle_alarm),
            label,
            pi
        ).build()
    }

    private fun notify(ctx: Context, n: Nudge) {

        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)

            val ch = NotificationChannel(CHANNEL, "Sanya nudges", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)

            val type = Type.byKey(n.type)
            val nid = NOTIFY_BASE + (type?.ordinal ?: 0)
            val open = PendingIntent.getActivity(ctx, 22, Intent(ctx, MainActivity::class.java), FLAGS)

            // On a locked screen she shows only that there is a message, not what it says.
            val publicView = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sanya")
                .setContentText("You have a message from Sanya")
                .build()

            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sanya")
                .setContentText(n.text)
                .setStyle(Notification.BigTextStyle().bigText(n.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicView)

            if (n.offer.isNotEmpty() || n.run.isNotEmpty()) {
                b.addAction(action(ctx, ACTION_ACCEPT, n, "Yes"))
                b.addAction(action(ctx, ACTION_DECLINE, n, "Not now"))
            }

            b.addAction(action(ctx, ACTION_MUTE, n, "Stop these"))

            nm.notify(nid, b.build())

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Nudge notification", e)
        }
    }

    /** Runs a command through BubbleService (starting it if it is not running). */
    fun runCommand(ctx: Context, cmd: String) {

        try {
            val i = Intent(ctx, BubbleService::class.java).putExtra("run", cmd)

            if (BubbleService.alive) ctx.startService(i) else ctx.startForegroundService(i)

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Nudge action", e)
        }
    }

    /** The Yes / Not now / Stop these buttons. */
    @Synchronized
    fun fromNotification(ctx: Context, intent: Intent) {

        Brain.setup(ctx)

        val type = Type.byKey(intent.getStringExtra("type") ?: "")
        val cmd = intent.getStringExtra("cmd") ?: ""
        val now = System.currentTimeMillis()

        if (type != null) {
            try {
                ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_BASE + type.ordinal)
            } catch (e: Throwable) {
            }
        }

        when (intent.action) {

            ACTION_ACCEPT -> {
                if (type != null) record(ctx, type, Reply.ACCEPT, now)
                if (cmd.isNotEmpty()) runCommand(ctx, cmd)
            }

            ACTION_DECLINE -> {
                if (type != null) record(ctx, type, Reply.DECLINE, now)
            }

            ACTION_MUTE -> {
                if (type != null) {
                    Brain.put("setting", "nudge " + type.key, "off")
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Learning
    // ---------------------------------------------------------------

    private fun ledger(ctx: Context): LinkedHashMap<String, Stat> =
        LinkedHashMap(ProactiveRules.decodeLedger(p(ctx).getString("ledger", "") ?: ""))

    private fun saveLedger(ctx: Context, m: Map<String, Stat>) {
        p(ctx).edit().putString("ledger", ProactiveRules.encodeLedger(m)).apply()
    }

    private fun markShown(ctx: Context, type: Type, now: Long) {
        val m = ledger(ctx)
        val s = m[type.key] ?: Stat()
        m[type.key] = s.copy(shown = s.shown + 1, lastShown = now)
        saveLedger(ctx, m)
    }

    private fun record(ctx: Context, type: Type, reply: Reply, now: Long) {
        val m = ledger(ctx)
        m[type.key] = ProactiveRules.applyReply(m[type.key] ?: Stat(), reply, now, !type.hard)
        saveLedger(ctx, m)
    }

    private fun resetType(ctx: Context, type: Type) {
        val m = ledger(ctx)
        val s = m[type.key] ?: return
        m[type.key] = s.copy(strikes = 0, mutedUntil = 0L)
        saveLedger(ctx, m)
    }

    // An offer nobody answered for 25 seconds counts as ignored.
    private fun settleIgnored(ctx: Context, now: Long) {

        val pr = p(ctx)
        val raw = pr.getString("pend", "") ?: ""

        if (raw.isEmpty()) return

        val parts = raw.split("|", limit = 4)
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L

        if (now - at < PENDING_REPLY_MS) return

        pr.edit().remove("pend").apply()

        val type = Type.byKey(parts[0]) ?: return

        record(ctx, type, Reply.IGNORE, now)
    }

    /**
     * Called by BubbleService with every command you speak. If she had just made an offer, this is
     * your answer: yes is a yes, no is a no, anything else means you ignored it.
     */
    @Synchronized
    fun noteReply(ctx: Context, raw: String) {

        try {
            val pr = p(ctx)
            val pend = pr.getString("pend", "") ?: ""

            if (pend.isEmpty()) return

            val parts = pend.split("|", limit = 4)

            pr.edit().remove("pend").apply()

            val type = Type.byKey(parts[0]) ?: return
            val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            val t = Brain.clean(raw)

            val reply = when {
                now - at > PENDING_REPLY_MS -> Reply.IGNORE
                YES.containsMatchIn(t) -> Reply.ACCEPT
                NO.containsMatchIn(t) -> Reply.DECLINE
                else -> Reply.IGNORE
            }

            record(ctx, type, reply, now)

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Nudge reply", e)
        }
    }

    // What she said in the last 3 minutes: (kind, when, why).
    private fun recent(ctx: Context): Triple<Type, Long, String>? {

        val raw = p(ctx).getString("lastn", "") ?: ""
        val parts = raw.split("|", limit = 3)

        if (parts.size < 3) return null

        val type = Type.byKey(parts[0]) ?: return null
        val at = parts[1].toLongOrNull() ?: return null

        if (System.currentTimeMillis() - at > RECENT_MS) return null

        return Triple(type, at, parts[2])
    }

    private fun doneToday(pr: SharedPreferences, key: String, today: Int): List<String> {
        val raw = pr.getString(key, "") ?: ""
        return if (raw.startsWith("$today|")) {
            raw.substringAfter('|').split(";").filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }

    private fun markDone(pr: SharedPreferences, key: String, today: Int, items: List<String>) {
        pr.edit().putString(key, "$today|" + items.joinToString(";")).apply()
    }

    // ---------------------------------------------------------------
    // Voice control (reached through Personal)
    // ---------------------------------------------------------------

    /** Would [raw] be handled here? "Stop that" and friends only count just after she said something. */
    fun understands(ctx: Context, raw: String): Boolean {

        val cmd = ProactiveParse.parse(raw) ?: return false

        return when (cmd) {
            ProactiveParse.Cmd.Stop, ProactiveParse.Cmd.Helpful, ProactiveParse.Cmd.Why -> recent(ctx) != null
            else -> true
        }
    }

    /** Null if [raw] is not about her proactive side. Otherwise what she should say. */
    @Synchronized
    fun handle(ctx: Context, raw: String): String? {

        val cmd = ProactiveParse.parse(raw) ?: return null

        Brain.setup(ctx)

        return try {
            control(ctx, cmd)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Proactive command", e)
            "I couldn't change that just now."
        }
    }

    private fun control(ctx: Context, cmd: ProactiveParse.Cmd): String? {

        val pr = p(ctx)
        val now = System.currentTimeMillis()

        return when (cmd) {

            ProactiveParse.Cmd.Status -> status(ctx)

            is ProactiveParse.Cmd.Toggle -> {
                Brain.put("setting", "nudge " + cmd.type.key, if (cmd.on) "on" else "off")
                resetType(ctx, cmd.type)
                if (cmd.on) {
                    "Okay. ${cmd.type.label.replaceFirstChar { it.uppercase() }} are on."
                } else {
                    "Okay. ${cmd.type.label.replaceFirstChar { it.uppercase() }} are off. " +
                        "Say turn on ${cmd.type.label} to bring them back."
                }
            }

            is ProactiveParse.Cmd.Master -> {
                Brain.put("setting", "proactive", if (cmd.on) "on" else "off")
                if (cmd.on) pr.edit().remove("snooze").apply()
                schedule(ctx)
                if (cmd.on) {
                    "Okay. I'll speak up when something matters."
                } else {
                    "Okay. I won't speak first anymore. Say proactive on to bring it back."
                }
            }

            is ProactiveParse.Cmd.Quiet -> {
                val mins =
                    if (cmd.untilMorning) {
                        ProactiveRules.minutesUntil(
                            nowMinutes(),
                            ProactiveRules.quietEnd(Brain.setting("quiet hours", "22:00-07:00"))
                        )
                    } else {
                        cmd.minutes
                    }
                pr.edit().putLong("snooze", now + mins * ProactiveRules.MINUTE_MS).apply()
                "Okay. Quiet for " + Num.durText(mins) + ". Urgent things will still arrive as notifications."
            }

            ProactiveParse.Cmd.QuietOff -> {
                pr.edit().remove("snooze").apply()
                "Okay. I'll speak up again when something matters."
            }

            is ProactiveParse.Cmd.BatteryAt -> {
                Brain.put("setting", "battery warn", cmd.percent.toString())
                "Okay. I'll warn you about the battery at ${cmd.percent} percent."
            }

            ProactiveParse.Cmd.Stop -> {
                val r = recent(ctx)
                if (r == null) {
                    null
                } else if (r.first == Type.BRIEF) {
                    "Say stop morning brief to switch that off."
                } else {
                    Brain.put("setting", "nudge " + r.first.key, "off")
                    "Okay. I'll stop ${r.first.label}. Say turn on ${r.first.label} to bring them back."
                }
            }

            ProactiveParse.Cmd.Helpful -> {
                val r = recent(ctx)
                if (r == null) {
                    null
                } else {
                    record(ctx, r.first, Reply.ACCEPT, now)
                    "Glad it helped. I'll do more of that."
                }
            }

            ProactiveParse.Cmd.Why -> {
                val r = recent(ctx)
                if (r == null) null else "Because " + r.third.replaceFirstChar { it.lowercase() }
            }

            is ProactiveParse.Cmd.Say -> cmd.text
        }
    }

    fun status(ctx: Context): String {

        Brain.setup(ctx)

        if (Brain.setting("proactive", "on") == "off") return "Proactive is off. Say proactive on to bring it back."

        val now = System.currentTimeMillis()
        val led = ledger(ctx)
        val all = Type.values().toList()
        val off = all.filter { !enabled(it) }
        val resting = all.filter { enabled(it) && ProactiveRules.isMuted(led[it.key] ?: Stat(), now) }
        val parts = ArrayList<String>()

        parts.add("Proactive is on. ${all.size - off.size} of ${all.size} kinds are active.")

        if (off.isNotEmpty()) parts.add("Off: " + off.joinToString(", ") { it.label } + ".")

        if (resting.isNotEmpty()) {
            parts.add("Resting because you ignored them: " + resting.joinToString(", ") { it.label } + ".")
        }

        val snooze = p(ctx).getLong("snooze", 0L)

        if (snooze > now) {
            val mins = ((snooze - now + 59_999L) / ProactiveRules.MINUTE_MS).toInt().coerceAtLeast(1)
            parts.add("Quiet mode: " + Num.durText(mins) + " left.")
        }

        return parts.joinToString(" ")
    }

    // ---------------------------------------------------------------
    // Waking on time (exact alarm), so she does not have to poll
    // ---------------------------------------------------------------

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ALARM_CODE,
            Intent(ctx, ProactiveReceiver::class.java).setAction(ACTION_ALARM),
            FLAGS
        )

    /** Sets (or cancels) the one alarm for the next thing that matters. Safe to call any time. */
    fun schedule(ctx: Context) {

        try {
            Brain.setup(ctx)

            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(ctx)

            if (Brain.setting("proactive", "on") == "off") {
                am.cancel(pi)
                return
            }

            val now = System.currentTimeMillis()

            if (now - eventsAt > EXPENSIVE_GAP_MS || eventsDay != LocalDate.now().toEpochDay()) {
                refreshEvents(ctx, now)
            }

            // At night a wake every hour is plenty; by day every 15 minutes at most.
            val quiet = ProactiveRules.inQuiet(nowMinutes(), Brain.setting("quiet hours", "22:00-07:00"))
            val gap = if (quiet) 60 * ProactiveRules.MINUTE_MS else 15 * ProactiveRules.MINUTE_MS
            val at = ProactiveRules.nextWake(now, wakeTimes(ctx), gap)

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
            ErrorLog.add(ctx, "Proactive alarm", e)
        }
    }

    // Every moment she may need to speak: heads-ups, routine times, brief, evening check, bedtime, tasks, study break.
    private fun wakeTimes(ctx: Context): List<Long> {

        val out = ArrayList<Long>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val days = listOf(today, today.plusDays(1))

        fun at(d: LocalDate, min: Int): Long =
            d.atStartOfDay(zone).plusMinutes(min.toLong()).toInstant().toEpochMilli()

        val raw = Brain.setting("event heads up", "15").trim()
        val lead = if (raw == "off") 0 else (raw.toIntOrNull() ?: 15).coerceIn(0, 120)

        if (lead > 0) {
            for (e in todayEvents) if (!e.allDay) out.add(e.begin - lead * ProactiveRules.MINUTE_MS)
        }

        val brief = briefMinutes()
        val check = Routines.checkMinutes()
        val bedRaw = Brain.setting("bedtime nudge", "21:00").trim()
        val bed = if (bedRaw == "off") null else ProactiveRules.parseClock(bedRaw) ?: 1260
        val tasksAt = ProactiveRules.parseClock(Brain.setting("tasks time", "10:00")) ?: 600
        val routines = Routines.tasks()

        for (d in days) {

            if (brief != null) out.add(at(d, brief))
            if (check != null) out.add(at(d, check))
            if (bed != null) out.add(at(d, bed))

            out.add(at(d, tasksAt))

            for (t in routines) {
                if (t.spec.minutes >= 0 && Routines.activeOn(t, d).isNotEmpty()) out.add(at(d, t.spec.minutes))
            }
        }

        val timer = Study.running(ctx)

        if (timer != null) {
            val limit = Brain.setting("study break", "50").trim().toIntOrNull() ?: 50
            if (limit > 0) out.add(timer.second + limit * ProactiveRules.MINUTE_MS)
        }

        return out
    }

    /** The exact alarm went off. */
    fun alarmFired(ctx: Context) {

        if (BubbleService.alive) {
            try {
                // Let the running service decide: it knows if she is busy talking or listening.
                ctx.startService(Intent(ctx, BubbleService::class.java).putExtra("proactive", "alarm"))
                return
            } catch (e: Throwable) {
                ErrorLog.add(ctx, "Proactive wake", e)
            }
        }

        check(ctx, "alarm", false)
    }
}

/**
 * Fires for the exact wake-up alarm, for the Yes / Not now / Stop these buttons on a nudge, and after a
 * reboot or an app update (to set the alarm again).
 */
class ProactiveReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {

        Brain.setup(ctx)

        try {
            when (intent.action) {
                Proactive.ACTION_ALARM -> Proactive.alarmFired(ctx)
                Proactive.ACTION_ACCEPT, Proactive.ACTION_DECLINE, Proactive.ACTION_MUTE ->
                    Proactive.fromNotification(ctx, intent)
                else -> {}
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Proactive receiver", e)
        }

        Proactive.schedule(ctx)
    }
}
