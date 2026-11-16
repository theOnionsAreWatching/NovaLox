package io.github.theonionsarewatching.nova.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import coil.dispose
import coil.load
import coil.request.videoFrameMillis
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
import io.github.theonionsarewatching.nova.util.Perf
import io.github.theonionsarewatching.nova.util.PhoneUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class ThreadActivity : BaseActivity(), io.github.theonionsarewatching.nova.ui.ChatBackground.Host {

    companion object {
        const val EXTRA_CONVO_ID = "convo_id"
        /** Shared from another app via the share target: text and
         *  already-copied attachment files (paths/mimes/names, same order). */
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_SHARED_PATHS = "shared_paths"
        const val EXTRA_SHARED_MIMES = "shared_mimes"
        const val EXTRA_SHARED_NAMES = "shared_names"
        private const val REQ_CHAT_BG = 207
        const val EXTRA_TARGET_MESSAGE_ID = "target_message_id"
        // ONE pager (rows + hasMoreOlder + loadOlder). The 8-at-a-time
        // experiment is undone: measurement showed the initial load was
        // already fast (82–204ms) — the freeze lives elsewhere — and small
        // pages only cost scrolling interruptions.
        const val PAGE = 60
        const val PAGE_MORE = 60
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
        binding.msgList.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int
            ) {
                if (dy >= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findFirstVisibleItemPosition() <= 1) loadOlder()
            }
        })
        scroller = DpadScroller(
            binding.msgList,
            subScrollTallItems = true,
            lineStepPx = lineStep,
            onEdge = { down ->
                if (down) {
                    if (selecting && binding.threadSelectionBar.isShown) {
                        binding.btnSelCancelThread.requestFocus()
                    } else if (!selecting) {
                        enterComposeMode()
                    }
                    true
                } else if (hasMoreOlder) { loadOlder(); true }
                else if (loading) { true }
                else { enterHeader(); true }
            }
        )

        // the top bar is focusable ONLY while deliberately entered via
        // enterHeader(); otherwise a relayout (an image finishing its async
        // decode resizes its row) that recycles the focused row lets the
        // system fall back onto these buttons uninvited
        setHeaderFocusable(false)
        binding.btnBack.setOnClickListener { goBack() }
        binding.unreadChip.setOnClickListener {
            binding.msgList.scrollToPosition((rows.size - 1).coerceAtLeast(0))
            markRead()
        }
        binding.msgList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) markRead()
            }
            // D-pad scrolling uses scrollBy (no idle event) and a new message
            // appears through a layout pass; both report here (dx=dy=0 after layout)
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                scheduleMarkRead()
            }
        })
        binding.btnOverflow.setOnClickListener { threadOptions() }
        binding.btnAttach.setOnClickListener {
            AttachOrPaste.open(this, binding.composeInput,
                onAttach = { pickAttachment() },
                onRecord = { recordAudioAttachment() })
        }
        binding.btnSend.setOnClickListener { send() }
        binding.attachmentRow.setOnClickListener { manageAttachments() }
        binding.attachClear.setOnClickListener { manageAttachments() }
        ThemeUtils.applyFocusHighlightRound(
            binding.btnBack, binding.btnOverflow, binding.btnAttach, binding.btnSend
        )
        // reply field: no focus ring (it never tracked the growing text
        // reliably) — the shaded pill is the field's own visual, and the
        // blinking cursor marks focus
        ThemeUtils.applyRowFocus(binding.attachmentRow)

        binding.composeInput.maxLines = prefs.composeMaxLines
        // notice the system's own paste into the reply box
        io.github.theonionsarewatching.nova.util.ClipboardGuard.watch(binding.composeInput)
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
        applyTopBarSymbols()
        applyComposeButtonLayout()
        attachTouchDateBubble()
        if (prefs.touchMode) {
            // touch phones: focus lands in the box on entry, which popped the
            // keyboard over half the thread. Show it only when the box is
            // actually tapped.
            binding.composeInput.showSoftInputOnFocus = false
            binding.composeInput.setOnClickListener { v ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(v, 0)
            }
        }
        // On phones whose keyboard steals the right D-pad as Space (e.g. Kyocera
        // E4810) the user can't reach the on-screen Send button, and if the
        // softkey bar is off there's no softkey Send either. So when softkeys
        // are off, D-pad center sends while the compose box has focus.
        // the T9 word strip belongs to the phone's keyboard. Show it
        // only while he is typing in the box; hide it the moment focus leaves.
        if (!prefs.touchMode) binding.composeInput.showSoftInputOnFocus = false
        binding.composeInput.setOnFocusChangeListener { v, has ->
            updateSendHint(); updateCharCounter()
            if (has) {
                setCompact(false)
            } else {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                try {
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    // ends the half-typed T9 word cleanly (no stray underline)
                    imm.restartInput(v)
                } catch (_: Exception) {}
            }
        }
        binding.composeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateCharCounter()
                // typing again: let the keyboard (and its T9 strip) come back
                if (!prefs.touchMode && binding.composeInput.hasFocus()) {
                    try {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .showSoftInput(binding.composeInput, 0)
                    } catch (_: Exception) {}
                }
            }
        })
        if (binding.composeInput.text.isNullOrBlank()) {
            val draft = prefs.draft(convoId)
            if (draft.isNotBlank()) {
                binding.composeInput.setText(draft)
                binding.composeInput.setSelection(draft.length)
            }
        }
        if (pendingAttachments.isEmpty()) {
            DraftPrompt.decode(prefs.draftAttachments(convoId)).forEach { pendingAttachments.add(it) }
            if (pendingAttachments.isNotEmpty()) updateAttachmentChip()
        }
        updateSendHint()
        updateSoftkeys()

        lifecycleScope.launch {
            convo = repo.db.conversations().byId(convoId)
            val c = convo ?: run { finish(); return@launch }
            binding.threadTitle.text = c.displayTitle()
            binding.threadSubtitle.text =
                if (c.isGroup) getString(
                    if (c.groupMode == GroupMode.GROUP_MMS) R.string.subtitle_group
                    else R.string.subtitle_broadcast
                )
                else if (io.github.theonionsarewatching.nova.data.NotesChat.isNotes(c)) getString(R.string.notes_subtitle)
                else c.addressList().firstOrNull() ?: ""
            updateNotifStatusIcon(c)
            adapter = MessageAdapter(
                isGroup = c.isGroup,
                onPress = { pressMessage(it) },
                onHold = { holdMessage(it) },
                isSelected = { id -> selecting && id in selectedIds },
                backdropColor = { currentBackdropColor() }
            )
            binding.msgList.adapter = adapter
            applyChatBackground()
            binding.composeInput.setText(c.draft)
            if (c.draft.isNotBlank()) binding.composeInput.setSelection(c.draft.length)
            // shared/forwarded content means the user wants to send: open at the box
            val hadShared = intent.hasExtra(EXTRA_SHARED_TEXT) || intent.hasExtra(EXTRA_SHARED_PATHS)
            applySharedContent()

            // find the first unread BEFORE anything can mark it read,
            // so the "New messages" line and the landing spot are right.
            val firstUnread = repo.db.messages().firstUnreadId(convoId) ?: -1L
            dividerCaptured = true
            adapter.newDividerMessageId = firstUnread
            val target = intent.getLongExtra(EXTRA_TARGET_MESSAGE_ID, -1L)
            when {
                target > 0 -> loadAround(target)
                // unread waiting: land on the first one (top of the screen),
                // loading the page around it even in a very long thread
                firstUnread > 0 && !hadShared -> loadAround(firstUnread)
                else -> loadLatest(focusBottom = true)
            }
            markRead()
        }
    }

    override fun onStart() {
        super.onStart()
        ChangeBus.register(changeListener)
        // messages that arrived while stopped (flip closed / screen off) were
        // never loaded — the listener was off. Catch up now.
        onDataChanged()
    }

    override fun onStop() {
        super.onStop()
        ChangeBus.unregister(changeListener)
    }

    /** Bell-off / mute / vibrate-only badge on the thread bar. Priority:
     *  notifications blocked > muted > vibrate-only. */
    private fun updateNotifStatusIcon(c: ConversationEntity) {
        if (c.isGroup) {
            binding.iconGroupModeThread.setImageResource(
                if (c.groupMode == GroupMode.GROUP_MMS) R.drawable.ic_group_mms
                else R.drawable.ic_broadcast
            )
            binding.iconGroupModeThread.visibility = View.VISIBLE
        } else {
            binding.iconGroupModeThread.visibility = View.GONE
        }
        binding.iconPinThread.visibility = if (c.pinned) View.VISIBLE else View.GONE
        // these states are mutually exclusive in practice — a blocked number
        // has no notification to mute, a muted thread can't vibrate — so show
        // exactly one, strongest first
        val icon = ConvoStatusIcon.forConversation(this, c)
        if (icon == 0) {
            binding.threadNotifIcon.visibility = View.GONE
        } else {
            binding.threadNotifIcon.setImageResource(icon)
            binding.threadNotifIcon.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        visibleConvoId = convoId
        NotificationHelper.cancel(this, convoId)
        // the mute / blocked / vibrate state can be changed from the list or
        // this thread's own menu — re-read it so the top-bar badge is current
        lifecycleScope.launch {
            repo.db.conversations().byId(convoId)?.let {
                convo = it
                updateNotifStatusIcon(it)
            }
        }
        applyTopBarSymbols()
        applyComposeButtonLayout()
        updateSoftkeys()
        threadShown = true
        fullReadCheckDue = true
        scheduleMarkRead()
        // color / style / thumbnail settings can change while a settings screen
        // was on top; rebind so they take effect on return without reopening
        if (::adapter.isInitialized) {
            applyChatBackground()
            adapter.notifyDataSetChanged()
        }
    }

    override fun onPause() {
        prefs.setDraft(convoId, binding.composeInput.text?.toString().orEmpty())
        // last look before leaving (BACK / Home / screen off)
        markRead()
        threadShown = false
        binding.msgList.removeCallbacks(markReadSoon)
        super.onPause()
        visibleConvoId = -1L
        saveDraft()
    }

    /** Floating date label shown while fast-scrolling with a held D-pad. */
    /** Touch scrolling shows the same date bubble the D-pad hold-scroll uses.
     *  Attached once from onCreate. */
    private fun attachTouchDateBubble() {
        binding.msgList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            private var touching = false
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) touching = true
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    touching = false
                    binding.dateBubble.removeCallbacks(hideBubble)
                    binding.dateBubble.postDelayed(hideBubble, 600)
                }
            }
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (touching || rv.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING) {
                    showDateBubble()
                }
            }
        })
    }

    private fun showDateBubble() {
        // D-pad scrolling has a focused row; touch flings do NOT — the old
        // focusedChild-or-return meant the bubble could never appear for
        // touch (field report). Fall back to the first visible row.
        val pos = binding.msgList.focusedChild
            ?.let { binding.msgList.getChildAdapterPosition(it) }
            ?.takeIf { it >= 0 }
            ?: (binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.findFirstVisibleItemPosition()?.takeIf { it >= 0 }
            ?: return
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
        // the softkey bar already offers Cancel/Select/Delete — the button bar is
        // only for phones without it (touch or D-pad-only)
        val showBar = softkeys?.shouldShow() != true
        binding.threadSelectionBar.visibility = if (showBar) View.VISIBLE else View.GONE
        binding.composeBar.visibility = View.GONE
        binding.btnSelCancelThread.setOnClickListener { exitSelection() }
        binding.btnSelDeleteThread.setOnClickListener { deleteSelected() }
    }

    /** Long-press while selecting (touch users): the actions as a dialog. */
    private fun selectionActionsDialog() {
        val n = selectedIds.size
        DsmsAlertBuilder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.n_selected, n)))
            .setItems(arrayOf(
                getString(R.string.forward_n, n),
                getString(R.string.copy_n, n),
                getString(R.string.delete_n, n),
                getString(R.string.cancel_selection)
            )) { _, which ->
                when (which) {
                    0 -> forwardSelected()
                    1 -> copySelected()
                    2 -> deleteSelected()
                    3 -> exitSelection()
                }
            }
            .show()
    }

    /** Selected rows, oldest first (the order they were sent/received). */
    private fun selectedRowsInOrder(): List<MessageRow> =
        rows.filter { it.msg.id in selectedIds && !it.isSystemLine }
            .sortedWith(compareBy({ it.msg.date }, { it.msg.id }))

    /** Copy the text of all selected messages, oldest first, one per paragraph. */
    private fun copySelected() {
        val text = selectedRowsInOrder().map { it.msg.body.trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        if (text.isEmpty()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        ElementActions.copy(this, text)
        exitSelection()
    }

    /** Forward all selected messages (text + attachments), oldest first. */
    private fun forwardSelected() {
        val picked = selectedRowsInOrder()
        if (picked.isEmpty()) { exitSelection(); return }
        // full-screen chat list, Cancel on the RIGHT soft key
        ChatTargetPicker.show(
            this, getString(R.string.forward_n, picked.size),
            onNew = { forwardManyToNew(picked) },
            // tick several chats, Send forwards to all of them at once
            onPickMany = { chats ->
                lifecycleScope.launch {
                    repo.scope.async {
                        for (c in chats) for (r in picked) forwardOne(r, c.id)
                    }.await()
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this@ThreadActivity,
                        resources.getQuantityString(R.plurals.forwarded_to_chats, chats.size, chats.size),
                        Toast.LENGTH_SHORT).show()
                    exitSelection()
                }
            },
        ) { c ->
            lifecycleScope.launch {
                repo.scope.async {
                    for (r in picked) forwardOne(r, c.id)
                }.await()
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this@ThreadActivity,
                    getString(R.string.forwarded_n, picked.size), Toast.LENGTH_SHORT).show()
                exitSelection()
            }
        }
    }

    /** Copies a message's attachments so the forward owns its own files. */
    private fun copyParts(r: MessageRow): List<Triple<String, String, String>> {
        val dir = File(filesDir, "parts").apply { mkdirs() }
        return r.parts.mapNotNull { part ->
            try {
                val src = File(part.filePath)
                if (!src.exists()) return@mapNotNull null
                val copy = File(dir, "fwd_${System.currentTimeMillis()}_${part.id}_${part.fileName}")
                src.copyTo(copy, overwrite = true)
                Triple(copy.absolutePath, part.mimeType, part.fileName)
            } catch (_: Exception) { null }
        }
    }

    private suspend fun forwardOne(r: MessageRow, dest: Long) {
        try {
            val atts = copyParts(r)
            if (atts.isNotEmpty()) repo.sendAttachment(dest, r.msg.body, atts)
            else if (r.msg.body.isNotBlank()) repo.sendText(dest, r.msg.body)
        } catch (_: Exception) {}
    }

    /** "New number…": open the new-message screen with every text joined and
     *  every attachment staged (limits applied there). */
    private fun forwardManyToNew(picked: List<MessageRow>) {
        lifecycleScope.launch {
            val atts = repo.scope.async { picked.flatMap { copyParts(it) } }.await()
            val text = picked.map { it.msg.body.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            startActivity(Intent(this@ThreadActivity, ComposeActivity::class.java)
                .putExtra("prefill_body", text)
                .putStringArrayListExtra(EXTRA_SHARED_PATHS, ArrayList(atts.map { it.first }))
                .putStringArrayListExtra(EXTRA_SHARED_MIMES, ArrayList(atts.map { it.second }))
                .putStringArrayListExtra(EXTRA_SHARED_NAMES, ArrayList(atts.map { it.third })))
            exitSelection()
        }
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
        binding.composeBar.visibility = View.VISIBLE
        binding.threadSubtitle.text = convo?.let { c ->
            if (c.isGroup) getString(
                if (c.groupMode == GroupMode.GROUP_MMS) R.string.subtitle_group
                else R.string.subtitle_broadcast
            )
            else if (io.github.theonionsarewatching.nova.data.NotesChat.isNotes(c)) getString(R.string.notes_subtitle)
            else c.addressList().firstOrNull() ?: ""
        } ?: ""
        updateSoftkeys()
    }

    private fun updateSelectionUi() {
        binding.threadSubtitle.text = getString(R.string.n_selected, selectedIds.size)
        binding.btnSelDeleteThread.text = getString(R.string.delete_n, selectedIds.size)
        softkeys?.set(
            getString(R.string.softkey_options), getString(R.string.softkey_select),
            getString(R.string.delete),
            // Options: Forward / Copy / Delete / Cancel (BACK still cancels)
            onLeft = { selectionActionsDialog() },
            onCenter = { binding.msgList.focusedChild?.performClick() },
            onRight = { deleteSelected() },
            onMenu = { selectionActionsDialog() }
        )
    }

    private fun deleteSelected() {
        if (selectedIds.isEmpty()) { exitSelection(); return }
        lifecycleScope.launch {
            val lockedCount = selectedIds.count { id -> rows.firstOrNull { it.msg.id == id }?.msg?.locked == true }
            val ids = selectedIds.toList()
            if (lockedCount > 0) {
                DsmsAlertBuilder(this@ThreadActivity)
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
                DsmsAlertBuilder(this@ThreadActivity)
                    .setMessage(getString(R.string.delete_selected_confirm, ids.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch { repo.deleteMessages(ids, includeLocked = true); exitSelection() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private var dividerCaptured = false

    /** Reads are marked by VISIBILITY: only messages the user has actually
     *  scrolled to count as read. The chip at the bottom shows how many unread
     *  remain below, and clears as they're seen.
     *
     *  Fix for "newest message stays unread": the old version measured
     *  the last visible row right after a new message was added, BEFORE the
     *  list had laid it out, so it marked only up to the previous message —
     *  and nothing measured again (D-pad scrolling uses scrollBy, which never
     *  reports "scroll idle"). Now: re-check after every layout/scroll
     *  (scheduleMarkRead), and when the newest message is on screen mark the
     *  WHOLE thread read, exactly like "Mark as read" in the list. The DB work
     *  runs in the app scope so pressing BACK right away can't cancel it, and
     *  the list is pinged to recount. */
    private fun markRead() {
        // callable from onResume, which runs BEFORE the async convo load has
        // created the adapter — nothing to mark yet in that window
        if (!::adapter.isInitialized) return
        if (!threadShown) return          // screen off / another app on top
        // perf: the full check (read-report scan, notification clean-up, the
        // whole-database unread count) runs once per showing of the screen
        // and whenever this pass marks something new; the passes after each
        // scroll/layout in between only do the cheap per-chat queries
        val fullCheck = fullReadCheckDue
        fullReadCheckDue = false
        val lm = binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val lastVis = lm?.findLastVisibleItemPosition() ?: -1
        val snapshot = rows
        val anchor = snapshot.getOrNull(lastVis)?.msg
        val rowCount = snapshot.size
        val moreNewer = hasMoreNewer
        val lastLoadedId = snapshot.lastOrNull()?.msg?.id
        val cid = convoId
        val app = applicationContext
        lifecycleScope.launch {
            if (!dividerCaptured) {
                dividerCaptured = true
                adapter.newDividerMessageId =
                    repo.db.messages().firstUnreadId(cid) ?: -1L
            }
            val left = repo.scope.async {
                val msgs = repo.db.messages()
                val mode = io.github.theonionsarewatching.nova.util.ReadMarking.decide(
                    lastVis, rowCount, moreNewer, lastLoadedId, msgs.newest(cid)?.id
                )
                val changed = when (mode) {
                    io.github.theonionsarewatching.nova.util.ReadMarking.Mode.ALL ->
                        msgs.markThreadReadCount(cid)
                    io.github.theonionsarewatching.nova.util.ReadMarking.Mode.UP_TO ->
                        if (anchor != null) msgs.markReadUpTo(cid, anchor.date, anchor.id) else 0
                    else -> 0
                }
                // answer any read-report requests now that these are read
                if (fullCheck || changed > 0) {
                    try { repo.sendPendingReadRecs(cid) } catch (_: Exception) {}
                }
                if (changed > 0) {
                    repo.refreshConversation(cid)   // list count / bold / badge
                    ChangeBus.ping()                // list recounts even if already shown
                }
                val n = msgs.unreadCount(cid)
                if (n == 0 && (fullCheck || changed > 0)) {
                    val nm = androidx.core.app.NotificationManagerCompat.from(app)
                    nm.cancel(cid.toInt())
                    if (msgs.totalUnread() == 0) nm.cancel(1000)
                }
                n
            }.await()
            binding.unreadChip.text = left.toString()
            binding.unreadChip.visibility = if (left > 0) View.VISIBLE else View.GONE
        }
    }

    // ---------------- compact bars while scrolling ----------------
    private var compact = false

    /** Scrolling the messages: slimmer top bar (no subtitle) and compose bar
     *  (no + / send buttons, tighter padding) so more messages fit. Back in
     *  the box: full size. Only touches views when the state changes. */
    private fun setCompact(on: Boolean) {
        if (on == compact || !::binding.isInitialized) return
        compact = on
        val d = resources.displayMetrics.density
        val px = { v: Int -> (v * d).toInt() }
        (binding.threadTitle.parent?.parent as? View)?.minimumHeight = px(if (on) 26 else 36)
        if (!selecting) binding.threadSubtitle.visibility = if (on) View.GONE else View.VISIBLE
        binding.composeBar.setPaddingRelative(px(4), px(if (on) 1 else 3), px(4), px(if (on) 1 else 3))
        binding.composeInput.setPaddingRelative(px(12), px(if (on) 3 else 8), px(12), px(if (on) 3 else 8))
        if (on) {
            binding.btnAttach.visibility = View.GONE
            binding.btnSend.visibility = View.GONE
        } else {
            applyComposeButtonLayout()
        }
    }

    /** True between onResume and onPause: only then does looking count as reading. */
    private var threadShown = false

    /** Next markRead does the full check (see markRead); set on every resume. */
    private var fullReadCheckDue = true

    private val markReadSoon = Runnable { markRead() }

    /** Mark after the list has settled (layout done), debounced. */
    private fun scheduleMarkRead() {
        if (!::binding.isInitialized) return
        binding.msgList.removeCallbacks(markReadSoon)
        binding.msgList.postDelayed(markReadSoon, 150)
    }

    private fun saveDraft() {
        val text = binding.composeInput.text?.toString().orEmpty()
        prefs.setDraftAttachments(convoId, DraftPrompt.encode(pendingAttachments))
        // attachment-only draft still shows as "Draft: <name>" in the list
        val listText = text.ifBlank { pendingAttachments.firstOrNull()?.third ?: "" }
        lifecycleScope.launch {
            repo.db.conversations().setDraft(convoId, listText)
        }
    }

    private fun hasUnsent() = !binding.composeInput.text.isNullOrBlank() || pendingAttachments.isNotEmpty()

    private fun discardDraft() {
        draftRunnable?.let { draftHandler.removeCallbacks(it) }
        binding.composeInput.setText("")
        clearAttachments(deleteFiles = true)
        prefs.setDraft(convoId, ""); prefs.setDraftAttachments(convoId, "")
        lifecycleScope.launch { repo.db.conversations().setDraft(convoId, "") }
    }

    private fun saveDraftAndExit() {
        prefs.setDraft(convoId, binding.composeInput.text?.toString().orEmpty())
        saveDraft(); goBack()
    }

    // ============================== loading ==============================

    private suspend fun buildRows(messages0: List<MessageEntity>): List<MessageRow> {
        val c = convo ?: return emptyList()
        // a copy already stored twice for the same provider
        // row (made by the old ingest race) shows once
        val seenT = HashSet<String>()
        val messages = messages0.filter { m ->
            val t = m.telephonyId ?: return@filter true
            seenT.add((if (m.telephonyIsMms) "m" else "s") + t)
        }
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

    /** Opened straight into this thread (notification, dialer)? Back should
     *  land on the conversation list, not exit the app. */
    override fun onClipboardChanged() {
        // the compose-mode left label depends on the clipboard state
        if (composeMode) runOnUiThread { updateSoftkeys() }
    }

    private fun goBack() {
        if (isTaskRoot) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    override fun onBackPressed() {
        goBack()
    }

    private suspend fun updateScrollExtent() {
        val lm = binding.msgList.layoutManager as? BoundedLinearLayoutManager ?: return
        if (rows.isEmpty()) { lm.totalBefore = 0; lm.totalAfter = 0; return }
        val first = rows.first().msg
        val last = rows.last().msg
        lm.totalBefore = repo.db.messages().countOlderThan(convoId, first.date, first.id)
        lm.totalAfter = repo.db.messages().countNewerThan(convoId, last.date, last.id)
        binding.msgList.invalidate()
    }

    private fun loadLatest(focusBottom: Boolean, then: (() -> Unit)? = null) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val latest = repo.db.messages().latest(convoId, PAGE).reversed()
            val tQuery = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            rows = ArrayList(buildRows(latest))
            val tBuild = System.currentTimeMillis() - t1
            hasMoreOlder = latest.size >= PAGE
            hasMoreNewer = false
            val t2 = System.currentTimeMillis()
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            val tNotify = System.currentTimeMillis() - t2
            Perf.logLoad(this@ThreadActivity, rows, tQuery, tBuild, tNotify, t0)
            binding.emptyLabel.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            updateScrollExtent()
            if (focusBottom) {
                binding.msgList.scrollToPosition((rows.size - 1).coerceAtLeast(0))
                enterComposeMode()
            }
            // mark what's on screen once the initial layout settles — the
            // programmatic scroll above fires no scroll events, so without this
            // a thread opened into compose focus never marked anything read
            scheduleMarkRead()
            loading = false
            then?.invoke()
        }
    }

    private fun loadOlder() {
        if (loading || !hasMoreOlder || rows.isEmpty()) return
        loading = true
        binding.loadingBanner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val first = rows.first().msg
            val older = repo.db.messages()
                .olderThan(convoId, first.date, first.id, PAGE_MORE).reversed()
            val tQuery = System.currentTimeMillis() - t0
            hasMoreOlder = older.size >= PAGE_MORE
            if (older.isNotEmpty()) {
                // keep the exact scroll position AND the focused view: capture the
                // current anchor, insert above, then restore the anchor shifted by
                // the insert count — the focused view instance survives untouched,
                // so focus can never blip up to the header during the re-layout
                val hadFocus = binding.msgList.focusedChild != null
                val anchorView = binding.msgList.focusedChild ?: binding.msgList.getChildAt(0)
                val anchorPos = anchorView?.let { binding.msgList.getChildAdapterPosition(it) } ?: -1
                val anchorOffset = anchorView?.top ?: 0
                val newRows = buildRows(older)
                rows.addAll(0, newRows)
                adapter.rows = rows
                adapter.notifyItemRangeInserted(0, newRows.size)
                updateScrollExtent()
                if (anchorPos >= 0) {
                    (binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(anchorPos + newRows.size, anchorOffset)
                }
                if (hadFocus) binding.msgList.post {
                    // the insert can momentarily recycle the focused row; if
                    // focus escaped the list (it lands on the top bar), bring
                    // it back to the same message instead
                    if (binding.msgList.focusedChild == null) {
                        scroller?.focusPosition(anchorPos + newRows.size, false)
                    }
                }
                Perf.log(
                    this@ThreadActivity, "loadOlder",
                    "rows=${older.size} total=${rows.size} query=${tQuery}ms " +
                        "total=${System.currentTimeMillis() - t0}ms"
                )
            }
            binding.loadingBanner.visibility = View.GONE
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
                updateScrollExtent()
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
            updateScrollExtent()
            enterScrollMode(focusPos = pos)
            // only what is actually on screen gets marked
            scheduleMarkRead()
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
            val lmB = binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val atBottom = (lmB?.findLastVisibleItemPosition() ?: -1) >= adapter.itemCount - 1
            val t0 = System.currentTimeMillis()
            // After scrolling far back, `rows` can hold thousands of messages.
            // Re-loading ALL of them on every new message made heavy threads
            // stall. Now: keep the older part as-is (if a cheap fingerprint
            // says nothing in it changed) and only re-load the newest PAGE
            // plus anything newer. Any doubt -> the old full reload.
            val requested = maxOf(PAGE, rows.size)
            val tailRows = refreshTailOnly()
            val tQuery = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            if (tailRows != null) {
                rows = tailRows
            } else {
                val latest = repo.db.messages().latest(convoId, requested).reversed()
                rows = ArrayList(buildRows(latest))
                hasMoreOlder = latest.size >= requested
            }
            val tBuild = System.currentTimeMillis() - t1
            val t2 = System.currentTimeMillis()
            adapter.submit(rows) // diff: only changed rows repaint — no full-list flash
            Perf.logRefresh(
                this@ThreadActivity, rows.size, requested, tQuery, tBuild,
                System.currentTimeMillis() - t2, t0
            )
            binding.emptyLabel.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            updateScrollExtent()
            if (wasCompose && atBottom) {
                // follow new messages only when already at the bottom — never
                // yank the reader away from where they are
                binding.msgList.scrollToPosition((rows.size - 1).coerceAtLeast(0))
            } else if (focusedId != null) {
                val pos = rows.indexOfFirst { it.msg.id == focusedId }
                if (pos >= 0) scroller?.focusPosition(pos)
            }
            // after the new rows are laid out, not now (old bug: measured the
            // previous last row, so the newest message stayed unread)
            scheduleMarkRead()
        }
    }

    /** Live-tail refresh for big windows: returns the new row list, or null
     *  when the caller should fall back to the full reload. */
    private suspend fun refreshTailOnly(): ArrayList<MessageRow>? {
        val keep = rows.size - PAGE
        if (keep <= 0) return null                     // small window: full reload is cheap
        val first = rows.first().msg
        val cut = rows[keep - 1].msg
        // 1) is the kept (older) part unchanged? fingerprints only, no bodies
        val stamps = try {
            repo.db.messages().stampsBetween(convoId, first.date, first.id, cut.date, cut.id)
        } catch (_: Exception) { return null }
        if (stamps.size != keep) return null
        for (i in 0 until keep) {
            val r = rows[i]
            val st = stamps[i]
            val m = r.msg
            if (st.id != m.id || st.status != m.status || st.locked != m.locked ||
                st.date != m.date || st.sched != (m.scheduledAt ?: 0L) ||
                st.partCount != r.parts.size ||
                st.bodyBytes != m.body.toByteArray(Charsets.UTF_8).size.toLong()
            ) return null
        }
        // 2) re-load only the rows after the cut (the old tail + new messages)
        val limit = PAGE * 4
        val tail = repo.db.messages().newerThan(convoId, cut.date, cut.id, limit)
        if (tail.size >= limit) return null            // flood of new rows: full reload
        val out = ArrayList<MessageRow>(keep + tail.size)
        out.addAll(rows.subList(0, keep))
        out.addAll(buildRows(tail))
        return out
    }

    // ============================== compose <-> scroll boundary ==============================

    /** no symbols for soft-key actions in the top bar. With
     *  the soft-key bar showing, the options are on a soft key, so the
     *  three-dots button is hidden; only the small back arrow stays on the
     *  left. Touch phones (no soft-key bar) keep the dots to reach options. */
    private fun applyTopBarSymbols() {
        binding.btnOverflow.visibility =
            if (softkeys?.shouldShow() == true) View.GONE else View.VISIBLE
    }

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

    /** Compose-bar buttons for the current mode:
     *  - bar ON, normal: both on-screen buttons hidden (softkeys carry them)
     *  - bar ON, send-on-left: the + stays on screen at the left (attach /
     *    record / paste); Send lives on the LEFT softkey
     *  - bar OFF, normal: [ + ] [ box ] [ send ]
     *  - bar OFF, send-on-left: [ + ] [ send ] [ box ] with an explicit
     *    left-arrow focus chain box -> Send -> +, for keyboards that consume
     *    the right D-pad as Space (no escape to the right of the box). */
    private fun applyComposeButtonLayout() {
        val barShown = softkeys?.shouldShow() == true
        val sol = prefs.sendOnLeft
        binding.btnAttach.visibility = if (barShown && !sol) View.GONE else View.VISIBLE
        binding.btnSend.visibility = if (barShown) View.GONE else View.VISIBLE
        val bar = binding.composeBar
        val frame = binding.composeInput.parent as View
        val wantSendFirst = sol && !barShown
        val isSendFirst = bar.indexOfChild(binding.btnSend) in 0 until bar.indexOfChild(frame)
        if (wantSendFirst != isSendFirst) {
            bar.removeView(binding.btnAttach)
            bar.removeView(binding.btnSend)
            if (wantSendFirst) {
                bar.addView(binding.btnSend, 0)
                bar.addView(binding.btnAttach, 0)
            } else {
                bar.addView(binding.btnAttach, 0)
                bar.addView(binding.btnSend)
            }
        }
        if (wantSendFirst) {
            binding.composeInput.nextFocusLeftId = binding.btnSend.id
            binding.btnSend.nextFocusLeftId = binding.btnAttach.id
            binding.btnSend.nextFocusRightId = binding.composeInput.id
            binding.btnAttach.nextFocusRightId = binding.btnSend.id
        } else {
            binding.composeInput.nextFocusLeftId = View.NO_ID
            binding.btnSend.nextFocusLeftId = View.NO_ID
            binding.btnSend.nextFocusRightId = View.NO_ID
            binding.btnAttach.nextFocusRightId = View.NO_ID
        }
    }

    /** Compose mode: Attach | Send on the softkeys. Scroll mode: Options | Select | Compose. */
    private fun updateSoftkeys() {
        if (selecting) { updateSelectionUi(); return }
        if (composeMode) {
            if (prefs.sendOnLeft) {
                // send-on-left: Send is the LEFT softkey, the right stays
                // blank (its action is null so the physical key is inert), and
                // attach/record/paste live on the on-screen + button
                softkeys?.set(
                    getString(R.string.softkey_send), null, null,
                    onLeft = { send() },
                    onCenter = null,
                    onRight = null,
                    onMenu = { threadOptions() }
                )
            } else {
                val leftLabel = if (clipboardText().isNullOrBlank())
                    getString(R.string.softkey_attach) else getString(R.string.softkey_options)
                softkeys?.set(
                    leftLabel, null, getString(R.string.softkey_send),
                    onLeft = { AttachOrPaste.open(this, binding.composeInput,
                        onAttach = { pickAttachment() },
                        onRecord = { recordAudioAttachment() }) },
                    onCenter = null,
                    onRight = { send() },
                    onMenu = { threadOptions() }
                )
            }
        } else {
            // scroll mode: the center opens the focused message; it is NOT the
            // selection action (that's entered by long-press / hold), so it must
            // not read "Select" — only real selection mode shows that
            softkeys?.set(
                getString(R.string.softkey_options), null, getString(R.string.softkey_compose),
                onLeft = { threadOptions() },
                onCenter = { binding.msgList.focusedChild?.performClick() },
                onRight = { enterComposeMode() },
                onMenu = { threadOptions() }
            )
        }
    }

    /** D-pad-center sends only when the softkey bar is off (otherwise a
     *  softkey Send exists) and the compose box is focused. The on-screen
     *  "Send" hint bar this used to drive is gone (user request) — the
     *  BEHAVIOR stays as the escape hatch for keyboards that eat the right
     *  D-pad as Space. */
    private fun sendHintActive(): Boolean =
        softkeys?.shouldShow() != true && binding.composeInput.hasFocus()

    /** chars-over-segments counter in the corner of the compose box */
    private fun updateCharCounter() {
        val len = binding.composeInput.text?.length ?: 0
        // no SMS parts in the notes chat: nothing is sent
        if (len == 0 || !binding.composeInput.hasFocus() || io.github.theonionsarewatching.nova.data.NotesChat.isNotes(convo)) {
            binding.charCounter.visibility = View.GONE
            return
        }
        binding.charCounter.visibility = View.VISIBLE
        binding.charCounter.text = if (pendingAttachments.isNotEmpty()) {
            len.toString()
        } else {
            // real SMS parts. Plain (GSM) text: 160 in one part,
            // 153 per part when split; any emoji / non-GSM letter (e.g.
            // Hebrew) makes it UCS-2: 70 in one part, 67 per part when split.
            // Shown as (used in this part/parts).
            val text = binding.composeInput.text?.toString() ?: ""
            val r = try { android.telephony.SmsMessage.calculateLength(text, false) }
                catch (_: Exception) { null }
            if (r == null || r.size < 4 || r[0] < 1) {
                val segs = (len + 159) / 160
                "(${len - 160 * (segs - 1)}/$segs)"
            } else {
                val segs = r[0]
                val ucs2 = r[3] == android.telephony.SmsMessage.ENCODING_16BIT
                val perPart = if (ucs2) { if (segs > 1) 67 else 70 } else { if (segs > 1) 153 else 160 }
                val used = (perPart - r[2]).coerceIn(0, perPart)
                "($used/$segs)"
            }
        }
    }

    private fun updateSendHint() {
        // when the softkeys are D-pad selectable and in compose mode, the right
        // slot is Send — make D-pad-down from the text box land there first,
        // instead of Android's default nearest pick (the attach slot on the left)
        if (prefs.softkeysFocusable && composeMode && softkeys?.shouldShow() == true) {
            binding.composeInput.nextFocusDownId = binding.softkeyBar.softRight.id
        } else {
            binding.composeInput.nextFocusDownId = View.NO_ID
        }
    }

    private fun enterComposeMode() {
        composeMode = true
        setCompact(false)
        binding.composeInput.maxLines = prefs.composeMaxLines
        binding.moreIndicator.visibility = View.GONE
        binding.composeInput.requestFocus()
        binding.composeInput.setSelection(binding.composeInput.text?.length ?: 0)
        updateSoftkeys()
        updateSendHint()
    }

    private fun enterScrollMode(focusPos: Int = rows.size - 1) {
        if (rows.isEmpty()) return
        composeMode = false
        setCompact(true)
        binding.composeInput.maxLines = 1
        val multiline = (binding.composeInput.layout?.lineCount ?: 1) > 1 ||
            binding.composeInput.text?.contains('\n') == true
        binding.moreIndicator.visibility = if (multiline) View.VISIBLE else View.GONE
        // do NOT focus the list itself first: RecyclerView hands focus to its
        // topmost child, which flashed before the real target took over
        val target = focusPos.coerceIn(0, rows.size - 1)
        if (target == rows.size - 1) focusBottomPinned() else scroller?.focusPosition(target)
        updateSoftkeys()
        updateSendHint()
    }

    /** Focus the newest message but keep the list scrolled to the very bottom —
     *  a tall last message stays bottom-aligned until the next D-pad up sub-scrolls it. */
    private fun focusBottomPinned() {
        // a trailing "— switched to … —" line is inert: pin to the last REAL
        // message, or D-pad-up from the compose box has nothing to land on
        var pos = rows.size - 1
        while (pos >= 0 && rows[pos].isSystemLine) pos--
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

    /** Jump keys while a message is focused (UP/DOWN stay the
     *  small step with hold acceleration, in DpadScroller):
     *    LEFT / 2 / Page Up      one screen up      (held: keeps paging)
     *    RIGHT / 8 / Page Down   one screen down    (held: keeps paging)
     *    1                       top (oldest message of the whole chat)
     *    7                       bottom (newest message)
     *  1/7 act on key-up. */
    private fun handleJumpKey(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_PAGE_UP -> true
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_PAGE_DOWN -> false
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_7 -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    if (event.keyCode == KeyEvent.KEYCODE_1) jumpToTop() else jumpToBottom()
                }
                return true
            }
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (rows.isEmpty()) return true
        val rv = binding.msgList
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return true
        val pageH = (rv.height * 0.85f).toInt().coerceAtLeast(1)
        // raw scroll (the bounded manager only clamps focus-driven scrolls)
        rv.scrollBy(0, if (up) -pageH else pageH)
        val pos = if (up) lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: lm.findFirstVisibleItemPosition()
        else lm.findLastCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: lm.findLastVisibleItemPosition()
        if (pos >= 0) {
            val v = lm.findViewByPosition(pos)
            if (v != null && v.isFocusable) v.requestFocus() else scroller?.focusPosition(pos, !up)
        }
        if (up && pos in 0..3) loadOlder()
        if (!up && hasMoreNewer && pos > rows.size - 5) loadNewer()
        showDateBubble()
        return true
    }

    /** "1": the very first message of the chat (loads the oldest page). */
    private fun jumpToTop() {
        if (loading) return
        if (!hasMoreOlder) {
            binding.msgList.scrollToPosition(0)
            scroller?.focusPosition(0, false)
            return
        }
        loading = true
        lifecycleScope.launch {
            val oldest = repo.db.messages().newerThan(convoId, Long.MIN_VALUE, Long.MIN_VALUE, PAGE)
            rows = ArrayList(buildRows(oldest))
            hasMoreOlder = false
            hasMoreNewer = oldest.size >= PAGE
            adapter.rows = rows
            adapter.notifyDataSetChanged()
            updateScrollExtent()
            binding.msgList.scrollToPosition(0)
            binding.msgList.post { scroller?.focusPosition(0, false) }
            loading = false
        }
    }

    /** "7": the newest message (reloads the latest page if paged away). */
    private fun jumpToBottom() {
        if (loading) return
        if (hasMoreNewer) {
            loadLatest(focusBottom = false) { binding.msgList.post { enterScrollMode() } }
        } else {
            enterScrollMode()   // focuses the last row, pinned to the bottom
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (selecting && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            exitSelection()
            return true
        }
        if (selecting && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            (binding.btnSelCancelThread.hasFocus() || binding.btnSelDeleteThread.hasFocus())
        ) {
            binding.msgList.requestFocus()
            val lm = binding.msgList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            lm?.findLastVisibleItemPosition()?.takeIf { it >= 0 }?.let { scroller?.focusPosition(it) }
            return true
        }
        if (softkeys?.handleKey(event) == true) return true

        // a tall first message (a picture): keep scrolling it into view on
        // D-pad-up; only once its top is fully visible may focus leave the
        // list for the top bar
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        ) {
            val fv = currentFocus
            if (fv != null && fv.parent === binding.msgList &&
                binding.msgList.getChildAdapterPosition(fv) == 0
            ) {
                if (fv.top < 0) {
                    val step = (80 * resources.displayMetrics.density).toInt()
                    binding.msgList.scrollBy(0, maxOf(fv.top, -step))
                    return true
                }
                if (hasMoreOlder || loading) {
                    // older history exists: pull it in before focus may leave
                    loadOlder()
                    return true
                }
            }
        }

        // D-pad center = Send when the compose box is focused and the softkey
        // bar is off. This is the escape hatch for keyboards that repurpose the
        // right D-pad as Space (Kyocera E4810), leaving the on-screen Send
        // button unreachable by D-pad. ENTER is left alone so it still inserts
        // newlines in the multi-line input.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
            event.repeatCount == 0 && sendHintActive()
        ) {
            send()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && sendHintActive()
        ) {
            return true
        }

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
                // physical Back from the header means "leave this conversation",
                // same as the on-screen back arrow
                finish()
                return true
            }
        } else if (binding.btnBack.isFocusable && !binding.btnBack.hasFocus() && !binding.btnOverflow.hasFocus()) {
            // focus moved elsewhere by other means: relock the header
            setHeaderFocusable(false)
        }
        if (binding.msgList.hasFocus() && handleJumpKey(event)) return true
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
                if (!c.isGroup && !io.github.theonionsarewatching.nova.data.NotesChat.isNotes(c)) {
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

    /** A video too long for this carrier's MMS cap gets the CUTTER instead
     *  of a refusal: cut to the computed max (with a typed start time), or
     *  cancel. Returns the attachment list to send, or null when the user
     *  bailed (the dialog re-sends through the same path). The limit is
     *  computed per phone and per clip — carrier byte cap from the device's
     *  own MMS config, audio share from the clip's real bitrate. */
    private suspend fun offerVideoCutIfNeeded(
        text: String, attachments: List<Triple<String, String, String>>
    ): List<Triple<String, String, String>>? {
        val vc = io.github.theonionsarewatching.nova.util.VideoCompressor
        val over = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            attachments.firstOrNull { (path, mime, _) ->
                mime.startsWith("video/") && try {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(path)
                    val ms = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    r.release()
                    ms / 1000 > vc.maxDurationSecFor(this@ThreadActivity, java.io.File(path))
                } catch (_: Exception) { false }
            }
        } ?: return attachments
        val (path, _, name) = over
        val maxS = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vc.maxDurationSecFor(this@ThreadActivity, java.io.File(path))
        }
        val durMs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(path)
                val d = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                r.release(); d
            } catch (_: Exception) { 0L }
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.seconds_short_hint)
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val wrap = Dialogs.inputPanel(this, input, getString(R.string.cut_start_hint))
        DsmsAlertBuilder(this)
            .setTitle(R.string.cut_video_title)
            .setMessage(getString(R.string.cut_video_msg, durMs / 1000, maxS))
            .setView(wrap)
            .setPositiveButton(R.string.cut_and_send) { _, _ ->
                val start = (input.text?.toString()?.toLongOrNull() ?: 0L)
                    .coerceIn(0L, ((durMs / 1000 - maxS).coerceAtLeast(0L)))
                lifecycleScope.launch {
                    val cut = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vc.trim(this@ThreadActivity, java.io.File(path), start * 1000, maxS * 1000)
                    }
                    if (cut == null) {
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this@ThreadActivity, R.string.cut_failed,
                            Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val swapped = attachments.map { a ->
                        if (a.first == path) Triple(cut.absolutePath, "video/mp4",
                            name.substringBeforeLast('.', name) + "_cut.mp4") else a
                    }
                    repo.sendAttachment(convoId, text, swapped)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return null
    }

    private fun send() {
        val text = binding.composeInput.text?.toString()?.trim().orEmpty()
        val attachments = pendingAttachments.toList()
        if (text.isEmpty() && attachments.isEmpty()) return
        // pasted text has been used: clear it from the clipboard now
        io.github.theonionsarewatching.nova.util.ClipboardGuard.clearIfStillPasted(applicationContext)
        prefs.setDraft(convoId, "")
        prefs.setDraftAttachments(convoId, "")
        binding.composeInput.setText("")
        clearAttachments(deleteFiles = false)
        lifecycleScope.launch {
            repo.db.conversations().setDraft(convoId, "")
            if (attachments.isNotEmpty() &&
                io.github.theonionsarewatching.nova.data.NotesChat.isNotes(convo)) {
                // notes keep the text only (Repo says so); no video-cut prompt
                repo.sendAttachment(convoId, text, attachments)
            } else if (attachments.isNotEmpty()) {
                val ready = offerVideoCutIfNeeded(text, attachments) ?: return@launch
                repo.sendAttachment(convoId, text, ready)
            } else {
                repo.sendText(convoId, text)
            }
        }
        enterComposeMode()
    }

    /** Puts text/attachments shared from another app into the compose box.
     *  Runs once: the extras are removed so a recreate doesn't add them twice. */
    private fun applySharedContent() {
        val text = intent.getStringExtra(EXTRA_SHARED_TEXT)
        val paths = intent.getStringArrayListExtra(EXTRA_SHARED_PATHS)
        val mimes = intent.getStringArrayListExtra(EXTRA_SHARED_MIMES)
        val names = intent.getStringArrayListExtra(EXTRA_SHARED_NAMES)
        listOf(EXTRA_SHARED_TEXT, EXTRA_SHARED_PATHS, EXTRA_SHARED_MIMES, EXTRA_SHARED_NAMES)
            .forEach { intent.removeExtra(it) }
        if (!text.isNullOrBlank()) {
            val cur = binding.composeInput.text?.toString().orEmpty()
            val joined = if (cur.isBlank()) text else cur.trimEnd() + "\n" + text
            binding.composeInput.setText(joined)
            binding.composeInput.setSelection(joined.length)
        }
        if (paths.isNullOrEmpty()) return
        var skipped = 0
        paths.forEachIndexed { i, path ->
            val f = File(path)
            val mime = mimes?.getOrNull(i) ?: "application/octet-stream"
            val name = names?.getOrNull(i) ?: f.name
            if (!f.exists() || f.length() <= 0) { skipped++; return@forEachIndexed }
            if (pendingAttachments.size >= io.github.theonionsarewatching.nova.util.AttachLimits.MAX_ITEMS ||
                !io.github.theonionsarewatching.nova.util.AttachLimits.fits(this, pendingAttachments, mime, f.length())
            ) { runCatching { f.delete() }; skipped++; return@forEachIndexed }
            addAttachment(path, mime, name)
        }
        if (skipped > 0) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
            this, getString(R.string.attach_skipped, skipped), Toast.LENGTH_LONG
        ).show()
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
        DsmsAlertBuilder(this)
            .setTitle(getString(R.string.attached_n, pendingAttachments.size))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < pendingAttachments.size) {
                    val item = pendingAttachments[which]
                    DsmsAlertBuilder(this)
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
        // full Attach list. "Record audio" lives in the small first
        // popup (AttachOrPaste) only; Photo / Video / From gallery are one
        // "Photo/Video" item that opens the gallery picker (images+videos).
        // Rows stay centered (exception to the compact left rows) and the
        // pop-up is narrowed to its labels.
        val labels = arrayOf(
                getString(R.string.attach_menu_photo_video),
                getString(R.string.attach_menu_camera),
                getString(R.string.attach_menu_contact),
                getString(R.string.attach_menu_audio),
                getString(R.string.attach_menu_file),
                getString(R.string.attach_menu_emoji)
            )
        val d = Dialogs.centeredItems(DsmsAlertBuilder(this), labels) { which ->
                when (which) {
                    // our own Photo/Video grid; "Other gallery…" = old route
                    0 -> MediaPickerDialog.show(
                        this,
                        maxItems = io.github.theonionsarewatching.nova.util.AttachLimits.MAX_ITEMS -
                            pendingAttachments.size,
                        maxVideos = if (pendingAttachments.any { it.second.startsWith("video/") }) 0 else 1,
                        onOtherGallery = { pickFromGalleryApp() }
                    ) { deliverPicked(it) }
                    // the phone's own Contacts and Music apps
                    // (our in-app lists only as a fallback when there is none)
                    // Camera: in-app Photo / Video choice, then the camera app
                    1 -> CameraCapture.choose(this, pendingAttachments)
                    2 -> pickContactAttachment()
                    3 -> pickAudioFromMusicApp()
                    4 -> pickFileAttachment()
                    5 -> EmojiPicker.show(this, binding.composeInput)
                }
            }
            .create()
        // final width BEFORE the first frame (no jump)
        Dialogs.narrowToLabels(d, labels)
        d.show()
    }

    /** The gallery app's OWN picker, straight up (user request; on phones
     *  whose GET_CONTENT chooser is broken — Schok — this is the reliable
     *  route). Falls back to the pictures flow when no gallery responds.
     *  Results come back through the same 201 handler as pickMedia. */
    private fun pickFromGalleryApp() {
        // no system ask when All files access already covers reading media
        // (it popped up on every Gallery press on the Flip 2)
        if (android.os.Build.VERSION.SDK_INT <= 32 &&
            !(android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 71)
        }
        // pictures AND videos (user request). "image/* video/*" is not a
        // valid single MIME — galleries ignored the video half. Layered:
        // (1) GET_CONTENT */* with both types listed (galleries that honor
        //     EXTRA_MIME_TYPES show one combined grid),
        // (2) PICK on the media provider (many OEM galleries show all media),
        // (3) the old image-only PICK as the floor.
        // The chosen layer is logged so field logs tell us per phone.
        fun tryStart(i: Intent, tag: String): Boolean = try {
            startActivityForResult(i, 201)
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                this, "attach", "gallery picker via $tag")
            true
        } catch (_: Exception) { false }
        val both = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            InAppPickers.askMultiple(this)
        }
        val pickAll = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "*/*")
            // most galleries ignore this on PICK; harmless where they do
            InAppPickers.askMultiple(this)
        }
        val pickImages = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            InAppPickers.askMultiple(this)
        }
        // the phone's REAL gallery app first, opened
        // explicitly (never the file manager / DocumentsUI, no chooser)
        val gallery = InAppPickers.galleryIntent(this)
        if (gallery != null && tryStart(gallery, "gallery-app " + gallery.component?.packageName)) return
        // ORDER MATTERS (08-09 log: "via get-content-both" opened the Files
        // UI, not the gallery): ACTION_PICK is the gallery's verb — it goes
        // first; GET_CONTENT-with-both-types is the fallback for phones whose
        // gallery ignores PICK; image-only PICK is the floor.
        if (!tryStart(pickAll, "pick-all-media") &&
            !(both.resolveActivity(packageManager) != null && tryStart(both, "get-content-both")) &&
            !tryStart(pickImages, "pick-images")
        ) {
            pickMedia("image/*", null)
        }
    }

    /** Typed picker: gallery/file apps filtered to [mime], with a capture app
     *  (camera / recorder) offered alongside. No OPENABLE category — some
     *  music apps return tracks that category filters out. */
    private fun pickMedia(mime: String, capture: Intent?) {
        // media-store uris returned by gallery/music apps need this on older
        // Android; without it the copy dies with a SecurityException
        // no system ask when All files access already covers reading media
        // (it popped up on every Gallery press on the Flip 2)
        if (android.os.Build.VERSION.SDK_INT <= 32 &&
            !(android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 71)
        }
        // some keypad phones (Schok Classic Flip in the field) have no app
        // registered for GET_CONTENT, so the chooser came up empty or threw —
        // fall back to the classic gallery picker, then the documents UI
        fun tryStart(i: Intent): Boolean = try {
            startActivityForResult(i, 201); true
        } catch (_: Exception) { false }
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mime
            InAppPickers.askMultiple(this)
        }
        val chooser = Intent.createChooser(pick, getString(R.string.softkey_attach))
        if (capture != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
        }
        val started = (pick.resolveActivity(packageManager) != null && tryStart(chooser)) ||
            tryStart(Intent(Intent.ACTION_PICK).apply {
                setDataAndType(
                    if (mime.startsWith("video"))
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    mime
                )
            }) ||
            tryStart(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = mime
            })
        if (!started) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_gallery,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** In-app voice recording: the fleet's recorder apps don't implement the
     *  capture contract, so we record ourselves and attach deterministically. */
    private fun recordAudioAttachment() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 72)
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                this, R.string.mic_permission_hint, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        io.github.theonionsarewatching.nova.ui.AudioRecorderDialog.show(this) { path, mime, name ->
            addAttachment(path, mime, name)
        }
    }

    private var cameraOutPath: String? = null

    /** Full-resolution camera capture straight into attachment staging. */
    private fun cameraImageIntent(): Intent? = try {
        val dir = java.io.File(filesDir, "parts").apply { mkdirs() }
        val out = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
        cameraOutPath = out.absolutePath
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", out
        )
        Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) { null }

    /** Files: the system file picker directly (ACTION_OPEN_DOCUMENT goes
     *  straight to it; GET_CONTENT + chooser listed every app twice). If
     *  the phone has no file picker, our own D-pad file browser. */
    private fun pickFileAttachment() {
        // the phone's file manager, opened DIRECTLY (explicit
        // component, no chooser); none found -> our own D-pad file browser
        val direct = InAppPickers.fileManagerIntent(this)
        if (direct != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(direct, 201)
                return
            } catch (_: Exception) {}
        }
        FileBrowserDialog.show(this,
            listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".mp4", ".3gp", ".m4a", ".amr", ".mp3", ".ogg", ".vcf"),
            { uri, _ -> deliverPicked(listOf(uri)) })
    }

    /** Hand in-app picks to the same code as a picker app's result. */
    private fun deliverPicked(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val data = Intent()
        val clip = android.content.ClipData.newRawUri("pick", uris[0])
        for (k in 1 until uris.size) clip.addItem(android.content.ClipData.Item(uris[k]))
        data.clipData = clip
        @Suppress("DEPRECATION")
        onActivityResult(201, RESULT_OK, data)
    }

    /** Attach > Contact: the phone's Contacts app (result: 231). */
    private fun pickContactAttachment() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI), 231
            )
        } catch (_: Exception) {
            // no Contacts app answers: our own list as the fallback
            InAppPickers.pickContact(this) { stageContactVcf(it) }
        }
    }

    /** Attach > Audio: the phone's Music app, opened directly (result: the
     *  normal attach handler, 201). Fallback: our own list. */
    private fun pickAudioFromMusicApp() {
        val music = InAppPickers.musicIntent(this)
        if (music != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(music, 201)
                return
            } catch (_: Exception) {}
        }
        InAppPickers.pickAudio(this, onRecord = { recordAudioAttachment() }) {
            deliverPicked(listOf(it))
        }
    }

    private fun stageContactVcf(contactUri: android.net.Uri) {
        lifecycleScope.launch {
            val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.theonionsarewatching.nova.util.ContactVcf
                    .buildFromContactUri(this@ThreadActivity, contactUri)
            } ?: return@launch
            try {
                val dir = File(filesDir, "parts").apply { mkdirs() }
                val safe = card.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
                    .ifBlank { "contact" }.replace(' ', '_')
                val out = File(dir, "${safe}_out_${System.currentTimeMillis()}.vcf")
                out.writeText(card.vcf)
                addAttachment(out.absolutePath, "text/x-vcard", out.name)
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQ_CHAT_BG ||
             requestCode == io.github.theonionsarewatching.nova.ui.ChatBackground.REQ_BG_GALLERY)
            && resultCode == RESULT_OK
        ) {
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
        if (requestCode == 231 && resultCode == RESULT_OK) {
            data?.data?.let { stageContactVcf(it) }
            return
        }
        if (requestCode == CameraCapture.REQ) {
            CameraCapture.onResult(this, resultCode, data, pendingAttachments,
                add = { p, m, n -> addAttachment(p, m, n) },
                fromUri = { deliverPicked(listOf(it)) })
            return
        }
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uris = ArrayList<android.net.Uri>()
            uris.addAll(InAppPickers.resultUris(this, data))
            if (uris.isEmpty()) {
                // camera capture with EXTRA_OUTPUT returns no uri — the photo
                // is already in our staging file
                val cam = cameraOutPath?.let { java.io.File(it) }
                cameraOutPath = null
                if (cam != null && cam.exists() && cam.length() > 0) {
                    addAttachment(cam.absolutePath, "image/jpeg", cam.name)
                }
                return
            }
            lifecycleScope.launch {
                var skipped = 0
                for (uri in uris) {
                    if (pendingAttachments.size >= io.github.theonionsarewatching.nova.util.AttachLimits.MAX_ITEMS) { skipped++; continue }
                    try {
                        val mime = contentResolver.getType(uri)
                            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                        val ext = io.github.theonionsarewatching.nova.util.MimeExt.forMime(mime)
                        // carry the source's display name (song title, photo
                        // name) into our filename, ahead of the usual suffix
                        val display = try {
                            contentResolver.query(
                                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null, null, null
                            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        } catch (_: Exception) { null }
                        val baseName = display?.substringBeforeLast('.')
                            ?.replace(Regex("[^A-Za-z0-9 _-]"), "")?.trim()
                            ?.replace(' ', '_')?.take(40)?.takeIf { it.isNotBlank() }
                            ?.plus("_") ?: ""
                        val dir = File(filesDir, "parts").apply { mkdirs() }
                        val out = File(dir, "${baseName}out_${System.currentTimeMillis()}_${uris.indexOf(uri)}$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (out.exists() && out.length() > 0 &&
                        !io.github.theonionsarewatching.nova.util.AttachLimits.fits(this@ThreadActivity, pendingAttachments, mime, out.length())
                    ) {
                        // over the MMS limits (multi-select): drop it, tell the user below
                        out.delete(); skipped++
                    } else if (out.exists() && out.length() > 0) {
                            // staged, not sent — goes out with the next Send press
                            addAttachment(out.absolutePath, mime, out.name)
                        }
                    } catch (e: Exception) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            this@ThreadActivity, "attach",
                            "stage failed: ${e.message} uri=$uri"
                        )
                    }
                }
                if (skipped > 0) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                    this@ThreadActivity, getString(R.string.attach_skipped, skipped),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============================== message press / hold ==============================

    /** Long text, or a row taller than the visible list (cut off). */
    private fun isLongRow(row: MessageRow): Boolean {
        val pos = rows.indexOf(row)
        val h = binding.msgList.findViewHolderForAdapterPosition(pos)?.itemView?.height ?: 0
        val listH = binding.msgList.height - binding.msgList.paddingTop - binding.msgList.paddingBottom
        return LongTextReader.isLong(row.msg.body, h, listH)
    }

    private fun pressMessage(row: MessageRow) {
        if (row.isSystemLine) return
        if (selecting) { toggleSelected(row); return }
        val m = row.msg
        when (m.status) {
            MsgStatus.FAILED, MsgStatus.RETRYING -> {
                // both retry paths, same as the long-press menu (user request)
                DsmsAlertBuilder(this)
                    .setItems(arrayOf(
                        getString(R.string.retry),
                        getString(R.string.retry_on_service),
                        getString(R.string.delete)
                    )) { _, w ->
                        when (w) {
                            0 -> lifecycleScope.launch { repo.retry(m.id) }
                            1 -> {
                                io.github.theonionsarewatching.nova.util.AutoRetry.add(this, m.id)
                                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.retry_on_service_armed,
                                    Toast.LENGTH_SHORT).show()
                            }
                            2 -> deleteMessage(m)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            MsgStatus.CANCELED -> {
                stateDialog(row, R.string.resend) { lifecycleScope.launch { repo.retry(m.id) } }
                return
            }
            MsgStatus.SCHEDULED -> {
                val items = arrayOf(getString(R.string.send_now), getString(R.string.cancel_schedule),
                    getString(R.string.message_options))
                DsmsAlertBuilder(this)
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
        val stub = io.github.theonionsarewatching.nova.data.MmsStub.decode(m.body)
        when {
            stub != null -> {
                if (stub.location.isBlank()) {
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                        this, R.string.mms_download_unavailable,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                        this, R.string.mms_downloading, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    io.github.theonionsarewatching.nova.util.ManualDownloads.mark(convoId)
                    io.github.theonionsarewatching.nova.sms.MmsPushReceiver.fetch(
                        this, stub.location, stub.transactionId, null, stub.subId
                    )
                }
            }
            // one attachment (and no long text) opens
            // straight away (a picture/video full screen). Several
            // attachments, or long text with attachments, show a pick list
            // with sharp thumbnails (+ "Read text" for the long text).
            row.parts.size > 1 || (row.parts.isNotEmpty() && hasLongText(row)) ->
                chooseAttachment(row)
            row.parts.isNotEmpty() -> openAttachment(row, 0)
            // a long text opens the full-screen reader; OK inside
            // it still offers the links/numbers
            row.parts.isEmpty() && isLongRow(row) -> LongTextReader.show(
                this, row.senderName.ifBlank { convo?.displayTitle() ?: "" }, m.body,
                if (row.elements.isNotEmpty()) {
                    { ElementActions.showForMessage(this, row.elements) { num -> messageNumber(num) } }
                } else null
            )
            row.elements.isNotEmpty() -> ElementActions.showForMessage(this, row.elements) { num -> messageNumber(num) }
            else -> { /* plain text, no elements: press does nothing (by design) */ }
        }
    }

    private fun stateDialog(row: MessageRow, primaryRes: Int, primary: () -> Unit) {
        val items = arrayOf(getString(primaryRes), getString(R.string.message_options))
        DsmsAlertBuilder(this)
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

    private fun openPartExternally(part: io.github.theonionsarewatching.nova.data.PartEntity) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(part.filePath)
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, part.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (part.isAudio()) {
                // the phone's stock/default audio player directly; chooser only as fallback
                val def = packageManager.resolveActivity(view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                if (def != null && def.packageName != "android" && !def.name.contains("Resolver")) {
                    view.setClassName(def.packageName, def.name)
                    startActivity(view)
                } else {
                    startActivity(Intent.createChooser(view, getString(R.string.player_open_with))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                return
            }
            startActivity(view)
        } catch (_: Exception) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_app_for_file,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Text of a message WITH attachments is "long" when it alone would
     *  fill most of the list (or 500+ characters); the picture's height
     *  does not count. */
    private fun hasLongText(row: MessageRow): Boolean {
        val body = row.msg.body.trim()
        if (body.isEmpty()) return false
        if (LongTextReader.isLong(body, 0, 0)) return true
        val listH = binding.msgList.height - binding.msgList.paddingTop - binding.msgList.paddingBottom
        val w = (binding.msgList.width * 0.8f).toInt()
        if (listH <= 0 || w <= 0) return false
        val paint = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, prefs.msgTextSp, resources.displayMetrics)
        }
        val h = android.text.StaticLayout.Builder.obtain(body, 0, body.length, paint, w).build().height
        return h > listH * 0.8f
    }

    /** Pick list for a message with several attachments, or long text plus
     *  attachments: compact rows, each with a sharp
     *  thumbnail decoded from the original file, and "Read text" first when
     *  the text is long. OK opens the row. */
    private fun chooseAttachment(row: MessageRow) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val thumbPx = dp(48)
        val showText = hasLongText(row)
        // entries: -1 = read text, otherwise the part index
        val entries = (if (showText) listOf(-1) else emptyList()) + row.parts.indices
        fun label(i: Int): String {
            if (i < 0) return getString(R.string.read_text)
            val part = row.parts[i]
            return when {
                part.isImage() -> getString(R.string.attach_kind_image, i + 1)
                part.isVideo() -> getString(R.string.attach_kind_video, i + 1)
                part.isAudio() -> part.fileName.ifBlank { getString(R.string.attach_kind_audio, i + 1) }
                part.isVCard() -> vcardTitle(part)
                else -> part.fileName.ifBlank { getString(R.string.attach_kind_file, i + 1) }
            }
        }
        val textColors = androidx.core.content.ContextCompat.getColorStateList(this, R.color.dialog_item_text)
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(p: Int): Any = entries[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: android.view.ViewGroup): View {
                val rowView = (convert as? android.widget.LinearLayout) ?: android.widget.LinearLayout(this@ThreadActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = thumbPx + dp(8)
                    setPaddingRelative(dp(12), dp(4), dp(12), dp(4))
                    val img = android.widget.ImageView(this@ThreadActivity).apply {
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) =
                                o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                        }
                    }
                    addView(img, android.widget.LinearLayout.LayoutParams(thumbPx, thumbPx))
                    addView(android.widget.TextView(this@ThreadActivity).apply {
                        textSize = 15f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        isDuplicateParentStateEnabled = true
                        textColors?.let { setTextColor(it) }
                        setPaddingRelative(dp(10), 0, 0, 0)
                    }, android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val img = rowView.getChildAt(0) as android.widget.ImageView
                val tv = rowView.getChildAt(1) as android.widget.TextView
                val e = entries[p]
                tv.text = label(e)
                img.dispose()
                val part = row.parts.getOrNull(e)
                if (part != null && (part.isImage() || part.isVideo())) {
                    img.background = null
                    img.imageTintList = null
                    img.setPadding(0, 0, 0, 0)
                    img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    // sharp: decoded from the original file at the thumbnail's
                    // exact pixel size, full colour
                    img.load(File(part.filePath)) {
                        size(thumbPx, thumbPx)
                        scale(coil.size.Scale.FILL)
                        precision(coil.size.Precision.EXACT)
                        allowRgb565(false)
                        if (part.isVideo()) videoFrameMillis(1000)
                    }
                } else {
                    img.setImageResource(when {
                        part == null -> R.drawable.ic_mail_line
                        part.isAudio() -> R.drawable.ic_headphones_small
                        part.isVCard() -> R.drawable.ic_person_small
                        else -> R.drawable.ic_paperclip_small
                    })
                    img.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    img.setPadding(dp(12), dp(12), dp(12), dp(12))
                    img.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    img.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ThemeUtils.accentColor(this@ThreadActivity))
                        cornerRadius = dp(8).toFloat()
                    }
                }
                return rowView
            }
        }
        DsmsAlertBuilder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.attachments_title)))
            .setAdapter(adapter) { _, which ->
                val e = entries[which]
                if (e < 0) {
                    LongTextReader.show(
                        this, row.senderName.ifBlank { convo?.displayTitle() ?: "" }, row.msg.body)
                } else openAttachment(row, e)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun vcardTitle(part: io.github.theonionsarewatching.nova.data.PartEntity): String =
        try {
            val name = File(part.filePath).readText().take(2000).lineSequence()
                .firstOrNull { it.startsWith("FN", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            if (name.isNullOrBlank()) getString(R.string.attach_contact)
            else getString(R.string.attach_contact_named, name)
        } catch (_: Exception) { getString(R.string.attach_contact) }

    private fun openAttachment(row: MessageRow, index: Int) {
        val part = row.parts.getOrNull(index) ?: return
        if (part.isImage() || part.isVideo()) {
            startActivity(Intent(this, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_CONVO_ID, convoId)
                putExtra(MediaViewerActivity.EXTRA_PART_ID, part.id)
            })
            return
        }
        if (part.isAudio()) {
            // in-app playback: deterministic on every phone, no chooser lottery
            io.github.theonionsarewatching.nova.ui.AudioPlayerDialog.show(
                this, part.filePath, part.fileName,
                onOpenExternal = { openPartExternally(part) },
                onSave = {
                    lifecycleScope.launch {
                        // D-SMS/Voice notes or D-SMS/Audio (see Saver)
                        val loc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.theonionsarewatching.nova.ui.Saver.save(
                            this@ThreadActivity, File(part.filePath),
                            part.fileName, part.mimeType
                        ) }
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                            this@ThreadActivity,
                            if (loc != null) getString(R.string.saved_to, loc) else getString(R.string.save_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            return
        }
        // vCards and other files: hand off to the system app for that type
        val mime = if (part.isVCard()) "text/x-vcard" else part.mimeType
        val openLabel = when {
            part.isVCard() -> getString(R.string.open_in_contacts)
            part.isAudio() -> getString(R.string.open_in_player)
            else -> getString(R.string.open)
        }
        DsmsAlertBuilder(this)
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
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_app_for_file,
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
            MsgStatus.FAILED, MsgStatus.RETRYING -> {
                items += getString(R.string.retry) to { lifecycleScope.launch { repo.retry(m.id) } }
                items += getString(R.string.retry_on_service) to {
                    io.github.theonionsarewatching.nova.util.AutoRetry.add(this, m.id)
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.retry_on_service_armed, Toast.LENGTH_SHORT).show()
                }
            }
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
        if (row.parts.size > 1) {
            items += getString(R.string.attachments_n, row.parts.size) to { chooseAttachment(row) }
        }
        if (row.parts.isNotEmpty()) {
            // already saves every part; the label just owns up to it now
            items += getString(
                if (row.parts.size > 1) R.string.save_all_attachments else R.string.save_attachment
            ) to {
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

        DsmsAlertBuilder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.message_options)))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun deleteMessage(m: MessageEntity) {
        val doDelete = { lifecycleScope.launch { repo.deleteMessage(m.id) }; Unit }
        if (m.locked) {
            DsmsAlertBuilder(this)
                .setMessage(R.string.delete_locked_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> doDelete() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            DsmsAlertBuilder(this)
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
        if (!m.isMine && m.telephonyId != null) {
            // sender-side timestamp, read on demand from the provider row.
            // SMS: date_sent (millis, 0 when the carrier didn't supply it).
            // MMS: the row's date IS the PDU Date header (sender-side,
            // seconds) and our m.date came from it, so there's nothing
            // extra to show — SMS only.
            if (!m.telephonyIsMms) {
                var sentAt = 0L
                try {
                    contentResolver.query(
                        android.provider.Telephony.Sms.CONTENT_URI,
                        arrayOf(android.provider.Telephony.Sms.DATE_SENT),
                        "_id = ?", arrayOf(m.telephonyId.toString()), null
                    )?.use { c -> if (c.moveToFirst()) sentAt = c.getLong(0) }
                } catch (_: Exception) {}
                if (sentAt > 0 && kotlin.math.abs(sentAt - m.date) > 2000) {
                    sb.append(getString(R.string.detail_sent_at)).append(": ")
                        .append(Formatters.full(sentAt)).append('\n')
                }
            }
        }
        if (m.isMine) {
            sb.append(getString(R.string.detail_status)).append(": ")
                .append(Sender.statusLabel(this, m.status)).append('\n')
            if (m.recipientStatuses.isNotBlank()) {
                // the field carries two formats: numeric per-recipient statuses
                // (SMS broadcast) and D/R letters (MMS reports).
                // Own-number entries are filtered: new sends no longer create
                // them, and old messages that carry one (user report: "the
                // sender's number shows in message details") stay clean.
                val own = repo.ownNumbersForDisplay()
                m.recipientStatuses.split(",").filter { it.contains("=") }
                    .filterNot { it.substringBeforeLast("=") in own }
                    .forEach { e ->
                    val addr = e.substringBeforeLast("=")
                    val vRaw = e.substringAfterLast("=")
                    val label = when (vRaw) {
                        "R" -> Sender.statusLabel(this, MsgStatus.READ_BY_RECIPIENT)
                        "D" -> Sender.statusLabel(this, MsgStatus.DELIVERED)
                        else -> Sender.statusLabel(this, vRaw.toIntOrNull() ?: 0)
                    }
                    sb.append("  ").append(addr).append(": ").append(label).append('\n')
                }
            }
        }
        if (m.locked) sb.append(getString(R.string.detail_locked)).append('\n')
        // simplified delivery report: the plain facts, distilled from the
        // stored trail — no more raw debug dump in the user-facing dialog
        if (m.isMine) {
            sb.append('\n').append(getString(R.string.detail_delivery_header)).append('\n')
            val trail = m.deliveryDebug
            // when did a "delivered" line last land? (its [MM-dd HH:mm:ss] stamp)
            val deliveredLine = trail.lines().lastOrNull {
                it.contains("delivered", ignoreCase = true) &&
                    !it.contains("not delivered", ignoreCase = true)
            }
            val deliveredStamp = deliveredLine
                ?.substringAfter('[', "")?.substringBefore(']', "")
                ?.takeIf { it.isNotBlank() }
            val rejection = trail.lines().lastOrNull { it.contains("carrier rejected", ignoreCase = true) }
                ?.substringAfter("carrier rejected:", "")?.trim()?.takeIf { it.isNotBlank() }
            val readLine = trail.lines().lastOrNull { it.contains("READ report") }
            val readStamp = readLine?.substringAfter('[', "")?.substringBefore(']', "")
                ?.takeIf { it.isNotBlank() }
            when {
                m.status == MsgStatus.READ_BY_RECIPIENT ->
                    sb.append(getString(R.string.delivery_read_at,
                        readStamp ?: getString(R.string.delivery_yes))).append('\n')
                m.status == MsgStatus.DELIVERED ->
                    sb.append(getString(R.string.delivery_delivered_at,
                        deliveredStamp ?: getString(R.string.delivery_yes))).append('\n')
                m.status == MsgStatus.FAILED && rejection != null ->
                    sb.append(getString(R.string.delivery_rejected, rejection)).append('\n')
                m.status == MsgStatus.FAILED ->
                    sb.append(getString(R.string.delivery_failed_plain)).append('\n')
                m.status == MsgStatus.SENT ->
                    sb.append(getString(R.string.delivery_sent_no_report)).append('\n')
                else ->
                    sb.append(Sender.statusLabel(this, m.status)).append('\n')
            }
        }
        DsmsAlertBuilder(this)
            .setTitle(R.string.details)
            .setMessage(sb.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun forward(row: MessageRow) {
        // full-screen chat list ("New number…" first),
        // Cancel on the LEFT soft key, Send on the RIGHT
        ChatTargetPicker.show(
            this, getString(R.string.forward_to),
            onNew = {
                startActivity(Intent(this@ThreadActivity, ComposeActivity::class.java)
                    .putExtra("prefill_body", row.msg.body))
            },
            // tick several chats, Send forwards to all of them at once
            onPickMany = { chats ->
                lifecycleScope.launch {
                    for (c in chats) doForward(row, c.id, toast = false)
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this@ThreadActivity,
                        resources.getQuantityString(R.plurals.forwarded_to_chats, chats.size, chats.size),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        ) { c -> lifecycleScope.launch { doForward(row, c.id) } }
    }

    private suspend fun doForward(row: MessageRow, destConvoId: Long, toast: Boolean = true) {
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
        if (toast) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.forwarded, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- chat background ----------------

    /** The chat background as one opaque color, for contrast decisions.
     *  Mirrors applyChatBackground; photo backgrounds report a mid grey. */
    /** The effective chat background value for THIS conversation, with the
     *  dark-theme rule applied in one place so the two consumers can't drift:
     *  dark = "same as light" uses the light choice (color or picture) —
     *  UNLESS the light choice is App default, in which case the DARK app
     *  default applies. Per-conversation values resolve before any of this. */
    private fun resolvedChatBg(): String {
        val own = prefs.chatBgOwn(convoId)?.takeIf { it.isNotBlank() }
        val globalLight = prefs.chatBg(-1L)
        var v = own ?: globalLight
        if (ThemeUtils.isNight(this)) {
            v = when (val d = prefs.darkChatBg) {
                // "same as light": every chat gets the DARK app default —
                // UNLESS this chat has its own custom light background (then
                // only THIS chat carries it), or the GENERAL light background
                // is custom (then it carries everywhere)
                "same" -> own ?: globalLight.takeIf { it.isNotBlank() } ?: ""
                "default" -> ""
                else -> d
            }
            if (v.isBlank() &&
                (prefs.messageStyle == "plain" || prefs.messageStyle == "accentbar")
            ) {
                // dark counterpart of the card-style default backdrop
                v = "#1B1B1E"
            }
            // full themes: their own page colour instead of the grey defaults
            if ((v == "#1B1B1E" || v.isBlank()) && ThemeUtils.palette(this) != null) v = ""
        } else if (v.isBlank() &&
            (prefs.messageStyle == "plain" || prefs.messageStyle == "accentbar")
        ) {
            // card styles read as white squares over a very light grey
            v = "#F2F2F3"
        }
        if (!ThemeUtils.isNight(this) && v == "#F2F2F3" && ThemeUtils.palette(this) != null) v = ""
        io.github.theonionsarewatching.nova.util.ThemeDebug
            .dump(this, "thread convo=$convoId", convoId, v)
        return v
    }

    private fun currentBackdropColor(): Int {
        val v = resolvedChatBg()
        val night = ThemeUtils.isNight(this)
        return when {
            v.isBlank() -> ThemeUtils.palette(this)?.page
                ?: if (night) 0xFF121212.toInt() else android.graphics.Color.WHITE
            v.startsWith("#") ->
                try { android.graphics.Color.parseColor(v) }
                catch (_: Exception) { if (night) 0xFF121212.toInt() else android.graphics.Color.WHITE }
            else -> 0xFF888888.toInt()
        }
    }

    private fun applyChatBackground() {
        val v = resolvedChatBg()
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

    override fun applyBackgroundForCurrent() { applyChatBackground() }

    override fun startPicturePickerForBackground(convoId: Long) {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(i, REQ_CHAT_BG)
        } catch (_: Exception) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_file_picker,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun savePart(part: io.github.theonionsarewatching.nova.data.PartEntity) {
        lifecycleScope.launch {
            val loc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Saver.save(this@ThreadActivity, File(part.filePath), part.fileName, part.mimeType) }
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                this@ThreadActivity,
                if (loc != null) getString(R.string.saved_to, loc) else getString(R.string.save_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================== thread options ==============================

    private fun threadOptions() {
        val c = convo ?: return
        lifecycleScope.launch { threadOptionsInner(c) }
    }

    private suspend fun threadOptionsInner(c: ConversationEntity) {
        val hasMedia = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { repo.db.messages().mediaCountForConvo(c.id) > 0 } catch (_: Exception) { true }
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        // drafts first (Back is backspace while typing, so it can't ask)
        if (hasUnsent()) {
            items += getString(R.string.draft_save_exit) to { saveDraftAndExit() }
            items += getString(R.string.draft_discard_item) to { discardDraft() }
        }
        // SAME item set and order as the list's long-press menu (user rule:
        // the two menus mirror each other) — except list-only actions like
        // "Select conversations", which have no meaning inside a thread.
        // the notes chat has no number: no call / contact / block /
        // schedule / mark unread
        val isNotes = io.github.theonionsarewatching.nova.data.NotesChat.isNotes(c)
        if (!c.isGroup && !isNotes) {
            val addr = c.addressList().firstOrNull().orEmpty()
            val lookupUri = io.github.theonionsarewatching.nova.util.ContactsHelper
                .lookupContactUri(this, addr)
            if (lookupUri != null) {
                items += getString(R.string.view_contact) to {
                    try { startActivity(Intent(Intent.ACTION_VIEW, lookupUri)) } catch (_: Exception) {}
                }
            } else if (addr.isNotBlank()) {
                items += getString(R.string.add_to_contacts) to { addToContactsDialog(addr) }
            }
        }
        if (c.isGroup) {
            items += getString(
                if (c.customName.isBlank()) R.string.name_group else R.string.rename_group
            ) to { nameGroupDialog(c) }
        }
        items += (if (c.pinned) getString(R.string.unpin) else getString(R.string.pin)) to {
            lifecycleScope.launch {
                repo.db.conversations().setPinned(c.id, !c.pinned); ChangeBus.ping()
                refreshConvoState()
            }
        }
        items += (if (c.archived) getString(R.string.unarchive) else getString(R.string.archive)) to {
            lifecycleScope.launch {
                repo.db.conversations().setArchived(c.id, !c.archived); ChangeBus.ping()
                refreshConvoState()
            }
        }
        if (!isNotes) items += getString(R.string.block_and_menu) to { blockAndMenu(c) }
        if (c.isGroup) {
            items += getString(R.string.participants_title, c.addressList().size) to {
                GroupParticipants.show(this, c)
            }
        }
        if (hasMedia) items += getString(R.string.view_media) to {
            startActivity(Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_CONVO_ID, convoId))
        }
        if (!isNotes) items += getString(R.string.schedule_send) to { scheduleSend() }
        if (!isNotes) items += getString(R.string.mark_unread) to {
            lifecycleScope.launch {
                repo.db.messages().newest(convoId)?.let { repo.db.messages().setRead(it.id, false) }
                repo.refreshConversation(convoId)
                ChangeBus.ping()
                finish()
            }
        }
        items += getString(R.string.delete_thread) to { deleteThreadFlow(c) }

        DsmsAlertBuilder(this)
            .setCustomTitle(Dialogs.title(this, c.displayTitle()))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Same dialog as the list menu: name or rename a group. */
    private fun nameGroupDialog(c: ConversationEntity) {
        val input = android.widget.EditText(this).apply {
            setText(c.customName)
            hint = getString(R.string.name_group_short_hint)
            setSingleLine(true)
        }
        val wrap = Dialogs.inputPanel(this, input, getString(R.string.name_group_hint))
        DsmsAlertBuilder(this)
            .setTitle(R.string.name_group)
            .setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    repo.db.conversations().setCustomName(c.id, name)
                    refreshConvoState()
                    ChangeBus.ping()
                }
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this,
                    if (name.isBlank()) R.string.group_name_removed else R.string.group_name_set,
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Same dialog as the list menu: create-new or add-to-existing; email
     *  addresses land in the contact's EMAIL field, not the number field. */
    private fun addToContactsDialog(address: String) {
        val labels = arrayOf(
            getString(R.string.contact_create_new),
            getString(R.string.contact_add_existing)
        )
        val field = if (address.contains("@"))
            android.provider.ContactsContract.Intents.Insert.EMAIL
        else android.provider.ContactsContract.Intents.Insert.PHONE
        DsmsAlertBuilder(this)
            .setTitle(address)
            .setItems(labels) { _, which ->
                try {
                    if (which == 0) {
                        startActivity(Intent(Intent.ACTION_INSERT).apply {
                            type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                            putExtra(field, address)
                        })
                    } else {
                        startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                            type = android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE
                            putExtra(field, address)
                        })
                    }
                } catch (_: Exception) {}
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Mute / block / archive / hide, grouped. */
    private fun blockAndMenu(c: ConversationEntity) {
        ConvoMenus.notifications(this, repo, c,
            onChanged = { refreshConvoState() },
            onHidden = { finish() })
    }

    /** Sound, background, style and colors for this look. */
    private fun customizeMenu() {
        val items = arrayListOf<Pair<String, () -> Unit>>(
            getString(R.string.chat_background) to {
                io.github.theonionsarewatching.nova.ui.ChatBackground
                    .show(this, prefs, convoId, this)
            },
            getString(R.string.pref_message_style) to { pickMessageStyle() },
            getString(R.string.pref_message_color) to { messageColorDialog() }
        )
        DsmsAlertBuilder(this)
            .setTitle(R.string.customize_menu)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun pickMessageStyle() {
        val values = resources.getStringArray(R.array.style_values)
        val labels = resources.getStringArray(R.array.style_entries)
        val current = values.indexOf(prefs.messageStyle).coerceAtLeast(0)
        DsmsAlertBuilder(this)
            .setTitle(R.string.pref_message_style)
            .setSingleChoiceItems(labels, current) { d, w ->
                d.dismiss()
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putString("message_style", values[w]).apply()
                applyChatBackground()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Sent / incoming coloring toggles; enabling one opens its color chooser. */
    private fun messageColorDialog() {
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        fun addSwitch(labelRes: Int, key: String, default: Boolean, chooser: () -> Unit) {
            val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = getString(labelRes)
                textSize = 15f
                isChecked = sp.getBoolean(key, default)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isFocusable = true
                setOnCheckedChangeListener { _, on ->
                    sp.edit().putBoolean(key, on).apply()
                    adapter.notifyDataSetChanged()
                    if (on) chooser()
                }
            }
            column.addView(sw)
        }
        addSwitch(R.string.pref_sent_colored, "sent_colored", true) {
            io.github.theonionsarewatching.nova.ui.ChatBackground.chooseColor(
                this, topOptionRes = R.string.sent_color_accent,
                onTop = {
                    prefs.sentColor = ""
                    adapter.notifyDataSetChanged()
                }
            ) { hex -> prefs.sentColor = hex; adapter.notifyDataSetChanged() }
        }
        addSwitch(R.string.pref_color_incoming, "color_incoming", false) {
            io.github.theonionsarewatching.nova.ui.ChatBackground.chooseColor(
                this, topOptionRes = R.string.incoming_color_accent,
                onTop = {
                    prefs.incomingColor = "accent"
                    adapter.notifyDataSetChanged()
                }
            ) { hex -> prefs.incomingColor = hex; adapter.notifyDataSetChanged() }
        }
        if (convo?.isGroup == true) {
            addSwitch(R.string.pref_group_colors, "group_member_colors", false) {}
        }
        DsmsAlertBuilder(this)
            .setTitle(R.string.pref_message_color)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Re-read the conversation and refresh the top-bar badges. */
    private fun refreshConvoState() {
        lifecycleScope.launch {
            repo.db.conversations().byId(convoId)?.let {
                convo = it
                updateNotifStatusIcon(it)
            }
        }
    }


    private fun scheduleSend() {
        val text = binding.composeInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.schedule_needs_text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        io.github.theonionsarewatching.nova.ui.ScheduleTimePicker.show(this) { at ->
            binding.composeInput.setText("")
            lifecycleScope.launch {
                repo.db.conversations().setDraft(convoId, "")
                repo.scheduleMessage(convoId, text, at)
            }
        }
    }

    private fun deleteThreadFlow(c: ConversationEntity) {
        lifecycleScope.launch {
            val locked = repo.db.messages().lockedCount(c.id)
            if (locked > 0) {
                DsmsAlertBuilder(this@ThreadActivity)
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
                DsmsAlertBuilder(this@ThreadActivity)
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
