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
            .apply {
                max = 100; isIndeterminate = true
                // accent-colored bar, like the rest of the pop-ups
                val accent = android.content.res.ColorStateList.valueOf(ThemeUtils.accentColor(activity))
                progressTintList = accent
                indeterminateTintList = accent
            }
        // fixed height: ONE line, always reserved (even before the
        // first update), never wraps; long file names / phase texts are cut
        // in the middle ("Voice_1030…_2.m4a", "Rebuilding con…12 of 340") so
        // the start and the counter stay visible. The pop-up no longer
        // resizes, so the centered title/underline no longer jump.
        val detail = TextView(activity).apply {
            textSize = 11f
            isSingleLine = true
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            text = " "
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            // centered under the bar ("Message N of M")
            gravity = android.view.Gravity.CENTER
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        }
        val dm = activity.resources.displayMetrics
        val pad = (16 * dm.density).toInt()
        var dialog: AlertDialog? = null
        // our own centered button (the dialog's neutral button sits at the
        // start of the button row, which made the pop-up look lopsided)
        // two full-width rows (Run in background, Cancel): the old single
        // centered button was cut off on 240px screens; rows may wrap to
        // two lines at large font sizes instead of clipping
        fun row(label: String) = TextView(activity).apply {
            text = label
            textSize = 14f
            maxLines = 2
            gravity = android.view.Gravity.CENTER
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            isFocusable = true
            minHeight = (36 * dm.density).toInt()
            setPadding(pad, (6 * dm.density).toInt(), pad, (6 * dm.density).toInt())
            ThemeUtils.applyDialogRowFocus(this)
            background = ThemeUtils.dialogRowBackground(activity, insetHDp = 0, insetVDp = 0)
        }
        val bgButton = row(activity.getString(R.string.run_in_background)).apply {
            setOnClickListener {
                runCatching { dialog?.dismiss() }
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity.applicationContext,
                    R.string.background_warning, Toast.LENGTH_LONG).show()
            }
        }
        // Cancel: the task stops at its next safe point (backup: the partial
        // file is deleted; restore: what was written is kept)
        val cancelButton = row(activity.getString(
            if (bt.cancelRequested) R.string.task_stopping else R.string.task_cancel)).apply {
            setTextColor(ThemeUtils.accentColor(activity))
            setOnClickListener {
                if (bt.cancelRequested) return@setOnClickListener
                if (bt.cancel()) {
                    text = activity.getString(R.string.task_stopping)
                    bgButton.requestFocus()
                } else {
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity.applicationContext,
                        R.string.task_cant_stop, Toast.LENGTH_SHORT).show()
                }
            }
        }
        // equal padding on every side; the bar spans the same width as the
        // text, so its left and right margins are equal
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 4 })
            addView(bgButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
            // import / re-import can't stop safely: no Cancel row there
            if (bt.cancellable || bt.cancelRequested) addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog = DsmsAlertBuilder(activity)
            // centered title with the accent underline (Dialogs.title)
            .setCustomTitle(Dialogs.title(activity,
                if (bt.titleRes != 0) activity.getString(bt.titleRes) else ""))
            .setView(box)
            .setCancelable(false)
            .create()
        // one fixed middle width and an explicit centered
        // place, set BEFORE the first frame, so the pop-up opens in the
        // middle of the screen at its final size and stays there while
        // the counter text changes
        val shown = dialog!!
        Dialogs.narrowToLabels(shown, arrayOf(
            if (bt.titleRes != 0) activity.getString(bt.titleRes) else "",
            activity.getString(R.string.run_in_background),
            activity.getString(R.string.task_cancel)))
        shown.window?.setGravity(android.view.Gravity.CENTER)
        shown.show()
        bgButton.requestFocus()
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
                // newlines would defeat the single line: flatten them
                if (d != null) detail.text = d.replace('\n', ' ').ifEmpty { " " }
            },
            done = { ok ->
                runCatching { dialog?.dismiss() }
                val msg = bt.resultMessage
                if (bt.wasCancelled) {
                    // Cancel: a short toast, no pop-up to dismiss
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity.applicationContext,
                        msg ?: activity.getString(R.string.backup_cancelled), Toast.LENGTH_LONG).show()
                    return@attach
                }
                val rebuild = bt.settingsApplied
                val res = if (ok) bt.toastOk else bt.toastFail
                if ((msg != null || rebuild || !ok) && !activity.isFinishing) {
                    // a popup the user dismisses with OK: the message may name a
                    // file path or a reason, too long for a toast on a 240px screen
                    val text = msg ?: if (res != 0) activity.getString(res) else ""
                    val shown = if (rebuild) text + (if (text.isNotEmpty()) "\n\n" else "") +
                        activity.getString(R.string.settings_restored) else text
                    runCatching {
                        DsmsAlertBuilder(activity)
                            .setMessage(shown)
                            .setPositiveButton(android.R.string.ok, null)
                            .setOnDismissListener {
                                if (rebuild && !activity.isFinishing) activity.recreate()
                            }
                            .show()
                    }.onFailure {
                        if (rebuild) runCatching { activity.recreate() }
                    }
                } else if (res != 0) {
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity.applicationContext, res, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}
