package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.theonionsarewatching.nova.R
import java.util.Calendar

/**
 * Schedule chooser built for D-pad phones, per spec:
 *  1) Today / Change date.
 *  2) Today -> time dialog: up/down moves between rows, LEFT/RIGHT adjusts the
 *     focused row (Hour, Minute; AM/PM row only on 12-hour phones).
 *  3) Change date -> date dialog: Year / Month / Day rows the same way, then
 *     the time dialog.
 * Rows are full-width lines inside a ScrollView — nothing clips on narrow
 * screens, and there are no spinner wheels to trap focus.
 */
object ScheduleTimePicker {

    fun show(activity: Activity, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.schedule_send)
            .setItems(
                arrayOf(
                    activity.getString(R.string.sched_today),
                    activity.getString(R.string.sched_change_date)
                )
            ) { _, which ->
                when (which) {
                    0 -> timeDialog(activity, cal, onPicked)
                    1 -> dateDialog(activity, cal) { timeDialog(activity, cal, onPicked) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A focusable row: [label ......... < value > ]; LEFT/RIGHT adjusts value. */
    private fun adjustRow(
        activity: Activity,
        label: String,
        value: () -> String,
        onLeft: () -> Unit,
        onRight: () -> Unit,
        refreshAll: () -> Unit
    ): Pair<View, () -> Unit> {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val left = TextView(activity).apply { text = "\u2039"; textSize = 20f; setPadding(dp(10), 0, dp(10), 0) }
        val valueView = TextView(activity).apply {
            textSize = 16f
            minWidth = dp(64)
            gravity = Gravity.CENTER
        }
        val right = TextView(activity).apply { text = "\u203A"; textSize = 20f; setPadding(dp(10), 0, dp(10), 0) }
        row.addView(left); row.addView(valueView); row.addView(right)

        ThemeUtils.applyFocusHighlight(row)
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft(); refreshAll(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onRight(); refreshAll(); true }
                else -> false
            }
        }
        left.setOnClickListener { onLeft(); refreshAll() }
        right.setOnClickListener { onRight(); refreshAll() }
        val refresh = { valueView.text = value() }
        return row to refresh
    }

    private fun buildDialog(
        activity: Activity,
        title: Int,
        rows: List<Pair<View, () -> Unit>>,
        summary: (() -> String)?,
        positiveLabel: Int,
        onPositive: () -> Unit
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        var summaryView: TextView? = null
        if (summary != null) {
            summaryView = TextView(activity).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            }
            column.addView(summaryView)
        }
        for ((view, refresh) in rows) {
            column.addView(view)
            refresh()
        }
        summaryView?.text = summary?.invoke() ?: ""
        val scroll = ScrollView(activity).apply { addView(column) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(positiveLabel) { _, _ -> onPositive() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun timeDialog(activity: Activity, cal: Calendar, onPicked: (Long) -> Unit) {
        val is24 = DateFormat.is24HourFormat(activity)
        val fmtTime = DateFormat.getTimeFormat(activity)
        val fmtDate = DateFormat.getMediumDateFormat(activity)

        val rows = ArrayList<Pair<View, () -> Unit>>()
        lateinit var summaryRefresh: () -> Unit
        val refreshAll: () -> Unit = { rows.forEach { it.second() }; summaryRefresh() }

        rows += adjustRow(activity, activity.getString(R.string.sched_hour),
            value = {
                if (is24) "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
                else { val h = cal.get(Calendar.HOUR); (if (h == 0) 12 else h).toString() }
            },
            onLeft = { cal.add(Calendar.HOUR_OF_DAY, -1) },
            onRight = { cal.add(Calendar.HOUR_OF_DAY, 1) },
            refreshAll = { refreshAll() })

        rows += adjustRow(activity, activity.getString(R.string.sched_min),
            value = { "%02d".format(cal.get(Calendar.MINUTE)) },
            onLeft = { cal.add(Calendar.MINUTE, -1) },
            onRight = { cal.add(Calendar.MINUTE, 1) },
            refreshAll = { refreshAll() })

        if (!is24) {
            rows += adjustRow(activity, activity.getString(R.string.sched_ampm),
                value = {
                    if (cal.get(Calendar.AM_PM) == Calendar.AM) activity.getString(R.string.sched_am)
                    else activity.getString(R.string.sched_pm)
                },
                onLeft = { cal.add(Calendar.HOUR_OF_DAY, 12) },
                onRight = { cal.add(Calendar.HOUR_OF_DAY, 12) },
                refreshAll = { refreshAll() })
        }

        val summaryFn: () -> String = {
            activity.getString(R.string.schedule_summary_fmt, fmtDate.format(cal.time), fmtTime.format(cal.time))
        }
        summaryRefresh = {}  // summary text set in buildDialog

        buildDialog(activity, R.string.schedule_send, rows, summaryFn, R.string.schedule_confirm) {
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                android.widget.Toast.makeText(activity, R.string.schedule_in_past, android.widget.Toast.LENGTH_SHORT).show()
            } else onPicked(cal.timeInMillis)
        }
    }

    private fun dateDialog(activity: Activity, cal: Calendar, then: () -> Unit) {
        val fmtDate = DateFormat.getLongDateFormat(activity)
        val rows = ArrayList<Pair<View, () -> Unit>>()
        val refreshAll: () -> Unit = { rows.forEach { it.second() } }

        fun clampDay() {
            val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            if (cal.get(Calendar.DAY_OF_MONTH) > max) cal.set(Calendar.DAY_OF_MONTH, max)
        }

        rows += adjustRow(activity, activity.getString(R.string.sched_year),
            value = { cal.get(Calendar.YEAR).toString() },
            onLeft = { cal.add(Calendar.YEAR, -1); clampDay() },
            onRight = { cal.add(Calendar.YEAR, 1); clampDay() },
            refreshAll = { refreshAll() })

        rows += adjustRow(activity, activity.getString(R.string.sched_month),
            value = { java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(cal.time) },
            onLeft = { cal.add(Calendar.MONTH, -1); clampDay() },
            onRight = { cal.add(Calendar.MONTH, 1); clampDay() },
            refreshAll = { refreshAll() })

        rows += adjustRow(activity, activity.getString(R.string.sched_day),
            value = { cal.get(Calendar.DAY_OF_MONTH).toString() },
            onLeft = { cal.add(Calendar.DAY_OF_YEAR, -1) },
            onRight = { cal.add(Calendar.DAY_OF_YEAR, 1) },
            refreshAll = { refreshAll() })

        buildDialog(activity, R.string.sched_change_date, rows, { fmtDate.format(cal.time) }, R.string.next) {
            then()
        }
    }
}
