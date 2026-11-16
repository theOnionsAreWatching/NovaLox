package io.github.theonionsarewatching.nova.data

import java.util.Locale

/** Pure MMS rules: no provider, network or deletion operations. */
object MmsContentPolicy {
    fun payloadType(type: Int) = type == 128 || type == 132

    fun unresolved(address: String) = address.isBlank() ||
        address.trim().equals("Unknown", true) ||
        address.trim().equals("insert-address-token", true)

    private fun identity(address: String): String {
        val lower = address.trim().lowercase(Locale.ROOT)
        if (lower.contains('@') || lower.any { it.isLetter() }) return lower
        // Full digits only: no suffix guessing between different numbers.
        return lower.filter { it.isDigit() }.ifEmpty { lower }
    }

    fun verifiedSender(from: List<String>): String? =
        from.filterNot { unresolved(it) }.distinctBy { identity(it) }.singleOrNull()

    /** Sender only counts as 1:1 when the native thread's own canonical
     * recipients are exactly that sender. */
    fun verifiedOneToOne(from: List<String>, to: List<String>, nativeRecipients: List<String>): String? {
        val sender = verifiedSender(from) ?: return null
        if (to.filterNot { unresolved(it) }.distinctBy { identity(it) }.size > 1) return null
        return if (nativeRecipients.filterNot { unresolved(it) }.map { identity(it) }.toSet() == setOf(identity(sender))) sender else null
    }

    fun managedPart(name: String, messageId: Long) = Regex("^m${messageId}_p[0-9]+(?:\\.|_repair)").containsMatchIn(name)

    fun copiedPart(name: String, messageId: Long, partId: Long): Boolean {
        val prefix = "m${messageId}_p${partId}"
        return name.startsWith("$prefix.") || name.startsWith("${prefix}_repair")
    }

    fun emptyContent(isMms: Boolean, body: String, parts: Int) = isMms && body.isBlank() && parts == 0

    fun partKind(type: String) = type.substringBefore(';').trim().lowercase(Locale.ROOT)

    fun unresolvedKey(providerId: Long) = "unresolved-mms:$providerId"
}
