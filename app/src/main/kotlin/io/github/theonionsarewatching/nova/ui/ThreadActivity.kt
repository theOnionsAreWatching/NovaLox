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

    private val draftHandler = Handler(Looper.getMainLooper())
    private var draftRunnable: Runnable? = null
    private val changeListener: () -> Unit = { onDataChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)
        convoId = intent.getLongExtra(EXTRA_CONVO_ID, -1L)
        if (convoId <= 0) { finish(); return }

        binding.msgList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.msgList.isFocusable = true
        scroller = DpadScroller(
            binding.msgList,
            subScrollTallItems = true,
            lineStepPx = { (prefs.msgTextSp * resources.displayMetrics.scaledDensity * 3).toInt() },
            onEdge = { down ->
                if (down) { enterComposeMode(); true }
                else { loadOlder(); true }
            }
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOverflow.setOnClickListener { threadOptions() }
        binding.btnAttach.setOnClickListener { pickAttachment() }
        binding.btnSend.setOnClickListener { send() }

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

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.softkey_attach), getString(R.string.softkey_select), getString(R.string.softkey_send),
                onLeft = { pickAttachment() },
                onCenter = { binding.msgList.focusedChild?.performClick() },
                onRight = { send() },
                onMenu = { threadOptions() }
            )
        }
        if (softkeys?.shouldShow() == true) {
            binding.btnAttach.visibility = View.GONE
            binding.btnSend.visibility = View.GONE
        }

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
                onHold = { holdMessage(it) }
            )
            binding.msgList.adapter = adapter
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
        markRead()
    }

    override fun onPause() {
        super.onPause()
        visibleConvoId = -1L
        saveDraft()
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
                val newRows = buildRows(older)
                rows.addAll(0, newRows)
                adapter.rows = rows
                adapter.notifyItemRangeInserted(0, newRows.size)
                scroller?.focusPosition(newRows.size) // keep focus on the same message
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

    private fun enterComposeMode() {
        composeMode = true
        binding.composeInput.maxLines = prefs.composeMaxLines
        binding.moreIndicator.visibility = View.GONE
        binding.composeInput.requestFocus()
        binding.composeInput.setSelection(binding.composeInput.text?.length ?: 0)
    }

    private fun enterScrollMode(focusPos: Int = rows.size - 1) {
        if (rows.isEmpty()) return
        composeMode = false
        binding.composeInput.maxLines = 1
        val multiline = (binding.composeInput.layout?.lineCount ?: 1) > 1 ||
            binding.composeInput.text?.contains('\n') == true
        binding.moreIndicator.visibility = if (multiline) View.VISIBLE else View.GONE
        binding.msgList.requestFocus()
        scroller?.focusPosition(focusPos.coerceIn(0, rows.size - 1))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (softkeys?.handleKey(event) == true) return true

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            binding.composeInput.hasFocus()
        ) {
            val layout = binding.composeInput.layout
            val sel = binding.composeInput.selectionStart.coerceAtLeast(0)
            val line = if (layout != null) layout.getLineForOffset(sel) else 0
            if (line == 0) {
                enterScrollMode()
                return true
            }
        }

        if (binding.msgList.hasFocus()) {
            if (scroller?.onKey(event) == true) {
                // prefetch older pages as the focus climbs
                val v = binding.msgList.focusedChild
                if (v != null && binding.msgList.getChildAdapterPosition(v) < 4) loadOlder()
                if (v != null && hasMoreNewer &&
                    binding.msgList.getChildAdapterPosition(v) > rows.size - 5
                ) loadNewer()
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
        if (text.isEmpty()) return
        binding.composeInput.setText("")
        lifecycleScope.launch {
            repo.db.conversations().setDraft(convoId, "")
            repo.sendText(convoId, text)
        }
        enterComposeMode()
    }

    private fun pickAttachment() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "text/x-vcard"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, getString(R.string.softkey_attach)), 201)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val text = binding.composeInput.text?.toString()?.trim().orEmpty()
            binding.composeInput.setText("")
            lifecycleScope.launch {
                try {
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val ext = when {
                        mime.contains("jpeg") -> ".jpg"; mime.contains("png") -> ".png"
                        mime.contains("gif") -> ".gif"; mime.contains("mp4") -> ".mp4"
                        mime.contains("3gpp") -> ".3gp"; mime.contains("amr") -> ".amr"
                        mime.contains("vcard") -> ".vcf"; else -> ".bin"
                    }
                    val dir = File(filesDir, "parts").apply { mkdirs() }
                    val out = File(dir, "out_${System.currentTimeMillis()}$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && out.length() > 0) {
                        repo.db.conversations().setDraft(convoId, "")
                        repo.sendAttachment(convoId, text, out.absolutePath, mime, out.name)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ============================== message press / hold ==============================

    private fun pressMessage(row: MessageRow) {
        if (row.isSystemLine) return
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
        if (part.isVCard()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.attach_vcard)
                .setItems(arrayOf(getString(R.string.open_in_contacts), getString(R.string.save))) { _, which ->
                    when (which) {
                        0 -> try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this, "$packageName.fileprovider", File(part.filePath)
                            )
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "text/x-vcard")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) {}
                        1 -> savePart(part)
                    }
                }.show()
            return
        }
        startActivity(Intent(this, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_CONVO_ID, convoId)
            putExtra(MediaViewerActivity.EXTRA_PART_ID, part.id)
        })
    }

    private fun holdMessage(row: MessageRow) {
        if (row.isSystemLine) return
        val m = row.msg
        val items = ArrayList<Pair<String, () -> Unit>>()

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
            .setTitle(R.string.message_options)
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
                repo.sendAttachment(destConvoId, row.msg.body, copy.absolutePath, part.mimeType, part.fileName)
            } catch (_: Exception) {}
        } else if (row.msg.body.isNotBlank()) {
            repo.sendText(destConvoId, row.msg.body)
        }
        android.widget.Toast.makeText(this, R.string.forwarded, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun savePart(part: io.github.theonionsarewatching.nova.data.PartEntity) {
        lifecycleScope.launch {
            val ok = Saver.saveToDownloads(this@ThreadActivity, File(part.filePath), part.fileName, part.mimeType)
            android.widget.Toast.makeText(
                this@ThreadActivity,
                if (ok) R.string.saved_to_downloads else R.string.save_failed,
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
        items += getString(R.string.delete_thread) to { deleteThreadFlow(c) }

        AlertDialog.Builder(this)
            .setTitle(c.displayTitle())
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
