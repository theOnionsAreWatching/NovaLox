package io.github.theonionsarewatching.nova.ui

/** Screen-coordinate placement only; never changes the parent or hides system bars. */
internal object AudioOptionsPosition {
    fun top(parentTop: Int, parentHeight: Int, childHeight: Int, visibleTop: Int, visibleBottom: Int): Int {
        val lastTop = (visibleBottom - childHeight).coerceAtLeast(visibleTop)
        return (parentTop + parentHeight / 2 - childHeight / 2).coerceIn(visibleTop, lastTop)
    }
}
