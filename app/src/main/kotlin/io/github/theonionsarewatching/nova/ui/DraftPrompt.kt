package io.github.theonionsarewatching.nova.ui


/** Draft attachments, kept per chat next to the draft text. */
object DraftPrompt {
    /** Attachments of a draft, stored as path\u0001mime\u0001name per line. */
    fun encode(list: List<Triple<String, String, String>>) =
        list.joinToString("\n") { "${it.first}\u0001${it.second}\u0001${it.third}" }
    fun decode(s: String): List<Triple<String, String, String>> =
        s.lines().mapNotNull { l -> l.split('\u0001').takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) } }
            .filter { java.io.File(it.first).exists() }
}
