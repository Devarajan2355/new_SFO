package com.sanya.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Calendar

/**
 * GuardCare: the people side of Focus Guard. Five things:
 *
 *   1. The settings lock. The Accessibility, app-info, uninstall and Private DNS pages are closed ALL THE TIME
 *      (GuardService does it, GuardLock decides), and always during urge mode. To get in: say "unlock guard
 *      settings", wait 15 minutes, say it again, and they open for 10 minutes. Turning the lock off waits 15 minutes.
 *   4. Partner alerts (off until you turn them on): if the guard or the adult filter is switched off, the
 *      Accessibility service stays off for 5 minutes, or the Private DNS filter changes, she sends your partner ONE
 *      short text from your phone (at most one every 6 hours). Turning the alerts off tells your partner first.
 *   5. The Private DNS watch: she notices if the system-wide filter you set under Settings > Private DNS changes.
 *   2. An accountability partner: someone you choose to share your week with. Setting one is instant.
 *      Changing or removing one waits 15 minutes, because that loosens the guard.
 *   3. The weekly report: numbers only (see GuardReport). On the report day she offers to get it ready.
 *      She opens a message draft addressed to your partner. YOU tap send. She never sends anything herself,
 *      and nothing leaves the phone any other way.
 *
 * Where it is kept: the "sanya_guard" preferences (with the rest of Focus Guard), not the Brain file, not the
 * backup.
 *
 * Settings (Brain file; they only change WHEN she offers, never what is shared):
 *   setting | guard report day = sunday
 *   setting | guard report time = 20:00
 */
object GuardCare {

    private const val PREFS = "sanya_guard"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------
    // What is set
    // ---------------------------------------------------------------

    fun settingsLockOn(ctx: Context): Boolean = p(ctx).getBoolean("slock", true)

    fun partnerName(ctx: Context): String = p(ctx).getString("partner_name", "") ?: ""

    private fun partnerNumber(ctx: Context): String = p(ctx).getString("partner_num", "") ?: ""

    fun reportsOn(ctx: Context): Boolean = partnerName(ctx).isNotEmpty() && p(ctx).getBoolean("report_on", true)

    private fun config(ctx: Context) = GuardReport.Config(
        Guard.enabled(ctx),
        Guard.shortsOn(ctx),
        Guard.wordsOn(ctx),
        Guard.serviceEnabled(ctx),
        Guard.limits(ctx).size
    )

    private fun summary(ctx: Context): GuardReport.Summary =
        GuardReport.summarize(GuardReport.lastDays(GuardLog.days(ctx), LocalDate.now().toEpochDay()))

    // ---------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------

    private fun report(ctx: Context): String {

        val said = GuardReport.spoken(summary(ctx), config(ctx))
        val who = partnerName(ctx)

        return if (who.isEmpty()) {
            "$said To share it, say: set my accountability partner to Ravi."
        } else {
            "$said Say send my focus guard report to get it ready for $who."
        }
    }

    /** Opens a message draft with the report. You read it and tap send. */
    fun openDraft(ctx: Context): String {

        val text = GuardReport.partnerText(summary(ctx), config(ctx), partnerName(ctx))
        val number = partnerNumber(ctx)
        val who = partnerName(ctx)

        val i: Intent =
            if (number.isNotEmpty()) {
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
            } else {
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    "Send my Focus Guard report"
                )
            }

        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (who.isEmpty()) {
                "Opening the share sheet. Pick who to send it to. I never send it myself."
            } else {
                "Your report is ready for $who. Read it, then tap send. I never send it myself."
            }
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Guard report", e)
            "I couldn't open the message app."
        }
    }

    /**
     * (what she says, the command she runs on a yes) once on the report day, or null.
     * Called by Proactive (the Focus Guard kind), never at night.
     */
    fun reportNudge(ctx: Context, now: Long): Pair<String, String>? {

        if (!reportsOn(ctx)) return null

        val pr = p(ctx)
        val day = GuardReport.dayOf(Brain.setting("guard report day", "sunday")) ?: DayOfWeek.SUNDAY
        val at = ProactiveRules.parseClock(Brain.setting("guard report time", "20:00")) ?: 1200
        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = LocalDate.now()

        if (!GuardReport.due(today, minutes, pr.getLong("report_last", 0L), day, at)) return null

        pr.edit().putLong("report_last", today.toEpochDay()).apply()

        return Pair(
            "It's time for your weekly Focus Guard report. Shall I get it ready for ${partnerName(ctx)}?",
            "send my focus guard report"
        )
    }

    // ---------------------------------------------------------------
    // Your partner
    // ---------------------------------------------------------------

    private fun setPartner(ctx: Context, spoken: String): String {

        val name = spoken.trim()

        if (name.isEmpty()) return "Who should it be? Say, for example: set my accountability partner to Ravi."

        var display = name.split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        var number = ""

        val people = try {
            ContactBook.find(ctx, name).filter { it.score >= 70 }
        } catch (e: Throwable) {
            emptyList()
        }

        val amb = ContactBook.ambiguous(people)

        if (amb.isNotEmpty()) {
            return "That fits " + Choose.joinOr(amb.take(3).map { it.name }) + ". Say the full name."
        }

        val top = people.firstOrNull()

        if (top != null) {
            display = top.name
            number = ContactBook.distinctNumbers(top).firstOrNull()?.number ?: ""
        }

        val old = partnerName(ctx)

        // Swapping your partner for someone else is a way to loosen the guard, so it waits like the rest.
        if (old.isNotEmpty() && Brain.clean(old) != Brain.clean(display)) {
            Guard.gate(ctx, "partner:" + Brain.clean(display), "change your accountability partner to $display")
                ?.let { return it }
        }

        val ed = p(ctx).edit()
            .putString("partner_name", display)
            .putString("partner_num", number)
            .putBoolean("report_on", true)

        if (old.isEmpty()) ed.putLong("report_last", LocalDate.now().toEpochDay())

        ed.apply()

        return "Okay. $display is your accountability partner. " +
            (if (number.isEmpty()) "I couldn't find a number, so I'll open the share sheet. " else "") +
            "Each week I'll offer to get your report ready. You tap send. I never send it myself."
    }

    private fun clearPartner(ctx: Context): String {

        if (partnerName(ctx).isEmpty()) return "You don't have an accountability partner set."

        Guard.gate(ctx, "partner-off", "remove your accountability partner")?.let { return it }

        p(ctx).edit().remove("partner_name").remove("partner_num").apply()

        return "Okay. I removed your accountability partner."
    }

    private fun whoPartner(ctx: Context): String {

        val who = partnerName(ctx)

        if (who.isEmpty()) return "You don't have an accountability partner yet. Say: set my accountability partner to Ravi."

        return "Your accountability partner is $who. Weekly reports are " +
            (if (p(ctx).getBoolean("report_on", true)) "on." else "off.")
    }

    private fun setReminders(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("report_on", true).apply()
            return "Okay. I'll offer your weekly Focus Guard report."
        }

        if (!p(ctx).getBoolean("report_on", true)) return "Weekly reports are already off."

        Guard.gate(ctx, "report-off", "switch the weekly reports off")?.let { return it }

        p(ctx).edit().putBoolean("report_on", false).apply()

        return "Okay. I won't offer the weekly report. You can still say send my focus guard report."
    }

    // ---------------------------------------------------------------
    // The settings lock
    // ---------------------------------------------------------------

    private fun setLock(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("slock", true).apply()
            return "Okay. During urge mode I'll close Sanya's Accessibility and app pages."
        }

        if (!settingsLockOn(ctx)) return "The settings lock is already off."

        Guard.gate(ctx, "slock-off", "switch the settings lock off")?.let { return it }

        p(ctx).edit().putBoolean("slock", false).apply()

        return "The settings lock is off. Say turn on settings lock to bring it back."
    }

    // ---------------------------------------------------------------
    // The lock: always on, with a timed window
    // ---------------------------------------------------------------

    private const val WINDOW_MS = 10 * 60_000L
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    const val ACTION_CHECK = "com.sanya.assistant.GUARD_TAMPER_CHECK"

    fun lockAlways(ctx: Context): Boolean = p(ctx).getBoolean("slock_always", true)

    fun windowOpen(ctx: Context): Boolean = System.currentTimeMillis() < p(ctx).getLong("sopen_until", 0L)

    fun closeWindow(ctx: Context) {
        p(ctx).edit().remove("sopen_until").apply()
    }

    /** Are Sanya's settings pages closed right now? [urgeLeftMs] is Guard.urgeLeftMs(ctx). */
    fun lockActive(ctx: Context, urgeLeftMs: Long): Boolean =
        GuardLock.active(urgeLeftMs, settingsLockOn(ctx), lockAlways(ctx), windowOpen(ctx))

    private fun unlockSettings(ctx: Context): String {

        val urge = Guard.urgeLeftMs(ctx)

        if (!settingsLockOn(ctx) || (!lockAlways(ctx) && urge <= 0L)) {
            return "Sanya's settings aren't locked right now."
        }

        if (windowOpen(ctx)) {
            val left = ((p(ctx).getLong("sopen_until", 0L) - System.currentTimeMillis() + 59_999L) / 60_000L)
                .toInt().coerceAtLeast(1)
            return "The settings window is already open for " + Num.durText(left) + "."
        }

        // Refused during urge mode, and otherwise it waits 15 minutes, like every other loosening.
        Guard.gate(ctx, "settings-window", "open Sanya's settings for 10 minutes")?.let { return it }

        p(ctx).edit().putLong("sopen_until", System.currentTimeMillis() + WINDOW_MS).apply()

        return "Okay. Sanya's settings are open for 10 minutes, then they lock again by themselves."
    }

    private fun lockNow(ctx: Context): String {
        closeWindow(ctx)
        return "Locked. Sanya's settings are closed again."
    }

    private fun setLockAlways(ctx: Context, on: Boolean): String {

        if (on) {
            p(ctx).edit().putBoolean("slock_always", true).putBoolean("slock", true).apply()
            return "Okay. Sanya's Accessibility, app and Private DNS pages stay locked all the time. " +
                "To get in, say unlock guard settings, then wait 15 minutes."
        }

        if (!lockAlways(ctx)) return "The lock is already only for urge mode."

        Guard.gate(ctx, "slock-always-off", "lock the settings only during urge mode")?.let { return it }

        p(ctx).edit().putBoolean("slock_always", false).apply()

        return "Okay. The settings are locked only during urge mode. Say always lock guard settings to bring it back."
    }

    // ---------------------------------------------------------------
    // Partner alerts: one short text if something is switched off
    // ---------------------------------------------------------------

    fun alertOn(ctx: Context): Boolean = p(ctx).getBoolean("alert_on", false)

    private fun smsAllowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun setAlerts(ctx: Context, on: Boolean): String {

        val who = partnerName(ctx)

        if (on) {
            if (who.isEmpty()) return "Set an accountability partner first. Say: set my accountability partner to Ravi."
            if (partnerNumber(ctx).isEmpty()) {
                return "I don't have a phone number for $who. Say the full name of a contact, then try again."
            }
            if (!smsAllowed(ctx)) {
                return "I need permission to send texts. Open the Health check, tap Fix on Send text, then ask again."
            }

            p(ctx).edit().putBoolean("alert_on", true).apply()

            return "Okay. If the guard or the adult filter is switched off, the Accessibility service stays off, " +
                "or the Private DNS filter changes, I'll text $who one short message from your phone. " +
                "It may cost an SMS. Tell $who first."
        }

        if (!alertOn(ctx)) return "Partner alerts are already off."

        Guard.gate(ctx, "alerts-off", "switch the partner alerts off")?.let { return it }

        // The last text tells your partner. Then the alerts go off.
        tamperAlert(ctx, "alerts", true)

        p(ctx).edit().putBoolean("alert_on", false).apply()

        return "Okay. Partner alerts are off, and I told $who that you turned them off."
    }

    /**
     * Texts your partner, if you turned alerts on. [what]: guard, filter, service, dns or alerts.
     * At most one text every 6 hours unless [force]. Returns true if a text was sent.
     */
    @Suppress("DEPRECATION")
    fun tamperAlert(ctx: Context, what: String, force: Boolean = false): Boolean {

        if (!alertOn(ctx)) return false

        val number = partnerNumber(ctx)

        if (number.isEmpty()) return false

        val pr = p(ctx)
        val now = System.currentTimeMillis()

        if (!force && now - pr.getLong("alert_at", 0L) < 6 * 3_600_000L) return false

        if (!smsAllowed(ctx)) {
            ErrorLog.note(ctx, "Guard alert", "Could not text your partner: the Send text permission is off.")
            return false
        }

        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val text = GuardReport.alertText(partnerName(ctx), what, minutes)

        return try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            pr.edit().putLong("alert_at", now).apply()
            GuardLog.note(ctx, "alert")
            true
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Guard alert", e)
            false
        }
    }

    /**
     * The guard service just stopped. An alarm checks again in 5 minutes: if Accessibility still has it switched
     * off, it was switched off, not just restarted. (An alarm outlives the dying service.)
     */
    fun checkServiceLater(ctx: Context) {

        try {
            if (!alertOn(ctx) || !Guard.enabled(ctx)) return

            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 31,
                Intent(ctx, GuardTamperReceiver::class.java).setAction(ACTION_CHECK),
                FLAGS
            )
            val at = System.currentTimeMillis() + 5 * 60_000L
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
            ErrorLog.add(ctx, "Guard tamper check", e)
        }
    }

    /** The alarm went off. */
    fun serviceCheck(ctx: Context) {

        Brain.setup(ctx)

        if (Guard.enabled(ctx) && !Guard.serviceEnabled(ctx)) {
            tamperAlert(ctx, "service")
        }
    }

    // ---------------------------------------------------------------
    // The Private DNS watch
    // ---------------------------------------------------------------

    @Volatile private var lastDnsCheck = 0L

    private fun readDns(ctx: Context): Pair<String?, String?> =
        try {
            val cr = ctx.contentResolver
            Pair(
                Settings.Global.getString(cr, "private_dns_mode"),
                Settings.Global.getString(cr, "private_dns_specifier")
            )
        } catch (e: Throwable) {
            Pair(null, null)
        }

    private fun watchDns(ctx: Context, on: Boolean): String {

        if (on) {
            val dns = readDns(ctx)

            if (!GuardDns.canWatch(dns.first, dns.second)) {
                return "Set a Private DNS provider first: Settings, Private DNS, provider hostname, for example " +
                    "family-filter-dns.cleanbrowsing.org. Then say: watch my private dns."
            }

            val host = (dns.second ?: "").trim().lowercase()

            p(ctx).edit().putString("dns_host", host).remove("dns_bad").apply()

            return "Okay. I'll watch that Private DNS stays set to $host. Its settings page is locked too."
        }

        if ((p(ctx).getString("dns_host", "") ?: "").isEmpty()) return "I'm not watching Private DNS."

        Guard.gate(ctx, "dns-watch-off", "stop watching Private DNS")?.let { return it }

        p(ctx).edit().remove("dns_host").remove("dns_bad").apply()

        return "Okay. I've stopped watching Private DNS."
    }

    /** GuardService calls this every tick. It reads the setting at most once a minute. */
    fun checkDns(ctx: Context, now: Long) {

        if (now - lastDnsCheck < 60_000L) return
        lastDnsCheck = now

        val pr = p(ctx)
        val watched = pr.getString("dns_host", "") ?: ""

        if (watched.isEmpty()) return

        val dns = readDns(ctx)

        if (!GuardDns.changed(dns.first, dns.second, watched)) {
            if (pr.getLong("dns_bad", 0L) != 0L) pr.edit().remove("dns_bad").apply()
            return
        }

        // One report per episode: until it is put right, or 6 hours pass.
        val since = pr.getLong("dns_bad", 0L)

        if (since != 0L && now - since < 6 * 3_600_000L) return

        pr.edit().putLong("dns_bad", now).apply()

        GuardLog.note(ctx, "dnsoff")
        tamperAlert(ctx, "dns")
    }

    // ---------------------------------------------------------------
    // Voice (reached through Guard.run)
    // ---------------------------------------------------------------

    fun run(ctx: Context, c: GuardParse.Cmd): String = when (c) {
        GuardParse.Cmd.Report -> report(ctx)
        GuardParse.Cmd.SendReport -> openDraft(ctx)
        is GuardParse.Cmd.Partner -> setPartner(ctx, c.name)
        GuardParse.Cmd.PartnerOff -> clearPartner(ctx)
        GuardParse.Cmd.PartnerWho -> whoPartner(ctx)
        is GuardParse.Cmd.ReportReminders -> setReminders(ctx, c.on)
        is GuardParse.Cmd.SettingsLock -> setLock(ctx, c.on)
        GuardParse.Cmd.UnlockSettings -> unlockSettings(ctx)
        GuardParse.Cmd.LockSettingsNow -> lockNow(ctx)
        is GuardParse.Cmd.LockAlways -> setLockAlways(ctx, c.on)
        is GuardParse.Cmd.Alerts -> setAlerts(ctx, c.on)
        is GuardParse.Cmd.WatchDns -> watchDns(ctx, c.on)
        else -> "I can't do that one."
    }
}

/** Fires 5 minutes after the guard service stops, to see whether it was switched off or just restarted. */
class GuardTamperReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {

        try {
            GuardCare.serviceCheck(ctx)
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Guard tamper check", e)
        }
    }
}
