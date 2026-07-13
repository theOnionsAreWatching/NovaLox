package com.klinker.android.send_message

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import com.android.mms.dom.smil.parser.SmilXmlSerializer
import com.google.android.mms.smil.SmilHelper
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.SendReq
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random

/**
 * Our own MMS send-request builder over the system pathway.
 *
 * Exists because the engine's builder hardcodes "delivery report: NO" into
 * every outgoing PDU (Transaction.buildPdu), making MMS delivery reports
 * impossible no matter what the settings say. This mirrors that builder
 * faithfully — same SMIL compatibility part, same headers — except the
 * delivery-report flag obeys the app setting, and the persisted store row's
 * id is returned so the caller can link it immediately (which also closes
 * the duplicate-message race at its source).
 *
 * Lives in the engine's package for access to its SMIL helper.
 */
object SystemMmsSender {

    private const val DEFAULT_EXPIRY_TIME = 7L * 24 * 60 * 60 // seconds
    private const val DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL

    /**
     * Builds, persists (outbox) and hands the MMS to the system for sending.
     * Returns the telephony row id of the persisted message.
     * Throws on any failure — caller falls back to the engine path.
     */
    fun send(
        context: Context,
        appMessageId: Long,
        text: String,
        addresses: List<String>,
        attachments: List<Triple<ByteArray, String, String>>, // bytes, mime, name
        requestDeliveryReport: Boolean,
        groupMms: Boolean,
        linkRow: (Long) -> Unit = {}
    ): Long? {
        // ---- build the send request (address handling mirrors AOSP Bugle) ----
        val req = SendReq()
        // FROM: set only a clean self number; otherwise leave unset so the
        // composer emits the insert-address-token and the CARRIER fills in the
        // verified subscriber address. Strict MMSCs (Verizon/Visible) reject
        // the whole message with resp=132 SENDING-ADDRESS-UNRESOLVED when the
        // From doesn't match their records — the token path is immune to every
        // line-number quirk. (Bugle MmsUtils.createSendReq:2076-2079.)
        val rawSelf = try { Utils.getMyPhoneNumber(context) } catch (_: Exception) { null }
        val selfNumber = rawSelf
            ?.let { android.telephony.PhoneNumberUtils.stripSeparators(it) }
            ?.takeIf { n -> n.count { it.isDigit() } >= 7 }
        if (selfNumber != null) {
            req.from = EncodedStringValue(selfNumber)
        }
        io.github.theonionsarewatching.nova.util.DiagLog.log(
            context, "mms-send",
            "from=" + (selfNumber ?: "insert-address-token")
        )
        // TO: emails pass through; phone numbers are stripped of spaces,
        // dashes and parentheses — formatted numbers are another resp=132
        // trigger on strict MMSCs.
        for (r in addresses) {
            val clean = if (r.contains("@")) r
                else android.telephony.PhoneNumberUtils.stripSeparators(r)
            if (!clean.isNullOrBlank()) req.addTo(EncodedStringValue(clean))
        }
        req.date = System.currentTimeMillis() / 1000

        val body = PduBody()
        var size = 0
        var index = 0
        if (text.isNotBlank()) {
            size += addPart(body, text.toByteArray(Charsets.UTF_8), "text/plain", "text_$index.txt")
            index++
        }
        for ((bytes, mime, name) in attachments) {
            size += addPart(body, bytes, mime, name)
            index++
        }

        // SMIL compatibility part, exactly as the engine does it
        val out = ByteArrayOutputStream()
        SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(body), out)
        val smil = PduPart().apply {
            contentId = "smil".toByteArray()
            contentLocation = "smil.xml".toByteArray()
            contentType = ContentType.APP_SMIL.toByteArray()
            data = out.toByteArray()
        }
        body.addPart(0, smil)

        req.body = body
        req.messageSize = size.toLong()
        req.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
        req.expiry = DEFAULT_EXPIRY_TIME
        try {
            req.priority = DEFAULT_PRIORITY
            // THE line the engine got wrong:
            req.deliveryReport =
                if (requestDeliveryReport) PduHeaders.VALUE_YES else PduHeaders.VALUE_NO
            req.readReport = PduHeaders.VALUE_NO
        } catch (_: Exception) {
        }

        // ---- persist to the outbox (this is the system store row) ----
        val persister = PduPersister.getPduPersister(context)
        val messageUri = persister.persist(
            req, Uri.parse("content://mms/outbox"), true, groupMms, null,
            Settings.DEFAULT_SUBSCRIPTION_ID
        )
        val telephonyId = messageUri?.lastPathSegment?.toLongOrNull()

        // Link our app row to this store row NOW, synchronously, before the
        // system starts sending and before any content-observer pass can see an
        // unlinked outgoing row (that race spawned the retry duplicate).
        if (telephonyId != null) linkRow(telephonyId)

        // ---- stage the composed bytes for the system to read ----
        val fileName = "send." + abs(Random.nextLong()) + ".dat"
        val sendFile = File(context.cacheDir, fileName)
        FileOutputStream(sendFile).use { it.write(PduComposer(context, req).make()) }
        val contentUri = Uri.Builder()
            .authority(context.packageName + ".MmsFileProvider")
            .path(fileName)
            .scheme(ContentResolver.SCHEME_CONTENT)
            .build()

        val overrides = Bundle().apply {
            putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, groupMms)
            putInt(
                SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE,
                io.github.theonionsarewatching.nova.util.CarrierMms.limits(context).maxBytes
            )
        }

        val sentIntent = Intent(
            context, io.github.theonionsarewatching.nova.sms.MmsSentReceiverImpl::class.java
        ).apply {
            putExtra(io.github.theonionsarewatching.nova.sms.Sender.EXTRA_MESSAGE_ID, appMessageId)
            putExtra("content_uri", messageUri?.toString())
            putExtra("file_path", sendFile.absolutePath)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(
            context, appMessageId.toInt(), sentIntent, flags
        )

        @Suppress("DEPRECATION")
        SmsManager.getDefault().sendMultimediaMessage(context, contentUri, null, overrides, pi)
        return telephonyId
    }

    private fun addPart(pb: PduBody, bytes: ByteArray, mime: String, name: String): Int {
        val part = PduPart()
        if (mime.startsWith("text")) part.charset = CharacterSets.UTF_8
        part.contentType = mime.toByteArray()
        part.contentLocation = name.toByteArray()
        val dot = name.lastIndexOf('.')
        part.contentId = (if (dot == -1) name else name.substring(0, dot)).toByteArray()
        part.data = bytes
        pb.addPart(part)
        return bytes.size
    }
}
