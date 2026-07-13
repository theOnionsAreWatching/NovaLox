package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.BackgroundTasks

/**
 * The one progress dialog for app-wide background tasks (import, re-import,
 * backup, restore). Any screen can attach it; "Run in background" detaches it
 * while the task continues. Reattaching later shows current progress.
 */
object TaskDialogs {

    fun attach(activity: Activity) {
        val bt = BackgroundTasks
        if (!bt.running) return

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
            .apply { max = 100; isIndeterminate = true }
        val detail = TextView(activity).apply {
            textSize = 11f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val pad = (18 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(detail)
        }
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(activity)
            .setTitle(bt.titleRes)
            .setView(box)
            .setCancelable(false)
            .setNeutralButton(R.string.run_in_background) { _, _ ->
                Toast.makeText(activity.applicationContext,
                    R.string.background_warning, Toast.LENGTH_LONG).show()
            }
            .show()
        dialog.setOnDismissListener {
            // whatever dismissed us (background button, done handler, activity
            // teardown): stop listening; the task itself keeps running
            bt.attach(null, null)
        }
        bt.attach(
            update = { p, d ->
                if (p < 0) {
                    bar.isIndeterminate = true
                } else {
                    bar.isIndeterminate = false
                    bar.progress = p
                }
                if (d != null) detail.text = d
            },
            done = { ok ->
                Toast.makeText(activity.applicationContext,
                    if (ok) bt.toastOk else bt.toastFail, Toast.LENGTH_LONG).show()
                runCatching { dialog?.dismiss() }
            }
        )
    }
}
