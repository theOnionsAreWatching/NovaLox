package io.github.theonionsarewatching.nova.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.MessageEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.ui.ThreadActivity
import io.github.theonionsarewatching.nova.util.Prefs
import kotlinx.coroutines.launch

object NotificationHelper {

    private const val DEFAULT_PREFIX = "messages_"
    private const val CONVO_PREFIX = "msg_"

    /** Effective settings after applying the per-conversation override, if any.
     *  tone: "" = system default sound, "silent" = none, else a sound URI. */
    private fun effectiveTone(context: Context, convo: ConversationEntity?): String {
        val perConvo = convo?.customTone.orEmpty()
        return if (perConvo.isNotBlank()) perConvo
        else Prefs.get(context).defaultTone
    }

    private fun effectiveVibrate(context: Context, convo: ConversationEntity?): Boolean =
        when (convo?.vibrateMode ?: 0) {
            1 -> true
            2 -> false
            else -> Prefs.get(context).shouldVibrate(context)
        }

    private fun soundUri(tone: String): android.net.Uri? = when (tone) {
        "silent" -> null
        "" -> android.media.RingtoneManager.getDefaultUri(
            android.media.RingtoneManager.TYPE_NOTIFICATION)
        else -> android.net.Uri.parse(tone)
    }

    /** Channels are immutable once created, so the id embeds the settings —
     *  changing tone or vibration simply produces a fresh channel. */
    private fun ensureChannel(context: Context, convo: ConversationEntity?): String {
        val tone = effectiveTone(context, convo)
        val vibrate = effectiveVibrate(context, convo)
        val overridden = convo != null &&
            (convo.customTone.isNotBlank() || convo.vibrateMode != 0)
        val key = (tone + "|" + vibrate).hashCode()
        val id = if (overridden) "$CONVO_PREFIX${convo!!.id}_$key" else "$DEFAULT_PREFIX$key"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                val name = if (overridden) convo!!.displayTitle()
                else context.getString(R.string.channel_messages)
                val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(vibrate)
                if (!vibrate) ch.vibrationPattern = null
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ch.setSound(soundUri(tone), attrs)
                nm.createNotificationChannel(ch)
            }
        }
        return id
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) ensureChannel(context, null)
    }

    /** Drop a conversation's channels when its sound settings change. */
    fun refreshConvoChannels(context: Context, convoId: Long) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notificationChannels
            .filter { it.id.startsWith("$CONVO_PREFIX${convoId}_") }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    /** Drop everything when the APP-LEVEL defaults change (per-conversation channels
     *  can embed the app default vibrate/tone, so they're stale too). */
    fun refreshAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notificationChannels
            .filter { it.id.startsWith(DEFAULT_PREFIX) || it.id.startsWith(CONVO_PREFIX) ||
                it.id == "messages" }
            .forEach { nm.deleteNotificationChannel(it.id) }
        ensureChannel(context, null)
    }

    /** Mute ladder: hidden / notifBlocked -> nothing; muted -> silent; else full alert. */
    fun notifyMessage(context: Context, convo: ConversationEntity, msg: MessageEntity) {
        if (convo.hidden || convo.notifBlocked) return
        if (ThreadActivity.visibleConvoId == convo.id) return

        val title = convo.displayTitle()
        val text = if (msg.body.isNotBlank()) msg.body
        else context.getString(if (msg.isMms) R.string.snippet_attachment else R.string.app_name)

        val open = Intent(context, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_CONVO_ID, convo.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(context, convo.id.toInt(), open, flags)

        val markRead = Intent(context, MarkReadReceiver::class.java).putExtra("convo_id", convo.id)
        val markReadPi = PendingIntent.getBroadcast(
            context, (convo.id + 100_000).toInt(), markRead, flags
        )

        val builder = NotificationCompat.Builder(context, ensureChannel(context, convo))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.action_mark_read), markReadPi)
        if (Build.VERSION.SDK_INT < 26) {
            builder.setSound(soundUri(effectiveTone(context, convo)))
            builder.setVibrate(
                if (effectiveVibrate(context, convo)) longArrayOf(0, 250, 150, 250)
                else longArrayOf(0)
            )
        }
        if (convo.muted) builder.setSilent(true)

        try {
            NotificationManagerCompat.from(context).notify(convo.id.toInt(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted yet
        }
    }

    fun cancel(context: Context, convoId: Long) {
        NotificationManagerCompat.from(context).cancel(convoId.toInt())
    }
}

class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val convoId = intent.getLongExtra("convo_id", -1L)
        if (convoId <= 0) return
        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                repo.db.messages().markThreadRead(convoId)
                repo.refreshConversation(convoId)
                io.github.theonionsarewatching.nova.data.ChangeBus.ping()
                NotificationHelper.cancel(context, convoId)
            } finally {
                pending.finish()
            }
        }
    }
}
