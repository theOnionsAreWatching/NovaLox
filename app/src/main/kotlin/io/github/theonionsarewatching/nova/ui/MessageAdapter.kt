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
import coil.request.videoFrameMillis
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
    private val isSelected: (Long) -> Boolean = { false },
    /** current chat backdrop as an opaque color (mid grey for photo bgs) —
     *  the selection ring must contrast with the backdrop AND the bubble */
    private val backdropColor: () -> Int = { Color.WHITE }
) : androidx.recyclerview.widget.RecyclerView.Adapter<MessageAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    var rows: List<MessageRow> = emptyList()

    /** The first message that was unread when the conversation was opened —
     *  the "New messages" line renders above it for the whole visit. */
    var newDividerMessageId: Long = -1L

    /** Replace the data with a minimal diff — only genuinely changed rows
     *  rebind, so a status tick no longer repaints (and flashes) the screen. */
    fun submit(newRows: List<MessageRow>) {
        val old = rows
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(o: Int, n: Int) =
                    old[o].msg.id == newRows[n].msg.id
                override fun areContentsTheSame(o: Int, n: Int) =
                    old[o].signature() == newRows[n].signature()
                override fun getChangePayload(o: Int, n: Int): Any? {
                    // if everything EXCEPT the meta line (status/time) is equal,
                    // send a lightweight payload so only the meta text updates
                    val a = old[o]; val b = newRows[n]
                    return if (a.bodySignature() == b.bodySignature()) "meta" else null
                }
            }, false
        )
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

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

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("meta")) {
            // status/time-only change: repaint just the meta line, leave the
            // bubble and thumbnail untouched so the row doesn't flash
            val row = rows[position]
            val m = row.msg
            val ctx = holder.itemView.context
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
            holder.b.iconScheduled.visibility =
                if (m.status == MsgStatus.SCHEDULED) View.VISIBLE else View.GONE
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val m = row.msg
        val ctx = holder.itemView.context
        holder.b.newDivider.visibility =
            if (m.id == newDividerMessageId) View.VISIBLE else View.GONE
        holder.b.newDivider.setTextColor(ThemeUtils.accentColor(ctx))
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
                // bar + tail drawn as the BACKGROUND, so long text can never
                // squeeze it out of the layout; padding keeps a gap to the text
                holder.b.accentBar.visibility = View.GONE
                val barColor = if (m.isMine) accent else Color.GRAY
                val card = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(cardColor(ctx))
                }
                holder.b.bubbleBox.background = android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        card,
                        AccentBarDrawable(
                            barColor, tailOnRight = m.isMine,
                            tailPx = dp(6).toFloat(), barPx = dp(4).toFloat(),
                            gapPx = dp(7).toFloat()
                        )
                    )
                )
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
            "plain" -> {
                holder.b.bubbleBox.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(cardColor(ctx))
                }
                holder.b.accentBar.visibility = View.GONE
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
            "square" -> { // square corners with a small side tail
                holder.b.accentBar.visibility = View.GONE
                val fill = bubbleFillColor(ctx, m.isMine, accent)
                holder.b.bubbleBox.background = TailBubbleDrawable(
                    fill, dp(4).toFloat(), tailOnRight = m.isMine, tailPx = dp(7).toFloat()
                )
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
            else -> { // bubble: rounded body, the original bottom-corner tail
                holder.b.accentBar.visibility = View.GONE
                holder.b.bubbleBox.background = BottomTailBubbleDrawable(
                    bubbleFillColor(ctx, m.isMine, accent), dp(10).toFloat(),
                    tailOnRight = m.isMine, tailPx = dp(7).toFloat()
                )
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                bubbleParams.gravity = if (m.isMine) Gravity.END else Gravity.START
                bubbleParams.marginStart = if (m.isMine) dp(28) else 0
                bubbleParams.marginEnd = if (m.isMine) 0 else dp(28)
            }
        }
        // custom sent-side color: white text when the chosen fill is dark
        // (light theme only; night uses fixed dark fills + light text_primary)
        if (!ThemeUtils.isNight(ctx) && m.isMine && customSentColor(ctx) != null &&
            prefs.messageStyle != "plain" && prefs.messageStyle != "accentbar"
        ) {
            val fill = customSentColor(ctx)!!
            if (androidx.core.graphics.ColorUtils.calculateLuminance(fill) < 0.5) {
                holder.b.body.setTextColor(Color.WHITE)
            }
        }
        // the effective fill this row sits on — drives adaptive text below
        val effectiveFill = when (prefs.messageStyle) {
            "plain", "accentbar" -> cardColor(ctx)
            else -> if (!ThemeUtils.isNight(ctx) && m.isMine && customSentColor(ctx) != null)
                customSentColor(ctx)!!
            else bubbleFillColor(ctx, m.isMine, accent)
        }
        holder.b.bubbleBox.layoutParams = bubbleParams
        // the background drawables (tails, accent bar) declare content insets;
        // a bare setPadding here ERASED them — that's why text touched the bar
        // and sat on the bubble tail. Merge them into the base padding instead.
        val bgPad = android.graphics.Rect()
        val hasBgPad = holder.b.bubbleBox.background?.getPadding(bgPad) == true
        if (!hasBgPad) bgPad.set(0, 0, 0, 0)
        holder.b.bubbleBox.setPadding(
            dp(8) + bgPad.left, dp(6) + bgPad.top,
            dp(8) + bgPad.right, dp(6) + bgPad.bottom
        )

        // ---- body ----
        holder.b.body.textSize = prefs.msgTextSp
        val stub = io.github.theonionsarewatching.nova.data.MmsStub.decode(m.body)
        if (stub != null) {
            // not-yet-downloaded MMS (auto-download off): a tap-to-download line
            holder.b.body.visibility = View.VISIBLE
            val label = ctx.getString(R.string.mms_tap_to_download)
            val sp = android.text.SpannableString(label)
            sp.setSpan(android.text.style.UnderlineSpan(), 0, label.length, 0)
            holder.b.body.text = sp
            holder.b.body.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_download_small, 0, 0, 0
            )
            holder.b.body.compoundDrawablePadding = dp(6)
        } else if (m.body.isNotBlank()) {
            holder.b.body.visibility = View.VISIBLE
            holder.b.body.text = m.body
            holder.b.body.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            holder.b.body.compoundDrawablePadding = 0
        } else {
            holder.b.body.visibility = View.GONE
            holder.b.body.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }

        // ---- attachment preview ----
        val visual = row.parts.firstOrNull { it.isImage() || it.isVideo() }
        val other = row.parts.firstOrNull { !it.isImage() && !it.isVideo() }
        if (visual != null) {
            holder.b.thumb.visibility = View.VISIBLE
            holder.b.thumb.load(File(visual.filePath)) {
                size(dp(220), dp(220))
                // videos: grab a frame from 1s in — frame zero is often black
                if (visual.isVideo()) videoFrameMillis(1000)
            }
            // play badge over video thumbnails
            if (visual.isVideo()) {
                val badge = androidx.core.content.ContextCompat.getDrawable(
                    ctx, io.github.theonionsarewatching.nova.R.drawable.ic_play_badge
                )
                val layer = android.graphics.drawable.LayerDrawable(arrayOf(badge))
                val sz = dp(40)
                layer.setLayerGravity(0, Gravity.CENTER)
                layer.setLayerSize(0, sz, sz)
                holder.b.thumb.foreground = layer
            } else {
                holder.b.thumb.foreground = null
            }
        } else {
            holder.b.thumb.visibility = View.GONE
            holder.b.thumb.setImageDrawable(null)
        }
        val nonVisual = row.parts.filter { !it.isImage() && !it.isVideo() }
        val multiNonVisual = nonVisual.size > 1
        if (other != null || (visual != null && row.parts.size > 1)) {
            holder.b.attachLabel.visibility = View.VISIBLE
            holder.b.attachLabel.textSize = prefs.msgTextSp - 2f
            holder.b.attachLabel.maxLines = 1
            holder.b.attachLabel.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            // the label text (audio filename, contact card lines, file name)
            // follows the body text color for this bubble exactly — deriving
            // it from raw fill luminance went light-on-light on translucent
            // accent fills, whose raw color is dark but which render light
            val bodyColor = holder.b.body.currentTextColor
            holder.b.attachLabel.setTextColor(bodyColor)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                holder.b.attachLabel,
                android.content.res.ColorStateList.valueOf(bodyColor)
            )
            holder.b.attachLabel.text = when {
                // several non-visual parts collapse to a count; the tap opens
                // the chooser so each one is still reachable
                multiNonVisual -> ctx.getString(R.string.more_attachments, nonVisual.size)
                other == null -> ctx.getString(R.string.more_attachments, row.parts.size)
                other.isAudio() -> other.fileName
                other.isVCard() -> vcardSummary(ctx, other)
                else -> ctx.getString(R.string.attach_file, other.fileName)
            }
            if (!multiNonVisual && other != null && other.isAudio()) {
                // headphones marker so an audio message is recognizable at a glance
                holder.b.attachLabel.background = null
                holder.b.attachLabel.setCompoundDrawablesWithIntrinsicBounds(
                    io.github.theonionsarewatching.nova.R.drawable.ic_headphones_small, 0, 0, 0
                )
                holder.b.attachLabel.compoundDrawablePadding = dp(6)
                holder.b.attachLabel.setPadding(0, dp(2), 0, dp(2))
            } else if (!multiNonVisual && other != null && other.isVCard()) {
                // rounded contact card: icon + name / number / email
                holder.b.attachLabel.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(withAlpha(Color.GRAY, 40))
                    setStroke(1, withAlpha(Color.GRAY, 90))
                }
                holder.b.attachLabel.setCompoundDrawablesWithIntrinsicBounds(
                    io.github.theonionsarewatching.nova.R.drawable.ic_person_small, 0, 0, 0
                )
                holder.b.attachLabel.compoundDrawablePadding = dp(8)
                holder.b.attachLabel.setPadding(dp(10), dp(8), dp(12), dp(8))
            } else {
                holder.b.attachLabel.background = null
                holder.b.attachLabel.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                holder.b.attachLabel.setPadding(0, 0, 0, 0)
            }
        } else {
            holder.b.attachLabel.visibility = View.GONE
        }

        // ---- meta line: time + status ----
        holder.b.metaRow.visibility = View.VISIBLE
        holder.b.iconAttach.visibility =
            if (row.parts.isNotEmpty()) View.VISIBLE else View.GONE
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

        // focus shade stays on the row — a neutral grey, not the accent, so the
        // bubble's own colors stay legible; SELECTION outlines the bubble itself
        holder.b.root.background = ThemeUtils.focusFillNeutral(ctx)
        holder.b.root.foreground = null
        if (isSelected(m.id)) {
            // single clean ring that adapts: accent when it reads against the
            // bubble fill, otherwise the on-fill contrast color; corner radius
            // follows the bubble shape so the ring hugs the message
            val density = ctx.resources.displayMetrics.density
            val radius = when (prefs.messageStyle) {
                "square" -> dp(4).toFloat()
                "plain", "accentbar" -> dp(6).toFloat()
                else -> dp(10).toFloat()
            }
            // translucent fills (the accent-tinted sent bubble) must be
            // composited over the backdrop before judging brightness — raw
            // luminance of a 22%-alpha color says "dark" for what renders light
            val backdrop = backdropColor()
            val fillOnScreen = androidx.core.graphics.ColorUtils
                .compositeColors(effectiveFill, backdrop)
            fun lum(c: Int) = androidx.core.graphics.ColorUtils.calculateLuminance(c)
            val candidates = listOf(accent, Color.WHITE, 0xFF202020.toInt())
            val ringColor = candidates.firstOrNull { c ->
                kotlin.math.abs(lum(c) - lum(fillOnScreen)) >= 0.22 &&
                    kotlin.math.abs(lum(c) - lum(backdrop)) >= 0.22
            } ?: candidates.maxByOrNull { c ->
                minOf(
                    kotlin.math.abs(lum(c) - lum(fillOnScreen)),
                    kotlin.math.abs(lum(c) - lum(backdrop))
                )
            }!!
            val ring = GradientDrawable().apply {
                cornerRadius = radius
                setColor(withAlpha(ringColor, 26))
                setStroke((2.5f * density).toInt(), ringColor)
            }
            holder.b.bubbleBox.foreground = ring
        } else {
            holder.b.bubbleBox.foreground = null
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onPress(rows[pos])
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onHold(rows[pos])
            true
        }
    }

}

/** Content fingerprint for DiffUtil: any field the row DISPLAYS. When two
 *  rows share an id but differ here, exactly that row rebinds. */
fun MessageRow.signature(): String = buildString {
    append(msg.status); append('|')
    append(msg.body); append('|')
    append(msg.date); append('|')
    append(msg.locked); append('|')
    append(msg.scheduledAt ?: 0L); append('|')
    append(parts.size)
}

/** Signature of everything the full bind renders EXCEPT the meta line, so a
 *  status/time-only change can be applied as a partial update (no flash). */
fun MessageRow.bodySignature(): String = buildString {
    append(msg.body); append('|')
    append(msg.locked); append('|')
    append(parts.size); append('|')
    append(msg.isMine)
}

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private var sentColorCache: Pair<String, Int?>? = null

/** The user's custom sent-bubble color, or null for the default tint. */
private fun customSentColor(ctx: android.content.Context): Int? {
    val hex = io.github.theonionsarewatching.nova.util.Prefs.get(ctx).sentColor
    sentColorCache?.let { if (it.first == hex) return it.second }
    val parsed = hex.takeIf { it.isNotBlank() }?.let {
        try { Color.parseColor(it) } catch (_: Exception) { null }
    }
    sentColorCache = hex to parsed
    return parsed
}

private fun bubbleFillColor(ctx: android.content.Context, isMine: Boolean, accent: Int): Int {
    // dark theme: darker neutral bubbles with light text; colored fills
    // (accent tint / custom sent color) are a light-theme look only
    if (ThemeUtils.isNight(ctx)) {
        return if (isMine) 0xFF37373E.toInt() else 0xFF2A2A2E.toInt()
    }
    if (isMine) customSentColor(ctx)?.let { return it }
    return if (isMine) withAlpha(accent, 56)
    else androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_other)
}

/** The card behind plain / accent-bar messages: white over the light-grey
 *  chat background in light theme, dark grey in dark theme. */
private fun cardColor(ctx: android.content.Context): Int =
    if (ThemeUtils.isNight(ctx)) 0xFF2A2A2E.toInt() else Color.WHITE

/** Text color that reads on the given fill. */
private fun onColor(fill: Int): Int =
    if (androidx.core.graphics.ColorUtils.calculateLuminance(fill) < 0.5) Color.WHITE
    else 0xDE000000.toInt()

/** name / numbers / emails pulled from a tiny .vcf for the in-bubble card —
 *  lines prefixed with small line icons (no emoji). */
private fun vcardSummary(ctx: android.content.Context, part: PartEntity): CharSequence {
    return try {
        val text = java.io.File(part.filePath).readText().take(4000)
        val name = text.lineSequence()
            .firstOrNull { it.startsWith("FN", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val tels = text.lineSequence()
            .filter { it.startsWith("TEL", ignoreCase = true) }
            .mapNotNull { it.substringAfter(':').trim().takeIf { t -> t.isNotBlank() } }
            .take(2).toList()
        val emails = text.lineSequence()
            .filter { it.startsWith("EMAIL", ignoreCase = true) }
            .mapNotNull { it.substringAfter(':').trim().takeIf { e -> e.isNotBlank() } }
            .take(1).toList()
        val sb = android.text.SpannableStringBuilder()
        sb.append(name ?: part.fileName)
        fun iconLine(iconRes: Int, value: String) {
            sb.append('\n')
            val at = sb.length
            sb.append("\u2000 ")   // placeholder for the icon span + a space
            androidx.core.content.ContextCompat.getDrawable(ctx, iconRes)?.let { d ->
                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                sb.setSpan(
                    android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_BASELINE),
                    at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            sb.append(value)
        }
        tels.forEach { iconLine(io.github.theonionsarewatching.nova.R.drawable.ic_phone_line, it) }
        emails.forEach { iconLine(io.github.theonionsarewatching.nova.R.drawable.ic_mail_line, it) }
        sb
    } catch (_: Exception) { part.fileName }
}

/** Rounded rectangle with a small tail on the outer SIDE, near the top —
 *  the classic square-bubble look. Content is auto-padded off the tail. */
class TailBubbleDrawable(
    private val color: Int,
    private val radiusPx: Float,
    private val tailOnRight: Boolean,
    private val tailPx: Float
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@TailBubbleDrawable.color
        style = android.graphics.Paint.Style.FILL
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val body = android.graphics.RectF(
            b.left + if (tailOnRight) 0f else tailPx,
            b.top.toFloat(),
            b.right - if (tailOnRight) tailPx else 0f,
            b.bottom.toFloat()
        )
        path.reset()
        path.addRoundRect(body, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        // reference shape: starts below the top, top edge almost horizontal,
        // bottom edge long and steep back to the bubble
        val t0 = body.top + radiusPx.coerceAtLeast(3f) + 2f
        if (tailOnRight) {
            path.moveTo(body.right, t0)
            path.lineTo(body.right + tailPx, t0 + tailPx * 0.22f)
            path.lineTo(body.right, t0 + tailPx * 2.1f)
        } else {
            path.moveTo(body.left, t0)
            path.lineTo(body.left - tailPx, t0 + tailPx * 0.22f)
            path.lineTo(body.left, t0 + tailPx * 2.1f)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun getPadding(padding: android.graphics.Rect): Boolean {
        padding.set(if (tailOnRight) 0 else tailPx.toInt(), 0,
            if (tailOnRight) tailPx.toInt() else 0, 0)
        return true
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Accent-bar style background: a thin bar on the outer edge with an outward
 *  tail; getPadding keeps the text clear of both. */
class AccentBarDrawable(
    private val color: Int,
    private val tailOnRight: Boolean,
    private val tailPx: Float,
    private val barPx: Float,
    private val gapPx: Float
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@AccentBarDrawable.color
        style = android.graphics.Paint.Style.FILL
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val bar = if (tailOnRight)
            android.graphics.RectF(b.right - tailPx - barPx, b.top.toFloat(),
                b.right - tailPx, b.bottom.toFloat())
        else
            android.graphics.RectF(b.left + tailPx, b.top.toFloat(),
                b.left + tailPx + barPx, b.bottom.toFloat())
        path.reset()
        path.addRect(bar, android.graphics.Path.Direction.CW)
        val t0 = b.top + 5f
        if (tailOnRight) {
            path.moveTo(bar.right, t0)
            path.lineTo(bar.right + tailPx, t0 + tailPx * 0.22f)
            path.lineTo(bar.right, t0 + tailPx * 2.1f)
        } else {
            path.moveTo(bar.left, t0)
            path.lineTo(bar.left - tailPx, t0 + tailPx * 0.22f)
            path.lineTo(bar.left, t0 + tailPx * 2.1f)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun getPadding(padding: android.graphics.Rect): Boolean {
        val side = (tailPx + barPx + gapPx).toInt()
        padding.set(if (tailOnRight) 0 else side, 0, if (tailOnRight) side else 0, 0)
        return true
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** The original bubble tail: a small triangle at the bottom outer corner. */
class BottomTailBubbleDrawable(
    private val color: Int,
    private val radiusPx: Float,
    private val tailOnRight: Boolean,
    private val tailPx: Float
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@BottomTailBubbleDrawable.color
        style = android.graphics.Paint.Style.FILL
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val body = android.graphics.RectF(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom - tailPx
        )
        // body + tail as a SINGLE union path, filled once — overlapping two
        // translucent fills darkened the seam on alpha bubbles (received side).
        // Path.op UNION merges them so the fill is uniform everywhere.
        path.reset()
        path.addRoundRect(body, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        val tail = android.graphics.Path()
        if (tailOnRight) {
            tail.moveTo(body.right - tailPx * 2.4f, body.bottom - radiusPx)
            tail.lineTo(body.right - tailPx * 0.2f, body.bottom + tailPx)
            tail.lineTo(body.right - tailPx * 0.2f, body.bottom - radiusPx)
        } else {
            tail.moveTo(body.left + tailPx * 2.4f, body.bottom - radiusPx)
            tail.lineTo(body.left + tailPx * 0.2f, body.bottom + tailPx)
            tail.lineTo(body.left + tailPx * 0.2f, body.bottom - radiusPx)
        }
        tail.close()
        path.op(tail, android.graphics.Path.Op.UNION)
        canvas.drawPath(path, paint)
    }

    override fun getPadding(padding: android.graphics.Rect): Boolean {
        padding.set(0, 0, 0, tailPx.toInt())
        return true
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
