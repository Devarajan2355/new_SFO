package com.sanya.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * OrbView: Sanya's bubble. A glowing glass sphere whose look follows what she is doing.
 *
 *   IDLE        slow breathing, violet
 *   LISTENING   ripples spreading out, mint
 *   SPEAKING    a waveform ring, cyan
 *   HANDS_FREE  three dots orbiting, amber
 *
 * setting | bubble animation = active (default: moves only while she listens, speaks or is hands-free)
 *                              full   (also breathes while idle, about 15 frames a second)
 *                              off    (no motion)
 *
 * Cheap on purpose: shaders are built once per size, nothing is allocated per frame, and when
 * nothing is moving no further frames are requested at all.
 */
class OrbView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, SPEAKING, HANDS_FREE }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                changedAt = SystemClock.uptimeMillis()
                invalidate()
            }
        }

    /** "active", "full" or "off". */
    var anim: String = "active"
        set(value) {
            field = value
            invalidate()
        }

    private val t0 = SystemClock.uptimeMillis()
    private var changedAt = t0

    private var energy = 0.12f

    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    private val cores = arrayOfNulls<Shader>(4)
    private val glows = arrayOfNulls<Shader>(4)
    private var ring1: SweepGradient? = null
    private var ring2: SweepGradient? = null

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private fun tintOf(m: Mode): Int = when (m) {
        Mode.IDLE -> Ui.VIOLET
        Mode.LISTENING -> Ui.MINT
        Mode.SPEAKING -> Ui.CYAN
        Mode.HANDS_FREE -> Ui.AMBER
    }

    private fun energyOf(m: Mode): Float = when (m) {
        Mode.IDLE -> 0.12f
        Mode.LISTENING -> 0.65f
        Mode.SPEAKING -> 0.9f
        Mode.HANDS_FREE -> 0.3f
    }

    /** A small press effect, called from the bubble's touch handler. */
    fun press(down: Boolean) {
        val s = if (down) 0.9f else 1f
        animate().scaleX(s).scaleY(s).setDuration(110).start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = minOf(w, h) / 2f * 0.56f

        val clear = 0x00FFFFFF
        ring1 = SweepGradient(
            cx, cy,
            intArrayOf(clear, 0xF2FFFFFF.toInt(), clear, clear),
            floatArrayOf(0f, 0.16f, 0.34f, 1f)
        )
        ring2 = SweepGradient(
            cx, cy,
            intArrayOf(clear, 0xB3FFFFFF.toInt(), clear, clear),
            floatArrayOf(0f, 0.10f, 0.22f, 1f)
        )

        for (i in 0 until 4) {
            cores[i] = null
            glows[i] = null
        }
    }

    override fun onDraw(canvas: Canvas) {

        if (r <= 0f) return

        val now = SystemClock.uptimeMillis()
        val m = mode
        val idx = m.ordinal
        val tint = tintOf(m)

        val live = when (anim) {
            "off" -> false
            "full" -> true
            else -> m != Mode.IDLE
        }

        val tt = if (live) (now - t0) / 1000f else 0f

        val target = energyOf(m)
        if (live) energy += (target - energy) * 0.12f else energy = target
        val easing = live && abs(energy - target) > 0.02f

        val breathe = if (live) 1f + 0.035f * sin(tt * 1.7f) else 1f

        canvas.save()
        canvas.scale(breathe, breathe, cx, cy)

        // ---- glow: one gradient, built once ----
        val g = glows[idx] ?: RadialGradient(
            cx, cy, r * 1.75f,
            intArrayOf(Ui.withAlpha(tint, 120), Ui.withAlpha(tint, 0)),
            null,
            Shader.TileMode.CLAMP
        ).also { glows[idx] = it }

        glow.shader = g
        glow.alpha = (255f * (0.45f + 0.55f * energy)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r * 1.75f, glow)

        // ---- the sphere ----
        val c = cores[idx] ?: RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.6f,
            intArrayOf(Ui.mix(tint, Color.WHITE, 0.55f), tint, Ui.mix(tint, Color.BLACK, 0.55f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        ).also { cores[idx] = it }

        core.shader = c
        canvas.drawCircle(cx, cy, r, core)

        // a thin glass rim
        line.shader = null
        line.color = Ui.withAlpha(Color.WHITE, 80)
        line.strokeWidth = r * 0.03f
        canvas.drawCircle(cx, cy, r, line)

        // ---- rotating light rings ----
        val spin = tt * (60f + 240f * energy)

        arc.alpha = (110f + 145f * energy).toInt().coerceIn(0, 255)

        arc.shader = ring1
        arc.strokeWidth = r * 0.10f
        canvas.save()
        canvas.rotate(spin, cx, cy)
        canvas.drawCircle(cx, cy, r * 1.14f, arc)
        canvas.restore()

        arc.shader = ring2
        arc.strokeWidth = r * 0.05f
        canvas.save()
        canvas.rotate(90f - spin * 0.6f, cx, cy)
        canvas.drawCircle(cx, cy, r * 1.28f, arc)
        canvas.restore()

        // ---- highlight ----
        fill.color = Ui.withAlpha(Color.WHITE, 70)
        canvas.save()
        canvas.rotate(-25f, cx - r * 0.28f, cy - r * 0.6f)
        rect.set(cx - r * 0.62f, cy - r * 0.8f, cx + r * 0.06f, cy - r * 0.4f)
        canvas.drawOval(rect, fill)
        canvas.restore()

        // ---- the voice bars in the middle ----
        fill.color = Ui.withAlpha(Color.WHITE, 235)
        val bw = r * 0.14f
        val gap = r * 0.12f
        for (i in -1..1) {
            val h = r * (0.22f + 0.55f * energy * (0.5f + 0.5f * sin(tt * (5.5f + i) + i * 1.7f)))
            val x = cx + i * (bw + gap)
            rect.set(x - bw / 2f, cy - h / 2f, x + bw / 2f, cy + h / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, fill)
        }

        // ---- what each state adds ----
        when (m) {

            Mode.LISTENING -> {
                if (live) {
                    for (k in 0 until 3) {
                        val p = (tt * 0.8f + k / 3f) % 1f
                        line.color = Ui.withAlpha(tint, ((1f - p) * 170f).toInt())
                        line.strokeWidth = r * 0.06f * (1f - p) + 1f
                        canvas.drawCircle(cx, cy, r * (1.1f + p * 0.85f), line)
                    }
                }
            }

            Mode.SPEAKING -> {
                path.reset()
                val n = 60
                for (i in 0..n) {
                    val a = i.toFloat() / n * 6.2831855f
                    val w = sin(a * 5f + tt * 7f) * 0.5f + sin(a * 3f - tt * 5f) * 0.5f
                    val rad = r * 1.26f + r * 0.17f * energy * w
                    val x = cx + cos(a) * rad
                    val y = cy + sin(a) * rad
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                line.color = Ui.withAlpha(Ui.mix(tint, Color.WHITE, 0.4f), 200)
                line.strokeWidth = r * 0.07f
                canvas.drawPath(path, line)
            }

            Mode.HANDS_FREE -> {
                line.color = Ui.withAlpha(tint, 90)
                line.strokeWidth = r * 0.025f
                canvas.drawCircle(cx, cy, r * 1.42f, line)

                fill.color = Ui.withAlpha(Ui.mix(tint, Color.WHITE, 0.5f), 230)
                for (k in 0 until 3) {
                    val a = tt * 1.3f + k * 2.0943952f
                    canvas.drawCircle(cx + cos(a) * r * 1.42f, cy + sin(a) * r * 1.42f, r * 0.085f, fill)
                }
            }

            Mode.IDLE -> {}
        }

        // ---- a flash when the state changes ----
        val p = (now - changedAt) / 550f
        if (p < 1f) {
            line.color = Ui.withAlpha(Ui.mix(tint, Color.WHITE, 0.5f), ((1f - p) * 200f).toInt())
            line.strokeWidth = r * 0.08f * (1f - p) + 1f
            canvas.drawCircle(cx, cy, r * (1f + p * 0.9f), line)
        }

        canvas.restore()

        // ---- next frame: only if something is moving, and never faster than needed ----
        if (live || easing || p < 1f) {
            val gap = if (m == Mode.IDLE && p >= 1f && !easing) 66L else 33L
            postInvalidateDelayed(gap)
        }
    }
}
