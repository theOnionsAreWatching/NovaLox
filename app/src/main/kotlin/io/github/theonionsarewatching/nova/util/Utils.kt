package io.github.theonionsarewatching.nova.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.github.theonionsarewatching.nova.data.ElementEntity
import io.github.theonionsarewatching.nova.data.ElementType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================== Phone / address keys ==============================
object PhoneUtils {

    /** Normalized form used for matching. Digits only, last 10 kept for long numbers.
     *  Short codes and email addresses are kept as-is (lowercased). */
    fun normalize(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase(Locale.ROOT)
        val digits = a.filter { it.isDigit() }
        if (digits.isEmpty()) return a.lowercase(Locale.ROOT)
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /** Stable conversation key: sorted normalized addresses joined with ",". */
    fun convoKey(addresses: Collection<String>): String =
        addresses.map { normalize(it) }.distinct().sorted().joinToString(",")
}

// ============================== Date formatting ==============================
object Formatters {
    // All formatting follows the SYSTEM settings: 12/24-hour clock and the
    // locale's date order (day/month vs month/day) come from the phone.
    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    fun time(ts: Long): String =
        android.text.format.DateFormat.getTimeFormat(appContext).format(Date(ts))

    fun full(ts: Long): String = android.text.format.DateUtils.formatDateTime(
        appContext, ts,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_YEAR or
            android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
            android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY or
            android.text.format.DateUtils.FORMAT_SHOW_TIME
    )

    /** Timestamp under a message inside a conversation. */
    fun messageStamp(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val withinWeek = now.timeInMillis - ts < 6L * 24 * 60 * 60 * 1000 && ts <= now.timeInMillis
        return when {
            sameDay -> time(ts)
            withinWeek -> android.text.format.DateUtils.formatDateTime(
                appContext, ts,
                android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                    android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY or
                    android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            sameYear -> android.text.format.DateUtils.formatDateTime(
                appContext, ts,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                    android.text.format.DateUtils.FORMAT_NO_YEAR or
                    android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            else -> android.text.format.DateUtils.formatDateTime(
                appContext, ts,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                    android.text.format.DateUtils.FORMAT_SHOW_YEAR or
                    android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
        }
    }

    fun listStamp(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        return when {
            sameDay -> time(ts)
            sameYear -> android.text.format.DateUtils.formatDateTime(
                appContext, ts,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                    android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                    android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY or
                    android.text.format.DateUtils.FORMAT_NO_YEAR
            )
            // older than this year: ALWAYS show the year (explicitly — the automatic
            // rule skipped it on dates more than a year back), drop the weekday
            else -> android.text.format.DateUtils.formatDateTime(
                appContext, ts,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                    android.text.format.DateUtils.FORMAT_SHOW_YEAR
            )
        }
    }
}

// ============================== Element extraction ==============================
object ElementExtractor {

    // scheme:// links, www. links, and bare domains with a known TLD (so "file.txt" doesn't match)
    private const val TLDS =
        "com|org|net|edu|gov|mil|int|io|co|me|info|biz|app|dev|xyz|tv|online|site|store|link|ly|gg|to|fm|" +
            "us|uk|ca|au|de|fr|il|nl|ru|in|es|it|ch|be|at|pl|se|no|dk|fi|br|mx|jp|cn|kr|ie|nz|za|ar|cl|tr|gr|pt|cz|hu|ro|ua"
    private val urlRegex = Regex(
        "(?i)\\b(" +
            "https?://[^\\s]+" +
            "|www\\.[\\w\\-]+(?:\\.[\\w\\-]+)+(?:[/?#][^\\s]*)?" +
            "|[a-z0-9][a-z0-9\\-]*(?:\\.[a-z0-9\\-]+)*\\.(?:$TLDS)(?:/[^\\s]*)?" +
            ")"
    )
    private val emailRegex = Regex(
        "(?i)\\b[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}\\b"
    )
    private val phoneRegex = Regex(
        "\\+?\\d[\\d\\-\\s().]{5,}\\d"
    )
    private val addressRegex = Regex(
        "(?i)\\b\\d{1,5}\\s+(?:[A-Za-z][A-Za-z'.]*\\s+){0,4}" +
            "(?:St(?:reet)?|Ave(?:nue)?|Rd|Road|Blvd|Boulevard|Ln|Lane|Dr(?:ive)?|Ct|Court|Pl(?:ace)?|Way|Pkwy|Parkway|Ter(?:race)?)\\.?\\b"
    )

    fun extract(messageId: Long, body: String): List<ElementEntity> {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<ElementEntity>()
        val masked = StringBuilder(body)

        fun mask(range: IntRange) {
            for (i in range) if (i < masked.length) masked.setCharAt(i, '\u0000')
        }

        // emails first, so "user@gmail.com" isn't half-eaten by the bare-domain link matcher
        emailRegex.findAll(masked.toString()).forEach {
            out += ElementEntity(messageId = messageId, type = ElementType.EMAIL, value = it.value)
            mask(it.range)
        }
        urlRegex.findAll(masked.toString()).forEach {
            val cleaned = it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'')
            if (cleaned.length > 3) {
                out += ElementEntity(messageId = messageId, type = ElementType.URL, value = cleaned)
            }
            mask(it.range)
        }
        addressRegex.findAll(masked.toString()).forEach {
            out += ElementEntity(messageId = messageId, type = ElementType.ADDRESS, value = it.value.trim())
            mask(it.range)
        }
        phoneRegex.findAll(masked.toString()).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 7..15) {
                out += ElementEntity(messageId = messageId, type = ElementType.PHONE, value = m.value.trim())
            }
        }
        return out.distinctBy { it.type.toString() + "|" + it.value }
    }
}

// ============================== Contacts ==============================
object MimeExt {
    /** Correct file extension for a MIME type. The old mapping dumped every
     *  unknown type — including mp3! — to ".bin", which broke playback app
     *  matching (stock players are extension-sensitive with content:// uris)
     *  and produced "x.bin.mp3" names on receiving phones. */
    fun forMime(mime: String?): String {
        val m = (mime ?: "").lowercase()
        val known = when {
            m.contains("jpeg") || m.contains("jpg") -> "jpg"
            m.contains("png") -> "png"
            m.contains("gif") -> "gif"
            m.contains("webp") -> "webp"
            m.contains("bmp") -> "bmp"
            m.contains("mp4") && m.startsWith("audio") -> "m4a"
            m.contains("mp4") -> "mp4"
            m.contains("3gpp2") -> "3g2"
            m.contains("3gpp") -> "3gp"
            m.contains("webm") -> "webm"
            m.contains("mkv") || m.contains("matroska") -> "mkv"
            m.contains("mpeg") && m.startsWith("audio") -> "mp3"
            m.contains("mp3") -> "mp3"
            m.contains("amr") -> "amr"
            m.contains("ogg") || m.contains("opus") -> "ogg"
            m.contains("wav") -> "wav"
            m.contains("flac") -> "flac"
            m.contains("aac") -> "aac"
            m.contains("midi") || m.contains("mid") -> "mid"
            m.contains("vcard") || m.contains("x-vcard") -> "vcf"
            m.contains("calendar") -> "ics"
            m.contains("pdf") -> "pdf"
            m.contains("plain") -> "txt"
            else -> null
        }
        if (known != null) return ".$known"
        val fromMap = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
        return if (!fromMap.isNullOrBlank()) ".$fromMap" else ".bin"
    }
}

object AudioSniff {
    /** Some carriers (Verizon's MMSC notably) relabel audio parts with their
     *  legacy audio/qcelp type. The bytes don't lie: identify the real format
     *  from the file magic and return (extension, mime), or null if unknown. */
    /** First bytes as hex, for diagnostics when a format isn't recognized. */
    fun magicHex(file: java.io.File): String = try {
        val b = ByteArray(12)
        val n = file.inputStream().use { it.read(b) }
        (0 until maxOf(0, n)).joinToString(" ") { String.format("%02X", b[it]) }
    } catch (_: Exception) { "?" }

    fun sniff(file: java.io.File): Pair<String, String>? {
        return try {
            val head = ByteArray(16)
            val n = file.inputStream().use { it.read(head) }
            if (n < 8) return null
            fun startsWith(sig: String, at: Int = 0) =
                head.size >= at + sig.length &&
                    String(head, at, sig.length, Charsets.ISO_8859_1) == sig
            // 3gp/mp4 boxes sometimes wrap AMR audio: ....ftyp3gp / ....ftypM4A
            when {
                startsWith("#!AMR-WB") -> ".awb" to "audio/amr-wb"
                startsWith("#!AMR") -> ".amr" to "audio/amr"
                startsWith("ftyp", 4) -> ".m4a" to "audio/mp4"
                startsWith("RIFF") && startsWith("QLCM", 8) -> ".qcp" to "audio/qcelp"
                startsWith("QLCM", 8) -> ".qcp" to "audio/qcelp"
                startsWith("RIFF") && startsWith("WAVE", 8) -> ".wav" to "audio/wav"
                startsWith("OggS") -> ".ogg" to "audio/ogg"
                startsWith("fLaC") -> ".flac" to "audio/flac"
                startsWith("ID3") -> ".mp3" to "audio/mpeg"
                (head[0].toInt() and 0xFF) == 0xFF &&
                    (head[1].toInt() and 0xE0) == 0xE0 -> ".mp3" to "audio/mpeg"
                else -> null
            }
        } catch (_: Exception) { null }
    }
}

object ContactsHelper {

    data class Contact(val name: String, val number: String, val photoUri: String = "")

    /** Lookup URI for viewing an existing contact, or null when unsaved. */
    fun lookupContactUri(context: Context, number: String): android.net.Uri? {
        if (number.isBlank() || number.contains("@") || !hasPermission(context)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) ContactsContract.Contacts.getLookupUri(
                    c.getLong(0), c.getString(1)
                ) else null
            }
        } catch (_: Exception) { null }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Full phone contact list (name + number + photo), used for auto-suggest and caches. */
    fun loadAll(context: Context): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val photo = c.getString(2) ?: ""
                    out += Contact(name, number, photo)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun lookupName(context: Context, address: String): String? {
        if (!hasPermission(context) || address.isBlank() || address.contains("@")) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    fun lookupPhoto(context: Context, address: String): String? {
        if (!hasPermission(context) || address.isBlank() || address.contains("@")) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
