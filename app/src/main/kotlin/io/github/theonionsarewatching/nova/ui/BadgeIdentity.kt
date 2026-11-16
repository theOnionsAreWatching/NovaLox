package io.github.theonionsarewatching.nova.ui

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** Stable per-chat fallback identity: colour from a stable key (never list
 * position) and a short Unicode-safe monogram. */
object BadgeIdentity {
    /** All entries keep white text at >= 4.5:1 contrast. */
    val palette = intArrayOf(
        0xFF1565C0.toInt(), 0xFF087A65.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(),
        0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(), 0xFF455A64.toInt(), 0xFF283593.toInt(),
        0xFF006978.toInt(), 0xFF705700.toInt(), 0xFFB43B16.toInt(), 0xFF5D4037.toInt())

    fun color(key: String): Int = palette[Math.floorMod(key.hashCode(), palette.size)]

    private fun firstCluster(word: String): String {
        val upper = Normalizer.normalize(word, Normalizer.Form.NFC).uppercase(Locale.ROOT)
        if (upper.isEmpty()) return ""
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(upper)
        val end = it.following(0)
        return if (end == BreakIterator.DONE) "" else upper.substring(0, end)
    }

    fun initials(name: String): String {
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*")
            .findAll(Normalizer.normalize(name, Normalizer.Form.NFC)).map { it.value }.toList()
        if (words.isEmpty()) return ""
        return firstCluster(words.first()) + if (words.size > 1) firstCluster(words.last()) else ""
    }

    fun monogram(customName: String, names: List<String>, address: String, group: Boolean): String {
        initials(customName).takeIf { it.isNotBlank() }?.let { return it }
        if (group) {
            val firsts = names.filter { it.isNotBlank() }.map { initials(it) }.filter { it.isNotBlank() }.take(2)
            return if (firsts.isNotEmpty()) firsts.joinToString("") { firstCluster(it) } else "G"
        }
        names.firstOrNull { it.isNotBlank() }?.let { n -> initials(n).takeIf { it.isNotBlank() }?.let { return it } }
        // Unknown numbers: only the last two digits, never more of the number.
        val digits = address.codePoints().toArray().filter { Character.isDigit(it) }.takeLast(2)
        if (digits.isNotEmpty()) return digits.joinToString("") { String(Character.toChars(it)) }
        if (!address.contains('@')) return "#"
        return initials(address.substringBefore('@')).ifBlank { "@" }
    }
}
