package io.github.theonionsarewatching.nova.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LinearLayoutManager whose focus-driven "bring child into view" scrolls are CLAMPED
 * to a fixed step. Without this, focusing a tall message or a picture makes the list
 * jump to reveal the whole item; with it, every press moves the list by at most one
 * consistent step, and the DpadScroller's sub-scroll reads the rest gradually.
 */
class BoundedLinearLayoutManager(
    context: Context,
    private val maxStepPx: () -> Int
) : LinearLayoutManager(context) {

    // Honest, steady scrollbar for a lazily-paged list of variable-height rows.
    // Item-unit metrics: every message in the WHOLE conversation counts as one
    // unit (loaded or not), so the thumb (a) spans the entire thread, (b) moves
    // at a constant average pace instead of leaping over tall messages, and
    // (c) reaches the ends exactly at the first/last message.
    var totalBefore: Int = 0          // messages older than the loaded window
    var totalAfter: Int = 0           // messages newer than the loaded window
    private val UNIT = 1000

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int =
        (totalBefore + itemCount + totalAfter).coerceAtLeast(1) * UNIT

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        val first = findFirstVisibleItemPosition()
        val last = findLastVisibleItemPosition()
        if (first < 0 || last < 0) return UNIT
        return ((last - first + 1) * UNIT)
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        val first = findFirstVisibleItemPosition()
        if (first < 0) return 0
        // fractional progress through the first visible row, so the thumb
        // glides within a row instead of stepping row by row
        val v = findViewByPosition(first)
        val frac = if (v != null && v.height > 0) {
            ((-v.top).coerceAtLeast(0).toFloat() / v.height).coerceIn(0f, 1f)
        } else 0f
        return ((totalBefore + first) * UNIT + (frac * UNIT).toInt())
    }


    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean {
        val parentTop = parent.paddingTop
        val parentBottom = parent.height - parent.paddingBottom
        val childTop = child.top + rect.top
        val childBottom = child.top + rect.bottom

        // how far the default behavior would scroll
        val offScreenTop = minOf(0, childTop - parentTop)
        val offScreenBottom = maxOf(0, childBottom - parentBottom)
        var dy = if (offScreenBottom != 0) offScreenBottom else offScreenTop
        if (dy == 0) return false

        val cap = maxStepPx().coerceAtLeast(24)
        dy = dy.coerceIn(-cap, cap)
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }
}

/**
 * One-at-a-time D-pad movement with hold acceleration:
 * step grows with key repeat, capped, and damps near the list edges.
 * Optionally sub-scrolls items taller than the viewport line by line
 * before moving focus, so long messages are read in full.
 *
 * onEdge(down) fires when a press can't move further; return true to consume,
 * false to let the event fall through (e.g. so a press at the top can move focus
 * to the header).
 */
class DpadScroller(
    private val rv: RecyclerView,
    private val subScrollTallItems: Boolean,
    private val lineStepPx: () -> Int,
    private val onEdge: ((down: Boolean) -> Boolean)? = null
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
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return onEdge?.invoke(down) == true

        val focused = rv.focusedChild
        val pos = if (focused != null) rv.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
        if (pos == RecyclerView.NO_POSITION) {
            // focus fell off a recycled view mid-hold: re-anchor to the nearest
            // VISIBLE row in the direction of travel and keep going — never to
            // index 0, which is what made held scrolling snap back to the top
            val lm = rv.layoutManager as? LinearLayoutManager
            val anchor = if (down) {
                lm?.findLastVisibleItemPosition()?.takeIf { it >= 0 }
            } else {
                lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 }
            } ?: if (down) count - 1 else 0
            focusPosition(anchor, down)
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
        focusPosition(finalTarget, down)
        return true
    }

    fun focusPosition(position: Int) = focusPosition(position, null)

    /** towardBottom: when jumping to a not-yet-laid-out row during fast scroll,
     *  place it at the bottom edge (scrolling down) or top edge (scrolling up),
     *  so the focus stays on the leading edge of the movement. */
    fun focusPosition(position: Int, towardBottom: Boolean?) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val existing = rv.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            // the (bounded) layout manager handles keeping it in view
            existing.itemView.requestFocus()
        } else {
            val offset = if (towardBottom == true) (rv.height * 2 / 3).coerceAtLeast(0) else 0
        lm.scrollToPositionWithOffset(position, offset)
            rv.post {
                rv.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }
}
