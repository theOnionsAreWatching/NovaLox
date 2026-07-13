package io.github.theonionsarewatching.nova.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import io.github.theonionsarewatching.nova.data.MsgStatus
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.util.Prefs
import kotlinx.coroutines.launch
import java.io.File

object Sender {

    const val EXTRA_MESSAGE_ID = "dsms_message_id"
    const val EXTRA_RECIPIENT = "dsms_recipient"
    const val EXTRA_IS_LAST_PART = "dsms_is_last_part"

    private fun piFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        return flags
    }

    /** Broadcast/single SMS: one system-provider row + one system send per recipient. */
    fun sendSmsToAll(context: Context, messageId: Long, text: String, addresses: List<String>) {
        val repo = Repo.get(context)
        repo.scope.launch {
            var anyStarted = false
            for (address in addresses) {
                val ok = sendSmsSingle(context, messageId, text, address)
                anyStarted = anyStarted || ok
                if (!ok) repo.onSmsPartSent(messageId, address, isLastPart = true, ok = false)
            }
            if (!anyStarted) repo.onSmsPartSent(messageId, addresses.firstOrNull() ?: "", true, false)
        }
    }

    private fun sendSmsSingle(context: Context, messageId: Long, text: String, address: String): Boolean {
        val dest = PhoneNumberUtils.stripSeparators(address)
        if (dest.isNullOrBlank() || text.isBlank()) return false
        return try {
            // system provider row (OS compatibility)
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, dest)
                    put(Telephony.Sms.BODY, text)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (_: Exception) {}

            val smsManager = Repo.smsManagerFor(context, -1)
            val parts = smsManager.divideMessage(text) ?: return false
            val count = parts.size
            val sentIntents = ArrayList<PendingIntent>(count)
            val deliveredIntents = ArrayList<PendingIntent?>(count)
            val wantDelivery = Prefs.get(context).deliveryReports
            for (i in 0 until count) {
                val isLast = i == count - 1
                val sent = Intent(context, SmsSentStatusReceiver::class.java).apply {
                    putExtra(EXTRA_MESSAGE_ID, messageId)
                    putExtra(EXTRA_RECIPIENT, dest)
                    putExtra(EXTRA_IS_LAST_PART, isLast)
                }
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        context, requestCode(messageId, dest, i, false), sent, piFlags()
                    )
                )
                if (wantDelivery) {
                    // attach to EVERY part: some radio stacks only honor the
                    // status-report request per-part, and report per-part too
                    val del = Intent(context, SmsDeliveredStatusReceiver::class.java).apply {
                        putExtra(EXTRA_MESSAGE_ID, messageId)
                        putExtra(EXTRA_RECIPIENT, dest)
                    }
                    deliveredIntents.add(
                        PendingIntent.getBroadcast(
                            context, requestCode(messageId, dest, i, true), del, piFlags()
                        )
                    )
                } else deliveredIntents.add(null)
            }
            smsManager.sendMultipartTextMessage(dest, null, parts, sentIntents, deliveredIntents)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requestCode(messageId: Long, dest: String, part: Int, delivered: Boolean): Int {
        var h = messageId.hashCode()
        h = h * 31 + dest.hashCode()
        h = h * 31 + part
        h = h * 31 + if (delivered) 1 else 0
        return h and 0x7FFFFFFF
    }

    /** MMS (attachments or group-MMS text) via the smsmms engine, system sending path. */
    fun sendMms(
        context: Context, messageId: Long, text: String,
        addresses: List<String>, attachments: List<Triple<String, String, String>>
    ) {
        try {
            val settings = Settings().apply {
                useSystemSending = true
                deliveryReports = Prefs.get(context).deliveryReports
                group = true
            }
            val transaction = Transaction(context, settings)
            val message = Message(text, addresses.toTypedArray())
            for ((path, mime, name) in attachments) {
                try {
                    val bytes = File(path).readBytes()
                    message.addMedia(bytes, mime, name, name)
                } catch (_: Exception) {}
            }
            val sentIntent = Intent(context, MmsSentReceiverImpl::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
            }
            transaction.setExplicitBroadcastForSentMms(sentIntent)
            transaction.sendNewMessage(message)
        } catch (_: Exception) {
            val repo = Repo.get(context)
            repo.scope.launch { repo.onMmsSent(messageId, ok = false) }
        }
    }

    fun statusLabel(context: Context, status: Int): String = when (status) {
        MsgStatus.SENDING -> context.getString(io.github.theonionsarewatching.nova.R.string.status_sending)
        MsgStatus.SENT -> context.getString(io.github.theonionsarewatching.nova.R.string.status_sent)
        MsgStatus.DELIVERED -> context.getString(io.github.theonionsarewatching.nova.R.string.status_delivered)
        MsgStatus.FAILED -> context.getString(io.github.theonionsarewatching.nova.R.string.status_failed)
        MsgStatus.CANCELED -> context.getString(io.github.theonionsarewatching.nova.R.string.status_canceled)
        MsgStatus.SCHEDULED -> context.getString(io.github.theonionsarewatching.nova.R.string.status_scheduled)
        else -> ""
    }
}
