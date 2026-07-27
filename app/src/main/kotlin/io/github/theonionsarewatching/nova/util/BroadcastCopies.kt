package io.github.theonionsarewatching.nova.util

import android.content.Context

/**
 * Registry of telephony MMS rows that are BROADCAST COPIES.
 *
 * A broadcast is an illusion: one message in the broadcast conversation, but
 * on the wire it's N separate one-to-one MMS sends. Each of those copies gets
 * its own row in the telephony provider under the RECIPIENT'S thread. Without
 * this registry the sync pass found those rows unlinked and ingested them as
 * new outgoing messages in the one-to-one conversations — the same message
 * appearing twice. Sync consults [isCopy] to skip them, and the delivery/read
 * matcher uses [messageIdFor] so every copy's reports land on the ONE
 * broadcast message (which is what feeds "Delivered to N · Read by N").
 *
 * Persisted in prefs as "tid:messageId" pairs, capped so it can't grow
 * without bound. Replies are untouched: they arrive as genuine incoming 1:1
 * messages and belong in the one-to-one conversations.
 */
object BroadcastCopies {

    private const val MAX_ENTRIES = 400

    @Synchronized
    fun record(context: Context, tid: Long, messageId: Long) {
        if (tid <= 0) return
        val prefs = Prefs.get(context)
        val entries = prefs.broadcastCopyMap.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$tid:") }
            .toMutableList()
        entries += "$tid:$messageId"
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        prefs.broadcastCopyMap = entries.joinToString(",")
    }

    fun messageIdFor(context: Context, tid: Long?): Long? {
        if (tid == null || tid <= 0) return null
        val prefix = "$tid:"
        return Prefs.get(context).broadcastCopyMap.split(",")
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(":")?.toLongOrNull()
    }

    fun isCopy(context: Context, tid: Long): Boolean = messageIdFor(context, tid) != null

    // ---- SMS fan-out copies (text-only broadcast / group SMS) ----
    // The SMS path writes one telephony row PER RECIPIENT (Sender writes them
    // itself as the default SMS app); only the first row links to the app
    // message. The rest are registered here so no ingest route can turn them
    // into duplicate one-to-one messages. Keys are "s<id>" so the SMS and MMS
    // id spaces can't collide.

    @Synchronized
    fun recordSms(context: Context, rowId: Long, messageId: Long) {
        if (rowId <= 0) return
        val prefs = Prefs.get(context)
        val key = "s$rowId"
        val entries = prefs.broadcastCopyMap.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$key:") }
            .toMutableList()
        entries += "$key:$messageId"
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        prefs.broadcastCopyMap = entries.joinToString(",")
    }

    fun isSmsCopy(context: Context, rowId: Long): Boolean {
        if (rowId <= 0) return false
        val prefix = "s$rowId:"
        return Prefs.get(context).broadcastCopyMap.split(",")
            .any { it.startsWith(prefix) }
    }
}
