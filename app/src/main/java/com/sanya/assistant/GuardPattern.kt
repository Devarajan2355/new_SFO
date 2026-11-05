package com.sanya.assistant

/**
 * GuardPattern: when your urges come. Pure, unit-tested. Only 24 numbers are kept (urges per hour of the
 * day), never what you were doing.
 */
object GuardPattern {

    fun decode(raw: String): IntArray {
        val a = IntArray(24)
        raw.split(",").forEachIndexed { i, s -> if (i < 24) a[i] = s.trim().toIntOrNull() ?: 0 }
        return a
    }

    fun encode(a: IntArray): String = a.joinToString(",")

    fun bump(a: IntArray, hour: Int): IntArray {
        val o = a.copyOf()
        o[hour.coerceIn(0, 23)]++
        return o
    }

    /** (first hour of the busiest two-hour window, urges in it), or null with fewer than 3 urges in all. */
    fun peak(a: IntArray): Pair<Int, Int>? {
        if (a.sum() < 3) return null
        var bestH = 0
        var best = -1
        for (h in 0 until 24) {
            val n = a[h] + a[(h + 1) % 24]
            if (n > best) {
                best = n
                bestH = h
            }
        }
        return Pair(bestH, best)
    }

    fun text(a: IntArray): String {
        val total = a.sum()
        if (total == 0) return "I haven't logged any urges yet."
        val p = peak(a) ?: return "I've logged $total " + (if (total == 1) "urge" else "urges") +
            " so far. I need a few more before I can see a pattern."
        val from = ReminderParse.clock12(p.first * 60)
        val to = ReminderParse.clock12(((p.first + 2) % 24) * 60)
        return p.second.toString() + " of your $total urges came between $from and $to. " +
            "That is your hard stretch. Plan something for it, like a pomodoro or a walk."
    }
}
