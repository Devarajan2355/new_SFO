package com.sanya.assistant

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ui: the look of the whole app (dark, glassy, neon accents) in one place.
 *
 *  - colours and small builders (cards, tiles, buttons, chips) used by the start screen
 *  - skin(activity): re-themes any screen that was built the plain way (buttons, inputs, titles),
 *    and keeps styling views the screen adds later (lists that redraw). SanyaApp calls it for
 *    every screen, so the older screens need no changes.
 *
 * A view whose tag is set (the builders below set "keep") is never restyled by skin().
 */
object Ui {

    val BG_TOP = 0xFF0A0F1F.toInt()
    val BG_BOTTOM = 0xFF1A1038.toInt()
    val TEXT = 0xFFEAF0FF.toInt()
    val SUB = 0xFF9AA6C8.toInt()
    val CYAN = 0xFF18D2FF.toInt()
    val VIOLET = 0xFF7B61FF.toInt()
    val LAVENDER = 0xFFA99BFF.toInt()
    val MINT = 0xFF00E5A8.toInt()
    val AMBER = 0xFFFFA63D.toInt()
    val RED = 0xFFFF5D73.toInt()

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---------------------------------------------------------------
    // Colours (pure maths, unit-tested)
    // ---------------------------------------------------------------

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** [f] = 0 gives [a], 1 gives [b]. */
    fun mix(a: Int, b: Int, f: Float): Int {
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            out = out or ((x + (y - x) * f).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /** True if any colour channel differs by more than [limit]. */
    fun far(a: Int, b: Int, limit: Int = 10): Boolean {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            if (abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) > limit) return true
        }
        return false
    }

    fun vgrad(top: Float, bottom: Float, c1: Int, c2: Int): Shader =
        LinearGradient(0f, top, 0f, bottom, c1, c2, Shader.TileMode.CLAMP)

    // ---------------------------------------------------------------
    // Shapes
    // ---------------------------------------------------------------

    fun dp(ctx: Context, n: Int): Int = (n * ctx.resources.displayMetrics.density).toInt()

    fun background(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))

    fun shape(ctx: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(ctx, 1), stroke)
        }

    private fun ripple(ctx: Context, content: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            content,
            shape(ctx, Color.WHITE, 0, radiusDp)
        )

    fun params(
        ctx: Context, w: Int, h: Int,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0,
        weight: Float = 0f
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
        }

    // ---------------------------------------------------------------
    // Builders (their views are tagged "keep")
    // ---------------------------------------------------------------

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            tag = "keep"
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun gradientText(tv: TextView) {
        val w = tv.paint.measureText(tv.text.toString()).coerceAtLeast(1f)
        tv.paint.shader = LinearGradient(0f, 0f, w, 0f, CYAN, LAVENDER, Shader.TileMode.CLAMP)
        tv.invalidate()
    }

    fun section(ctx: Context, s: String): TextView =
        text(ctx, s.uppercase(), 12f, SUB, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 6))
        }

    fun card(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            tag = "keep"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = shape(ctx, 0x1AFFFFFF, 0x26FFFFFF, 18)
        }

    fun chip(ctx: Context, label: String, color: Int): TextView =
        TextView(ctx).apply {
            tag = "keep"
            textSize = 12f
            setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
            setChip(this, label, color)
        }

    fun setChip(tv: TextView, label: String, color: Int) {
        tv.text = label
        tv.setTextColor(color)
        tv.background = shape(tv.context, withAlpha(color, 40), withAlpha(color, 130), 20)
    }

    fun primary(ctx: Context, label: String, click: () -> Unit): Button =
        Button(ctx).apply {
            tag = "keep"
            text = label
            isAllCaps = false
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(ctx, 16), dp(ctx, 15), dp(ctx, 16), dp(ctx, 15))
            val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET))
                .apply { cornerRadius = dp(ctx, 18).toFloat() }
            background = ripple(ctx, fill, 18)
            setOnClickListener { click() }
        }

    fun ghost(ctx: Context, label: String, click: () -> Unit): Button =
        Button(ctx).apply {
            tag = "keep"
            text = label
            styleButton(this)
            setOnClickListener { click() }
        }

    fun tile(ctx: Context, icon: String, title: String, sub: String, click: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            tag = "keep"
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            background = ripple(ctx, shape(ctx, 0x1AFFFFFF, 0x26FFFFFF, 18), 18)
            addView(text(ctx, icon, 26f))
            addView(text(ctx, title, 15f, TEXT, true).apply { setPadding(0, dp(ctx, 6), 0, 0) })
            addView(text(ctx, sub, 12f, SUB))
            setOnClickListener { click() }
        }

    // ---------------------------------------------------------------
    // Skin: re-theme a screen that was built the plain way
    // ---------------------------------------------------------------

    private val done = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun skin(a: Activity) {

        a.window.statusBarColor = BG_TOP
        a.window.navigationBarColor = BG_BOTTOM
        a.window.setBackgroundDrawable(background())

        if (!done.add(a)) return

        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return

        walk(content)
    }

    private fun walk(v: View) {

        style(v)

        if (v is ViewGroup) {

            // Lists that redraw add new views later: style those too.
            v.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child != null) walk(child)
                }

                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })

            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }

    private fun style(v: View) {

        if (v.tag != null) return

        when (v) {
            is Button -> styleButton(v)
            is EditText -> styleInput(v)
            is TextView -> styleText(v)
        }
    }

    fun styleButton(b: Button) {

        val ctx = b.context

        b.isAllCaps = false
        b.setTextColor(if (b.text.toString() == "✕") RED else TEXT)
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
        b.background = ripple(ctx, shape(ctx, 0x1FFFFFFF, withAlpha(CYAN, 90), 14), 14)

        (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val m = dp(ctx, 3)
            it.setMargins(m, m, m, m)
            b.requestLayout()
        }
    }

    private fun styleInput(e: EditText) {

        val ctx = e.context

        e.setTextColor(TEXT)
        e.setHintTextColor(SUB)
        e.background = shape(ctx, 0x14FFFFFF, 0x33FFFFFF, 12)
        e.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
    }

    private fun styleText(t: TextView) {

        t.setTextColor(TEXT)

        // The 20 sp bold titles at the top of each screen get the gradient.
        val isTitle = t.typeface?.isBold == true &&
            abs(t.textSize - 20f * t.resources.displayMetrics.scaledDensity) < 1f

        if (isTitle && t.paint.shader == null) gradientText(t)
    }
}
