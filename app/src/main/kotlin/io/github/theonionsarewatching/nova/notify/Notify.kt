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
import kotlinx.coroutines.launch

object NotificationHelper {

    const val CHANNEL_MESSAGES = "messages"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
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

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.action_mark_read), markReadPi)
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
