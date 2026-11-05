package com.sanya.assistant

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * My reminders: every reminder with when it will fire, a button to delete it, and + Add.
 *
 * When adding, the "When?" box takes what you would say:
 *   tomorrow at 9      in 2 hours      every monday at 7 pm      12 march
 *
 * Open it from the start screen. By voice you can say: remind me tomorrow at 9 to pay rent.
 */
class RemindersActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var box: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun ldt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

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
            text = "My reminders"
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

        summary = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(summary)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        root.addView(scroll)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(action("+ Add") { add() })
        bottom.addView(action("Delete all") { confirmClear() })
        root.addView(bottom)

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()      // pick up reminders set by voice
    }

    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun render() {

        box.removeAllViews()

        val list = Reminders.all(this)
        val now = LocalDateTime.now()

        if (list.isEmpty()) {
            summary.text = "No reminders"
            box.addView(TextView(this).apply {
                text = "Say: remind me tomorrow at 9 to pay rent.\nOr tap + Add."
                setPadding(0, dp(12), 0, 0)
            })
            return
        }

        val first = list[0]

        summary.text = list.size.toString() + (if (list.size == 1) " reminder" else " reminders") +
            ". Next: " + ReminderParse.whenText(ldt(first.at), first.repeat, now)

        for (r in list) box.addView(row(r, now))
    }

    private fun row(r: Reminders.Reminder, now: LocalDateTime): View {

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        words.addView(TextView(this).apply {
            text = r.text
            textSize = 16f
        })

        words.addView(TextView(this).apply {
            text = cap(ReminderParse.whenText(ldt(r.at), r.repeat, now))
            textSize = 13f
            alpha = 0.7f
        })

        line.addView(words)

        line.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            setOnClickListener { confirmDelete(r) }
        })

        return line
    }

    private fun add() {

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val what = EditText(this).apply {
            hint = "What? (pay rent, call ravi...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val whenBox = EditText(this).apply {
            hint = "When? (tomorrow at 9, in 2 hours, every monday at 7 pm)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        form.addView(what)
        form.addView(whenBox)

        AlertDialog.Builder(this)
            .setTitle("Add a reminder")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->

                val text = what.text.toString().trim()
                val whenText = whenBox.text.toString().trim()

                if (text.isEmpty()) {
                    toast("Please say what to remind you about")
                } else if (Reminders.all(this).size >= 100) {
                    toast("You already have 100 reminders. Delete some first.")
                } else {
                    val spec = ReminderParse.parseWhen(whenText, LocalDateTime.now(), Reminders.defaultTod())
                    if (spec == null) {
                        toast("I can't read that time. Try: tomorrow at 9, in 2 hours, or every monday at 7 pm.")
                    } else {
                        Reminders.add(this, spec, text)
                        Reminders.schedule(this)
                        render()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(r: Reminders.Reminder) {

        AlertDialog.Builder(this)
            .setTitle("Delete this reminder?")
            .setMessage(r.text)
            .setPositiveButton("Delete") { _, _ ->
                Reminders.remove(this, r.id)
                Reminders.schedule(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {

        if (Reminders.all(this).isEmpty()) return toast("There are no reminders")

        AlertDialog.Builder(this)
            .setTitle("Delete all reminders?")
            .setPositiveButton("Delete all") { _, _ ->
                Reminders.clearAll(this)
                Reminders.schedule(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
