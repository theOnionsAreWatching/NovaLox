package io.github.theonionsarewatching.nova.util

/** Pure photo-URI ordering: only Contacts-provider URIs, current IDs only. */
object ContactPhotoPolicy {
    fun candidates(full: String, thumb: String, contactId: Long?, photoId: Long?, fileId: Long?, probe: Boolean): List<String> {
        val out = ArrayList<String>()
        for (u in listOf(full, thumb)) if (u.startsWith("content://com.android.contacts/")) out.add(u)
        if (contactId != null && contactId > 0 &&
            (probe || out.isNotEmpty() || (photoId ?: 0) > 0 || (fileId ?: 0) > 0)) {
            out.add("content://com.android.contacts/contacts/$contactId/display_photo")
            out.add("content://com.android.contacts/contacts/$contactId/photo")
        }
        return out.distinct()
    }
}
