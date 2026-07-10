package io.github.theonionsarewatching.nova.ui

import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.Repo
import kotlinx.coroutines.launch

/**
 * Notification tone picker. Our own dialog (D-pad friendly, works on ROMs without
 * a system ringtone picker) listing the phone's notification sounds; moving the
 * selection plays a short preview. Sounds dropped into the device's Notifications
 * folder appear here too, so sideloaded custom tones are supported.
 *
 * Values: "" = the default (system default at app level, app default at
 * conversation level), "silent" = no sound, anything else = a sound URI.
 */
object TonePicker {

    fun pick(
        activity: BaseActivity,
        current: String,
        defaultLabelRes: Int,
        onPicked: (String) -> Unit
    ) {
        val labels = ArrayList<String>()
        val values = ArrayList<String>()
        labels.add(activity.getString(defaultLabelRes)); values.add("")
        labels.add(activity.getString(R.string.tone_silent)); values.add("silent")

        try {
            val rm = RingtoneManager(activity)
            rm.setType(RingtoneManager.TYPE_NOTIFICATION)
            val cursor = rm.cursor
            while (cursor.moveToNext()) {
                val pos = cursor.position
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                val uri = rm.getRingtoneUri(pos) ?: continue
                labels.add(title)
                values.add(uri.toString())
            }
        } catch (_: Exception) {
            // tones not enumerable on this device; Default and Silent still offered
        }

        var selected = values.indexOf(current).coerceAtLeast(0)
        var preview: Ringtone? = null
        fun stopPreview() {
            try { preview?.stop() } catch (_: Exception) {}
            preview = null
        }

        val dialog = AlertDialog.Builder(activity)
            .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.tone_title)))
            .setSingleChoiceItems(labels.toTypedArray(), selected) { _, which ->
                selected = which
                stopPreview()
                val v = values[which]
                if (v.isNotBlank() && v != "silent") {
                    try {
                        preview = RingtoneManager.getRingtone(
                            activity, android.net.Uri.parse(v)
                        )?.also { r ->
                            r.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            r.play()
                        }
                    } catch (_: Exception) {}
                }
            }
            .setPositiveButton(R.string.save) { _, _ -> onPicked(values.getOrElse(selected) { "" }) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { stopPreview() }
        dialog.show()
    }

    /** Human name for a stored tone value, for menu rows and summaries. */
    fun toneName(activity: BaseActivity, value: String, defaultLabelRes: Int): String = when {
        value.isBlank() -> activity.getString(defaultLabelRes)
        value == "silent" -> activity.getString(R.string.tone_silent)
        else -> try {
            RingtoneManager.getRingtone(activity, android.net.Uri.parse(value))
                ?.getTitle(activity) ?: activity.getString(defaultLabelRes)
        } catch (_: Exception) {
            activity.getString(defaultLabelRes)
        }
    }
}

/**
 * Per-conversation Sound & vibration dialog: a two-row menu showing the current
 * tone and vibration choice, each opening its own picker. "Vibrate only" is
 * simply tone = Silent + vibration = On.
 */
object SoundDialog {

    fun show(activity: BaseActivity, convoId: Long) {
        activity.lifecycleScope.launch {
            val repo = Repo.get(activity)
            val c = repo.db.conversations().byId(convoId) ?: return@launch
            val toneRow = activity.getString(
                R.string.tone_row,
                TonePicker.toneName(activity, c.customTone, R.string.tone_app_default)
            )
            val vibRow = activity.getString(
                R.string.vibration_row,
                activity.getString(
                    when (c.vibrateMode) {
                        1 -> R.string.vib_on
                        2 -> R.string.vib_off
                        else -> R.string.tone_app_default
                    }
                )
            )
            AlertDialog.Builder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.sound_and_vibration_title)))
                .setItems(arrayOf(toneRow, vibRow)) { _, which ->
                    when (which) {
                        0 -> TonePicker.pick(activity, c.customTone, R.string.tone_app_default) { tone ->
                            activity.lifecycleScope.launch {
                                repo.setConversationTone(convoId, tone)
                                Toast.makeText(activity, R.string.tone_saved, Toast.LENGTH_SHORT).show()
                            }
                        }
                        1 -> {
                            val opts = arrayOf(
                                activity.getString(R.string.tone_app_default),
                                activity.getString(R.string.vib_on),
                                activity.getString(R.string.vib_off)
                            )
                            AlertDialog.Builder(activity)
                                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.vibration_title)))
                                .setSingleChoiceItems(opts, c.vibrateMode) { d, mode ->
                                    activity.lifecycleScope.launch {
                                        repo.setConversationVibrate(convoId, mode)
                                        Toast.makeText(activity, R.string.tone_saved, Toast.LENGTH_SHORT).show()
                                    }
                                    d.dismiss()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
