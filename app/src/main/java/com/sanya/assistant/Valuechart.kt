package com.sanya.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** One bar: what it is called and how big it is. */
data class ValueBar(val label: String, val value: Long)

/**
 * A bar chart where the height is the value (money spent, minutes studied).
 * The newest bar (the last one) glows cyan to mint, the others are violet, and an empty one is a
 * thin stub. [format] turns a value into the short label drawn above its bar.
 */
class ValueChartView(context: Context) : View(context) {

    var bars: List<ValueBar> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var format: (Long) -> String = { it.toString() }

    private val d = resources.displayMetrics.density

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        strokeWidth = 1f * d
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val none = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2AFFFFFF }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SUB
        textSize = 9f * d
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val n = bars.size
        if (n == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        val labelH = 18f * d
        val topPad = 16f * d
        val chartH = h - labelH - topPad
        val base = topPad + chartH

        val slot = w / n
        val barW = slot * 0.62f
        val rad = minOf(barW / 2f, 6f * d)

        val max = bars.maxOf { it.value }

        canvas.drawLine(0f, topPad, w, topPad, axis)
        canvas.drawLine(0f, topPad + chartH / 2f, w, topPad + chartH / 2f, axis)
        canvas.drawLine(0f, base, w, base, axis)

        for (i in 0 until n) {

            val b = bars[i]
            val cx = slot * i + slot / 2f

            val frac = if (max <= 0L) 0f else b.value.toFloat() / max.toFloat()
            val bh = if (b.value <= 0L) 3f * d else maxOf(chartH * frac, 3f * d)
            val top = base - bh

            if (b.value <= 0L) {
                rect.set(cx - barW / 2f, top, cx + barW / 2f, base)
                canvas.drawRoundRect(rect, rad, rad, none)
            } else {
                val latest = i == n - 1

                if (latest) {
                    glow.color = Ui.withAlpha(Ui.CYAN, 50)
                    rect.set(cx - barW / 2f - 3f * d, top - 3f * d, cx + barW / 2f + 3f * d, base)
                    canvas.drawRoundRect(rect, rad + 3f * d, rad + 3f * d, glow)
                }

                bar.shader = Ui.vgrad(
                    top, base,
                    if (latest) Ui.CYAN else Ui.VIOLET,
                    if (latest) Ui.MINT else Ui.withAlpha(Ui.VIOLET, 90)
                )
                rect.set(cx - barW / 2f, top, cx + barW / 2f, base)
                canvas.drawRoundRect(rect, rad, rad, bar)

                canvas.drawText(format(b.value), cx, top - 3f * d, text)
            }

            canvas.drawText(b.label, cx, h - 4f * d, text)
        }
    }
}

/** Short labels for chart bars. */
object ChartFormat {

    /** 950 -> "950", 1200 -> "1.2k", 25000 -> "25k". */
    fun compact(v: Long): String = when {
        v >= 100_000L -> (v / 1000).toString() + "k"
        v >= 10_000L -> (v / 1000).toString() + "k"
        v >= 1_000L -> (v / 100 / 10.0).toString() + "k"
        else -> v.toString()
    }
}
