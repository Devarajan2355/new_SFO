package com.sanya.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * GuardReport: the pure part of the weekly Focus Guard report. No Android calls, so it is unit-tested.
 *
 * What is counted, per day (numbers only, never a web address, a word you searched or a page title):
 *   shorts      Shorts blocked
 *   words       adult-word filter hits in a browser
 *   limits      an app limit used up (sent Home)
 *   urgeKicks   a tempting app opened during urge mode, or a locked Settings page
 *   urges       times you started urge mode
 *   loosened    a safeguard really was loosened (guard off, Shorts allowed, a limit raised or removed ...)
 *   svcOff      the guard service stopped (switched off in Accessibility, or Android restarted it)
 *   offMin      minutes the guard was not running (includes the phone being off)
 *   dnsOff      the Private DNS filter she was told to watch was changed or switched off
 *   alerts      automatic texts she sent your partner because something was switched off
 *   lockHits    times Sanya's locked settings pages were opened and closed again (an attempt to get in)
 *
 * The report is built to be HONEST, not flattering: it always shows loosened safeguards, stopped service and
 * time not running, so a partner can trust the good weeks.
 */
object GuardReport {

    const val KEEP_DAYS = 45

    data class Day(
        val day: Long,
        val shorts: Int = 0,
        val words: Int = 0,
        val limits: Int = 0,
        val urgeKicks: Int = 0,
        val urges: Int = 0,
        val loosened: Int = 0,
        val svcOff: Int = 0,
        val offMin: Int = 0,
        val dnsOff: Int = 0,
        val alerts: Int = 0,
        val lockHits: Int = 0
    ) {
        val blocks: Int get() = shorts + words + limits + urgeKicks
    }

    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val blocks: Int,
        val shorts: Int,
        val words: Int,
        val limits: Int,
        val urgeKicks: Int,
        val urges: Int,
        val loosened: Int,
        val svcOff: Int,
        val offMin: Int,
        val dnsOff: Int,
        val alerts: Int,
        val lockHits: Int,
        val noWordDays: Int,
        val perDay: List<Int>,
        val dayNames: List<String>
    )

    /** How things are set right now. */
    data class Config(
        val guardOn: Boolean,
        val shortsOn: Boolean,
        val wordsOn: Boolean,
        val serviceOn: Boolean,
        val limits: Int
    )

    // ---------------------------------------------------------------
    // The counters
    // ---------------------------------------------------------------

    /** Adds [n] to one counter of one day. An unknown [kind] changes nothing. */
    fun bump(m: Map<Long, Day>, day: Long, kind: String, n: Int = 1): Map<Long, Day> {

        val d = m[day] ?: Day(day)

        val next = when (kind) {
            "shorts" -> d.copy(shorts = d.shorts + n)
            "words" -> d.copy(words = d.words + n)
            "limit" -> d.copy(limits = d.limits + n)
            "urgekick" -> d.copy(urgeKicks = d.urgeKicks + n)
            "urge" -> d.copy(urges = d.urges + n)
            "loosen" -> d.copy(loosened = d.loosened + n)
            "svcoff" -> d.copy(svcOff = d.svcOff + n)
            "offmin" -> d.copy(offMin = d.offMin + n)
            "dnsoff" -> d.copy(dnsOff = d.dnsOff + n)
            "alert" -> d.copy(alerts = d.alerts + n)
            "lockhit" -> d.copy(lockHits = d.lockHits + n)
            else -> return m
        }

        val out = LinkedHashMap(m)
        out[day] = next

        return out
    }

    /** Forgets everything older than [KEEP_DAYS]. */
    fun prune(m: Map<Long, Day>, today: Long): Map<Long, Day> =
        m.filterKeys { it > today - KEEP_DAYS }

    fun encode(m: Map<Long, Day>): String =
        m.values.sortedBy { it.day }.joinToString(";") {
            listOf(
                it.day, it.shorts, it.words, it.limits, it.urgeKicks, it.urges, it.loosened, it.svcOff, it.offMin,
                it.dnsOff, it.alerts, it.lockHits
            ).joinToString(",")
        }

    fun decode(raw: String): Map<Long, Day> {

        val out = LinkedHashMap<Long, Day>()

        for (part in raw.split(";")) {

            val p = part.split(",")
            if (p.size < 9) continue

            val day = p[0].toLongOrNull() ?: continue
            val n = IntArray(8)
            var bad = false

            for (i in 0 until 8) {
                val v = p[i + 1].toIntOrNull()
                if (v == null) bad = true else n[i] = v
            }

            if (bad) continue

            // Older logs have 9 fields: the two newest counters are then zero.
            val dns = p.getOrNull(9)?.toIntOrNull() ?: 0
            val alerts = p.getOrNull(10)?.toIntOrNull() ?: 0
            val lock = p.getOrNull(11)?.toIntOrNull() ?: 0

            out[day] = Day(day, n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], dns, alerts, lock)
        }

        return out
    }

    /** The [n] days ending at [endDay], oldest first. A day with nothing recorded is all zeros. */
    fun lastDays(m: Map<Long, Day>, endDay: Long, n: Int = 7): List<Day> =
        (n - 1 downTo 0).map { k -> m[endDay - k] ?: Day(endDay - k) }

    /** Minutes the guard was not running between two heartbeats. Under 10 minutes is not worth counting. */
    fun offGap(lastBeat: Long, now: Long): Int {

        if (lastBeat <= 0L || now <= lastBeat) return 0

        val m = ((now - lastBeat) / 60_000L).toInt()

        return if (m >= 10) minOf(m, 1440) else 0
    }

    // ---------------------------------------------------------------
    // The week
    // ---------------------------------------------------------------

    fun summarize(days: List<Day>): Summary {

        if (days.isEmpty()) {
            val z = LocalDate.ofEpochDay(0)
            return Summary(z, z, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList())
        }

        return Summary(
            from = LocalDate.ofEpochDay(days.first().day),
            to = LocalDate.ofEpochDay(days.last().day),
            blocks = days.sumOf { it.blocks },
            shorts = days.sumOf { it.shorts },
            words = days.sumOf { it.words },
            limits = days.sumOf { it.limits },
            urgeKicks = days.sumOf { it.urgeKicks },
            urges = days.sumOf { it.urges },
            loosened = days.sumOf { it.loosened },
            svcOff = days.sumOf { it.svcOff },
            offMin = days.sumOf { it.offMin },
            dnsOff = days.sumOf { it.dnsOff },
            alerts = days.sumOf { it.alerts },
            lockHits = days.sumOf { it.lockHits },
            noWordDays = days.count { it.words == 0 },
            perDay = days.map { it.blocks },
            dayNames = days.map {
                LocalDate.ofEpochDay(it.day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            }
        )
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun rangeText(from: LocalDate, to: LocalDate): String =
        from.dayOfMonth.toString() + " " + MONTHS[from.monthValue - 1] + " to " +
            to.dayOfMonth + " " + MONTHS[to.monthValue - 1]

    fun configText(c: Config): String {

        val guard = when {
            !c.guardOn -> "guard OFF"
            !c.serviceOn -> "guard on but its Accessibility service is OFF"
            else -> "guard on"
        }

        return guard +
            ", Shorts blocker " + (if (c.shortsOn) "on" else "OFF") +
            ", adult filter " + (if (c.wordsOn) "on" else "OFF") +
            ", " + c.limits + (if (c.limits == 1) " app limit" else " app limits")
    }

    private fun times(n: Int): String = if (n == 1) "once" else "$n times"

    /** The message you send to your partner. Plain text, a few lines, numbers only. */
    fun partnerText(s: Summary, c: Config, partner: String): String {

        val sb = StringBuilder()

        sb.append(if (partner.isBlank()) "My Focus Guard report" else "Hi $partner, my Focus Guard report")
            .append(" for ").append(rangeText(s.from, s.to)).append(":\n")

        sb.append("Blocks: ").append(s.blocks)

        val parts = ArrayList<String>()
        if (s.shorts > 0) parts.add("Shorts ${s.shorts}")
        if (s.words > 0) parts.add("adult words ${s.words}")
        if (s.limits > 0) parts.add("limits ${s.limits}")
        if (s.urgeKicks > 0) parts.add("urge mode ${s.urgeKicks}")

        if (parts.isNotEmpty()) sb.append(" (").append(parts.joinToString(", ")).append(")")

        sb.append("\n")
        sb.append("Urges handled: ").append(s.urges).append("\n")
        sb.append("Days with no adult-word block: ").append(s.noWordDays).append(" of ").append(s.perDay.size).append("\n")
        sb.append("Safeguards loosened: ").append(s.loosened).append("\n")
        sb.append("Guard not running: ")
            .append(if (s.offMin == 0) "none" else Num.short(s.offMin) + " (includes phone off)")

        if (s.svcOff > 0) sb.append(". Guard service stopped: ").append(s.svcOff).append(" (includes restarts)")

        sb.append("\n")

        if (s.lockHits > 0) sb.append("Tried to open Sanya's locked settings: ").append(s.lockHits).append("\n")
        if (s.dnsOff > 0) sb.append("Private DNS filter changed: ").append(s.dnsOff).append("\n")
        if (s.alerts > 0) sb.append("Automatic alerts sent to you: ").append(s.alerts).append("\n")

        sb.append("Per day: ")
            .append(s.dayNames.indices.joinToString(", ") { s.dayNames[it] + " " + s.perDay[it] })
            .append("\n")
        sb.append("Right now: ").append(configText(c)).append(".")

        return sb.toString()
    }

    /** What she says aloud to you. Facts, and no scolding. */
    fun spoken(s: Summary, c: Config): String {

        val parts = ArrayList<String>()

        parts.add(
            if (s.blocks == 0) "This week: no blocks."
            else "This week: ${s.blocks} " + (if (s.blocks == 1) "block." else "blocks.")
        )

        if (s.urges > 0) {
            parts.add("You handled ${s.urges} " + (if (s.urges == 1) "urge." else "urges.") + " That takes effort.")
        }

        parts.add("${s.noWordDays} of ${s.perDay.size} days with no adult-word block.")

        if (s.loosened > 0) parts.add("A safeguard was loosened " + times(s.loosened) + ".")

        if (s.svcOff > 0) parts.add("The guard service stopped " + times(s.svcOff) + " (that includes restarts).")

        if (s.offMin > 0) parts.add("It wasn't running for " + Num.durText(s.offMin) + ".")

        if (s.lockHits > 0) parts.add("You tried to open Sanya's locked settings " + times(s.lockHits) + ".")

        if (s.dnsOff > 0) parts.add("The Private DNS filter was changed " + times(s.dnsOff) + ".")

        if (s.alerts > 0) parts.add("I texted your partner " + times(s.alerts) + ".")

        parts.add("Right now: " + configText(c) + ".")

        return parts.joinToString(" ")
    }

    /**
     * The one short text she sends your partner if something is switched off (only if you turned that on).
     * [what]: guard, filter, service, dns or alerts. [minutesOfDay] is the time it happened.
     */
    fun alertText(partner: String, what: String, minutesOfDay: Int): String {

        val event = when (what) {
            "guard" -> "my Focus Guard was switched off"
            "filter" -> "my adult word filter was switched off"
            "service" -> "my Focus Guard's Accessibility service was switched off"
            "dns" -> "my Private DNS filter was changed"
            "alerts" -> "I turned off these alerts"
            else -> "my Focus Guard was changed"
        }

        val hi =
            if (partner.isBlank()) "Automatic message from my Focus Guard"
            else "Hi $partner, automatic message from my Focus Guard"

        return "$hi: $event at ${ReminderParse.clock12(minutesOfDay)}."
    }

    // ---------------------------------------------------------------
    // When to offer the report
    // ---------------------------------------------------------------

    fun dayOf(word: String): DayOfWeek? =
        DayOfWeek.values().firstOrNull { it.name.equals(word.trim(), true) }

    /**
     * Offer the report on [reportDay] from [atMinutes] on, once per week. If that day was missed, offer it
     * once more after 9 days instead of never. [lastPromptDay] is an epoch day, 0 for never.
     */
    fun due(today: LocalDate, minutesNow: Int, lastPromptDay: Long, reportDay: DayOfWeek, atMinutes: Int): Boolean {

        if (minutesNow < atMinutes) return false

        val t = today.toEpochDay()

        if (lastPromptDay == t) return false

        val since = if (lastPromptDay <= 0L) Long.MAX_VALUE else t - lastPromptDay

        if (today.dayOfWeek == reportDay) return since >= 6

        return lastPromptDay > 0L && since >= 9
    }
}
