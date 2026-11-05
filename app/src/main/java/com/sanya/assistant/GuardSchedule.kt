package com.sanya.assistant

/**
 * GuardSchedule: the pure part of per-app time windows. No Android calls, so it is unit-tested.
 * A rule blocks one app between two times of day. Minutes are counted after midnight.
 * 22:00 to 06:00 crosses midnight and is handled.
 */
object GuardSchedule {

    data class Rule(val label: String, val start: Int, val end: Int)

    /** True if [now] is inside the window. start == end means no window at all. */
    fun active(r: Rule, now: Int): Boolean = when {
        r.start == r.end -> false
        r.start < r.end -> now in r.start until r.end
        else -> now >= r.start || now < r.end
    }

    fun blocked(rules: List<Rule>, label: String, now: Int): Rule? =
        rules.firstOrNull { it.label == label && active(it, now) }

    /**
     * True if [new] leaves some minute open that [old] had closed. That loosens the rule, so it waits
     * 15 minutes. A longer window, or the same one, is instant. A shifted window loosens too.
     */
    fun loosens(old: Rule, new: Rule): Boolean {
        for (m in 0 until 1440) if (active(old, m) && !active(new, m)) return true
        return false
    }

    fun encode(l: List<Rule>): String = l.joinToString(";") { it.label + "=" + it.start + "-" + it.end }

    fun decode(raw: String): List<Rule> = raw.split(";").mapNotNull { part ->
        val kv = part.split("=")
        val t = kv.getOrNull(1)?.split("-")
        val s = t?.getOrNull(0)?.toIntOrNull()
        val e = t?.getOrNull(1)?.toIntOrNull()
        if (kv.size != 2 || kv[0].isEmpty() || s == null || e == null || s !in 0..1439 || e !in 0..1439) {
            null
        } else {
            Rule(kv[0], s, e)
        }
    }

    fun text(r: Rule): String =
        r.label.replaceFirstChar { it.uppercase() } + " is blocked from " +
            ReminderParse.clock12(r.start) + " to " + ReminderParse.clock12(r.end)
}
