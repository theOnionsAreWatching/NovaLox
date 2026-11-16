package io.github.theonionsarewatching.nova.util

import android.content.Context
import java.io.File

/**
 * Staging-time limits for attachments picked in bulk (gallery multi-select).
 * Mirrors what the send path can actually deliver in one MMS:
 *  - images are shrunk at send time (Sender, at least ~60 KB each), so they
 *    only count toward the item limit;
 *  - a video is compressed at send time to the WHOLE size budget
 *    (VideoCompressor.targetBytes), so only one video fits per message;
 *  - everything else (audio, gif, files) is sent as-is and must fit the
 *    carrier cap together (same cap as Sender: carrier max minus 30 KB).
 */
object AttachLimits {

    /** most attachments in one message (keeps each image above the
     *  ~60 KB floor of an ~800 KB MMS) */
    const val MAX_ITEMS = 10

    private fun shrinkable(mime: String) =
        mime.startsWith("image/") && !mime.equals("image/gif", true)

    /** True if a file of [mime]/[size] can join [current] (path, mime, name). */
    fun fits(context: Context, current: List<Triple<String, String, String>>, mime: String, size: Long): Boolean {
        if (current.size >= MAX_ITEMS) return false
        if (shrinkable(mime)) return true
        if (mime.startsWith("video/")) return current.none { it.second.startsWith("video/") }
        val cap = CarrierMms.limits(context).maxBytes - 30 * 1024
        val fixed = current.filter { !shrinkable(it.second) && !it.second.startsWith("video/") }
            .sumOf { try { File(it.first).length() } catch (_: Exception) { 0L } }
        return fixed + size <= cap
    }
}
