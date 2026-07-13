package io.github.theonionsarewatching.nova.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import coil.load
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ChangeBus
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.GroupMode
import io.github.theonionsarewatching.nova.data.MessageEntity
import io.github.theonionsarewatching.nova.data.MsgStatus
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityThreadBinding
import io.github.theonionsarewatching.nova.notify.NotificationHelper
import io.github.theonionsarewatching.nova.sms.Sender
import io.github.theonionsarewatching.nova.util.Formatters
import io.github.theonionsarewatching.nova.util.PhoneUtils
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

class ThreadActivity : BaseActivity() {

    companion object {
        const val EXTRA_CONVO_ID = "convo_id"
        private const val REQ_CHAT_BG = 207
        const val EXTRA_TARGET_MESSAGE_ID = "target_message_id"
        const val PAGE = 60
        var visibleConvoId: Long = -1L
    }

    private lateinit var binding: ActivityThreadBinding
    private lateinit var repo: Repo
    private lateinit var adapter: MessageAdapter
    private var scroller: DpadScroller? = null

    private var convoId = -1L
    private var convo: ConversationEntity? = null
    private var rows = ArrayList<MessageRow>()
    private var hasMoreOlder = true
    private var hasMoreNewer = false
    private var loading = false
    private var composeMode = true
    /** staged attachments: (path, mime, displayName) — sent together with the next Send */
    private val pendingAttachments = ArrayList<Triple<String, String, String>>()
    private var selecting = false
    private val selectedIds = HashSet<Long>()

    private val draftHandler = Handler(Looper.getMainLooper())
    private var draftRunnable: Runnable? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { binding.dateBubble.visibility = View.GONE }
    private val changeListener: () -> Unit = { onDataChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)
        convoId = intent.getLongExtra(EXTRA_CONVO_ID, -1L)
        if (convoId <= 0) { finish(); return }

        val lineStep = { (prefs.msgTextSp * resources.displayMetrics.scaledDensity * 3).toInt() }
        binding.msgList.layoutManager =
            BoundedLinearLayoutManager(this, maxStepPx = lineStep).apply { stackFromEnd = true }
        binding.msgList.isFocusable = true
        scroller = DpadScroller(
            binding.msgList,
            subScrollTallItems = true,
            lineStepPx = lineStep,
            onEdge = { down ->
                if (down) { enterComposeMode(); true }
                else if (hasMoreOlder) { loadOlder(); true }
                else { enterHeader(); true }
            }
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOverflow.setOnClickListener { threadOptions() }
        binding.btnAttach.setOnClickListener { pickAttachment() }
        binding.btnSend.setOnClickListener { send() }
        binding.attachmentRow.setOnClickListener { manageAttachments() }
        binding.attachClear.setOnClickListener { manageAttachments() }
        ThemeUtils.applyFocusHighlightRound(
            binding.btnBack, binding.btnOverflow, binding.btnAttach, binding.btnSend
        )
        ThemeUtils.applyFocusHighlightPill(binding.composeInput)
        ThemeUtils.applyRowFocus(binding.attachmentRow)

        binding.composeInput.maxLines = prefs.composeMaxLines
        binding.composeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draftRunnable?.let { draftHandler.removeCallbacks(it) }
                val r = Runnable { saveDraft() }
                draftRunnable = r
                draftHandler.postDelayed(r, 500)
            }
        })

        softkeys = Softkeys(this, binding.softkeyBar)
        if (softkeys?.shouldShow() == true) {
            binding.btnAttach.visibility = View.GONE
            binding.btnSend.visibility = View.GONE
        }
        updateSoftkeys()

        lifecycleScope.launch {
            convo = repo.db.conversations().byId(convoId)
            val c = convo ?: run { finish(); return@launch }
            binding.threadTitle.text = c.displayTitle()
            binding.threadSubtitle.text =
                if (c.isGroup) getString(R.string.n_recipients, c.addressList().size)
                else c.addressList().firstOrNull() ?: ""
            adapter = MessageAdapter(
                isGroup = c.isGroup,
                onPress = { pressMessage(it) },
                onHold = { holdMessage(it) },
                isSelected = { id -> selecting && id in selectedIds }
            )
            binding.msgList.adapter = adapter
            applyChatBackground()
            binding.composeInput.setText(c.draft)
            if (c.draft.isNotBlank()) binding.composeInput.setSelection(c.draft.length)

            val target = intent.getLongExtra(EXTRA_TARGET_MESSAGE_ID, -1L)
            if (target > 0) loadAround(target) else loadLatest(focusBottom = true)
            markRead()
        }
    }

    override fun onStart() {
        super.onStart()
        ChangeBus.register(changeListener)
    }

    override fun onStop() {
        super.onStop()
        ChangeBus.unregister(changeListener)
    }

    override fun onResume() {
        super.onResume()
        visibleConvoId = convoId
        NotificationHelper.cancel(this, convoId)
        val barShown = softkeys?.shouldShow() == true
        binding.btnAttach.visibility = if (barShown) View.GONE else View.VISIBLE
        binding.btnSend.visibility = if (barShown) View.GONE else View.VISIBLE
        updateSoftkeys()
        markRead()
    }

    override fun onPause() {
        super.onPause()
        visibleConvoId = -1L
        saveDraft()
    }

    /** Floating date label shown while fast-scrolling with a held D-pad. */
    private fun showDateBubble() {
        val v = binding.msgList.focusedChild ?: return
        val pos = binding.msgList.getChildAdapterPosition(v)
        val row = rows.getOrNull(pos) ?: return
        binding.dateBubble.text = Formatters.listStamp(row.msg.date)
        binding.dateBubble.visibility = View.VISIBLE
        bubbleHandler.removeCallbacks(hideBubble)
        bubbleHandler.postDelayed(hideBubble, 700)
    }

    // ============================== bulk selection ==============================

    private fun enterSelection(first: MessageRow) {
        selecting = true
        selectedIds.clear()
        selectedIds.add(first.msg.id)
        adapter.notifyDataSetChanged()
        updateSelectionUi()
        binding.threadSelectionBar.visibility = View.VISIBLE
        binding.btnSelCancelThread.setOnClickListener { exitSelection() }
        binding.btnSelDeleteThread.setOnClickListener { deleteSelected() }
    }

    /** Long-press while selecting (touch users): the actions as a dialog. */
    private fun selectionActionsDialog() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                getString(R.string.delete_n, selectedIds.size),
                getString(R.string.cancel_selection)
            )) { _, which ->
                when (which) {
                    0 -> deleteSelected()
                    1 -> exitSelection()
                }
            }
            .show()
    }

    private fun toggleSelected(row: MessageRow) {
        val id = row.msg.id
        if (!selectedIds.remove(id)) selectedIds.add(id)
        val pos = rows.indexOfFirst { it.msg.id == id }
        if (pos >= 0) adapter.notifyItemChanged(pos)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selecting = false
        selectedIds.clear()
        adapter.notifyDataSetChanged()
        binding.threadSelectionBar.visibility = View.GONE
        binding.threadSubtitle.text = convo?.let { c ->
            if (c.isGroup) getString(R.string.n_recipients, c.addressList().size)
            else c.addressList().firstOrNull() ?: ""
        } ?: ""
        updateSoftkeys()
    }

    private fun updateSelectionUi() {
        binding.threadSubtitle.text = getString(R.string.n_selected, selectedIds.size)
        binding.btnSelDeleteThread.text = getString(R.string.delete_n, selectedIds.size)
        softkeys?.set(
            getString(R.string.softkey_cancel), getString(R.string.softkey_select),
            getString(R.string.delete),
            onLeft = { exitSelection() },
            onCenter = { binding.msgList.focusedChild?.performClick() },
            onRight = { deleteSelected() },
            onMenu = { exitSelection() }
        )
    }

    private fun deleteSelected() {
        if (selectedIds.isEmpty()) { exitSelection(); return }
        lifecycleScope.launch {
            val lockedCount = selectedIds.count { id -> rows.firstOrNull { it.msg.id == id }?.msg?.locked == true }
            val ids = selectedIds.toList()
            if (lockedCount > 0) {
                AlertDialog.Builder(this@ThreadActivity)
                    .setMessage(getString(R.string.delete_selected_locked, ids.size, lockedCount))
                    .setPositiveButton(R.string.delete_all) { _, _ ->
                        lifecycleScope.launch { repo.deleteMessages(ids, includeLocked = true); exitSelection() }
                    }
                    .setNeutralButton(R.string.keep_locked) { _, _ ->
                        lifecycleScope.launch { repo.deleteMessages(ids, includeLocked = false); exitSelection() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this@ThreadActivity)
                    .setMessage(getString(R.string.delete_selected_confirm, ids.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch { repo.deleteMessages(ids, includeLocked = true); exitSelection() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun markRead() {
        lifecycleScope.launch {
            repo.db.messages().markThreadRead(convoId)
            repo.refreshConversation(convoId)
        }
    }

    private fun saveDraft() {
        val text = binding.composeInput.text?.toString().orEmpty()
        lifecycleScope.launch {
            repo.db.conversations().setDraft(convoId, text)
        }
    }

    // ============================== loading ==============================

    private suspend fun buildRows(messages: List<MessageEntity>): List<MessageRow> {
        val c = convo ?: return emptyList()
        val ids = messages.map { it.id }
        val parts = if (ids.isEmpty()) emptyList() else repo.db.parts().byMessages(ids)
        val elements = if (ids.isEmpty()) emptyList() else repo.db.elements().byMessages(ids)
        val partsBy = parts.groupBy { it.messageId }
        val elsBy = elements.groupBy { it.messageId }
        val names = HashMap<String, String>()
        c.addressList().forEachIndexed { i, a ->
            names[PhoneUtils.normalize(a)] = c.nameList().getOrNull(i)?.takeIf { it.isNotBlank() } ?: a
        }
        return messages.map { m ->
            MessageRow(
                msg = m,
                parts = partsBy[m.id] ?: emptyList(),
                elements = elsBy[m.id] ?: emptyList(),
                senderName = names[PhoneUtils.normalize(m.address)] ?: m.address
            )
        }
    }

    private fun loadLatest(focusBottom: Boolean) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val latest = repo.db.messages().latest(convoId, PAGE).reversed()
            rows = ArrayList(buildRows(latest))
            hasMoreOlder = latest.size >= PAGE
            hasMoreNewer = false
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            binding.emptyLabel.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (focusBottom) {
                binding.msgList.scrollToPosition((rows.size - 1).coerceAtLeast(0))
                enterComposeMode()
            }
            loading = false
        }
    }

    private fun loadOlder() {
        if (loading || !hasMoreOlder || rows.isEmpty()) return
        loading = true
        lifecycleScope.launch {
            val first = rows.first().msg
            val older = repo.db.messages().olderThan(convoId, first.date, first.id, PAGE).reversed()
            hasMoreOlder = older.size >= PAGE
            if (older.isNotEmpty()) {
                // keep the exact scroll position AND the focused view: capture the
                // current anchor, insert above, then restore the anchor shifted by
                // the insert count — the focused view instance survives untouched,
                // so focus can never blip up to the header during the re-layout
                val anchorView = binding.msgList.focusedChild ?: binding.msgList.getChildAt(0)
                val anchorPos = anchorView?.let { binding.msgList.getChildAdapterPosition(it) } ?: -1
                val anchorOffset = anchorView?.top ?: 0
                val newRows = buildRows(older)
                rows.addAll(0, newRows)
                adapter.rows = rows
                adapter.notifyItemRangeInserted(0, newRows.size)
                if (anchorPos >= 0) {
                    (binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(anchorPos + newRows.size, anchorOffset)
                }
            }
            loading = false
        }
    }

    private fun loadNewer() {
        if (loading || !hasMoreNewer || rows.isEmpty()) return
        loading = true
        lifecycleScope.launch {
            val last = rows.last().msg
            val newer = repo.db.messages().newerThan(convoId, last.date, last.id, PAGE)
            hasMoreNewer = newer.size >= PAGE
            if (newer.isNotEmpty()) {
                val newRows = buildRows(newer)
                val start = rows.size
                rows.addAll(newRows)
                adapter.rows = rows
                adapter.notifyItemRangeInserted(start, newRows.size)
            }
            loading = false
        }
    }

    private fun loadAround(targetId: Long) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val target = repo.db.messages().byId(targetId)
            if (target == null || target.convoId != convoId) {
                loading = false
                loadLatest(focusBottom = true)
                return@launch
            }
            val older = repo.db.messages().olderThan(convoId, target.date, target.id, PAGE / 2).reversed()
            val newer = repo.db.messages().newerThan(convoId, target.date, target.id, PAGE / 2)
            hasMoreOlder = older.size >= PAGE / 2
            hasMoreNewer = newer.size >= PAGE / 2
            rows = ArrayList(buildRows(older + target + newer))
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            binding.emptyLabel.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            val pos = rows.indexOfFirst { it.msg.id == targetId }.coerceAtLeast(0)
            enterScrollMode(focusPos = pos)
            loading = false
        }
    }

    private fun onDataChanged() {
        // live tail: refresh the newest window and keep the user's place
        if (!::adapter.isInitialized) return
        if (hasMoreNewer) return
        lifecycleScope.launch {
            val focusedId = binding.msgList.focusedChild?.let { v ->
                val p = binding.msgList.getChildAdapterPosition(v)
                rows.getOrNull(p)?.msg?.id
            }
            val wasCompose = composeMode
            val requested = maxOf(PAGE, rows.size)
            val latest = repo.db.messages().latest(convoId, requested).reversed()
            rows = ArrayList(buildRows(latest))
            hasMoreOlder = latest.size >= requested
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            binding.emptyLabel.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (wasCompose) {
                binding.msgList.scrollToPosition((rows.size - 1).coerceAtLeast(0))
            } else if (focusedId != null) {
                val pos = rows.indexOfFirst { it.msg.id == focusedId }
                if (pos >= 0) scroller?.focusPosition(pos)
            }
            markRead()
        }
    }

    // ============================== compose <-> scroll boundary ==============================

    /** Header buttons are focusable ONLY while deliberately visited — the framework
     *  can never auto-park focus on them during a list re-layout or page load. */
    private fun setHeaderFocusable(on: Boolean) {
        binding.btnBack.isFocusable = on
        binding.btnOverflow.isFocusable = on
    }

    private fun enterHeader() {
        setHeaderFocusable(true)
        if (!binding.btnOverflow.requestFocus() && !binding.btnBack.requestFocus()) {
            setHeaderFocusable(false)
        }
    }

    private fun leaveHeader() {
        // move focus to the list FIRST, then lock the header (see MainActivity)
        binding.msgList.requestFocus()
        setHeaderFocusable(false)
        binding.msgList.post {
            if (!binding.msgList.hasFocus()) binding.msgList.requestFocus()
            val lm = binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val first = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 }
                ?: lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            scroller?.focusPosition(first)
        }
    }

    /** Compose mode: Attach | Send on the softkeys. Scroll mode: Options | Select | Compose. */
    private fun updateSoftkeys() {
        if (selecting) { updateSelectionUi(); return }
        if (composeMode) {
            softkeys?.set(
                getString(R.string.softkey_attach), null, getString(R.string.softkey_send),
                onLeft = { pickAttachment() },
                onCenter = null,
                onRight = { send() },
                onMenu = { threadOptions() }
            )
        } else {
            softkeys?.set(
                getString(R.string.softkey_options), getString(R.string.softkey_select), getString(R.string.softkey_compose),
                onLeft = { threadOptions() },
                onCenter = { binding.msgList.focusedChild?.performClick() },
                onRight = { enterComposeMode() },
                onMenu = { threadOptions() }
            )
        }
    }

    private fun enterComposeMode() {
        composeMode = true
        binding.composeInput.maxLines = prefs.composeMaxLines
        binding.moreIndicator.visibility = View.GONE
        binding.composeInput.requestFocus()
        binding.composeInput.setSelection(binding.composeInput.text?.length ?: 0)
        updateSoftkeys()
    }

    private fun enterScrollMode(focusPos: Int = rows.size - 1) {
        if (rows.isEmpty()) return
        composeMode = false
        binding.composeInput.maxLines = 1
        val multiline = (binding.composeInput.layout?.lineCount ?: 1) > 1 ||
            binding.composeInput.text?.contains('\n') == true
        binding.moreIndicator.visibility = if (multiline) View.VISIBLE else View.GONE
        binding.msgList.requestFocus()
        val target = focusPos.coerceIn(0, rows.size - 1)
        if (target == rows.size - 1) focusBottomPinned() else scroller?.focusPosition(target)
        updateSoftkeys()
    }

    /** Focus the newest message but keep the list scrolled to the very bottom —
     *  a tall last message stays bottom-aligned until the next D-pad up sub-scrolls it. */
    private fun focusBottomPinned() {
        val pos = rows.size - 1
        if (pos < 0) return
        binding.msgList.scrollToPosition(pos)
        binding.msgList.post {
            val vh = binding.msgList.findViewHolderForAdapterPosition(pos) ?: return@post
            vh.itemView.requestFocus()
            binding.msgList.post {
                val v = binding.msgList.findViewHolderForAdapterPosition(pos)?.itemView ?: return@post
                val viewBottom = binding.msgList.height - binding.msgList.paddingBottom
                val delta = v.bottom - viewBottom
                if (delta != 0) binding.msgList.scrollBy(0, delta)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (selecting && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            exitSelection()
            return true
        }
        if (softkeys?.handleKey(event) == true) return true

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            binding.composeInput.hasFocus()
        ) {
            val layout = binding.composeInput.layout
            val sel = binding.composeInput.selectionStart.coerceAtLeast(0)
            val line = if (layout != null) layout.getLineForOffset(sel) else 0
            if (line == 0) {
                if (binding.attachmentRow.visibility == View.VISIBLE) {
                    binding.attachmentRow.requestFocus()
                } else {
                    enterScrollMode()
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && binding.attachmentRow.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { enterScrollMode(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { enterComposeMode(); return true }
            }
        }

        if (binding.btnBack.hasFocus() || binding.btnOverflow.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                leaveHeader()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true // nothing above the header
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                leaveHeader()
                return true
            }
        } else if (binding.btnBack.isFocusable && !binding.btnBack.hasFocus() && !binding.btnOverflow.hasFocus()) {
            // focus moved elsewhere by other means: relock the header
            setHeaderFocusable(false)
        }
        if (binding.msgList.hasFocus()) {
            if (scroller?.onKey(event) == true) {
                // prefetch older pages as the focus climbs
                val v = binding.msgList.focusedChild
                if (v != null && binding.msgList.getChildAdapterPosition(v) < 4) loadOlder()
                if (v != null && hasMoreNewer &&
                    binding.msgList.getChildAdapterPosition(v) > rows.size - 5
                ) loadNewer()
                if (event.repeatCount >= 3) showDateBubble()
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_CALL) {
            convo?.let { c ->
                if (!c.isGroup) {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.addressList().first())))
                    } catch (_: Exception) {}
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ============================== sending ==============================

    private fun send() {
        val text = binding.composeInput.text?.toString()?.trim().orEmpty()
        val attachments = pendingAttachments.toList()
        if (text.isEmpty() && attachments.isEmpty()) return
        binding.composeInput.setText("")
        clearAttachments(deleteFiles = false)
        lifecycleScope.launch {
            repo.db.conversations().setDraft(convoId, "")
            if (attachments.isNotEmpty()) {
                repo.sendAttachment(convoId, text, attachments)
            } else {
                repo.sendText(convoId, text)
            }
        }
        enterComposeMode()
    }

    private fun addAttachment(path: String, mime: String, name: String) {
        pendingAttachments.add(Triple(path, mime, name))
        updateAttachmentChip()
        enterComposeMode()
    }

    private fun updateAttachmentChip() {
        if (pendingAttachments.isEmpty()) {
            binding.attachmentRow.visibility = View.GONE
            binding.attachThumb.setImageDrawable(null)
            return
        }
        binding.attachmentRow.visibility = View.VISIBLE
        binding.attachmentName.text =
            if (pendingAttachments.size == 1)
                getString(R.string.attached_label, pendingAttachments[0].third)
            else getString(R.string.attached_n, pendingAttachments.size)
        val visual = pendingAttachments.firstOrNull {
            it.second.startsWith("image/") || it.second.startsWith("video/")
        }
        if (visual != null) {
            binding.attachThumb.visibility = View.VISIBLE
            binding.attachThumb.load(File(visual.first)) { size(96, 96) }
        } else {
            binding.attachThumb.visibility = View.GONE
        }
    }

    /** OK on the chip: list every staged file — pick one to remove, or remove all. */
    private fun manageAttachments() {
        if (pendingAttachments.isEmpty()) return
        val labels = pendingAttachments.map { it.third } + getString(R.string.remove_all)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.attached_n, pendingAttachments.size))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < pendingAttachments.size) {
                    val item = pendingAttachments[which]
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.remove_one_confirm, item.third))
                        .setPositiveButton(R.string.remove) { _, _ ->
                            runCatching { File(item.first).delete() }
                            pendingAttachments.removeAt(which)
                            updateAttachmentChip()
                            enterComposeMode()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    clearAttachments(deleteFiles = true)
                    enterComposeMode()
                }
            }
            .show()
    }

    private fun clearAttachments(deleteFiles: Boolean) {
        if (deleteFiles) pendingAttachments.forEach { runCatching { File(it.first).delete() } }
        pendingAttachments.clear()
        binding.attachmentRow.visibility = View.GONE
        binding.attachThumb.setImageDrawable(null)
    }

    private fun pickAttachment() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "text/x-vcard"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, getString(R.string.softkey_attach)), 201)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHAT_BG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                try {
                    val dir = File(filesDir, "backgrounds").apply { mkdirs() }
                    val dest = File(dir, "bg_$convoId")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    if (dest.exists() && dest.length() > 0) {
                        prefs.setChatBg(convoId, dest.absolutePath)
                        applyChatBackground()
                    }
                } catch (_: Exception) {}
            }
            return
        }
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uris = ArrayList<android.net.Uri>()
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            data?.data?.let { if (uris.isEmpty()) uris.add(it) }
            if (uris.isEmpty()) return
            lifecycleScope.launch {
                for (uri in uris) {
                    try {
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        val ext = when {
                            mime.contains("jpeg") -> ".jpg"; mime.contains("png") -> ".png"
                            mime.contains("gif") -> ".gif"; mime.contains("mp4") -> ".mp4"
                            mime.contains("3gpp") -> ".3gp"; mime.contains("amr") -> ".amr"
                            mime.contains("vcard") -> ".vcf"; else -> ".bin"
                        }
                        val dir = File(filesDir, "parts").apply { mkdirs() }
                        val out = File(dir, "out_${System.currentTimeMillis()}_${uris.indexOf(uri)}$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (out.exists() && out.length() > 0) {
                            // staged, not sent — goes out with the next Send press
                            addAttachment(out.absolutePath, mime, out.name)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ============================== message press / hold ==============================

    private fun pressMessage(row: MessageRow) {
        if (row.isSystemLine) return
        if (selecting) { toggleSelected(row); return }
        val m = row.msg
        when (m.status) {
            MsgStatus.FAILED -> {
                stateDialog(row, R.string.retry) { lifecycleScope.launch { repo.retry(m.id) } }
                return
            }
            MsgStatus.CANCELED -> {
                stateDialog(row, R.string.resend) { lifecycleScope.launch { repo.retry(m.id) } }
                return
            }
            MsgStatus.SCHEDULED -> {
                val items = arrayOf(getString(R.string.send_now), getString(R.string.cancel_schedule),
                    getString(R.string.message_options))
                AlertDialog.Builder(this)
                    .setItems(items) { _, which ->
                        when (which) {
                            0 -> lifecycleScope.launch { repo.fireScheduled(m.id) }
                            1 -> lifecycleScope.launch { repo.cancelScheduled(m.id) }
                            2 -> holdMessage(row)
                        }
                    }.show()
                return
            }
        }
        when {
            row.parts.isNotEmpty() -> openAttachment(row, 0)
            row.elements.isNotEmpty() -> ElementActions.showForMessage(this, row.elements) { num -> messageNumber(num) }
            else -> { /* plain text, no elements: press does nothing (by design) */ }
        }
    }

    private fun stateDialog(row: MessageRow, primaryRes: Int, primary: () -> Unit) {
        val items = arrayOf(getString(primaryRes), getString(R.string.message_options))
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) primary() else holdMessage(row)
            }.show()
    }

    private fun messageNumber(number: String) {
        lifecycleScope.launch {
            val c = repo.getOrCreateConversation(listOf(number))
            startActivity(Intent(this@ThreadActivity, ThreadActivity::class.java)
                .putExtra(EXTRA_CONVO_ID, c.id))
        }
    }

    private fun openAttachment(row: MessageRow, index: Int) {
        val part = row.parts.getOrNull(index) ?: return
        if (part.isImage() || part.isVideo()) {
            startActivity(Intent(this, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_CONVO_ID, convoId)
                putExtra(MediaViewerActivity.EXTRA_PART_ID, part.id)
            })
            return
        }
        // audio, vCards and other files: hand off to the system app for that type
        val mime = if (part.isVCard()) "text/x-vcard" else part.mimeType
        val openLabel = when {
            part.isVCard() -> getString(R.string.open_in_contacts)
            part.isAudio() -> getString(R.string.open_in_player)
            else -> getString(R.string.open)
        }
        AlertDialog.Builder(this)
            .setTitle(part.fileName)
            .setItems(arrayOf(openLabel, getString(R.string.save))) { _, which ->
                when (which) {
                    0 -> try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "$packageName.fileprovider", File(part.filePath)
                        )
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, R.string.no_app_for_file,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> savePart(part)
                }
            }.show()
    }

    private fun holdMessage(row: MessageRow) {
        if (row.isSystemLine) return
        if (selecting) { selectionActionsDialog(); return }
        val m = row.msg
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += getString(R.string.select_messages) to { enterSelection(row) }

        when (m.status) {
            MsgStatus.FAILED -> items += getString(R.string.retry) to { lifecycleScope.launch { repo.retry(m.id) } }
            MsgStatus.SENDING -> items += getString(R.string.cancel_sending) to {
                lifecycleScope.launch { repo.cancelSending(m.id) }
            }
            MsgStatus.CANCELED -> items += getString(R.string.resend) to { lifecycleScope.launch { repo.retry(m.id) } }
            MsgStatus.SCHEDULED -> {
                items += getString(R.string.send_now) to { lifecycleScope.launch { repo.fireScheduled(m.id) } }
                items += getString(R.string.cancel_schedule) to { lifecycleScope.launch { repo.cancelScheduled(m.id) } }
            }
        }

        if (m.body.isNotBlank()) {
            items += getString(R.string.act_copy) to { ElementActions.copy(this, m.body) }
        }
        items += getString(R.string.forward) to { forward(row) }
        if (row.parts.isNotEmpty()) {
            items += getString(R.string.save_attachment) to {
                row.parts.forEach { savePart(it) }
            }
        }
        items += (if (m.locked) getString(R.string.unlock) else getString(R.string.lock)) to {
            lifecycleScope.launch { repo.db.messages().setLocked(m.id, !m.locked); ChangeBus.ping() }
        }
        items += getString(R.string.details) to { details(row) }
        items += getString(R.string.delete) to { deleteMessage(m) }

        row.elements.forEach { e ->
            items += ElementActions.label(this, e) to { ElementActions.show(this, e) { n -> messageNumber(n) } }
        }

        AlertDialog.Builder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.message_options)))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun deleteMessage(m: MessageEntity) {
        val doDelete = { lifecycleScope.launch { repo.deleteMessage(m.id) }; Unit }
        if (m.locked) {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_locked_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> doDelete() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_message_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> doDelete() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun details(row: MessageRow) {
        val m = row.msg
        val c = convo
        val sb = StringBuilder()
        sb.append(getString(R.string.detail_type)).append(": ")
            .append(if (m.isMms) "MMS" else "SMS").append('\n')
        if (m.isMine) {
            sb.append(getString(R.string.detail_to)).append(": ")
                .append(c?.displayTitle() ?: m.address).append('\n')
        } else {
            sb.append(getString(R.string.detail_from)).append(": ").append(row.senderName).append('\n')
        }
        sb.append(getString(R.string.detail_date)).append(": ").append(Formatters.full(m.date)).append('\n')
        if (m.isMine) {
            sb.append(getString(R.string.detail_status)).append(": ")
                .append(Sender.statusLabel(this, m.status)).append('\n')
            if (m.recipientStatuses.isNotBlank()) {
                repo.parseStatuses(m.recipientStatuses).forEach { (addr, st) ->
                    sb.append("  ").append(addr).append(": ")
                        .append(Sender.statusLabel(this, st)).append('\n')
                }
            }
        }
        if (m.locked) sb.append(getString(R.string.detail_locked)).append('\n')
        AlertDialog.Builder(this)
            .setTitle(R.string.details)
            .setMessage(sb.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun forward(row: MessageRow) {
        lifecycleScope.launch {
            val convos = repo.db.conversations().visible()
                .sortedByDescending { it.snippetDate }.take(15)
            val labels = convos.map { it.displayTitle() } + getString(R.string.new_message)
            AlertDialog.Builder(this@ThreadActivity)
                .setTitle(R.string.forward_to)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < convos.size) {
                        lifecycleScope.launch { doForward(row, convos[which].id) }
                    } else {
                        startActivity(Intent(this@ThreadActivity, ComposeActivity::class.java)
                            .putExtra("prefill_body", row.msg.body))
                    }
                }
                .show()
        }
    }

    private suspend fun doForward(row: MessageRow, destConvoId: Long) {
        val part = row.parts.firstOrNull()
        if (part != null) {
            try {
                val src = File(part.filePath)
                val dir = File(filesDir, "parts").apply { mkdirs() }
                val copy = File(dir, "fwd_${System.currentTimeMillis()}_${part.fileName}")
                src.copyTo(copy, overwrite = true)
                repo.sendAttachment(destConvoId, row.msg.body,
                    listOf(Triple(copy.absolutePath, part.mimeType, part.fileName)))
            } catch (_: Exception) {}
        } else if (row.msg.body.isNotBlank()) {
            repo.sendText(destConvoId, row.msg.body)
        }
        android.widget.Toast.makeText(this, R.string.forwarded, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- chat background ----------------

    private fun applyChatBackground() {
        val v = prefs.chatBg(convoId)
        when {
            v.isBlank() -> {
                binding.chatBackdrop.visibility = View.GONE
            }
            v.startsWith("#") -> {
                binding.chatBackdrop.setImageDrawable(null)
                binding.chatBackdrop.colorFilter = null
                binding.chatBackdrop.setBackgroundColor(android.graphics.Color.parseColor(v))
                binding.chatBackdrop.visibility = View.VISIBLE
            }
            else -> {
                binding.chatBackdrop.background = null
                binding.chatBackdrop.load(File(v))
                // slight dim so bubbles stay readable over busy photos
                binding.chatBackdrop.setColorFilter(0x2E000000)
                binding.chatBackdrop.visibility = View.VISIBLE
            }
        }
    }

    private fun chatBackgroundDialog() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                getString(R.string.bg_default),
                getString(R.string.bg_color),
                getString(R.string.bg_picture)
            )) { _, which ->
                when (which) {
                    0 -> {
                        prefs.setChatBg(convoId, "")
                        File(filesDir, "backgrounds/bg_$convoId").delete()
                        applyChatBackground()
                    }
                    1 -> chatBgColorPicker()
                    2 -> pickChatBgPicture()
                }
            }
            .show()
    }

    private fun chatBgColorPicker() {
        val colors = arrayOf(
            "#101418", "#1A2633", "#14261A", "#2A1A1A",
            "#F4EFE6", "#E7EEF6", "#EAF4EA", "#F6E7EA"
        )
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val grid = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        var dialog: AlertDialog? = null
        for (rowStart in colors.indices step 4) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            for (i in rowStart until minOf(rowStart + 4, colors.size)) {
                val hex = colors[i]
                val swatch = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor(hex))
                        setStroke(dp(1), 0x33000000)
                    }
                    isFocusable = true
                    setOnClickListener {
                        prefs.setChatBg(convoId, hex)
                        applyChatBackground()
                        dialog?.dismiss()
                    }
                }
                row.addView(swatch)
            }
            grid.addView(row)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bg_color)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickChatBgPicture() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(i, REQ_CHAT_BG)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.no_file_picker,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun savePart(part: io.github.theonionsarewatching.nova.data.PartEntity) {
        lifecycleScope.launch {
            val loc = Saver.save(this@ThreadActivity, File(part.filePath), part.fileName, part.mimeType)
            android.widget.Toast.makeText(
                this@ThreadActivity,
                if (loc != null) getString(R.string.saved_to, loc) else getString(R.string.save_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================== thread options ==============================

    private fun threadOptions() {
        val c = convo ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!c.isGroup) {
            items += getString(R.string.call_contact, c.displayTitle()) to {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.addressList().first())))
                } catch (_: Exception) {}
            }
        }
        if (c.isGroup) {
            items += getString(R.string.group_send_mode) to { pickGroupMode(c) }
        }
        items += getString(R.string.schedule_send) to { scheduleSend() }
        items += getString(R.string.view_media) to {
            startActivity(Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_CONVO_ID, convoId))
        }
        items += getString(R.string.mark_unread) to {
            lifecycleScope.launch {
                repo.db.messages().newest(convoId)?.let { repo.db.messages().setRead(it.id, false) }
                repo.refreshConversation(convoId)
                ChangeBus.ping()
                finish()
            }
        }
        if (c.isGroup) {
            items += getString(R.string.participants_title, c.addressList().size) to {
                GroupParticipants.show(this, c)
            }
        }
        items += getString(R.string.chat_background) to { chatBackgroundDialog() }
        items += getString(R.string.sound_and_vibration) to { SoundDialog.show(this, convoId) }
        items += getString(R.string.delete_thread) to { deleteThreadFlow(c) }

        AlertDialog.Builder(this)
            .setCustomTitle(Dialogs.title(this, c.displayTitle()))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun pickGroupMode(c: ConversationEntity) {
        val labels = arrayOf(getString(R.string.mode_broadcast), getString(R.string.mode_group_mms))
        val current = if (c.groupMode == GroupMode.GROUP_MMS) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.group_send_mode)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val newMode = if (which == 1) GroupMode.GROUP_MMS else GroupMode.BROADCAST
                if (newMode == c.groupMode) return@setSingleChoiceItems
                lifecycleScope.launch {
                    repo.db.conversations().setGroupMode(convoId, newMode)
                    convo = repo.db.conversations().byId(convoId)
                    // system line noting the switch (future messages only)
                    repo.db.messages().insert(
                        MessageEntity(
                            convoId = convoId, address = MessageRow.SYSTEM_ADDRESS,
                            body = getString(
                                if (newMode == GroupMode.GROUP_MMS) R.string.switched_group_mms
                                else R.string.switched_broadcast
                            ),
                            date = System.currentTimeMillis(), isMine = false,
                            status = MsgStatus.RECEIVED, read = true, elementsExtracted = true
                        )
                    )
                    ChangeBus.ping()
                }
            }
            .show()
    }

    private fun scheduleSend() {
        val text = binding.composeInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.schedule_needs_text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                cal.set(y, mo, d, h, mi, 0)
                val at = cal.timeInMillis
                if (at <= System.currentTimeMillis()) {
                    android.widget.Toast.makeText(this, R.string.schedule_in_past, android.widget.Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                binding.composeInput.setText("")
                lifecycleScope.launch {
                    repo.db.conversations().setDraft(convoId, "")
                    repo.scheduleMessage(convoId, text, at)
                }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun deleteThreadFlow(c: ConversationEntity) {
        lifecycleScope.launch {
            val locked = repo.db.messages().lockedCount(c.id)
            if (locked > 0) {
                AlertDialog.Builder(this@ThreadActivity)
                    .setTitle(R.string.delete_thread)
                    .setMessage(getString(R.string.delete_thread_locked, locked))
                    .setPositiveButton(R.string.delete_all) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, true); finish() }
                    }
                    .setNeutralButton(R.string.keep_locked) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, false); finish() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this@ThreadActivity)
                    .setMessage(getString(R.string.delete_thread_confirm, c.displayTitle()))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, true); finish() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
