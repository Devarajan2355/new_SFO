package com.sanya.assistant

import android.content.Context

/**
 * Personal: your call history (CallLogs), your lists (Lists) and her proactive controls (Proactive)
 * behind one door.
 *
 *   handle()      what she should say, or null if the sentence is not about any of them
 *   understands() would handle() take it?   (used to choose between several guesses of what you said)
 *   wantsReply()  she asked something ("Call back Ravi? Say yes or no.") and is waiting for your answer
 *
 * CommandRewriter calls these first, so a call, list or nudge-control command never reaches the older
 * fixed rules. Proactive only takes "stop that", "why did you say that" and "that was helpful" just
 * after she has spoken first, so those phrases stay free for everything else the rest of the time.
 */
object Personal {

    fun wantsReply(): Boolean = CallLogs.wantsReply() || Lists.wantsReply()

    fun understands(ctx: Context, raw: String): Boolean =
        CallLogs.understands(ctx, raw) || Lists.understands(ctx, raw) || Proactive.understands(ctx, raw)

    fun handle(ctx: Context, raw: String): String? =
        CallLogs.handle(ctx, raw) ?: Lists.handle(ctx, raw) ?: Proactive.handle(ctx, raw)
}
