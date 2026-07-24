package io.github.theonionsarewatching.nova.data

/**
 * A not-yet-downloaded incoming MMS, shown when auto-download is off.
 *
 * We avoid a schema migration by carrying the three things a tap-to-download
 * needs — content location, transaction id, subscription id — inside the
 * message body behind a private marker. The adapter recognizes the marker and
 * draws a Download button instead of text; nothing else in the app treats the
 * row specially, so search, backup and reconciliation keep working unchanged.
 *
 * When the user taps Download, MmsPushReceiver.fetch() runs the same platform
 * download the auto path uses; the arriving m_type=132 copy replaces this stub
 * (matched by transaction id in Repo.ingestMms).
 */
object MmsStub {
    private const val MARK = "\u0001nova-mms-stub\u0001"
    private const val SEP = "\u0001"

    fun encode(location: String, transactionId: String, subId: Int): String =
        MARK + location + SEP + transactionId + SEP + subId.toString()

    fun isStub(body: String): Boolean = body.startsWith(MARK)

    data class Info(val location: String, val transactionId: String, val subId: Int)

    fun decode(body: String): Info? {
        if (!isStub(body)) return null
        val parts = body.removePrefix(MARK).split(SEP)
        if (parts.size < 3) return null
        return Info(parts[0], parts[1], parts[2].toIntOrNull() ?: -1)
    }
}
