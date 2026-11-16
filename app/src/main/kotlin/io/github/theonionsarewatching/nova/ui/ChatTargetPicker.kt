package io.github.theonionsarewatching.nova.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ItemConversationBinding
import io.github.theonionsarewatching.nova.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Send to" / forward target: full screen, every visible
 * chat as the same cards as the main list, "New number…" as the first row
 * styled like a chat card, Cancel on the LEFT soft key (key-up). BACK
 * cancels too. Used by share-into-D-SMS and both forward paths.
 *
 * With [onPickMany] (forwarding): OK ticks / unticks a chat (accent tick
 * and outline), the RIGHT soft key "Send (n)" sends to every ticked chat at
 * once (key-up); Cancel is on the LEFT key.
 */
object ChatTargetPicker {

    fun show(
        activity: BaseActivity,
        title: String,
        onNew: () -> Unit,
        onCancel: () -> Unit = {},
        onPickMany: ((List<ConversationEntity>) -> Unit)? = null,
        onPick: (ConversationEntity) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val convos = try {
                withContext(Dispatchers.IO) {
                    // the notes chat is a fine target (forward/share to Notes
                    // just saves) while it is switched on
                    io.github.theonionsarewatching.nova.data.NotesChat.applyToList(activity, Repo.get(activity).db,
                        Repo.get(activity).db.conversations().visible())
                }
                    .sortedByDescending { it.snippetDate }
            } catch (_: Exception) { emptyList() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            open(activity, title, convos, onNew, onCancel, onPickMany, onPick)
        }
    }

    private fun open(
        activity: BaseActivity, title: String, convos: List<ConversationEntity>,
        onNew: () -> Unit, onCancel: () -> Unit,
        onPickMany: ((List<ConversationEntity>) -> Unit)?, onPick: (ConversationEntity) -> Unit,
    ) {
        var done = false
        var panel: FullScreenPanel? = null
        fun finish(action: () -> Unit) {
            if (done) return
            done = true
            panel?.dismiss()
            action()
        }
        val list = RecyclerView(activity).apply {
            layoutManager = SteadyLinearLayoutManager(activity)
            setPaddingRelative(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
            clipToPadding = true   // rows cut at the list edge, never under the title
            isVerticalScrollBarEnabled = true
            itemAnimator = null
        }
        val newRow = NewNumberAdapter(activity.getString(R.string.share_new_number)) {
            finish(onNew)
        }
        // multi-pick: ticked chat ids, in the order they were ticked
        val ticked = LinkedHashSet<Long>()
        var chatsRef: ConversationAdapter? = null
        var updateSend: () -> Unit = {}
        val chats = ConversationAdapter(
            onOpen = { c ->
                if (onPickMany == null) finish { onPick(c) }
                else {
                    if (!ticked.remove(c.id)) ticked.add(c.id)
                    chatsRef?.refreshIds(listOf(c.id))
                    updateSend()
                }
            },
            onOptions = { },         // no long-press menu here
            isSelected = { id -> id in ticked },
            showTick = onPickMany != null
        ).also { it.submit(convos) }
        chatsRef = chats
        list.adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).setStableIdMode(
                ConcatAdapter.Config.StableIdMode.NO_STABLE_IDS).build(),
            newRow, chats
        )
        val p = FullScreenPanel(activity, title, list)
        panel = p
        // the confirming action is on the RIGHT soft key (owner request),
        // Cancel on the LEFT; both act on key-up (FullScreenPanel)
        p.setLeft(activity.getString(R.string.softkey_cancel)) { finish(onCancel) }
        p.setRight(null, null)
        if (onPickMany != null) {
            p.bar.softRight.setTextColor(ThemeUtils.accentColor(activity))
            updateSend = {
                if (ticked.isEmpty()) p.setRight(null, null)
                else p.setRight(activity.getString(R.string.send_to_n, ticked.size)) {
                    val picked = ticked.mapNotNull { id -> convos.firstOrNull { it.id == id } }
                    if (picked.isNotEmpty()) finish { onPickMany(picked) }
                }
            }
        }
        p.dialog.setOnCancelListener { finish(onCancel) }   // BACK
        p.dialog.setCanceledOnTouchOutside(false)
        p.show()
        // first row ("New number…") takes focus
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    /** One row styled like a chat card: accent "+" circle and the label. */
    private class NewNumberAdapter(
        private val label: String,
        private val onClick: () -> Unit,
    ) : RecyclerView.Adapter<NewNumberAdapter.VH>() {
        class VH(val b: ItemConversationBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val b = ItemConversationBinding.inflate(LayoutInflater.from(ctx), parent, false)
            b.root.isFocusable = true
            b.root.isFocusableInTouchMode = false
            b.root.background = ThemeUtils.convoCardBackground(ctx)
            b.root.foreground = null
            val pad = ThemeUtils.densityPad(ctx)
            b.root.setPaddingRelative(b.root.paddingStart, pad, b.root.paddingEnd, pad)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = holder.b
            val ctx = b.root.context
            val prefs = Prefs.get(ctx)
            val px = (42 * ctx.resources.displayMetrics.scaledDensity).toInt()
            b.avatarFrame.layoutParams = b.avatarFrame.layoutParams.apply { width = px; height = px }
            b.convoAvatar.visibility = View.GONE
            b.avatarLetter.visibility = View.VISIBLE
            b.avatarLetter.text = "+"
            b.avatarLetter.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeUtils.accentColor(ctx))
            }
            b.convoTitle.text = label
            b.convoTitle.textSize = prefs.msgTextSp
            b.convoTitle.setTypeface(null, Typeface.BOLD)
            b.convoTime.visibility = View.GONE
            b.convoSnippet.visibility = View.GONE
            b.convoUnread.visibility = View.GONE
            b.unreadStripe.visibility = View.INVISIBLE
            b.root.setOnClickListener { onClick() }
        }
    }
}
