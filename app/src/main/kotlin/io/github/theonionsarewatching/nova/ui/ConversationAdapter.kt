package io.github.theonionsarewatching.nova.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.databinding.ItemConversationBinding
import io.github.theonionsarewatching.nova.util.Formatters
import io.github.theonionsarewatching.nova.util.Prefs

class ConversationAdapter(
    private val onOpen: (ConversationEntity) -> Unit,
    private val onOptions: (ConversationEntity) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    var items: List<ConversationEntity> = emptyList()

    override fun getItemId(position: Int): Long = items[position].id

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
        b.root.background = ThemeUtils.focusFill(ctx)
        b.root.foreground = ThemeUtils.focusStroke(ctx)
        val pad = ThemeUtils.densityPad(ctx)
        b.root.setPadding(b.root.paddingLeft, pad, b.root.paddingRight, pad)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val prefs = Prefs.get(ctx)

        // ---- avatar: contact photo, or colored letter circle ----
        val title = c.displayTitle()
        if (c.cachedPhotoUri.isNotBlank()) {
            holder.b.convoAvatar.visibility = android.view.View.VISIBLE
            holder.b.avatarLetter.visibility = android.view.View.GONE
            holder.b.convoAvatar.load(android.net.Uri.parse(c.cachedPhotoUri)) {
                transformations(CircleCropTransformation())
            }
        } else {
            holder.b.convoAvatar.visibility = android.view.View.GONE
            holder.b.avatarLetter.visibility = android.view.View.VISIBLE
            val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
            holder.b.avatarLetter.text = letter.toString()
            val palette = intArrayOf(
                0xFF1565C0.toInt(), 0xFF00796B.toInt(), 0xFF2E7D32.toInt(), 0xFFE65100.toInt(),
                0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(), 0xFF546E7A.toInt()
            )
            val color = palette[kotlin.math.abs(c.convoKey.hashCode()) % palette.size]
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            holder.b.avatarLetter.background = bg
        }

        holder.b.convoTitle.text = title
        holder.b.iconPin.visibility =
            if (c.pinned) android.view.View.VISIBLE else android.view.View.GONE
        holder.b.iconBlocked.visibility =
            if (c.notifBlocked) android.view.View.VISIBLE else android.view.View.GONE
        holder.b.iconMuted.visibility =
            if (c.muted && !c.notifBlocked) android.view.View.VISIBLE else android.view.View.GONE
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
