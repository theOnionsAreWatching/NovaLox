package io.github.theonionsarewatching.nova.sms

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import com.klinker.android.send_message.MmsReceivedReceiver
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.notify.NotificationHelper
import kotlinx.coroutines.launch
import java.io.File

// ============================== Incoming SMS ==============================

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // some radio stacks hand SMS STATUS REPORTS to the default app through
        // this pipeline instead of the sender's delivery callback — catch them
        val reports = messages.filter { try { it.isStatusReportMessage } catch (_: Exception) { false } }
        if (reports.isNotEmpty()) {
            val pending2 = goAsync()
            val repo2 = Repo.get(context)
            repo2.scope.launch {
                try {
                    for (r in reports) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "delivery",
                            "status report via inbox route: from=${r.displayOriginatingAddress} tp=${r.status}"
                        )
                        repo2.onStatusReportViaInbox(
                            r.displayOriginatingAddress ?: "", r.status
                        )
                    }
                } finally {
                    pending2.finish()
                }
            }
            if (reports.size == messages.size) return
        }

        val address = messages[0].displayOriginatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val date = System.currentTimeMillis()
        val subId = intent.getIntExtra("subscription", -1)

        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                val result = repo.receiveSms(address, body, date, subId)
                if (result != null) {
                    val (msg, convo) = result
                    if (!msg.blockedByKeyword) {
                        NotificationHelper.notifyMessage(context, convo, msg)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

// ============================== Incoming MMS ==============================

/** The engine's PushReceiver downloads the MMS, persists it into the telephony
 *  provider and then hands off here (located via the MMS_RECEIVED taskAffinity). */
class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean =
        Repo.get(context).isNumberBlocked(address)

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        val repo = Repo.get(context)
        repo.scope.launch {
            val result = repo.ingestLatestMmsFromTelephony() ?: return@launch
            val (msg, convo) = result
            if (!msg.isMine && !msg.blockedByKeyword) {
                NotificationHelper.notifyMessage(context, convo, msg)
            }
        }
    }

    override fun onError(context: Context, error: String) {
        // download failed; the engine retries per carrier rules
    }
}

// ============================== MMS sent ==============================

class MmsSentReceiverImpl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        // THE MISSING HALF of MMS delivery reports: the carrier's confirmation
        // carries the Message-ID it assigned; the later delivery notice
        // references that ID and the matcher looks it up on the sent row.
        // Nobody in the klinker lineage ever stored it — every delivery notice
        // failed the lookup and was silently dropped. The stock app stores it
        // (Bugle ProcessSentMessageAction -> updateSentMmsMessageStatus).
        if (ok) {
            try {
                val conf = intent.getByteArrayExtra(android.telephony.SmsManager.EXTRA_MMS_DATA)
                if (conf != null) {
                    val pdu = com.google.android.mms.pdu_alt.PduParser(conf, true).parse()
                    val sendConf = pdu as? com.google.android.mms.pdu_alt.SendConf
                    if (sendConf != null) {
                        val mid = sendConf.messageId?.let { String(it) }
                        val uriString2 = intent.getStringExtra("content_uri")
                        if (!mid.isNullOrBlank() && uriString2 != null) {
                            val values = ContentValues(2).apply {
                                put(Telephony.Mms.MESSAGE_ID, mid)
                                put(Telephony.Mms.RESPONSE_STATUS, sendConf.responseStatus)
                            }
                            context.contentResolver.update(Uri.parse(uriString2), values, null, null)
                        }
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-sent",
                            "send-conf: resp=${sendConf.responseStatus} m_id=$mid stored=${!mid.isNullOrBlank()}"
                        )
                    } else {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-sent", "send-conf payload present but not a SendConf"
                        )
                    }
                } else {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-sent",
                        "no send-conf payload from this stack — delivery matching not possible for this message"
                    )
                }
            } catch (e: Exception) {
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-sent", "send-conf parse failed: ${e.message}"
                )
            }
        }

        val rcName = when (resultCode) {
            android.app.Activity.RESULT_OK -> "OK"
            1 -> "UNSPECIFIED"
            2 -> "INVALID_APN"
            3 -> "UNABLE_TO_CONNECT_MMS"
            4 -> "HTTP_FAILURE"
            5 -> "IO_ERROR (usually: message over the carrier size cap, or staged file unreadable)"
            6 -> "RETRY"
            7 -> "CONFIGURATION_ERROR"
            8 -> "NO_DATA_NETWORK"
            else -> "code $resultCode"
        }
        io.github.theonionsarewatching.nova.util.DiagLog.log(
            context, "mms-sent",
            "engine result: ok=$ok rc=$resultCode ($rcName) msg=${intent.getLongExtra(Sender.EXTRA_MESSAGE_ID, -1L)}"
        )
        val messageId = intent.getLongExtra(Sender.EXTRA_MESSAGE_ID, -1L)

        // keep the system provider box in sync (mirrors the reference implementation)
        var okEffective = ok
        try {
            val uriString = intent.getStringExtra("content_uri")
            if (uriString != null) {
                if (!ok) {
                    // some stacks return an error code for messages that actually
                    // went out — if the system already moved the row to SENT,
                    // trust the store over the code
                    val boxNow = context.contentResolver.query(
                        Uri.parse(uriString), arrayOf(Telephony.Mms.MESSAGE_BOX),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
                    if (boxNow == Telephony.Mms.MESSAGE_BOX_SENT) {
                        okEffective = true
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-sent",
                            "rc=$resultCode but store box=SENT — treating as sent"
                        )
                    }
                }
                if (!(okEffective && !ok)) {
                    val box = if (okEffective) Telephony.Mms.MESSAGE_BOX_SENT
                        else Telephony.Mms.MESSAGE_BOX_FAILED
                    val values = ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, box) }
                    context.contentResolver.update(Uri.parse(uriString), values, null, null)
                }
            }
        } catch (_: SQLiteException) {
        } catch (_: Exception) {
        }
        try {
            intent.getStringExtra("file_path")?.let { File(it).delete() }
        } catch (_: Exception) {}

        if (messageId > 0) {
            val pending = goAsync()
            val repo = Repo.get(context)
            repo.scope.launch {
                try {
                    val tId = intent.getStringExtra("content_uri")
                        ?.let { android.net.Uri.parse(it).lastPathSegment?.toLongOrNull() }
                    repo.onMmsSent(messageId, okEffective, tId)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

// ============================== SMS status ==============================

class SmsSentStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val messageId = intent.getLongExtra(Sender.EXTRA_MESSAGE_ID, -1L)
        val recipient = intent.getStringExtra(Sender.EXTRA_RECIPIENT) ?: ""
        val isLast = intent.getBooleanExtra(Sender.EXTRA_IS_LAST_PART, true)
        if (messageId <= 0) return
        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                repo.onSmsPartSent(messageId, recipient, isLast, ok)
            } finally {
                pending.finish()
            }
        }
    }
}

class SmsDeliveredStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // the delivery result lives in the status-report PDU, not the resultCode:
        // the broadcast fires when the REPORT arrives, even if the report says
        // the message failed — and some phones never set resultCode at all
        var tpStatus = -1 // -1 = no PDU came with the broadcast
        val ok = try {
            val pdu = intent.getByteArrayExtra("pdu")
            if (pdu != null) {
                val format = intent.getStringExtra("format")
                val sms = if (format != null)
                    android.telephony.SmsMessage.createFromPdu(pdu, format)
                else @Suppress("DEPRECATION") android.telephony.SmsMessage.createFromPdu(pdu)
                // TP-Status: 0 = delivered; 32+ = temporary/permanent failures
                tpStatus = sms?.status ?: -1
                if (tpStatus >= 0) tpStatus < 32 else resultCode == Activity.RESULT_OK
            } else {
                resultCode == Activity.RESULT_OK
            }
        } catch (_: Exception) {
            resultCode == Activity.RESULT_OK
        }
        val messageId = intent.getLongExtra(Sender.EXTRA_MESSAGE_ID, -1L)
        val recipient = intent.getStringExtra(Sender.EXTRA_RECIPIENT) ?: ""
        if (messageId <= 0) return
        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                repo.onSmsDelivered(messageId, recipient, ok, tpStatus, resultCode)
            } finally {
                pending.finish()
            }
        }
    }
}

// ============================== Scheduled / boot ==============================

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra("message_id", -1L)
        if (messageId <= 0) return
        val pending = goAsync()
        val repo = Repo.get(context)
        repo.scope.launch {
            try {
                repo.fireScheduled(messageId)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Repo.get(context).rescheduleAllAlarms()
        }
    }
}

// ============================== Respond via message ==============================

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val recipients = intent.data?.schemeSpecificPart?.split(";", ",")
                ?.map { it.trim() }?.filter { it.isNotBlank() }
            if (!text.isNullOrBlank() && !recipients.isNullOrEmpty()) {
                val repo = Repo.get(this)
                repo.scope.launch {
                    val convo = repo.getOrCreateConversation(recipients)
                    repo.sendText(convo.id, text)
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
