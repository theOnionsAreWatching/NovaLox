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
import io.github.theonionsarewatching.nova.R
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
            // system provider row (OS compatibility). Capture its id and LINK it
            // to our app message right away — otherwise the content observer sees
            // an unowned outgoing SMS and ingests a duplicate (this is the SMS
            // retry-duplicate: the MMS path links, the SMS path never did).
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, dest)
                    put(Telephony.Sms.BODY, text)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
                val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                val tid = uri?.lastPathSegment?.toLongOrNull()
                if (tid != null) {
                    val repo0 = Repo.get(context)
                    // only link the FIRST recipient's row to this message (group
                    // sends fan out; the message represents the thread's send)
                    kotlinx.coroutines.runBlocking {
                        val existing = repo0.db.messages().byId(messageId)
                        if (existing != null && existing.telephonyId == null) {
                            repo0.db.messages().setTelephonyId(messageId, tid, false)
                        }
                    }
                }
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
            if (wantDelivery) {
                val repo = Repo.get(context)
                val stamp = android.text.format.DateFormat.format(
                    "MM-dd HH:mm", System.currentTimeMillis())
                repo.scope.launch {
                    repo.db.messages().appendDeliveryDebug(
                        messageId, "[$stamp] report requested ($count part${if (count == 1) "" else "s"})\n"
                    )
                }
            }
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
            io.github.theonionsarewatching.nova.util.MmsUserAgent.applyToSettings(context, settings)
            val transaction = Transaction(context, settings)
            val message = Message(text, addresses.toTypedArray())

            // ---- fit the carrier's size budget (engine enforces 800 KB) ----
            // Raw camera photos are megabytes; oversize PDUs fail with the
            // system's IO error (rc=5). Shrink images; refuse what can't shrink.
            // the carrier's own limit for this SIM (the AOSP Messaging approach),
            // minus slop for headers + the SMIL compatibility part
            val limits = io.github.theonionsarewatching.nova.util.CarrierMms.limits(context)
            val cap = limits.maxBytes - 30 * 1024
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-send",
                "carrier limits: max=${limits.maxBytes / 1024} KB imgW=${limits.maxImageWidth} imgH=${limits.maxImageHeight}"
            )
            data class Att(val bytes: ByteArray, val mime: String, val name: String)
            val loaded = ArrayList<Att>()
            for ((path, mime, name) in attachments) {
                try {
                    loaded.add(Att(File(path).readBytes(), mime, name))
                } catch (_: Exception) {}
            }
            val shrinkable = { a: Att ->
                a.mime.startsWith("image/") && !a.mime.equals("image/gif", true)
            }
            val fixedBytes = text.toByteArray().size +
                loaded.filter { !shrinkable(it) }.sumOf { it.bytes.size }
            if (fixedBytes > cap) {
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-send",
                    "REFUSED: non-image attachments total ${fixedBytes / 1024} KB, over the ~800 KB MMS limit (msg=$messageId)"
                )
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context, R.string.attachment_too_large, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                val repo = Repo.get(context)
                repo.scope.launch { repo.onMmsSent(messageId, ok = false) }
                return
            }
            val imageCount = loaded.count { shrinkable(it) }
            val perImageBudget = if (imageCount == 0) 0
                else ((cap - fixedBytes) / imageCount).coerceAtLeast(60 * 1024)
            val finalAtts = ArrayList<Triple<ByteArray, String, String>>()
            for (a in loaded) {
                if (shrinkable(a) && a.bytes.size > perImageBudget) {
                    val edge = maxOf(limits.maxImageWidth, limits.maxImageHeight)
                        .takeIf { it > 0 }?.coerceAtMost(1600) ?: 1600
                    val shrunk = io.github.theonionsarewatching.nova.util
                        .MediaShrink.shrinkToBudget(a.bytes, perImageBudget, edge)
                    if (shrunk != null) {
                        val jpgName = a.name.substringBeforeLast('.', a.name) + ".jpg"
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-send",
                            "shrunk ${a.name}: ${a.bytes.size / 1024} KB -> ${shrunk.size / 1024} KB"
                        )
                        finalAtts.add(Triple(shrunk, "image/jpeg", jpgName))
                        continue
                    }
                }
                finalAtts.add(Triple(a.bytes, a.mime, a.name))
            }

            // ---- preferred path: our own builder (honest delivery-report flag,
            //      immediate store-row link). Engine path below as fallback. ----
            try {
                val wantReport = Prefs.get(context).deliveryReports
                val repo0 = Repo.get(context)
                val tid = com.klinker.android.send_message.SystemMmsSender.send(
                    context, messageId, text, addresses, finalAtts,
                    requestDeliveryReport = wantReport, groupMms = true,
                    linkRow = { linkedTid ->
                        // synchronous link before the store row can be observed
                        kotlinx.coroutines.runBlocking {
                            repo0.db.messages().setTelephonyId(messageId, linkedTid, true)
                        }
                    }
                )
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-send",
                    "own builder: msg=$messageId tid=$tid d_rpt=${if (wantReport) "YES" else "no"} parts=${finalAtts.size}"
                )
                return
            } catch (e: Exception) {
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-send",
                    "own builder failed (${e::class.java.simpleName}: ${e.message}) — falling back to engine"
                )
            }

            for ((bytes, mime, name) in finalAtts) {
                message.addMedia(bytes, mime, name, name)
            }
            val sentIntent = Intent(context, MmsSentReceiverImpl::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
            }
            transaction.setExplicitBroadcastForSentMms(sentIntent)
            transaction.sendNewMessage(message)
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-send",
                "handed to engine: msg=$messageId to=${addresses.size} attachments=${attachments.size}"
            )
        } catch (e: Exception) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-send", "FAILED before engine: msg=$messageId ${e::class.java.simpleName}: ${e.message}"
            )
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
        MsgStatus.READ_BY_RECIPIENT -> context.getString(io.github.theonionsarewatching.nova.R.string.status_read)
        else -> ""
    }
}
