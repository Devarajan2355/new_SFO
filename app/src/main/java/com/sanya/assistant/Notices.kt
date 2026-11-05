package com.sanya.assistant

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notices: reads your notifications aloud and, if you ask, announces new messages.
 *
 * The last 40 notifications are kept IN MEMORY only (never written to disk). Ongoing ones
 * (music, navigation, downloads), group summaries and calls are skipped. A notification that
 * mentions a security code is never read out.
 *
 * Needs "Notification access" for Sanya (say: enable notification access).
 * Setting: setting | read notifications = off   stops collecting them completely.
 */
object Notices {

    data class Note(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val messaging: Boolean
    ) {
        val id: String get() = "$key|$title|$text"
    }

    private const val MAX = 40

    @Volatile var connected = false

    /** Set by BubbleService: called for each NEW notification (to announce messages). */
    @Volatile var listener: ((Note) -> Unit)? = null

    private val items = ArrayList<Note>()
    private val seen = HashSet<String>()

    private val SKIP_CATEGORIES = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_STATUS,
        Notification.CATEGORY_ALARM
    )

    private val MESSAGING = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "com.facebook.orca"
    )

    private val SECRET = Regex("""(?i)\b(?:otp|one[- ]time|verification code|security code|passcode|cvv)\b""")

    // ---------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------

    @Synchronized
    private fun put(n: Note): Boolean {

        val old = items.indexOfFirst { it.key == n.key }

        if (old >= 0) {
            if (items[old].text == n.text && items[old].title == n.title) return false
            items.removeAt(old)
        }

        items.add(n)

        while (items.size > MAX) {
            seen.remove(items.removeAt(0).id)
        }

        return true
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun clear() {
        items.clear()
        seen.clear()
    }

    // Newest first.
    @Synchronized
    private fun pick(all: Boolean, messagesOnly: Boolean): List<Note> =
        items.filter { (all || it.id !in seen) && (!messagesOnly || it.messaging) }.reversed()

    @Synchronized
    private fun markRead(list: List<Note>) {
        for (n in list) seen.add(n.id)
    }

    // ---------------------------------------------------------------
    // From the system
    // ---------------------------------------------------------------

    private fun accept(ctx: Context, sbn: StatusBarNotification): Note? {

        if (sbn.packageName == ctx.packageName) return null

        val n = sbn.notification ?: return null

        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val cat = n.category
        if (cat != null && cat in SKIP_CATEGORIES) return null

        val ex = n.extras ?: return null

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()

        if (title.isEmpty() && text.isEmpty()) return null

        val pm = ctx.packageManager

        val app = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val messaging = cat == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGING

        return Note(sbn.key, sbn.packageName, app, title, text, sbn.postTime, messaging)
    }

    fun take(ctx: Context, sbn: StatusBarNotification, announce: Boolean) {

        try {
            Brain.setup(ctx)

            if (Brain.setting("read notifications", "on") == "off") return

            val n = accept(ctx, sbn) ?: return

            if (put(n) && announce) listener?.invoke(n)

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Notification", e)
        }
    }

    // ---------------------------------------------------------------
    // Saying them
    // ---------------------------------------------------------------

    private fun clip(s: String, max: Int = 140): String =
        s.replace(Regex("[\\p{So}\\p{Sk}]"), "").replace(Regex("\\s+"), " ").trim().take(max)

    fun spokenOne(n: Note): String {

        if (SECRET.containsMatchIn(n.title + " " + n.text)) {
            return n.app + ": a security code. I won't read it aloud."
        }

        val title = clip(n.title)
        val body = clip(n.text)

        if (n.messaging) {
            val from = title.ifEmpty { n.app }
            return if (body.isEmpty()) "New message from $from on ${n.app}" else "From $from on ${n.app}: $body"
        }

        val rest = listOf(title, body).filter { it.isNotEmpty() }.joinToString(". ")

        return if (rest.isEmpty()) n.app else n.app + ": " + rest
    }

    private fun read(all: Boolean, messagesOnly: Boolean): String {

        val list = pick(all, messagesOnly)
        val kind = if (messagesOnly) "messages" else "notifications"

        if (list.isEmpty()) {
            return if (all) "I have no $kind to read." else "You have no new $kind."
        }

        val shown = list.take(5)
        val more = list.size - shown.size

        markRead(shown)

        val head = if (list.size == 1) "You have one " + kind.removeSuffix("s") + ". "
        else "You have ${list.size} ${if (all) "" else "new "}$kind. "

        return head + shown.joinToString(". ") { spokenOne(it) } + "." +
            (if (more > 0) " And $more more." else "")
    }

    private fun openAccessSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        ErrorLog.add(ctx, "Notification access settings", e)
        false
    }

    /** Null if [raw] is not about notifications. Otherwise what she should say. */
    fun handle(ctx: Context, raw: String): String? {

        val cmd = PhoneParse.notice(raw) ?: return null

        return when (cmd) {

            is PhoneParse.NoticeCmd.Read ->
                if (!connected) {
                    openAccessSettings(ctx)
                    "I can't see your notifications yet. I'm opening the notification access page. " +
                        "Switch on Sanya there, then ask again."
                } else {
                    read(cmd.all, cmd.messagesOnly)
                }

            PhoneParse.NoticeCmd.Clear -> {
                clear()
                "Okay. I've cleared my list. Your phone's own notifications are untouched."
            }

            PhoneParse.NoticeCmd.Enable ->
                if (connected) "Notification access is already on."
                else if (openAccessSettings(ctx)) "Opening the notification access page. Switch on Sanya."
                else "I couldn't open that page. Look for Notification access in your phone's settings."

            is PhoneParse.NoticeCmd.Announce -> {
                Brain.put("setting", "announce messages", if (cmd.on) "on" else "off")
                if (cmd.on) {
                    "Okay. I'll read out new messages as they arrive, when I'm not busy."
                } else {
                    "Okay. I won't read out messages by myself."
                }
            }
        }
    }
}

/** The system service behind it. Switched on by you in Notification access. */
class NoticeListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Notices.connected = true
        try {
            activeNotifications?.forEach { Notices.take(this, it, false) }
        } catch (e: Throwable) {
            ErrorLog.add(this, "Notification list", e)
        }
    }

    override fun onListenerDisconnected() {
        Notices.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) Notices.take(this, sbn, true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) Notices.remove(sbn.key)
    }
}
