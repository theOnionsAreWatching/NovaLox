package io.github.theonionsarewatching.nova.util

import android.content.Context

/**
 * Registry of telephony rows that are BROADCAST COPIES.
 *
 * A broadcast is an illusion: one message in the broadcast conversation, but
 * on the wire it's N separate one-to-one sends. Each copy gets its own row in
 * the telephony provider under the RECIPIENT'S thread. Without this registry
 * the sync/import passes found those rows unlinked and ingested them as new
 * outgoing messages in the one-to-one conversations — the same message
 * appearing twice. Sync consults [isCopy]/[isSmsCopy] to skip them, and the
 * delivery/read matcher uses [messageIdFor] so every copy's reports land on
 * the ONE broadcast message (which feeds "Delivered to N · Read by N").
 *
 * FORMATS (one comma-joined prefs string, oldest first):
 *   MMS: "tid:messageId"           — the pdu table is AUTOINCREMENT (verified
 *        in AOSP MmsSmsDatabaseHelper), so MMS row ids are NEVER reused and
 *        an id alone identifies the row forever.
 *   SMS: "s<rowId>:<messageId>:<dateMillis>" — the sms table has NO
 *        autoincrement, so row ids ARE recycled after deletions. An id-only
 *        entry could therefore claim a FUTURE unrelated message that lands on
 *        a recycled id, silently swallowing it. Each SMS entry now carries
 *        the row's date, and a match requires the dates to agree within one
 *        day — a recycled row months later has a different date and is
 *        correctly ingested. The date also makes LONG retention safe, which
 *        full re-import needs: an old copy row still matches its own entry by
 *        id + original date.
 *
 * Entries persist until the size cap evicts the oldest. Replies are
 * untouched: they arrive as genuine incoming 1:1 messages and belong in the
 * one-to-one conversations.
 */
object BroadcastCopies {

    // raised from 400: entries are ~14 (MMS) / ~28 (SMS) chars, so 2000 is
    // roughly 50 KB of prefs worst-case — fine for storage, and the parsed
    // in-memory cache below keeps lookups off the string entirely
    private const val MAX_ENTRIES = 2000
    private const val DAY_MS = 24 * 3600_000L

    // parsed snapshot of the prefs string, rebuilt on every write. The full
    // re-import loop calls isSmsCopy once per historical row; parsing a large
    // string per row would crawl on keypad-phone flash/CPU.
    @Volatile private var cacheSource: String? = null
    @Volatile private var mmsMap: Map<Long, Long> = emptyMap()
    @Volatile private var smsMap: Map<Long, Pair<Long, Long>> = emptyMap()

    private fun ensureCache(context: Context) {
        migrateSmsDatesOnce(context)
        val raw = Prefs.get(context).broadcastCopyMap
        if (raw === cacheSource) return
        val mms = HashMap<Long, Long>()
        val sms = HashMap<Long, Pair<Long, Long>>()
        raw.split(",").forEach { e ->
            if (e.isBlank()) return@forEach
            if (e.startsWith("s")) {
                val p = e.substring(1).split(":")
                if (p.size == 3) {
                    val id = p[0].toLongOrNull(); val msg = p[1].toLongOrNull()
                    val date = p[2].toLongOrNull()
                    if (id != null && msg != null && date != null) sms[id] = msg to date
                }
            } else {
                val p = e.split(":")
                if (p.size == 2) {
                    val id = p[0].toLongOrNull(); val msg = p[1].toLongOrNull()
                    if (id != null && msg != null) mms[id] = msg
                }
            }
        }
        mmsMap = mms
        smsMap = sms
        cacheSource = raw
    }

    // ---- MMS copies ----

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
        cacheSource = null
    }

    fun messageIdFor(context: Context, tid: Long?): Long? {
        if (tid == null || tid <= 0) return null
        ensureCache(context)
        return mmsMap[tid]
    }

    fun isCopy(context: Context, tid: Long): Boolean = messageIdFor(context, tid) != null

    // ---- SMS fan-out copies (text-only broadcast / group SMS) ----
    // The SMS path writes one telephony row PER RECIPIENT (Sender writes them
    // itself as the default SMS app); only the first row links to the app
    // message. The rest are registered here so no ingest route can turn them
    // into duplicate one-to-one messages. Keys are "s<id>" so the SMS and MMS
    // id spaces can't collide.

    /** [date] must be the DATE value written on the telephony row. */
    @Synchronized
    fun recordSms(context: Context, rowId: Long, messageId: Long, date: Long) {
        if (rowId <= 0) return
        val prefs = Prefs.get(context)
        val key = "s$rowId"
        val entries = prefs.broadcastCopyMap.split(",")
            .filter { it.isNotBlank() && !it.startsWith("$key:") }
            .toMutableList()
        entries += "$key:$messageId:$date"
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        prefs.broadcastCopyMap = entries.joinToString(",")
        cacheSource = null
    }

    /** A match needs the id AND the row's date within a day of the recorded
     *  one — the "same id can't be reused across days" rule that defuses SMS
     *  row-id recycling. */
    fun isSmsCopy(context: Context, rowId: Long, rowDate: Long): Boolean {
        if (rowId <= 0) return false
        ensureCache(context)
        val entry = smsMap[rowId] ?: return false
        return kotlin.math.abs(rowDate - entry.second) < DAY_MS
    }

    // ---- restore support ----

    /** Merge a backed-up registry string into the live one (backup/restore).
     *  Live entries win on key conflicts; backup entries are placed OLDEST so
     *  the size cap evicts them first. SMS entries from another phone are
     *  inert by construction: a match needs the row id AND a same-day date,
     *  which foreign rows won't satisfy. Legacy date-less SMS entries from an
     *  old backup are handled by the normal migration on next load. */
    @Synchronized
    fun restoreMerge(context: Context, backedUp: String) {
        if (backedUp.isBlank()) return
        val prefs = Prefs.get(context)
        val current = prefs.broadcastCopyMap.split(",").filter { it.isNotBlank() }
        val currentKeys = current.map { it.substringBefore(":") }.toHashSet()
        val incoming = backedUp.split(",").filter {
            it.isNotBlank() && it.contains(":") && it.substringBefore(":") !in currentKeys
        }
        if (incoming.isEmpty()) return
        val entries = (incoming + current).toMutableList()
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        prefs.broadcastCopyMap = entries.joinToString(",")
        cacheSource = null
        migrated = false  // a restored v1 backup can reintroduce date-less entries
        DiagLog.log(
            context, "bcast-registry",
            "restore merged ${incoming.size} entries from backup (${current.size} live kept)"
        )
    }

    // ---- one-time migration: stamp legacy id-only SMS entries with dates ----
    // Entries written before this version are "s<id>:<msgId>" with no date.
    // For each, the provider row's CURRENT date is the copy's real date while
    // the row still exists (ids aren't recycled while occupied) — stamp it.
    // Entries whose row is GONE are exactly the recycling hazard and carry no
    // remaining value: dropped.

    @Volatile private var migrated = false

    @Synchronized
    private fun migrateSmsDatesOnce(context: Context) {
        if (migrated) return
        migrated = true
        val prefs = Prefs.get(context)
        val raw = prefs.broadcastCopyMap
        if (!raw.split(",").any { it.startsWith("s") && it.count { ch -> ch == ':' } == 1 }) return
        try {
            val legacyIds = raw.split(",")
                .filter { it.startsWith("s") && it.count { ch -> ch == ':' } == 1 }
                .mapNotNull { it.substring(1).substringBefore(":").toLongOrNull() }
            val dates = HashMap<Long, Long>()
            if (legacyIds.isNotEmpty()) {
                context.contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms._ID, android.provider.Telephony.Sms.DATE),
                    "_id IN (${legacyIds.joinToString(",")})", null, null
                )?.use { c ->
                    while (c.moveToNext()) dates[c.getLong(0)] = c.getLong(1)
                }
            }
            var stamped = 0; var dropped = 0
            val rewritten = raw.split(",").mapNotNull { e ->
                if (!e.startsWith("s") || e.count { ch -> ch == ':' } != 1) return@mapNotNull e
                val id = e.substring(1).substringBefore(":").toLongOrNull()
                    ?: return@mapNotNull null
                val d = dates[id]
                if (d != null) { stamped++; "$e:$d" } else { dropped++; null }
            }.filter { it.isNotBlank() }
            prefs.broadcastCopyMap = rewritten.joinToString(",")
            cacheSource = null
            DiagLog.log(
                context, "bcast-registry",
                "sms-date migration: $stamped stamped from provider, $dropped dead entries dropped"
            )
        } catch (e: Exception) {
            DiagLog.log(context, "bcast-registry", "sms-date migration failed: $e")
        }
    }
}
