package io.github.theonionsarewatching.nova.ui

import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * One-at-a-time D-pad movement with hold acceleration:
 * step grows with key repeat, capped, and damps near the list edges.
 * Optionally sub-scrolls items taller than the viewport line by line
 * before moving focus, so long messages are read in full.
 */
class DpadScroller(
    private val rv: RecyclerView,
    private val subScrollTallItems: Boolean,
    private val lineStepPx: () -> Int,
    private val onEdge: ((down: Boolean) -> Boolean)? = null // return true = consumed at boundary
) {
    private val maxStep = 6

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val down = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_DPAD_UP -> false
            else -> return false
        }
        if (!rv.hasFocus()) return false
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return onEdge?.invoke(down) == true

        val focused = rv.focusedChild
        val pos = if (focused != null) rv.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
        if (pos == RecyclerView.NO_POSITION) {
            focusPosition(if (down) 0 else count - 1)
            return true
        }

        // sub-scroll a tall focused item before leaving it
        if (subScrollTallItems && focused != null) {
            val top = focused.top
            val bottom = focused.bottom
            val viewTop = rv.paddingTop
            val viewBottom = rv.height - rv.paddingBottom
            if (down && bottom > viewBottom + 4) {
                rv.scrollBy(0, minOf(lineStepPx(), bottom - viewBottom))
                return true
            }
            if (!down && top < viewTop - 4) {
                rv.scrollBy(0, -minOf(lineStepPx(), viewTop - top))
                return true
            }
        }

        // acceleration: faster the longer it's held, capped; slow near the edges
        var step = 1 + event.repeatCount / 5
        if (step > maxStep) step = maxStep
        val target = (if (down) pos + step else pos - step).coerceIn(0, count - 1)
        val nearEdge = target <= 1 || target >= count - 2
        val finalTarget = if (nearEdge && step > 1) {
            (if (down) pos + 1 else pos - 1).coerceIn(0, count - 1)
        } else target

        if (finalTarget == pos) {
            // at the boundary
            return onEdge?.invoke(down) == true
        }
        focusPosition(finalTarget)
        return true
    }

    fun focusPosition(position: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val existing = rv.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            existing.itemView.requestFocus()
            // keep it fully on screen
            rv.post {
                val v = existing.itemView
                val viewBottom = rv.height - rv.paddingBottom
                if (v.bottom > viewBottom) rv.scrollBy(0, minOf(v.bottom - viewBottom, v.height))
                if (v.top < rv.paddingTop) rv.scrollBy(0, -minOf(rv.paddingTop - v.top, v.height))
            }
        } else {
            lm.scrollToPositionWithOffset(position, 0)
            rv.post {
                rv.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }
}
