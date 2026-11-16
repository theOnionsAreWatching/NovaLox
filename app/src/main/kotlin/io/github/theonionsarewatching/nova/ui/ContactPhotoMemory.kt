package io.github.theonionsarewatching.nova.ui

import android.graphics.Bitmap
import android.util.LruCache

/** Small process-local cache of finished badge bitmaps. Avoids the fallback
 * flash when the chat list rebinds after leaving a thread. Not a Contacts
 * store; entries are dropped when a contact photo may have changed. */
object ContactPhotoMemory {
    private val lock = Any()
    // ~24 finished 42dp badges (~1–2 MB). Low-RAM keypad phones stay bounded.
    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, new: Bitmap?) {
            if (evicted && old !== new && !old.isRecycled) old.recycle()
        }
    }

    fun key(address: String, color: Int) = "round|${address.trim()}|$color"

    fun get(key: String): Bitmap? = synchronized(lock) {
        cache.get(key)?.takeUnless { it.isRecycled }
    }

    fun put(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        // Own copy: Coil may reuse/recycle its result bitmap.
        val kept = if (bitmap.isMutable) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        if (kept == null || kept.isRecycled) return
        synchronized(lock) { cache.put(key, kept) }
    }

    fun invalidateAddress(address: String) {
        val needle = address.trim()
        if (needle.isEmpty()) return
        synchronized(lock) {
            cache.snapshot().keys.filter { it.startsWith("round|$needle|") }.forEach { cache.remove(it) }
        }
    }

    fun clear() { synchronized(lock) { cache.evictAll() } }
}
