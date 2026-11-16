package io.github.theonionsarewatching.nova.util

/**
 * Pure decision for "what does looking at the thread mark as read" — kept
 * free of Android types so it can be checked on a plain JVM.
 *
 *  - ALL: the newest message of the conversation is loaded AND on screen
 *    (last visible row is the last row, no newer page pending, and the
 *    database's newest message is that same row). Everything in the thread is
 *    then read — the same full update as "Mark as read" in the list.
 *  - UP_TO: reading further up; mark up to the last visible row only.
 *  - NONE: nothing laid out yet.
 */
object ReadMarking {
    enum class Mode { NONE, UP_TO, ALL }

    fun decide(
        lastVisible: Int,
        rowCount: Int,
        hasMoreNewer: Boolean,
        lastLoadedId: Long?,
        newestDbId: Long?
    ): Mode = when {
        rowCount <= 0 || lastVisible < 0 || lastVisible >= rowCount -> Mode.NONE
        !hasMoreNewer && lastVisible == rowCount - 1 &&
            lastLoadedId != null && lastLoadedId == newestDbId -> Mode.ALL
        else -> Mode.UP_TO
    }
}
