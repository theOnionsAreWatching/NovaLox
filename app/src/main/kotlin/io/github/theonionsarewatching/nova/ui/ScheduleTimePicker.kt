package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.theonionsarewatching.nova.R
import java.util.Calendar

/**
 * A schedule-time chooser built for D-pad phones. The native DatePicker/
 * TimePicker spinner wheels trap focus (up/down spins the wheel, you can't reach
 * OK) and clip on narrow screens. This uses focusable quick presets plus simple
 * +/- stepper rows, every control reachable by left/right/up/down, and a real
 * OK button that always takes focus.
 */
object ScheduleTimePicker {

    fun show(activity: Activity, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val summary = TextView(activity).apply {
            textSize = 16f
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(summary)

        fun refresh() {
            summary.text = activity.getString(
                R.string.schedule_summary_fmt,
                android.text.format.DateFormat.getLongDateFormat(activity).format(cal.time),
                android.text.format.DateFormat.getTimeFormat(activity).format(cal.time)
            )
        }

        // ---- quick presets ----
        val presets = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        data class Preset(val labelRes: Int, val apply: (Calendar) -> Unit)
        val list = listOf(
            Preset(R.string.sched_1h) { it.add(Calendar.HOUR_OF_DAY, 1) },
            Preset(R.string.sched_3h) { it.add(Calendar.HOUR_OF_DAY, 3) },
            Preset(R.string.sched_tomorrow9) {
                it.add(Calendar.DAY_OF_YEAR, 1)
                it.set(Calendar.HOUR_OF_DAY, 9); it.set(Calendar.MINUTE, 0)
            }
        )
        for (pr in list) {
            presets.addView(Button(activity).apply {
                text = activity.getString(pr.labelRes)
                isAllCaps = false
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener {
                    val now = Calendar.getInstance()
                    cal.timeInMillis = now.timeInMillis
                    cal.set(Calendar.SECOND, 0)
                    pr.apply(cal)
                    refresh()
                }
            })
        }
        root.addView(presets)

        // ---- stepper rows: each field with focusable - / + ----
        fun stepperRow(labelRes: Int, minus: () -> Unit, plus: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(2))
            }
            row.addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val mk = { txt: String, act: () -> Unit ->
                Button(activity).apply {
                    text = txt
                    isAllCaps = false
                    minWidth = dp(52)
                    setOnClickListener { act(); refresh() }
                }
            }
            row.addView(mk("\u2212") { minus() })   // minus sign
            row.addView(mk("+") { plus() })
            root.addView(row)
        }

        stepperRow(R.string.sched_day,
            { cal.add(Calendar.DAY_OF_YEAR, -1) }, { cal.add(Calendar.DAY_OF_YEAR, 1) })
        stepperRow(R.string.sched_hour,
            { cal.add(Calendar.HOUR_OF_DAY, -1) }, { cal.add(Calendar.HOUR_OF_DAY, 1) })
        stepperRow(R.string.sched_min,
            { cal.add(Calendar.MINUTE, -5) }, { cal.add(Calendar.MINUTE, 5) })

        refresh()

        AlertDialog.Builder(activity)
            .setTitle(R.string.schedule_send)
            .setView(root)
            .setPositiveButton(R.string.schedule_confirm) { _, _ ->
                val at = cal.timeInMillis
                if (at <= System.currentTimeMillis()) {
                    android.widget.Toast.makeText(
                        activity, R.string.schedule_in_past, android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onPicked(at)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
