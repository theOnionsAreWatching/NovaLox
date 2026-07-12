package io.github.theonionsarewatching.nova.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import coil.load
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ElementEntity
import io.github.theonionsarewatching.nova.data.MessageEntity
import io.github.theonionsarewatching.nova.data.MsgStatus
import io.github.theonionsarewatching.nova.data.PartEntity
import io.github.theonionsarewatching.nova.databinding.ItemMessageBinding
import io.github.theonionsarewatching.nova.sms.Sender
import io.github.theonionsarewatching.nova.util.Formatters
import io.github.theonionsarewatching.nova.util.Prefs
import java.io.File

data class MessageRow(
    val msg: MessageEntity,
    val parts: List<PartEntity>,
    val elements: List<ElementEntity>,
    val senderName: String
) {
    val isSystemLine: Boolean get() = msg.address == SYSTEM_ADDRESS

    companion object {
        const val SYSTEM_ADDRESS = "__system__"
    }
}

class MessageAdapter(
    private val isGroup: Boolean,
    private val onPress: (MessageRow) -> Unit,
    private val onHold: (MessageRow) -> Unit,
    private val isSelected: (Long) -> Boolean = { false }
) : androidx.recyclerview.widget.RecyclerView.Adapter<MessageAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    var rows: List<MessageRow> = emptyList()

    override fun getItemId(position: Int): Long = rows[position].msg.id

    class VH(val b: ItemMessageBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.isFocusable = true
        b.root.isFocusableInTouchMode = false
        b.root.foreground = ThemeUtils.focusStroke(parent.context)
        return VH(b)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val m = row.msg
        val ctx = holder.itemView.context
        val prefs = Prefs.get(ctx)
        val accent = ThemeUtils.accentColor(ctx)
        val pad = ThemeUtils.densityPad(ctx)
        val dp = { v: Int -> ThemeUtils.dp(ctx, v) }

        holder.b.root.setPadding(dp(8), pad / 2, dp(8), pad / 2)

        // ---- system line ----
        if (row.isSystemLine) {
            holder.b.senderName.visibility = View.GONE
            holder.b.thumb.visibility = View.GONE
            holder.b.attachLabel.visibility = View.GONE
            holder.b.metaRow.visibility = View.GONE
            holder.b.accentBar.visibility = View.GONE
            holder.b.bubbleBox.background = null
            val lp = holder.b.bubbleBox.layoutParams as LinearLayout.LayoutParams
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER_HORIZONTAL
            holder.b.bubbleBox.layoutParams = lp
            holder.b.body.visibility = View.VISIBLE
            holder.b.body.text = m.body
            holder.b.body.textSize = prefs.msgTextSp - 3f
            holder.b.body.setTypeface(null, Typeface.ITALIC)
            holder.b.body.setTextColor(Color.GRAY)
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            return
        }

        // ---- sender name (incoming group messages) ----
        if (isGroup && !m.isMine) {
            holder.b.senderName.visibility = View.VISIBLE
            holder.b.senderName.text = row.senderName.ifBlank { m.address }
            holder.b.senderName.textSize = prefs.timeTextSp
            holder.b.senderName.setTextColor(accent)
        } else {
            holder.b.senderName.visibility = View.GONE
        }

        // ---- direction styling ----
        val bubbleParams = holder.b.bubbleBox.layoutParams as LinearLayout.LayoutParams
        holder.b.body.setTypeface(null, Typeface.NORMAL)
        holder.b.body.setTextColor(
            androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary)
        )
        when (prefs.messageStyle) {
            "accentbar" -> {
                holder.b.bubbleBox.background = null
                holder.b.accentBar.visibility = View.VISIBLE
                holder.b.accentBar.setBackgroundColor(if (m.isMine) accent else Color.GRAY)
                bubbleParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                bubbleParams.gravity = Gravity.START
                bubbleParams.marginStart = 0
                bubbleParams.marginEnd = 0
            }
            "plain" -> {
                holder.b.bubbleBox.background = null
                holder.b.accentBar.visibility = View.GONE
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
            else -> { // bubble
                holder.b.accentBar.visibility = View.GONE
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(
                        if (m.isMine) withAlpha(accent, 56)
                        else androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_other)
                    )
                }
                holder.b.bubbleBox.background = bg
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
        }
        holder.b.bubbleBox.layoutParams = bubbleParams
        holder.b.bubbleBox.setPadding(dp(8), dp(6), dp(8), dp(6))

        // ---- body ----
        holder.b.body.textSize = prefs.msgTextSp
        if (m.body.isNotBlank()) {
            holder.b.body.visibility = View.VISIBLE
            holder.b.body.text = m.body
        } else {
            holder.b.body.visibility = View.GONE
        }

        // ---- attachment preview ----
        val visual = row.parts.firstOrNull { it.isImage() || it.isVideo() }
        val other = row.parts.firstOrNull { !it.isImage() && !it.isVideo() }
        if (visual != null) {
            holder.b.thumb.visibility = View.VISIBLE
            holder.b.thumb.load(File(visual.filePath)) {
                size(dp(220), dp(220))
            }
        } else {
            holder.b.thumb.visibility = View.GONE
            holder.b.thumb.setImageDrawable(null)
        }
        if (other != null || (visual != null && row.parts.size > 1)) {
            holder.b.attachLabel.visibility = View.VISIBLE
            holder.b.attachLabel.textSize = prefs.msgTextSp - 2f
            holder.b.attachLabel.text = when {
                other == null -> ctx.getString(R.string.more_attachments, row.parts.size)
                other.isAudio() -> ctx.getString(R.string.attach_audio)
                other.isVCard() -> ctx.getString(R.string.attach_vcard)
                else -> ctx.getString(R.string.attach_file, other.fileName)
            }
        } else {
            holder.b.attachLabel.visibility = View.GONE
        }

        // ---- meta line: time + status ----
        holder.b.metaRow.visibility = View.VISIBLE
        holder.b.iconLock.visibility = if (m.locked) View.VISIBLE else View.GONE
        holder.b.iconScheduled.visibility =
            if (m.status == MsgStatus.SCHEDULED) View.VISIBLE else View.GONE
        holder.b.metaLine.textSize = prefs.timeTextSp
        val time = if (m.status == MsgStatus.SCHEDULED && m.scheduledAt != null)
            ctx.getString(R.string.scheduled_for, Formatters.full(m.scheduledAt))
        else Formatters.messageStamp(m.date)
        val status = if (m.isMine) Sender.statusLabel(ctx, m.status) else ""
        holder.b.metaLine.text = if (status.isNotBlank()) "$time \u00B7 $status" else time
        holder.b.metaLine.setTextColor(
            when (m.status) {
                MsgStatus.FAILED, MsgStatus.CANCELED -> Color.RED
                else -> Color.GRAY
            }
        )
        holder.b.metaRow.gravity = if (m.isMine && prefs.messageStyle != "accentbar") Gravity.END else Gravity.START

        // bulk-selection tint (solid row) vs normal focus shade (behind content only)
        if (isSelected(m.id)) {
            val accent2 = ThemeUtils.accentColor(ctx)
            holder.b.root.setBackgroundColor(
                Color.argb(56, Color.red(accent2), Color.green(accent2), Color.blue(accent2))
            )
        } else {
            holder.b.root.background = ThemeUtils.focusFill(ctx)
        }

        holder.itemView.setOnClickListener { onPress(row) }
        holder.itemView.setOnLongClickListener { onHold(row); true }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
