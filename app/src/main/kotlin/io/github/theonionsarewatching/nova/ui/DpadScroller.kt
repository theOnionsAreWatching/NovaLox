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
 * onEdge(down, fresh) fires when a press can't move further; `fresh` is true only
 * for a deliberate standalone press — NOT for key repeats, and NOT for the rapid
 * separate presses some keypad hardware emits while a key is held (detected by
 * timing). Return true to consume, false to let the event fall through
 * (e.g. so only a fresh press at the top can move focus to the header).
 */
class DpadScroller(
    private val rv: RecyclerView,
    private val subScrollTallItems: Boolean,
    private val lineStepPx: () -> Int,
    private val onEdge: ((down: Boolean, fresh: Boolean) -> Boolean)? = null
) {
    private val maxStep = 6
    private var lastNavDownAt = 0L
    private var syntheticRepeat = 0

    /** A press only counts as deliberate if the key wasn't just pressed a moment ago. */
    private fun freshness(event: KeyEvent): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val gap = now - lastNavDownAt
        lastNavDownAt = now
        // hardware repeats and hold-emitted rapid presses arrive well under this gap
        val fresh = event.repeatCount == 0 && gap > 350
        syntheticRepeat = if (fresh) 0 else syntheticRepeat + 1
        return fresh
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val down = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_DPAD_UP -> false
            else -> return false
        }
        if (!rv.hasFocus()) return false
        val fresh = freshness(event)
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return onEdge?.invoke(down, fresh) == true

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

        // acceleration: faster the longer it's held, capped; slow near the edges.
        // syntheticRepeat also covers hardware that repeats as separate presses.
        var step = 1 + maxOf(event.repeatCount, syntheticRepeat) / 5
        if (step > maxStep) step = maxStep
        val target = (if (down) pos + step else pos - step).coerceIn(0, count - 1)
        val nearEdge = target <= 1 || target >= count - 2
        val finalTarget = if (nearEdge && step > 1) {
            (if (down) pos + 1 else pos - 1).coerceIn(0, count - 1)
        } else target

        if (finalTarget == pos) {
            // at the boundary
            return onEdge?.invoke(down, fresh) == true
        }
        focusPosition(finalTarget)
        return true
    }

    fun focusPosition(position: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val existing = rv.findViewHolderForAdapterPosition(position)
        if (existing != null) {
            // the (bounded) layout manager handles keeping it in view
            existing.itemView.requestFocus()
        } else {
            lm.scrollToPositionWithOffset(position, 0)
            rv.post {
                rv.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }
}
