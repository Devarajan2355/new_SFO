package com.sanya.assistant

import android.content.Context
import java.time.LocalDate

/**
 * GuardLog: the small daily counters behind the weekly report (see GuardReport).
 *
 * Kept in its own preferences file "sanya_guard_log", like the guard's own settings: NOT in the Brain file and
 * NOT in the backup, so restoring an old backup can never wipe out a bad week. Only numbers are stored.
 *
 *   note(ctx, "shorts")        one more Shorts block today (kinds: shorts, words, limit, urgekick, urge,
 *                              loosen, svcoff, offmin)
 *   beat(ctx, now)             "the guard is alive": GuardService calls it from its tick
 *   connected(ctx, now)        the guard service just started: the gap since the last heartbeat is time
 *                              it was not running (the phone being off counts too)
 */
object GuardLog {

    private const val PREFS = "sanya_guard_log"
    private const val BEAT_EVERY_MS = 60_000L

    @Volatile private var lastBeatSaved = 0L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun days(ctx: Context): Map<Long, GuardReport.Day> =
        GuardReport.decode(p(ctx).getString("days", "") ?: "")

    @Synchronized
    fun note(ctx: Context, kind: String, n: Int = 1) {

        try {
            val pr = p(ctx)
            val today = LocalDate.now().toEpochDay()
            val next = GuardReport.prune(
                GuardReport.bump(GuardReport.decode(pr.getString("days", "") ?: ""), today, kind, n),
                today
            )

            pr.edit().putString("days", GuardReport.encode(next)).apply()

        } catch (e: Throwable) {
            ErrorLog.add(ctx, "Guard log", e)
        }
    }

    @Synchronized
    fun beat(ctx: Context, now: Long) {

        if (now - lastBeatSaved < BEAT_EVERY_MS) return

        lastBeatSaved = now
        p(ctx).edit().putLong("beat", now).apply()
    }

    @Synchronized
    fun connected(ctx: Context, now: Long) {

        val last = p(ctx).getLong("beat", 0L)
        val gap = GuardReport.offGap(last, now)

        if (gap > 0) note(ctx, "offmin", gap)

        lastBeatSaved = now
        p(ctx).edit().putLong("beat", now).apply()
    }
}
