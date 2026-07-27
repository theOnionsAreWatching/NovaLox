package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.view.View

/**
 * Focus tracing for "focus jumps to the top bar."
 *
 * A global focus-change listener only hands you two View objects; this turns
 * them into names you can act on — the resource id name (`btnBack`,
 * `threadTitle`, `msgList`), the adapter position when the view lives in the
 * message list, and the chain of named ancestors when the view itself has no
 * id. With that, the log says exactly WHICH view took focus and WHERE it sits,
 * instead of leaving us to infer it from behavior.
 */
object FocusTrace {

    private fun name(context: Context, v: View?): String {
        if (v == null) return "<none>"
        val id = v.id
        val own = if (id != View.NO_ID) {
            try { context.resources.getResourceEntryName(id) } catch (_: Exception) { "id$id" }
        } else null
        val cls = v.javaClass.simpleName
        if (own != null) return "$own($cls)"
        // no id of its own: name the nearest identified ancestor so the view
        // is still locatable in the layout
        var p: android.view.ViewParent? = v.parent
        var depth = 0
        while (p is View && depth++ < 4) {
            val pv: View = p
            val pid = pv.id
            if (pid != View.NO_ID) {
                val pn = try {
                    context.resources.getResourceEntryName(pid)
                } catch (_: Exception) { "id$pid" }
                return "$cls under $pn"
            }
            p = pv.parent
        }
        return cls
    }

    fun log(
        context: Context,
        old: View?,
        new: View?,
        list: androidx.recyclerview.widget.RecyclerView?,
        state: String
    ) {
        try {
            val inList = { v: View? ->
                if (v != null && list != null && v.parent === list)
                    " pos=" + list.getChildAdapterPosition(v) else ""
            }
            DiagLog.log(
                context, "focus",
                "${name(context, old)}${inList(old)} -> " +
                    "${name(context, new)}${inList(new)} | $state"
            )
        } catch (_: Exception) {
        }
    }
}
