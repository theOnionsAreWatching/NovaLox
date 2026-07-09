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
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val fullFmt = SimpleDateFormat("EEE, d MMM yyyy HH:mm", Locale.getDefault())

    fun time(ts: Long): String = timeFmt.format(Date(ts))
    fun full(ts: Long): String = fullFmt.format(Date(ts))

    fun listStamp(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        ) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
    }
}

// ============================== Element extraction ==============================
object ElementExtractor {

    private val urlRegex = Regex(
        "(?i)\\b((?:https?://|www\\.)[\\w\\-]+(?:\\.[\\w\\-]+)+(?:[/?#][^\\s]*)?)"
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

        urlRegex.findAll(body).forEach {
            out += ElementEntity(messageId = messageId, type = ElementType.URL, value = it.value)
            mask(it.range)
        }
        emailRegex.findAll(masked.toString()).forEach {
            out += ElementEntity(messageId = messageId, type = ElementType.EMAIL, value = it.value)
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
object ContactsHelper {

    data class Contact(val name: String, val number: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Full phone contact list (name + number), used for auto-suggest and name cache. */
    fun loadAll(context: Context): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    out += Contact(name, number)
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
}
