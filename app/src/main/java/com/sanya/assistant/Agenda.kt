package com.sanya.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Agenda: your calendar, spoken. Reads the phone's calendar (any account synced to it).
 * Nothing is stored except a note of which meeting alerts were already spoken today.
 *
 *   what's on my calendar tomorrow / when is my next meeting / am i free tomorrow at 3
 *   what's my day        (calendar + routines + reminders + to-do + dates)
 *   a heads-up before a meeting (Proactive.kt):  setting | event heads up = 15  (minutes, or off)
 *
 * Needs the Calendar permission (asked when you tap Start Sanya).
 */
object Agenda {

    data class Ev(
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String
    )

    private const val PREFS = "sanya_agenda"
    private const val DAY_MS = 86_400_000L
    private const val FOLLOW_MS = 120_000L

    const val NEED = "I need calendar permission. Open the Sanya app and tap Start Sanya to allow it."

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Volatile private var lastDay: LocalDate? = null
    @Volatile private var lastAt = 0L

    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    private fun query(ctx: Context, from: Long, to: Long): List<Ev> {

        if (!allowed(ctx)) return emptyList()

        val out = ArrayList<Ev>()

        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, from)
            ContentUris.appendId(b, to)

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.VISIBLE
            )

            ctx.contentResolver.query(b.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->

                while (c.moveToNext() && out.size < 300) {

                    if (c.getInt(7) == 0) continue                                            // calendar hidden
                    if (c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue

                    val title = c.getString(0) ?: ""
                    var begin = c.getLong(1)
                    var end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    val loc = c.getString(4) ?: ""

                    if (allDay) {
                        // All-day events are stored as UTC midnights: turn them into local days.
                        val d1 = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val d2 = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        begin = d1.atStartOfDay(zone).toInstant().toEpochMilli()
                        end = maxOf(d2, d1.plusDays(1)).atStartOfDay(zone).toInstant().toEpochMilli()
                    }

                    out.add(Ev(title, begin, end, allDay, loc))
                }
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Reading the calendar", e)
        }

        return out.distinctBy { it.title + "|" + it.begin }
    }

    /** Everything that touches [date]: all-day events first, then by start time. */
    fun eventsOn(ctx: Context, date: LocalDate): List<Ev> {

        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        return query(ctx, start - DAY_MS, end + DAY_MS)
            .filter { it.begin < end && maxOf(it.end, it.begin + 1) > start }
            .sortedWith(compareBy({ !it.allDay }, { it.begin }))
    }

    // ---------------------------------------------------------------
    // Saying things
    // ---------------------------------------------------------------

    private fun timeOf(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return ReminderParse.clock12(t.hour * 60 + t.minute)
    }

    private fun title(e: Ev): String = e.title.trim().ifEmpty { "an event" }

    private fun label(e: Ev, nowMs: Long, isToday: Boolean): String =
        when {
            e.allDay -> title(e) + ", all day"
            isToday && e.begin <= nowMs -> title(e) + ", on now until " + timeOf(e.end)
            else -> title(e) + " at " + timeOf(e.begin)
        }

    private fun dayWord(d: LocalDate, today: LocalDate): String {

        val diff = ChronoUnit.DAYS.between(today, d)

        return when {
            diff == 0L -> "today"
            diff == 1L -> "tomorrow"
            diff in 2L..6L -> "on " + d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> "on " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
    }

    private fun count(n: Int) = if (n == 1) "one event" else "$n events"

    fun dayText(ctx: Context, date: LocalDate): String {

        if (!allowed(ctx)) return NEED

        val today = LocalDate.now()
        val nowMs = System.currentTimeMillis()
        val isToday = date == today

        val all = eventsOn(ctx, date)
        val list = if (isToday) all.filter { it.allDay || it.end > nowMs } else all
        val w = dayWord(date, today)

        if (list.isEmpty()) {
            return if (isToday && all.isNotEmpty()) {
                "You have nothing left on your calendar today."
            } else {
                "You have nothing on your calendar $w."
            }
        }

        val shown = list.take(5)
        val more = list.size - shown.size

        val head = if (isToday && all.size > list.size) {
            "You have ${count(list.size)} left today. "
        } else {
            "You have ${count(list.size)} $w. "
        }

        return head + shown.joinToString(". ") { label(it, nowMs, isToday) } + "." +
            (if (more > 0) " And $more more." else "")
    }

    fun nextText(ctx: Context): String {

        if (!allowed(ctx)) return NEED

        val now = System.currentTimeMillis()

        val ev = query(ctx, now, now + 14 * DAY_MS)
            .filter { !it.allDay && it.begin > now }
            .minByOrNull { it.begin }
            ?: return "You have no upcoming events in the next two weeks."

        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ev.begin), zone)
        val loc = ev.location.trim().replace(Regex("\\s+"), " ").take(60)

        return "Your next event is " + title(ev) + ", " + ReminderParse.whenText(at, "", LocalDateTime.now()) + "." +
            (if (loc.isEmpty()) "" else " Location: $loc.")
    }

    fun freeText(ctx: Context, whenText: String): String {

        if (!allowed(ctx)) return NEED

        val now = LocalDateTime.now()

        val spec = ReminderParse.parseWhen(whenText, now, Reminders.defaultTod())
            ?: return "I didn't catch the time. Say, for example: am I free tomorrow at 3?"

        val start = spec.at.atZone(zone).toInstant().toEpochMilli()
        val end = start + 60 * 60_000L

        val hits = query(ctx, start, end).filter { !it.allDay && it.begin < end && it.end > start }

        val whenWord = ReminderParse.whenText(spec.at, "", now)

        if (hits.isEmpty()) return "You're free $whenWord."

        val e = hits.minByOrNull { it.begin }!!

        return "You're busy $whenWord: " + title(e) + ", " + timeOf(e.begin) + " to " + timeOf(e.end) + "."
    }

    /** What you have on, in one go: calendar, routines, reminders, to-do list, dates. */
    fun myDay(ctx: Context): String {

        val parts = ArrayList<String>()
        val today = LocalDate.now()

        if (allowed(ctx)) {
            parts.add(dayText(ctx, today))
        }

        try {
            val routines = Routines.briefLine(ctx)
            if (routines.isNotEmpty()) parts.add(routines)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "My day: routines", e)
        }

        try {
            val reminders = Reminders.briefLine(ctx)
            if (reminders.isNotEmpty()) parts.add(reminders)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "My day: reminders", e)
        }

        val listsLine = Lists.briefLine(ctx)
        if (listsLine.isNotEmpty()) parts.add(listsLine)

        val dates = Mind.upcomingLine()
        if (dates.isNotEmpty()) parts.add(dates)

        if (!allowed(ctx)) parts.add("I can't see your calendar yet. Allow calendar permission in the Sanya app.")

        return if (parts.isEmpty()) "Nothing is planned for today." else parts.joinToString(" ")
    }

    /** One sentence for the morning brief, or "". */
    fun briefLine(ctx: Context): String {

        if (!allowed(ctx)) return ""

        val nowMs = System.currentTimeMillis()
        val list = eventsOn(ctx, LocalDate.now()).filter { it.allDay || it.end > nowMs }

        if (list.isEmpty()) return ""

        val shown = list.take(3)
        val more = list.size - shown.size

        return "On your calendar: " + shown.joinToString(", ") { label(it, nowMs, true) } +
            (if (more > 0) ", and $more more" else "") + "."
    }

    // ---------------------------------------------------------------
    // Meeting heads-up (called by Proactive.kt)
    // ---------------------------------------------------------------

    private fun leadMinutes(): Int {
        val raw = Brain.setting("event heads up", "15").trim()
        if (raw == "off") return 0
        return (raw.toIntOrNull() ?: 15).coerceIn(0, 120)
    }

    /** "Heads up: Standup starts in 15 minutes." once per event, or null. */
    fun headsUp(ctx: Context, nowMs: Long): String? {

        val lead = leadMinutes()
        if (lead <= 0 || !allowed(ctx)) return null

        val soon = query(ctx, nowMs, nowMs + (lead + 1) * 60_000L)
            .filter { !it.allDay && it.begin >= nowMs && it.begin - nowMs <= lead * 60_000L }

        if (soon.isEmpty()) return null

        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toEpochDay()
        val raw = p.getString("done", "") ?: ""

        val done =
            if (raw.startsWith("$today|")) raw.substringAfter('|').split(";").filter { it.isNotEmpty() }
            else emptyList()

        for (e in soon) {

            val key = e.begin.toString() + ":" + e.title.hashCode()
            if (key in done) continue

            p.edit().putString("done", "$today|" + (done + key).joinToString(";")).apply()

            val mins = ((e.begin - nowMs + 30_000L) / 60_000L).toInt()
            val loc = e.location.trim().replace(Regex("\\s+"), " ").take(60)

            return (if (mins < 1) title(e) + " is starting now." else "Heads up: " + title(e) + " starts in " + Num.durText(mins) + ".") +
                (if (loc.isEmpty()) "" else " Location: $loc.")
        }

        return null
    }

    // ---------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------

    private fun followValid(): Boolean =
        lastDay != null && System.currentTimeMillis() - lastAt < FOLLOW_MS

    private fun remember(d: LocalDate) {
        lastDay = d
        lastAt = System.currentTimeMillis()
    }

    /** Would [raw] be handled here? */
    fun understands(raw: String): Boolean {
        val c = AgendaParse.parse(raw) ?: return false
        return c !is AgendaParse.Cmd.Follow || followValid()
    }

    /** Null if [raw] is not about the calendar. Otherwise what she should say. */
    fun handle(ctx: Context, raw: String): String? {

        val cmd = AgendaParse.parse(raw) ?: return null

        return try {
            when (cmd) {

                is AgendaParse.Cmd.Day -> {
                    remember(cmd.date)
                    dayText(ctx, cmd.date)
                }

                is AgendaParse.Cmd.Follow ->
                    if (!followValid()) {
                        null
                    } else {
                        remember(cmd.date)
                        dayText(ctx, cmd.date)
                    }

                AgendaParse.Cmd.Next -> nextText(ctx)

                AgendaParse.Cmd.MyDay -> {
                    remember(LocalDate.now())
                    myDay(ctx)
                }

                is AgendaParse.Cmd.Free -> freeText(ctx, cmd.whenText)
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Agenda", e)
            "I couldn't read your calendar just now."
        }
    }
}
