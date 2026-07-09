package io.github.theonionsarewatching.nova.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.databinding.ItemConversationBinding
import io.github.theonionsarewatching.nova.util.Formatters
import io.github.theonionsarewatching.nova.util.Prefs

class ConversationAdapter(
    private val onOpen: (ConversationEntity) -> Unit,
    private val onOptions: (ConversationEntity) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    var items: List<ConversationEntity> = emptyList()

    fun submit(list: List<ConversationEntity>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemConversationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val ctx = parent.context
        b.root.isFocusable = true
        b.root.isFocusableInTouchMode = false
        b.root.foreground = ThemeUtils.focusForeground(ctx)
        val pad = ThemeUtils.densityPad(ctx)
        b.root.setPadding(b.root.paddingLeft, pad, b.root.paddingRight, pad)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val prefs = Prefs.get(ctx)

        val markers = buildString {
            if (c.pinned) append("\u2605 ")        // ★ pinned
            if (c.notifBlocked) append("\u2298 ")  // ⊘ notifications blocked
            else if (c.muted) append("(m) ")       // muted (silent)
        }
        holder.b.convoTitle.text = markers + c.displayTitle()
        holder.b.convoTitle.textSize = prefs.msgTextSp
        holder.b.convoTitle.setTypeface(null, if (c.unreadCount > 0) Typeface.BOLD else Typeface.NORMAL)

        val prefix = if (c.snippetIsMine) ctx.getString(R.string.you_prefix) + " " else ""
        val draft = c.draft.isNotBlank()
        holder.b.convoSnippet.text =
            if (draft) ctx.getString(R.string.draft_prefix) + " " + c.draft.take(80)
            else prefix + c.snippet
        holder.b.convoSnippet.textSize = prefs.msgTextSp - 2f
        holder.b.convoSnippet.setTypeface(null, if (c.unreadCount > 0) Typeface.BOLD else Typeface.NORMAL)

        holder.b.convoTime.text = if (c.snippetDate > 0) Formatters.listStamp(c.snippetDate) else ""
        holder.b.convoTime.textSize = prefs.timeTextSp
        holder.b.convoUnread.text = if (c.unreadCount > 0) "(${c.unreadCount})" else ""
        holder.b.convoUnread.textSize = prefs.timeTextSp
        holder.b.convoUnread.setTextColor(ThemeUtils.accentColor(ctx))

        holder.itemView.setOnClickListener { onOpen(c) }
        holder.itemView.setOnLongClickListener { onOptions(c); true }
    }
}
