package com.sanya.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.telecom.TelecomManager

/**
 * Calls: announce an incoming call, answer or reject it by voice, say who called.
 *
 * BubbleService watches the phone state and calls rangStart / rangEnd / ringText.
 * Answering and rejecting need the "answer calls" permission (asked when you tap Start Sanya).
 */
object Calls {

    @Volatile var ringing = false
    @Volatile var announced = false
    @Volatile var number = ""

    private var lastName = ""
    private var lastAt = 0L
    private var lastAnswered = false

    private const val NEED =
        "I need the answer calls permission. Open the Sanya app and tap Start Sanya to allow it."

    // ---------------------------------------------------------------
    // Ring state (called by BubbleService)
    // ---------------------------------------------------------------

    fun rangStart() {
        ringing = true
        announced = false
        number = ""
    }

    fun rangEnd(wasAnswered: Boolean) {
        ringing = false
        lastAnswered = wasAnswered
    }

    private fun nameFor(ctx: Context, num: String): String? {

        if (num.isBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null

        return try {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            ctx.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** What she says when the phone rings. Also remembers the caller for "who called". */
    fun ringText(ctx: Context): String {

        val name = nameFor(ctx, number)

        lastName = name ?: (if (number.isBlank()) "someone with a hidden number" else "an unknown number")
        lastAt = System.currentTimeMillis()
        lastAnswered = false

        val who = when {
            name != null -> name
            number.isBlank() -> "Someone"
            else -> "An unknown number"
        }

        return "$who is calling. Say answer or reject."
    }

    // ---------------------------------------------------------------
    // Doing things
    // ---------------------------------------------------------------

    private fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    private fun telecom(ctx: Context) = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    // Returns "" on success (no need to talk over the call).
    private fun answer(ctx: Context, speaker: Boolean): String {

        if (!allowed(ctx)) return NEED

        return try {
            telecom(ctx).acceptRingingCall()

            if (speaker) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = true
                    } catch (e: Throwable) {
                        ErrorLog.add(ctx, "Speakerphone", e)
                    }
                }, 1500L)
            }

            ""
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Answer call", e)
            "I couldn't answer that call."
        }
    }

    private fun endIt(ctx: Context, done: String): String {

        if (!allowed(ctx)) return NEED

        return try {
            if (telecom(ctx).endCall()) done else "I couldn't end the call."
        } catch (e: Throwable) {
            ErrorLog.add(ctx, "End call", e)
            "I couldn't end the call."
        }
    }

    private fun who(ctx: Context): String {
        //The phone's own call log is more reliable than what she saw ring while reuuning.
        CallLogs.lastCallerText(ctx)?.let{ return it }

        if (lastAt == 0L) return "I haven't seen any calls yet."

        val mins = (System.currentTimeMillis() - lastAt) / 60_000L

        val ago = when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins minutes ago"
            mins < 1440 -> "${mins / 60} hours ago"
            else -> "${mins / 1440} days ago"
        }

        return "The last call was from $lastName, $ago. " +
            if (lastAnswered) "You answered it." else "You didn't answer it."
    }

    private val CALL_WORD = Regex("""\b(?:call|phone)\b""")

    /**
     * Null if [raw] is not about calls (so the normal rules get it). Otherwise what she should say
     * ("" means say nothing). "answer" and "reject" only count while the phone is ringing,
     * unless you say the word "call".
     */
    fun handle(ctx: Context, raw: String): String? {

        val cmd = PhoneParse.call(raw) ?: return null
        val said = CALL_WORD.containsMatchIn(PhoneParse.norm(raw))

        return when (cmd) {

            is PhoneParse.CallCmd.Answer ->
                if (!ringing && !said) null
                else if (!ringing) "No call is ringing."
                else answer(ctx, cmd.speaker)

            PhoneParse.CallCmd.Reject ->
                if (!ringing && !said) null
                else if (!ringing) "No call is ringing."
                else endIt(ctx, "")

            PhoneParse.CallCmd.Hangup -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (!ringing && am.mode != AudioManager.MODE_IN_CALL) "There's no call to end."
                else endIt(ctx, "Call ended.")
            }

            PhoneParse.CallCmd.Who -> who(ctx)

            is PhoneParse.CallCmd.Announce -> {
                Brain.put("setting", "announce calls", if (cmd.on) "on" else "off")
                if (cmd.on) "Okay. I'll tell you who is calling." else "Okay. I won't announce calls."
            }
        }
    }
}
