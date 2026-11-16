package io.github.theonionsarewatching.nova.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import io.github.theonionsarewatching.nova.util.PhoneUtils
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.GroupMode
import io.github.theonionsarewatching.nova.databinding.ItemConversationBinding
import io.github.theonionsarewatching.nova.util.Formatters
import io.github.theonionsarewatching.nova.util.Prefs

class ConversationAdapter(
    private val onOpen: (ConversationEntity) -> Unit,
    private val onOptions: (ConversationEntity) -> Unit,
    private val isSelected: (Long) -> Boolean = { false },
    private val hasScheduled: (Long) -> Boolean = { false },
    /** "Send to" multi-pick: a selected row also gets an accent tick */
    private val showTick: Boolean = false
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    var items: List<ConversationEntity> = emptyList()

    override fun getItemId(position: Int): Long = items[position].id

    /** perf: blocked-number answers per address, so scrolling doesn't ask
     *  the system blocked-number store again for every row bind. Cleared on
     *  every refresh, so a block/unblock shows on the next list load. */
    private val blockedCache = HashMap<String, Boolean>()

    /** a refresh no longer resets the whole list
     *  (notifyDataSetChanged threw away every row and re-resolved focus,
     *  which made the highlight jump). DiffUtil sends exact inserts/moves/
     *  removals; rows still showing are re-bound in place (change animations
     *  are off on this list), so blocked/scheduled marks stay fresh. */
    fun submit(list: List<ConversationEntity>) {
        val old = items
        items = list
        blockedCache.clear()
        if (old.isEmpty() || list.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }, true).dispatchUpdatesTo(this)
        notifyItemRangeChanged(0, list.size)
    }

    /** re-bind just the row(s) for these conversations (selection marks) */
    fun refreshIds(ids: Collection<Long>) {
        for (id in ids) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) notifyItemChanged(i)
        }
    }

    /** re-bind every row in place, without resetting the list */
    fun rebindAll() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    class VH(val b: ItemConversationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val ctx = parent.context
        b.root.isFocusable = true
        b.root.isFocusableInTouchMode = false
        // rounded card; focus = light accent tint + 2dp accent
        // outline (colors from the accent + theme, see ThemeUtils)
        b.root.background = ThemeUtils.convoCardBackground(ctx)
        // perf: remember which accent/theme the card was built for, so the
        // first bind doesn't build it a second time
        b.root.setTag(R.id.convoAvatar,
            ThemeUtils.accentColor(ctx) * 31 + if (ThemeUtils.isNight(ctx)) 1 else 0)
        b.root.foreground = null
        // Both photos and fallbacks own the selected badge silhouette.
        b.convoAvatar.outlineProvider = null
        b.convoAvatar.clipToOutline = false
        val pad = ThemeUtils.densityPad(ctx)
        b.root.setPaddingRelative(b.root.paddingStart, pad, b.root.paddingEnd, pad)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val prefs = Prefs.get(ctx)

        // avatar sized from scaledDensity: shrinks/grows with BOTH the whole-app
        // resize (density) and the text-size resize (fontScale)
        run {
            val px = (42 * holder.itemView.resources.displayMetrics.scaledDensity).toInt()
            // perf: only touch the sizes when they differ — re-setting them
            // re-measured the whole row on every bind
            val fl = holder.b.avatarFrame.layoutParams
            if (fl.width != px || fl.height != px) {
                fl.width = px; fl.height = px; holder.b.avatarFrame.layoutParams = fl
            }
            val al = holder.b.convoAvatar.layoutParams
            if (al.width != px || al.height != px) {
                al.width = px; al.height = px; holder.b.convoAvatar.layoutParams = al
            }
            // Initials circles and round photos share this 42dp footprint.
        }

        // Real photos: round cover-crop in the same circle as initials.
        // Fallback rows keep a themed gradient circle with bold initials.
        val title = c.displayTitle()
        val avatar = holder.b.convoAvatar
        holder.b.avatarLetter.visibility = android.view.View.GONE
        avatar.visibility = android.view.View.VISIBLE
        avatar.background = null
        avatar.imageTintList = null // never tint photos or contrast-safe artwork
        avatar.setPadding(0, 0, 0, 0)
        avatar.clipToOutline = false
        avatar.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val identity = c.convoKey.ifBlank { "chat:${c.id}" }
        val color = BadgeIdentity.color(identity)
        val fallback = ContactBadgeDrawable(color,
            BadgeIdentity.monogram(c.customName, c.nameList(), c.addressList().firstOrNull().orEmpty(), c.isGroup), c.isGroup)
        val address = c.addressList().firstOrNull().orEmpty()
        val canPhoto = !c.isGroup && address.isNotBlank() && !address.contains("@") &&
            io.github.theonionsarewatching.nova.util.ContactsHelper.hasPermission(ctx)
        val photoKey = if (canPhoto) ContactPhotoMemory.key(address, color) else null
        val ready = photoKey?.let { ContactPhotoMemory.get(it) }
        avatar.dispose()
        if (ready != null) {
            // Instant finished badge: no fallback frame on resume/rebind.
            avatar.setImageBitmap(ready)
            avatar.setTag(R.id.dsms_photo_key, photoKey)
        } else {
            avatar.setImageDrawable(fallback)
            avatar.setTag(R.id.dsms_photo_key, null)
            if (canPhoto && photoKey != null) {
                avatar.load(ContactPhotoRequest(address)) {
                    diskCachePolicy(coil.request.CachePolicy.DISABLED)
                    placeholder(fallback)
                    error(fallback)
                    crossfade(false)
                    allowHardware(false)
                    transformations(ContactBadgePhotoTransformation(color))
                    listener(onSuccess = { _, result ->
                        val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null && !bmp.isRecycled) {
                            ContactPhotoMemory.put(photoKey, bmp)
                            avatar.setTag(R.id.dsms_photo_key, photoKey)
                        }
                    })
                }
            }
        }

        holder.b.convoTitle.text = title
        holder.b.iconPin.visibility =
            if (c.pinned) android.view.View.VISIBLE else android.view.View.GONE
        // one mutually-exclusive status badge (blocked number / notifications
        // blocked / muted / vibrate-only), resolved the same way as the
        // thread's top bar
        if (c.isGroup) {
            holder.b.iconGroupMode.setImageResource(
                if (c.groupMode == GroupMode.GROUP_MMS) R.drawable.ic_group_mms
                else R.drawable.ic_broadcast
            )
            holder.b.iconGroupMode.visibility = android.view.View.VISIBLE
        } else {
            holder.b.iconGroupMode.visibility = android.view.View.GONE
        }
        val statusIcon = ConvoStatusIcon.forConversation(holder.itemView.context, c) { a ->
            blockedCache.getOrPut(a) {
                io.github.theonionsarewatching.nova.data.Repo.get(ctx).isNumberBlocked(a)
            }
        }
        if (statusIcon == 0) {
            holder.b.iconStatus.visibility = android.view.View.GONE
        } else {
            holder.b.iconStatus.setImageResource(statusIcon)
            holder.b.iconStatus.visibility = android.view.View.VISIBLE
        }
        holder.b.iconScheduled.visibility =
            if (hasScheduled(c.id)) android.view.View.VISIBLE else android.view.View.GONE
        holder.b.convoTitle.textSize = prefs.msgTextSp
        // names are always bold in the card layout
        holder.b.convoTitle.setTypeface(null, Typeface.BOLD)

        val prefix = if (c.snippetIsMine) ctx.getString(R.string.you_prefix) + " " else ""
        val draft = c.draft.isNotBlank()
        holder.b.convoSnippet.text =
            if (draft) android.text.SpannableStringBuilder().apply {
                val p = ctx.getString(R.string.draft_prefix)
                append(p, android.text.style.ForegroundColorSpan(ThemeUtils.accentColor(ctx)),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, p.length, 0)
                append(" ").append(c.draft.take(80))
            }
            else prefix + c.snippet
        holder.b.convoSnippet.textSize = prefs.msgTextSp - 2f
        val mediaWords = setOf("MMS", "Picture", "Video", "Audio", "Contact", "Attachment")
        val italic = !draft && c.snippet in mediaWords
        holder.b.convoSnippet.setTypeface(null, when {
            c.unreadCount > 0 && italic -> Typeface.BOLD_ITALIC
            c.unreadCount > 0 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })

        holder.b.convoTime.text = if (c.snippetDate > 0) Formatters.listStamp(c.snippetDate) else ""
        holder.b.convoTime.textSize = (prefs.timeTextSp - 1f).coerceAtLeast(10f)
        // unread: accent count badge (was "(n)") + accent stripe on the start edge
        val accent = ThemeUtils.accentColor(ctx)
        // the app does not recreate this screen when the accent / theme
        // changes, so the card is re-tinted on every bind (cheap drawables)
        val bgTag = accent * 31 + if (ThemeUtils.isNight(ctx)) 1 else 0
        if (holder.itemView.getTag(R.id.convoAvatar) != bgTag) {
            holder.itemView.background = ThemeUtils.convoCardBackground(ctx)
            holder.itemView.setTag(R.id.convoAvatar, bgTag)
        }
        if (c.unreadCount > 0) {
            holder.b.convoUnread.text =
                if (c.unreadCount > 99) "99+" else c.unreadCount.toString()
            holder.b.convoUnread.textSize = (prefs.timeTextSp - 1f).coerceAtLeast(10f)
            holder.b.convoUnread.setTextColor(ThemeUtils.onAccent(ctx))
            // perf: each row keeps its own badge / stripe shapes and only
            // re-colors them (they were rebuilt on every bind)
            val badge = holder.b.convoUnread.background as? android.graphics.drawable.GradientDrawable
                ?: android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ThemeUtils.dp(ctx, 10).toFloat()
                }.also { holder.b.convoUnread.background = it }
            badge.setColor(accent)
            holder.b.convoUnread.visibility = android.view.View.VISIBLE
            val stripe = holder.b.unreadStripe.background as? android.graphics.drawable.GradientDrawable
                ?: android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ThemeUtils.dp(ctx, 2).toFloat()
                }.also { holder.b.unreadStripe.background = it }
            stripe.setColor(accent)
            holder.b.unreadStripe.visibility = android.view.View.VISIBLE
        } else {
            holder.b.convoUnread.text = ""
            holder.b.convoUnread.visibility = android.view.View.GONE
            holder.b.unreadStripe.visibility = android.view.View.INVISIBLE
        }

        // selection wash + outline over the focus shade (same scheme as messages)
        if (isSelected(c.id)) {
            val a = io.github.theonionsarewatching.nova.ui.ThemeUtils.accentColor(holder.itemView.context)
            val strokePx = (3 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.itemView.foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ThemeUtils.dp(holder.itemView.context, 14).toFloat()
                setColor(android.graphics.Color.argb(96, android.graphics.Color.red(a),
                    android.graphics.Color.green(a), android.graphics.Color.blue(a)))
                setStroke(strokePx, a)
            }
            if (showTick) {
                // visible tick before the name, in the theme accent
                val t = android.text.SpannableStringBuilder("\u2713 ")
                t.setSpan(android.text.style.ForegroundColorSpan(a), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                t.append(holder.b.convoTitle.text)
                holder.b.convoTitle.text = t
            }
        } else {
            holder.itemView.foreground = null
        }

        holder.itemView.setOnClickListener { onOpen(c) }
        holder.itemView.setOnLongClickListener { onOptions(c); true }
    }
}
