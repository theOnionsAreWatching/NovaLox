package io.github.theonionsarewatching.nova.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.DatabaseUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsManager
import com.google.android.mms.pdu_alt.DeliveryInd
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.ReadOrigInd
import com.klinker.android.send_message.MmsReceivedReceiver
import io.github.theonionsarewatching.nova.util.DiagLog
import io.github.theonionsarewatching.nova.util.MmsUserAgent
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Incoming MMS, handled by us instead of the engine's PushReceiver.
 *
 * WHY: the engine's downloader forwards only MMS_CONFIG_HTTP_PARAMS to the
 * platform. The platform sets the outgoing User-Agent from
 * MMS_CONFIG_USER_AGENT in the *config overrides bundle* — that is the channel
 * that produced "HTTP: User-Agent=vzmmms1.0" in the carrier app's own logs.
 * We cannot inject that key through the engine, so we make the download call
 * ourselves.
 *
 * 0.9.52 FIX — the 0.9.51 regression ("no MMS comes in"): the engine's
 * PushReceiver appends the transaction ID to the content location when the
 * location ends in '=' and the carrier config asks for it
 * (MMS_CONFIG_APPEND_TRANSACTION_ID). Verizon's MMSC URLs end in '=' and
 * REQUIRE the appended ID — downloading from the bare location fails every
 * time. 0.9.51 downloaded from the bare location. This class now mirrors the
 * engine's URL handling exactly, and also mirrors its delivery/read-indication
 * thread matching, its transaction-ID duplicate check, and its wakelock on the
 * completion leg.
 *
 * Everything after the download is unchanged: the result is re-broadcast to
 * the engine's MmsReceivedReceiver flow (our .sms.MmsReceiver), which parses
 * and persists exactly as before.
 */
class MmsPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val um = context.getSystemService(android.os.UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra("subscription", -1)
        DiagLog.log(
            context, "mms-push",
            "wap push: ${data.size} bytes, type=${intent.type}, subId=$subId"
        )
        val pending = goAsync()
        Thread {
            try {
                handle(context, data, subId)
            } catch (e: Exception) {
                DiagLog.log(context, "mms-push", "handling failed: $e")
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
                // ENGINE PARITY: match to the sent message FIRST; a notice we
                // cannot match is dropped (persisting it blind created orphan
                // rows the engine never made)
                val threadId = findThreadId(context, pdu, pdu.messageType)
                if (threadId == -1L) {
                    DiagLog.log(
                        context, "mms-push",
                        "indication type=${pdu.messageType} matches no sent message — dropped"
                    )
                    return
                }
                val uri = persister.persist(
                    pdu, Telephony.Mms.Inbox.CONTENT_URI, true, true, null, subId
                )
                try {
                    val values = ContentValues(1)
                    values.put(Telephony.Mms.THREAD_ID, threadId)
                    context.contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    DiagLog.log(context, "mms-push", "indication thread update failed: $e")
                }
                DiagLog.log(
                    context, "mms-push",
                    "indication persisted (type=${pdu.messageType}, thread=$threadId)"
                )
            }
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> {
                val nInd = pdu as NotificationInd
                val sm = smsManagerFor(subId)

                // THE 0.9.51 BUG: Verizon content locations end in '=' and the
                // MMSC requires the transaction ID appended before download.
                // Mirror the engine: modify the PDU before persisting so the
                // stored ct_l is the effective URL.
                val rawLocation = nInd.contentLocation?.let { String(it) }
                val appendWanted = carrierWantsTransactionId(context, sm)
                val loc = nInd.contentLocation
                if (appendWanted && loc != null && loc.isNotEmpty() &&
                    loc[loc.size - 1] == '='.code.toByte()
                ) {
                    val trId = nInd.transactionId
                    if (trId != null && trId.isNotEmpty()) {
                        nInd.contentLocation = loc + trId
                    }
                }
                val location = nInd.contentLocation?.let { String(it) }
                if (location.isNullOrBlank()) {
                    DiagLog.log(context, "mms-push", "notification with no content location")
                    return
                }
                DiagLog.log(
                    context, "mms-push",
                    "notification-ind: raw=$rawLocation append=$appendWanted effective=$location"
                )
                val transactionId = nInd.transactionId?.let { String(it) } ?: ""
                if (isDuplicate(context, transactionId) ||
                    !downloadedLocations.add(location)
                ) {
                    DiagLog.log(
                        context, "mms-push",
                        "duplicate notification (tr_id=$transactionId) — ignored"
                    )
                    return
                }
                val uri = persister.persist(
                    pdu, Telephony.Mms.Inbox.CONTENT_URI, true, true, null, subId
                )
                DiagLog.log(
                    context, "mms-push",
                    "notification persisted -> downloading (tr_id=$transactionId)"
                )
                download(context, sm, location, transactionId, uri, subId)
            }
            else -> {
                DiagLog.log(context, "mms-push", "ignored push type=${pdu.messageType}")
            }
        }
    }

    private fun smsManagerFor(subId: Int): SmsManager = try {
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

    /** Carrier config first (the platform's own answer), engine config second. */
    private fun carrierWantsTransactionId(context: Context, sm: SmsManager): Boolean {
        val fromCarrier = try {
            sm.carrierConfigValues
                ?.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID) ?: false
        } catch (e: Exception) {
            DiagLog.log(context, "mms-push", "carrier config read failed: $e")
            false
        }
        val fromEngine = try {
            com.android.mms.MmsConfig.getTransIdEnabled()
        } catch (_: Exception) { false }
        return fromCarrier || fromEngine
    }

    /** ENGINE PARITY: an already-seen transaction ID means a redelivered push. */
    private fun isDuplicate(context: Context, transactionId: String): Boolean = try {
        if (transactionId.isBlank()) false
        else {
            var found = false
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.TRANSACTION_ID} = ?", arrayOf(transactionId), null
            )?.use { c -> found = c.count > 0 }
            found
        }
    } catch (_: Exception) { false }

    /** ENGINE PARITY: match a delivery/read notice to its sent message. */
    private fun findThreadId(context: Context, pdu: Any, type: Int): Long {
        val messageId = try {
            if (type == PduHeaders.MESSAGE_TYPE_DELIVERY_IND) {
                String((pdu as DeliveryInd).messageId)
            } else {
                String((pdu as ReadOrigInd).messageId)
            }
        } catch (_: Exception) { return -1L }
        val selection = "(" + Telephony.Mms.MESSAGE_ID + "=" +
            DatabaseUtils.sqlEscapeString(messageId) + " AND " +
            Telephony.Mms.MESSAGE_TYPE + "=" +
            PduHeaders.MESSAGE_TYPE_SEND_REQ
        return try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
                selection, null, null
            )?.use { c ->
                if (c.count == 1 && c.moveToFirst()) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    /**
     * The download call — the whole point of this class. The overrides bundle
     * carries the client identity (User-Agent / UAProf) from the selected
     * profile in Settings.
     */
    private fun download(
        context: Context, sm: SmsManager, location: String,
        transactionId: String, uri: Uri?, subId: Int
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
        val pi = PendingIntent.getBroadcast(
            context, abs(Random.nextInt()), done, flags
        )

        val overrides = Bundle()
        MmsUserAgent.applyToOverrides(context, overrides)

        try {
            context.grantUriPermission(
                context.packageName + ".MmsFileProvider", contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

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
            // ENGINE PARITY: the parse-and-persist that follows runs after this
            // receiver returns; hold the CPU so it cannot stall half-done
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:mms-download")
                    .acquire(60_000L)
            } catch (_: Exception) {}
            val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            DiagLog.log(
                context, "mms-push",
                "download finished rc=$resultCode http=$http -> receive pipeline"
            )
            val forwarded = Intent(intent).apply {
                action = MmsReceivedReceiver.MMS_RECEIVED
                setClass(context, MmsReceiver::class.java)
            }
            context.sendBroadcast(forwarded)
        }
    }

    companion object {
        private const val ACTION_PREFIX =
            "io.github.theonionsarewatching.nova.MMS_DOWNLOADED."
        private val downloadedLocations: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())
    }
}
