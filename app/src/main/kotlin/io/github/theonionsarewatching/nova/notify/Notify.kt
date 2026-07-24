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
        val key = (tone + "|" + vibrate + "|L2").hashCode()
        val id = if (overridden) "$CONVO_PREFIX${convo!!.id}_$key" else "$DEFAULT_PREFIX$key"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                val name = if (overridden) convo!!.displayTitle()
                else context.getString(R.string.channel_messages)
                val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(vibrate)
                // flip phones with a notification LED: blink for new messages
                ch.enableLights(true)
                ch.lightColor = android.graphics.Color.WHITE
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

    /** Shown from boot when the default-SMS role was lost across a reboot. */
    fun notifyDefaultLost(context: Context) {
        val open = Intent(context, io.github.theonionsarewatching.nova.ui.MainActivity::class.java)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, 997, open, flags)
        val builder = NotificationCompat.Builder(context, ensureChannel(context, null))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.default_lost_title))
            .setContentText(context.getString(R.string.default_lost_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.default_lost_text)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        try {
            NotificationManagerCompat.from(context).notify(1001, builder.build())
        } catch (_: SecurityException) {}
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

        val title = convo.displayTitle()
        val text = when {
            io.github.theonionsarewatching.nova.data.MmsStub.isStub(msg.body) ->
                context.getString(R.string.snippet_mms_pending)
            msg.body.isNotBlank() -> msg.body
            else -> context.getString(if (msg.isMms) R.string.snippet_attachment else R.string.app_name)
        }

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
        builder.setLights(android.graphics.Color.WHITE, 500, 500)
        if (Build.VERSION.SDK_INT < 26) {
            builder.setSound(soundUri(effectiveTone(context, convo)))
            builder.setVibrate(
                if (effectiveVibrate(context, convo)) longArrayOf(0, 250, 150, 250)
                else longArrayOf(0)
            )
        }
        if (convo.muted) builder.setSilent(true)

        val single = io.github.theonionsarewatching.nova.util.Prefs.get(context)
            .notifMode != "per_convo"
        if (single) {
            // one notification for everything: count unread across conversations;
            // tapping opens the only unread thread, or the app when several
            val (convosWithUnread, totalUnread) = kotlinx.coroutines.runBlocking {
                val db = io.github.theonionsarewatching.nova.data.Repo.get(context).db
                db.messages().unreadConvoCount() to db.messages().totalUnread()
            }
            if (totalUnread > 1 || convosWithUnread > 1) {
                builder.setContentTitle(context.getString(R.string.app_name))
                builder.setContentText(
                    context.getString(R.string.new_messages_count, totalUnread)
                )
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.new_messages_count, totalUnread)
                ))
                builder.setNumber(totalUnread)
            }
            if (convosWithUnread > 1) {
                val home = Intent(context, io.github.theonionsarewatching.nova.ui.MainActivity::class.java)
                    .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
                builder.setContentIntent(PendingIntent.getActivity(context, 999, home, flags))
                // mark-all-read replaces the single-convo action
                val markAll = Intent(context, MarkReadReceiver::class.java).putExtra("convo_id", -2L)
                builder.clearActions()
                builder.addAction(0, context.getString(R.string.action_mark_all_read),
                    PendingIntent.getBroadcast(context, 998, markAll, flags))
            }
        }
        val notifId = if (single) 1000 else convo.id.toInt()
        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
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
        if (convoId <= 0 && convoId != -2L) return
        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                if (convoId == -2L) {
                    repo.db.messages().markAllRead()
                    repo.refreshAllConversationSummaries()
                } else {
                repo.db.messages().markThreadRead(convoId)
                repo.refreshConversation(convoId)
                }
                io.github.theonionsarewatching.nova.data.ChangeBus.ping()
                NotificationHelper.cancel(context, convoId)
            } finally {
                pending.finish()
            }
        }
    }
}
