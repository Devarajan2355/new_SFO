package com.sanya.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Focus Guard: what is blocked, what is limited, and the switches.
 *
 *   - whether the Accessibility service is on (the guard does nothing without it)
 *   - Guard, Shorts blocker and Adult filter switches. Switching ON is instant. Switching OFF starts the 15 minute
 *     wait: tap the same button again after it (the screen uses the same rules as the voice commands)
 *   - blocks per day for the last 14 days
 *   - today's limits with how much of each is used, and X to remove one (also waits 15 minutes)
 *   - the apps you used most today
 *   - Urge mode button: locks browsers, YouTube and limited apps for 10 minutes (it cannot be cut short)
 *   - the words and sites you blocked yourself, with X to unblock (also waits 15 minutes)
 *   - + Limit and + Word to add one by hand
 *
 * Open it by voice: "show my focus guard".
 */
class GuardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var serviceBtn: Button
    private lateinit var masterBtn: Button
    private lateinit var shortsBtn: Button
    private lateinit var wordsBtn: Button
    private lateinit var urgeBtn: Button
    private lateinit var summary: TextView
    private lateinit var pendingBox: LinearLayout
    private lateinit var pendingLine: TextView
    private lateinit var chartTitle: TextView
    private lateinit var chart: ValueChartView
    private lateinit var box: LinearLayout

    // Redraws every few seconds so the usage and the waiting time move.
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5000L)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.setup(this)

        val pad = dp(12)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Focus Guard"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(top)

        serviceBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener { openAccessibility() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(serviceBtn)

        val switches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        masterBtn = action("") { toggleMaster() }
        shortsBtn = action("") { toggleShorts() }
        switches.addView(masterBtn)
        switches.addView(shortsBtn)
        root.addView(switches)

        wordsBtn = action("") { toggleWords() }
        urgeBtn = action("") { startUrge() }
        val switches2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        switches2.addView(wordsBtn)
        switches2.addView(urgeBtn)
        root.addView(switches2)

        summary = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(summary)

        pendingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        pendingLine = TextView(this).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pendingBox.addView(pendingLine)
        pendingBox.addView(Button(this).apply {
            text = "Cancel request"
            isAllCaps = false
            setOnClickListener {
                toast(Guard.run(this@GuardActivity, GuardParse.Cmd.CancelRequest))
                render()
            }
        })
        root.addView(pendingBox)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        chartTitle = TextView(this).apply {
            text = "Blocks per day, last 14 days"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(10), 0, 0)
        }
        inner.addView(chartTitle)

        chart = ValueChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        inner.addView(chart)

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(box)

        scroll.addView(inner)
        root.addView(scroll)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(action("+ Limit") { addLimit() })
        bottom.addView(action("+ Word") { addWord() })
        bottom.addView(action("Report") { toast(GuardCare.run(this, GuardParse.Cmd.SendReport)) })
        root.addView(bottom)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)          // draws now, then every 5 seconds
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    private fun render() {

        val on = Guard.enabled(this)
        val shorts = Guard.shortsOn(this)
        val service = Guard.serviceEnabled(this)

        serviceBtn.text =
            if (service) "Blocker service: ON (tap to open Accessibility)"
            else "Blocker service is OFF. Tap, find Sanya Focus Guard, switch it on"

        masterBtn.text = if (on) "Guard: ON" else "Guard: OFF"
        shortsBtn.text = if (shorts) "Shorts blocker: ON" else "Shorts blocker: OFF"
        wordsBtn.text = if (Guard.wordsOn(this)) "Adult filter: ON" else "Adult filter: OFF"

        val urge = Guard.urgeLeftMs(this)
        urgeBtn.text =
            if (urge > 0L) "Urge mode: " + Num.short(((urge + 59_999L) / 60_000L).toInt()) + " left (tap to add time)"
            else "I have an urge"

        val n = Guard.blocksToday(this)
        val u = Guard.urgesToday(this)
        summary.text =
            (if (n == 0) "No blocks today" else if (n == 1) "1 block today" else "$n blocks today") +
                (if (u > 0) "   |   $u " + (if (u == 1) "urge" else "urges") + " handled" else "")

        val pending = Guard.pendingText(this)

        if (pending == null) {
            pendingBox.visibility = View.GONE
        } else {
            pendingLine.text = pending + " Tap the same switch again, or say it again, to confirm."
            pendingBox.visibility = View.VISIBLE
        }

        chart.bars = Guard.blockBars(this)

        box.removeAllViews()

        // ---- limits ----
        box.addView(section("TODAY'S LIMITS"))

        val limits = Guard.limits(this)

        if (limits.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "No limits yet. Say: limit youtube to 1 hour. Or tap + Limit."
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        val installed = Guard.apps(this)

        for (l in limits) {
            val pkg = installed.firstOrNull { it.label == l.label }?.pkg
            val used = if (pkg == null) 0L else Guard.usedMs(this, pkg)
            box.addView(limitRow(l, used))
        }

        // ---- your blocked words ----
        val mine = Guard.userWords(this)

        box.addView(section("YOUR BLOCKED WORDS AND SITES"))

        if (mine.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "None yet. A built-in list is always active. Say: block the word hentai. Or tap + Word."
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        for (e in mine) box.addView(wordRow(e))

        // ---- top apps ----
        val top = Guard.topApps(this, 5)

        if (top.isNotEmpty()) {
            box.addView(section("USED MOST TODAY"))
            for (t in top) {
                box.addView(TextView(this).apply {
                    text = t.first.replaceFirstChar { it.uppercase() } + "   " + Num.short((t.second / 60_000L).toInt())
                    textSize = 14f
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
        }

        box.addView(TextView(this).apply {
            text = "Say: block shorts, limit youtube to 1 hour, block the word hentai, urge mode, " +
                "focus guard status. Anything that loosens a rule waits " + GuardRules.DELAY_MIN + " minutes. " +
                "In browsers I read only the address bar, page title and search boxes, and store nothing."
            textSize = 12f
            alpha = 0.6f
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        alpha = 0.6f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun wordRow(entry: String): View {

        val site = entry.startsWith("s:")
        val name = entry.substring(2)

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        line.addView(TextView(this).apply {
            text = (if (site) "site   " else "word   ") + name
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        line.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener {
                toast(Guard.run(this@GuardActivity, GuardParse.Cmd.UnblockWord(name, site)))
                render()
            }
        })

        return line
    }

    private fun limitRow(l: Guard.Limit, usedMs: Long): View {

        val capMs = l.minutes * 60_000L
        val usedMin = (usedMs / 60_000L).toInt()
        val pct = if (capMs <= 0L) 0 else (usedMs * 100 / capMs).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        head.addView(TextView(this).apply {
            text = l.label.replaceFirstChar { it.uppercase() } + "   " + Num.short(usedMin) + " of " +
                Num.short(l.minutes) + "   (" + pct + "%)"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        head.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener {
                toast(Guard.run(this@GuardActivity, GuardParse.Cmd.RemoveLimit(l.label)))
                render()
            }
        })

        col.addView(head)

        val fill = pct.coerceIn(1, 100)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        }
        bar.addView(View(this).apply {
            setBackgroundColor(if (pct >= 100) Ui.RED else if (pct >= 80) Ui.AMBER else Ui.CYAN)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fill.toFloat())
        })
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - fill).toFloat())
        })
        col.addView(bar)

        return col
    }

    // ---------------------------------------------------------------
    // Actions (all go through Guard, so the 15 minute wait always applies)
    // ---------------------------------------------------------------

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            ErrorLog.add(this, "Accessibility settings", e)
            toast("That settings page did not open. Look for Accessibility in your phone's settings.")
        }
    }

    private fun toggleMaster() {
        toast(Guard.run(this, GuardParse.Cmd.Master(!Guard.enabled(this))))
        render()
    }

    private fun toggleShorts() {
        toast(Guard.run(this, GuardParse.Cmd.Shorts(!Guard.shortsOn(this))))
        render()
    }

    private fun toggleWords() {
        toast(Guard.run(this, GuardParse.Cmd.WordFilter(!Guard.wordsOn(this))))
        render()
    }

    private fun startUrge() {
        toast(Guard.run(this, GuardParse.Cmd.Urge))
        render()
    }

    private fun addWord() {

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val term = EditText(this).apply {
            hint = "Word, phrase or site name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val site = CheckBox(this).apply {
            text = "This is a site name (5 letters or more, like examplesite)"
        }

        form.addView(term)
        form.addView(site)

        AlertDialog.Builder(this)
            .setTitle("Block in browsers")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val text = term.text.toString().trim()

                if (text.isEmpty()) {
                    toast("Please enter a word or site")
                } else {
                    toast(Guard.run(this, GuardParse.Cmd.BlockWord(text, site.isChecked)))
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addLimit() {

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val app = EditText(this).apply {
            hint = "App (youtube, chrome, crunchyroll...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val length = EditText(this).apply {
            hint = "Daily limit (1 hour, 45 minutes, 1h30m)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        form.addView(app)
        form.addView(length)

        AlertDialog.Builder(this)
            .setTitle("Add a daily limit")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val name = app.text.toString().trim()
                val text = length.text.toString().trim()
                val minutes = Num.parseDuration(text) ?: text.toIntOrNull()?.takeIf { it in 1..1440 }

                if (name.isEmpty()) {
                    toast("Please enter an app name")
                } else if (minutes == null) {
                    toast("I couldn't read that length. Try 45 minutes or 1h30m.")
                } else {
                    toast(Guard.run(this, GuardParse.Cmd.SetLimit(name, minutes)))
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
