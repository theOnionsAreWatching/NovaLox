package io.github.theonionsarewatching.nova.util

/**
 * The user tapped "download" on an MMS stub: the message that arrives moments
 * later is, to them, the SAME message they already have a notification for —
 * a second notification is noise. Mark the conversation at the tap; the
 * notification path checks (and consumes) the mark within a short window.
 */
object ManualDownloads {
    private val marks = HashMap<Long, Long>()

    @Synchronized
    fun mark(convoId: Long) { marks[convoId] = System.currentTimeMillis() }

    @Synchronized
    fun shouldSilence(convoId: Long): Boolean {
        val t = marks.remove(convoId) ?: return false
        return System.currentTimeMillis() - t < 90_000
    }
}
