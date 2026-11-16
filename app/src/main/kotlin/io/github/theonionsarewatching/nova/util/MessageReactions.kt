package io.github.theonionsarewatching.nova.util

/*
 * Ported from DPAD Messaging (https://github.com/UncleAndy123/DPAD-Messaging)
 * Copyright (c) 2025 DPAD Messaging
 * Licensed under the MIT License; the full license text is in
 * THIRD_PARTY_NOTICES.md at the root of this repository.
 *
 * Turns reaction fallback texts ("Liked “…”", "👍 to “…”") into reactions on
 * the message they refer to.
 *
 * This is display-only: nothing is changed in the SMS/MMS provider, so turning
 * the setting off shows every original message again. A fallback text is only
 * hidden when its target was actually found in the thread.
 */

/** A reaction shown on a message bubble, derived from a reaction fallback SMS/MMS. */
data class MessageReaction(
    val emoji: String,
    /** Contact name or number of the person who reacted; unused when [isMine]. */
    val senderName: String,
    val isMine: Boolean,
    val date: Long
)

object MessageReactions {

    /**
     * Hold-OK menu order: when the message has reactions, "View reactions"
     * leads the list (and so gets the first focus); otherwise it is left out.
     */
    fun <T> withViewReactionsFirst(items: List<T>, hasReactions: Boolean, viewReactions: T): List<T> =
        if (hasReactions) listOf(viewReactions) + items else items

    /** Identifies a message in D-SMS's own messages table. */
    data class Key(val id: Long)

    /**
     * The fields the matcher needs. Kept free of Android / Room types so the
     * logic can be checked on a plain JVM.
     */
    data class Item(
        val id: Long,
        val body: String,
        val date: Long,
        val isMine: Boolean,
        val address: String,
        val senderName: String,
        /** Incoming or already-sent; skip drafts, scheduled, notes, system lines. */
        val eligible: Boolean = true,
        val mimeTypes: List<String> = emptyList()
    ) {
        val hasAttachments: Boolean get() = mimeTypes.isNotEmpty()
    }

    class Result(
        /** Reaction fallback messages that are now shown as reactions instead. */
        val hidden: Set<Key>,
        /** Reactions per target message, oldest first. */
        val reactions: Map<Key, List<MessageReaction>>,
        /** Hidden reaction message -> the message it was attached to. */
        val targets: Map<Key, Key> = emptyMap()
    ) {
        companion object {
            val EMPTY = Result(emptySet(), emptyMap(), emptyMap())
        }
    }

    fun keyOf(item: Item) = Key(item.id)

    /** [messages] must be in chronological order (oldest first). */
    fun resolve(messages: List<Item>): Result {
        val hidden = HashSet<Key>()
        val targets = HashMap<Key, Key>()
        // target -> (sender -> reaction); each sender holds one reaction per message.
        val byTarget = HashMap<Key, LinkedHashMap<String, MessageReaction>>()

        for ((index, message) in messages.withIndex()) {
            if (!message.eligible) continue
            if (message.body.isBlank() || message.hasAttachments) continue
            val parsed = ReactionParser.parse(message.body) ?: continue

            // Edits stay visible as their own message; they are not hidden.
            if (parsed.kind == ReactionParser.Kind.EDIT) continue

            val target = findTarget(messages, index, parsed, hidden) ?: continue
            val senders = byTarget.getOrPut(keyOf(target)) { LinkedHashMap() }
            val sender = senderKey(message)
            senders.remove(sender)
            if (parsed.kind == ReactionParser.Kind.ADD) {
                senders[sender] = MessageReaction(
                    emoji = parsed.emoji,
                    senderName = message.senderName.ifBlank { message.address },
                    isMine = message.isMine,
                    date = message.date
                )
            }
            val reactionKey = keyOf(message)
            hidden.add(reactionKey)
            targets[reactionKey] = keyOf(target)
        }

        if (hidden.isEmpty()) return Result.EMPTY
        val reactions = byTarget
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.values.toList() }
        return Result(hidden, reactions, targets)
    }

    /** Compact pill text: "👍2 ❤️". */
    fun summarize(reactions: List<MessageReaction>): String {
        if (reactions.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        for (r in reactions) counts[r.emoji] = (counts[r.emoji] ?: 0) + 1
        return counts.entries.joinToString(" ") { (emoji, n) ->
            if (n > 1) "$emoji$n" else emoji
        }
    }

    /**
     * Short preview for the chat list or a notification when [reactionId] was
     * hidden and attached to a target in [messages]. Null if it should stay a
     * normal snippet.
     */
    data class Preview(
        val senderName: String,
        val isMine: Boolean,
        val emoji: String,
        val kind: ReactionParser.Kind,
        val targetIsMine: Boolean
    )

    /** Short list/notification text, using [youLabel] ("You") and [yourMessage] ("your message"). */
    fun formatPreview(preview: Preview, youLabel: String, yourMessage: String): String {
        val who = if (preview.isMine) youLabel else preview.senderName
        return if (preview.kind == ReactionParser.Kind.REMOVE) {
            "$who removed ${preview.emoji}"
        } else if (preview.targetIsMine) {
            "$who ${preview.emoji} $yourMessage"
        } else {
            "$who ${preview.emoji}"
        }
    }

    fun preview(reactionId: Long, messages: List<Item>): Preview? {
        val result = resolve(messages)
        val key = Key(reactionId)
        if (key !in result.hidden) return null
        val reaction = messages.find { it.id == reactionId } ?: return null
        val parsed = ReactionParser.parse(reaction.body) ?: return null
        if (parsed.kind == ReactionParser.Kind.EDIT) return null
        val targetKey = result.targets[key] ?: return null
        val target = messages.find { it.id == targetKey.id } ?: return null
        val name = if (reaction.isMine) "" else reaction.senderName.ifBlank { reaction.address }
        return Preview(name, reaction.isMine, parsed.emoji, parsed.kind, target.isMine)
    }

    private fun findTarget(
        messages: List<Item>,
        reactionIndex: Int,
        parsed: ReactionParser.Parsed,
        hidden: Set<Key>
    ): Item? {
        val quote = normalize(if (parsed.isTruncated) parsed.quotePrefix else parsed.quotedText)
        for (i in reactionIndex - 1 downTo 0) {
            val candidate = messages[i]
            val key = keyOf(candidate)
            if (key in hidden) continue
            val matches = if (parsed.media != ReactionParser.Media.NONE) {
                matchesMedia(candidate, parsed.media)
            } else {
                val body = normalize(candidate.body)
                when {
                    quote.isEmpty() || body.isEmpty() -> false
                    parsed.isTruncated -> body.startsWith(quote)
                    else -> body == quote
                }
            }
            if (matches) return candidate
        }
        return null
    }

    private fun matchesMedia(message: Item, media: ReactionParser.Media): Boolean {
        if (!message.hasAttachments) return false
        if (media == ReactionParser.Media.ANY) return true
        val prefix = when (media) {
            ReactionParser.Media.IMAGE -> "image/"
            ReactionParser.Media.VIDEO -> "video/"
            else -> "audio/"
        }
        return message.mimeTypes.any { it.startsWith(prefix) }
    }

    private fun senderKey(message: Item): String {
        if (message.isMine) return "me"
        val digits = message.address.filter(Char::isDigit)
        return if (digits.length >= 7) digits.takeLast(10) else message.address.trim().lowercase()
    }

    private val whitespace = Regex("\\s+")

    /** Makes quote comparison tolerant of smart punctuation and whitespace differences. */
    private fun normalize(text: String): String = text
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('\u00A0', ' ')
        .replace(whitespace, " ")
        .trim()
}
