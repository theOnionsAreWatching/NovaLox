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

    private fun buildForContact(context: Context, contactId: Long, name: String): Card {
        val tels = ArrayList<String>()
        val emails = ArrayList<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                arrayOf(contactId.toString()), null
            )?.use { while (it.moveToNext()) it.getString(0)?.let { n -> tels.add(n) } }
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                arrayOf(contactId.toString()), null
            )?.use { while (it.moveToNext()) it.getString(0)?.let { e -> emails.add(e) } }
        } catch (_: Exception) {}
        val vcf = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:2.1\r\n")
            append("N:;").append(name.replace(";", " ")).append(";;;\r\n")
            append("FN:").append(name).append("\r\n")
            tels.forEach { append("TEL;CELL:").append(it).append("\r\n") }
            emails.forEach { append("EMAIL:").append(it).append("\r\n") }
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
