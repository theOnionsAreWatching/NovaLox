package io.github.theonionsarewatching.nova.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.klinker.android.send_message.MmsReceivedReceiver
import io.github.theonionsarewatching.nova.util.DiagLog
import io.github.theonionsarewatching.nova.util.MmsUserAgent
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Incoming MMS, handled by us instead of the engine's PushReceiver.
 *
 * WHY: the engine's downloader forwards only MMS_CONFIG_HTTP_PARAMS to the
 * platform. The platform sets the outgoing User-Agent from
 * MMS_CONFIG_USER_AGENT in the *config overrides bundle* — that is the channel
 * that produced "HTTP: User-Agent=vzmmms1.0" in the carrier app's own logs, and
 * the reason its downloads come back with playable audio while ours came back
 * as undecodable QCELP. We cannot inject that key through the engine, so we
 * make the download call ourselves.
 *
 * Everything after the download is unchanged: the result is re-broadcast to the
 * engine's MmsReceivedReceiver flow (our .sms.MmsReceiver), which parses and
 * persists exactly as before.
 */
class MmsPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val um = context.getSystemService(android.os.UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra("subscription", -1)
        val pending = goAsync()
        Thread {
            try {
                handle(context, data, subId)
            } catch (e: Exception) {
                DiagLog.log(context, "mms-push", "handling failed: ${e.message}")
            } finally {
                try { pending.finish() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun handle(context: Context, data: ByteArray, subId: Int) {
        val pdu = PduParser(data, true).parse()
        if (pdu == null) {
            DiagLog.log(context, "mms-push", "unparseable WAP push")
            return
        }
        val persister = PduPersister.getPduPersister(context)
        when (pdu.messageType) {
            PduHeaders.MESSAGE_TYPE_DELIVERY_IND,
            PduHeaders.MESSAGE_TYPE_READ_ORIG_IND -> {
                // delivery / read notices: persist so the existing matcher
                // (by Message-ID) can pick them up
                persister.persist(
                    pdu, Telephony.Mms.Inbox.CONTENT_URI, true, true, null, subId
                )
                DiagLog.log(
                    context, "mms-push",
                    "indication persisted (type=${pdu.messageType})"
                )
            }
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> {
                val nInd = pdu as NotificationInd
                val location = nInd.contentLocation?.let { String(it) }
                if (location.isNullOrBlank()) {
                    DiagLog.log(context, "mms-push", "notification with no content location")
                    return
                }
                if (isDuplicate(context, location)) {
                    DiagLog.log(context, "mms-push", "duplicate notification for $location — ignored")
                    return
                }
                val uri = persister.persist(
                    pdu, Telephony.Mms.Inbox.CONTENT_URI, true, true, null, subId
                )
                val transactionId = nInd.transactionId?.let { String(it) } ?: ""
                DiagLog.log(
                    context, "mms-push",
                    "notification-ind -> downloading ourselves (tr_id=$transactionId)"
                )
                download(context, location, transactionId, uri, subId)
            }
            else -> {
                DiagLog.log(context, "mms-push", "ignored push type=${pdu.messageType}")
            }
        }
    }

    /** Already have a notification row for this content location? */
    private fun isDuplicate(context: Context, location: String): Boolean = try {
        var found = false
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.CONTENT_LOCATION} = ?", arrayOf(location), null
        )?.use { c -> found = c.count > 0 }
        found
    } catch (_: Exception) { false }

    /**
     * The download call — the whole point of this class. The overrides bundle
     * carries the User-Agent the carrier's MMSC treats as first-class.
     */
    private fun download(
        context: Context, location: String, transactionId: String, uri: Uri?, subId: Int
    ) {
        val fileName = "download." + abs(Random.nextLong()) + ".dat"
        val downloadFile = File(context.cacheDir, fileName)
        val contentUri = Uri.Builder()
            .authority(context.packageName + ".MmsFileProvider")
            .path(fileName)
            .scheme(ContentResolver.SCHEME_CONTENT)
            .build()

        val action = ACTION_PREFIX + UUID.randomUUID().toString()
        val result = DownloadResultReceiver()
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.registerReceiver(
                result, filter, Context.RECEIVER_EXPORTED
            )
        } else {
            context.applicationContext.registerReceiver(result, filter)
        }

        val done = Intent(action).apply {
            putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, downloadFile.path)
            putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location)
            putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, true)
            putExtra(MmsReceivedReceiver.EXTRA_URI, uri)
            putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, subId)
        }
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, 0, done, flags)

        val overrides = Bundle()
        MmsUserAgent.applyToOverrides(context, overrides)

        try {
            context.grantUriPermission(
                context.packageName + ".MmsFileProvider", contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val sm = try {
            if (Build.VERSION.SDK_INT >= 22 && subId >= 0) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        DiagLog.log(
            context, "mms-push",
            "downloadMultimediaMessage overrides=" +
                (overrides.getString(SmsManager.MMS_CONFIG_USER_AGENT)
                    ?.let { "UA=$it" } ?: "none")
        )
        sm.downloadMultimediaMessage(context, location, contentUri, overrides, pi)
    }

    /** Hands the finished download to the existing receive pipeline. */
    private class DownloadResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try { context.applicationContext.unregisterReceiver(this) } catch (_: Exception) {}
            val forwarded = Intent(intent).apply {
                action = MmsReceivedReceiver.MMS_RECEIVED
                setClass(context, MmsReceiver::class.java)
            }
            DiagLog.log(context, "mms-push", "download finished -> handing to receive pipeline")
            context.sendBroadcast(forwarded)
        }
    }

    companion object {
        private const val ACTION_PREFIX =
            "io.github.theonionsarewatching.nova.MMS_DOWNLOADED."
    }
}
