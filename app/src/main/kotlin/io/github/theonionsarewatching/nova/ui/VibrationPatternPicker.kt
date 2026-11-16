package io.github.theonionsarewatching.nova.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.AdapterView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.VibrationPattern

/**
 * Themed pattern picker for Settings. Moving the D-pad onto a row, or
 * pressing OK on one, plays a short preview of that buzz.
 */
object VibrationPatternPicker {

    private const val DEFAULT_PREVIEW_MS = 400L

    fun show(
        activity: BaseActivity,
        current: String,
        onPicked: (String) -> Unit
    ) {
        val values = VibrationPattern.VALUES
        val labels = values.map { label(activity, it) }.toTypedArray()
        var selected = values.indexOf(VibrationPattern.normalize(current)).coerceAtLeast(0)

        val dialog = DsmsAlertBuilder(activity)
            .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.vibrate_pattern_title)))
            .setSingleChoiceItems(labels, selected) { d, which ->
                selected = which
                preview(activity, values[which])
                onPicked(values[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { stop(activity) }
        dialog.setOnShowListener {
            val list = dialog.listView ?: return@setOnShowListener
            list.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long
                ) {
                    if (position in values.indices) preview(activity, values[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            })
        }
        dialog.show()
    }

    fun label(context: Context, value: String): String {
        val res = when (VibrationPattern.normalize(value)) {
            VibrationPattern.LONG -> R.string.vibrate_pattern_long
            VibrationPattern.TWO_SHORT -> R.string.vibrate_pattern_two_short
            VibrationPattern.THREE_SHORT -> R.string.vibrate_pattern_three_short
            VibrationPattern.HEARTBEAT -> R.string.vibrate_pattern_heartbeat
            VibrationPattern.PULSE -> R.string.vibrate_pattern_pulse
            VibrationPattern.GENTLE -> R.string.vibrate_pattern_gentle
            VibrationPattern.OFF -> R.string.vibrate_pattern_off
            else -> R.string.vibrate_pattern_default
        }
        return context.getString(res)
    }

    fun preview(context: Context, pattern: String) {
        val v = vibrator(context) ?: return
        stop(context)
        val p = VibrationPattern.normalize(pattern)
        if (p == VibrationPattern.OFF) return
        val wave = VibrationPattern.waveform(p)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = if (wave != null)
                    VibrationEffect.createWaveform(wave, -1)
                else
                    VibrationEffect.createOneShot(
                        DEFAULT_PREVIEW_MS, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(wave ?: longArrayOf(0L, DEFAULT_PREVIEW_MS), -1)
            }
        } catch (_: Exception) {}
    }

    fun stop(context: Context) {
        try { vibrator(context)?.cancel() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? =
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}
