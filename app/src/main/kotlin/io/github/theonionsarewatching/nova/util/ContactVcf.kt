package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Builds vCards from the contacts provider: one contact for attaching, or the
 *  whole book for export. Minimal, widely-compatible vCard 2.1 output. */
object ContactVcf {

    data class Card(val name: String, val vcf: String)

    fun buildFromContactUri(context: Context, contactUri: Uri): Card? {
        return try {
            context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                buildForContact(context, c.getLong(0), c.getString(1) ?: "")
            }
        } catch (_: Exception) { null }
    }

    private fun phoneTypeLabel(t: Int): String = when (t) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME,
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "FAX"
        else -> "CELL"
    }

    private fun buildForContact(context: Context, contactId: Long, name: String): Card {
        val tels = ArrayList<Pair<String, String>>()   // type label to number
        val emails = ArrayList<String>()
        var given = ""; var family = ""
        var org = ""; var note = ""; var bday = ""
        val addresses = ArrayList<String>()
        var photoB64: String? = null
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE),
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                arrayOf(contactId.toString()), null
            )?.use { while (it.moveToNext()) it.getString(0)?.let { n ->
                tels.add(phoneTypeLabel(it.getInt(1)) to n) } }
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                arrayOf(contactId.toString()), null
            )?.use { while (it.moveToNext()) it.getString(0)?.let { e -> emails.add(e) } }
            // structured name / org / note / birthday / postal, one data query
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, "data1", "data2", "data3"),
                ContactsContract.Data.CONTACT_ID + " = ?",
                arrayOf(contactId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    when (c.getString(0)) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            given = c.getString(2) ?: given
                            family = c.getString(3) ?: family
                        }
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE ->
                            org = c.getString(1) ?: org
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                            note = c.getString(1) ?: note
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                            if (c.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                                bday = c.getString(1) ?: bday
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                            c.getString(1)?.let { a -> addresses.add(a) }
                    }
                }
            }
            // contact photo (user request: pictures were being left out)
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                    ContactsContract.Data.MIMETYPE + " = ?",
                arrayOf(contactId.toString(),
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val bytes = c.getBlob(0)
                    if (bytes != null && bytes.isNotEmpty()) {
                        photoB64 = android.util.Base64.encodeToString(
                            bytes, android.util.Base64.NO_WRAP)
                    }
                }
            }
        } catch (_: Exception) {}
        val vcf = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:2.1\r\n")
            append("N:").append(family.replace(";", " ")).append(';')
                .append(given.ifBlank { name }.replace(";", " ")).append(";;;\r\n")
            append("FN:").append(name).append("\r\n")
            tels.forEach { (t, n) -> append("TEL;").append(t).append(':').append(n).append("\r\n") }
            emails.forEach { append("EMAIL:").append(it).append("\r\n") }
            if (org.isNotBlank()) append("ORG:").append(org.replace(";", " ")).append("\r\n")
            addresses.forEach { append("ADR:;;").append(it.replace("\n", " ")).append(";;;;\r\n") }
            if (bday.isNotBlank()) append("BDAY:").append(bday).append("\r\n")
            if (note.isNotBlank()) append("NOTE:").append(note.replace("\n", " ")).append("\r\n")
            photoB64?.let { b64 ->
                // vCard 2.1 photo with folded base64 (importers expect the
                // continuation lines to start with a space)
                append("PHOTO;ENCODING=BASE64;JPEG:")
                b64.chunked(74).forEachIndexed { i, chunk ->
                    if (i > 0) append(" ")
                    append(chunk).append("\r\n")
                }
                append("\r\n")
            }
            append("END:VCARD\r\n")
        }
        return Card(name.ifBlank { "contact" }, vcf)
    }

    /** Every contact in one .vcf. */
    fun exportAll(context: Context): String {
        val sb = StringBuilder()
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    sb.append(buildForContact(context, c.getLong(0), c.getString(1) ?: "").vcf)
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }
}
