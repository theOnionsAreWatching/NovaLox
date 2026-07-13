package io.github.theonionsarewatching.nova.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.GroupMode
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityComposeBinding
import io.github.theonionsarewatching.nova.databinding.ItemSuggestionBinding
import io.github.theonionsarewatching.nova.util.ContactsHelper
import io.github.theonionsarewatching.nova.util.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComposeActivity : BaseActivity() {

    private lateinit var binding: ActivityComposeBinding
    private lateinit var repo: Repo
    private var contacts: List<ContactsHelper.Contact> = emptyList()
    private val recipients = ArrayList<String>()
    private var groupMode = GroupMode.BROADCAST
    private val pendingAttachments = ArrayList<Triple<String, String, String>>()
    private lateinit var suggestionAdapter: SuggestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)
        groupMode = if (prefs.defaultGroupMode == "group_mms") GroupMode.GROUP_MMS else GroupMode.BROADCAST

        suggestionAdapter = SuggestionAdapter { pick(it) }
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGroupMode.setOnClickListener { pickGroupMode() }
        binding.btnStart.setOnClickListener { start() }
        binding.btnSchedule.setOnClickListener { startScheduled() }
        binding.btnComposeAttach.setOnClickListener { pickAttachment() }
        binding.attachChip.setOnClickListener { manageAttachments() }

        binding.recipientInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filterSuggestions(s?.toString().orEmpty()) }
        })
        binding.recipientInput.setOnEditorActionListener { _, _, _ -> addTypedRecipient(); true }

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.softkey_attach), null, getString(R.string.softkey_send),
                onLeft = { pickAttachment() },
                onCenter = null,
                onRight = { start() }
            )
        }
        if (softkeys?.shouldShow() == true) {
            // softkeys carry Attach + Send; free the vertical space on small screens
            binding.sendRow.visibility = View.GONE
        }

        ThemeUtils.applyFocusHighlightRound(binding.btnBack, binding.btnComposeAttach)
        binding.recipientChips.isFocusable = true
        binding.recipientChips.isFocusableInTouchMode = false
        ThemeUtils.applyButtonFocus(binding.btnStart)
        ThemeUtils.applyFocusHighlight(
            binding.recipientInput, binding.recipientChips,
            binding.btnGroupMode, binding.attachChip
        )
        // the compose body's focus ring lives on its WRAPPER, which sizes to the
        // text — so the ring grows line by line instead of staying a fixed
        // 2-line box that drifts away from what's typed. Focus lands on the
        // EditText; mirror its focus state onto the frame.
        binding.bodyInputFrame.foreground = ThemeUtils.focusForeground(this)
        binding.bodyInput.setOnFocusChangeListener { _, hasFocus ->
            binding.bodyInputFrame.isSelected = hasFocus
        }

        lifecycleScope.launch {
            contacts = withContext(Dispatchers.IO) { ContactsHelper.loadAll(this@ComposeActivity) }
        }

        handleIncomingIntent()
        updateRecipientLabel()
        binding.recipientInput.requestFocus()
    }

    private fun handleIncomingIntent() {
        try {
            val data: Uri? = intent.data
            if (data != null && (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW)) {
                // sms:+1234567 URIs are OPAQUE — getQueryParameter() throws on them
                // (this is what crashed the dialer's "send message" hand-off).
                // Parse the raw scheme-specific part by hand instead.
                val raw = data.schemeSpecificPart.orEmpty()
                val numbersPart = raw.substringBefore('?')
                numbersPart.split(";", ",").map {
                    try { java.net.URLDecoder.decode(it.trim(), "UTF-8") } catch (_: Exception) { it.trim() }
                }.filter { it.isNotBlank() }.forEach {
                    if (recipients.none { r -> PhoneUtils.normalize(r) == PhoneUtils.normalize(it) }) {
                        recipients.add(it)
                    }
                }
                val query = raw.substringAfter('?', "")
                query.split('&').firstOrNull { it.startsWith("body=") }?.let {
                    val body = try {
                        java.net.URLDecoder.decode(it.removePrefix("body="), "UTF-8")
                    } catch (_: Exception) { it.removePrefix("body=") }
                    binding.bodyInput.setText(body)
                }
            }
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.bodyInput.setText(it) }
            intent.getStringExtra("prefill_body")?.let { binding.bodyInput.setText(it) }

            // shared FROM other apps (gallery etc.): stage the media as attachments
            val streams = ArrayList<Uri>()
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { streams.add(it) }
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.let { streams.addAll(it) }
            }
            if (streams.isNotEmpty()) stageIncomingUris(streams)
        } catch (_: Exception) {
            // never crash on a malformed external intent — open blank instead
        }
        updateRecipientLabel()
    }

    private fun filterSuggestions(q: String) {
        if (q.isBlank()) {
            suggestionAdapter.submit(emptyList())
            binding.suggestionList.visibility = View.GONE
            return
        }
        val lower = q.lowercase()
        val qDigits = q.filter { it.isDigit() }
        val matches = contacts.filter { c ->
            c.name.lowercase().contains(lower) ||
                (qDigits.length >= 2 && c.number.filter { it.isDigit() }.contains(qDigits))
        }.take(4)
        suggestionAdapter.submit(matches)
        binding.suggestionList.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pick(c: ContactsHelper.Contact) {
        if (recipients.none { PhoneUtils.normalize(it) == PhoneUtils.normalize(c.number) }) {
            recipients.add(c.number)
        }
        binding.recipientInput.setText("")
        suggestionAdapter.submit(emptyList())
        binding.suggestionList.visibility = View.GONE
        updateRecipientLabel()
    }

    private fun addTypedRecipient() {
        val typed = binding.recipientInput.text?.toString()?.trim().orEmpty()
        if (typed.isBlank()) return
        if (recipients.none { PhoneUtils.normalize(it) == PhoneUtils.normalize(typed) }) {
            recipients.add(typed)
        }
        binding.recipientInput.setText("")
        binding.suggestionList.visibility = View.GONE
        updateRecipientLabel()
    }

    private fun removeRecipientDialog() {
        if (recipients.isEmpty()) return
        val labels = recipients.map { addr ->
            val name = contacts.firstOrNull {
                PhoneUtils.normalize(it.number) == PhoneUtils.normalize(addr)
            }?.name
            if (name != null) "$name  ($addr)" else addr
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_recipient)
            .setItems(labels.toTypedArray()) { _, which ->
                recipients.removeAt(which)
                updateRecipientLabel()
            }
            .show()
    }

    private fun updateRecipientLabel() {
        val names = recipients.map { addr ->
            contacts.firstOrNull { PhoneUtils.normalize(it.number) == PhoneUtils.normalize(addr) }?.name ?: addr
        }
        binding.recipientChips.text =
            if (names.isEmpty()) getString(R.string.no_recipients)
            else names.joinToString(", ")
        binding.recipientChips.setOnClickListener { removeRecipientDialog() }
        binding.btnGroupMode.visibility = if (recipients.size > 1) View.VISIBLE else View.GONE
        binding.btnGroupMode.text = getString(
            R.string.group_mode_line,
            getString(
                if (groupMode == GroupMode.GROUP_MMS) R.string.mode_group_mms_short
                else R.string.mode_broadcast_short
            )
        )
    }

    private fun pickAttachment() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "text/x-vcard"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, getString(R.string.softkey_attach)), 211)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val chipsVisible = binding.recipientChips.isShown && recipients.isNotEmpty()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.btnBack.hasFocus() && chipsVisible &&
                        binding.recipientChips.requestFocus()
                    ) return true
                    if (binding.recipientChips.hasFocus() &&
                        binding.recipientInput.requestFocus()
                    ) return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.recipientInput.hasFocus() && chipsVisible &&
                        binding.recipientChips.requestFocus()
                    ) return true
                    if (binding.recipientChips.hasFocus() &&
                        binding.btnBack.requestFocus()
                    ) return true
                    if (binding.btnGroupMode.hasFocus() &&
                        binding.recipientInput.requestFocus()
                    ) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 211 || resultCode != RESULT_OK) return
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        data?.data?.let { if (uris.isEmpty()) uris.add(it) }
        if (uris.isEmpty()) return
        stageIncomingUris(uris)
    }

    /** Copies content URIs into app storage and stages them as attachments.
     *  Used by the attach picker AND by media shared from other apps. */
    private fun stageIncomingUris(uris: List<Uri>) {
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
                    val dir = java.io.File(filesDir, "parts").apply { mkdirs() }
                    val out = java.io.File(dir, "new_${System.currentTimeMillis()}_${uris.indexOf(uri)}$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && out.length() > 0) {
                        pendingAttachments.add(Triple(out.absolutePath, mime, out.name))
                    }
                } catch (_: Exception) {}
            }
            updateAttachChip()
        }
    }

    private fun updateAttachChip() {
        if (pendingAttachments.isEmpty()) {
            binding.attachChip.visibility = View.GONE
            return
        }
        binding.attachChip.visibility = View.VISIBLE
        binding.attachChip.text =
            if (pendingAttachments.size == 1)
                getString(R.string.attached_label, pendingAttachments[0].third)
            else getString(R.string.attached_n, pendingAttachments.size)
    }

    private fun manageAttachments() {
        if (pendingAttachments.isEmpty()) return
        val labels = pendingAttachments.map { it.third } + getString(R.string.remove_all)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.attached_n, pendingAttachments.size))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < pendingAttachments.size) {
                    runCatching { java.io.File(pendingAttachments[which].first).delete() }
                    pendingAttachments.removeAt(which)
                } else {
                    pendingAttachments.forEach { runCatching { java.io.File(it.first).delete() } }
                    pendingAttachments.clear()
                }
                updateAttachChip()
            }
            .show()
    }

    private fun pickGroupMode() {
        val labels = arrayOf(getString(R.string.mode_broadcast), getString(R.string.mode_group_mms))
        val current = if (groupMode == GroupMode.GROUP_MMS) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.group_send_mode)
            .setSingleChoiceItems(labels, current) { d, which ->
                groupMode = if (which == 1) GroupMode.GROUP_MMS else GroupMode.BROADCAST
                updateRecipientLabel()
                d.dismiss()
            }
            .show()
    }

    private fun startScheduled() {
        addTypedRecipient()
        if (recipients.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_recipients, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val body = binding.bodyInput.text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.schedule_needs_text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, 30) }
        android.app.DatePickerDialog(this, { _, y, mo, d ->
            android.app.TimePickerDialog(this, { _, h, mi ->
                cal.set(y, mo, d, h, mi, 0)
                val at = cal.timeInMillis
                if (at <= System.currentTimeMillis()) {
                    android.widget.Toast.makeText(this, R.string.schedule_in_past, android.widget.Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                lifecycleScope.launch {
                    val convo = repo.getOrCreateConversation(recipients)
                    if (convo.isGroup && convo.groupMode != groupMode) {
                        repo.db.conversations().setGroupMode(convo.id, groupMode)
                    }
                    repo.scheduleMessage(convo.id, body, at)
                    startActivity(Intent(this@ComposeActivity, ThreadActivity::class.java)
                        .putExtra(ThreadActivity.EXTRA_CONVO_ID, convo.id))
                    finish()
                }
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun start() {
        addTypedRecipient()
        if (recipients.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_recipients, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val body = binding.bodyInput.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            val convo = repo.getOrCreateConversation(recipients)
            if (convo.isGroup && convo.groupMode != groupMode) {
                repo.db.conversations().setGroupMode(convo.id, groupMode)
            }
            if (pendingAttachments.isNotEmpty()) {
                repo.sendAttachment(convo.id, body, pendingAttachments.toList())
            } else if (body.isNotBlank()) {
                repo.sendText(convo.id, body)
            } else {
                repo.db.conversations().setDraft(convo.id, "")
            }
            startActivity(Intent(this@ComposeActivity, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_CONVO_ID, convo.id))
            finish()
        }
    }

    class SuggestionAdapter(
        private val onPick: (ContactsHelper.Contact) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

        private var items: List<ContactsHelper.Contact> = emptyList()

        fun submit(list: List<ContactsHelper.Contact>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemSuggestionBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            b.root.isFocusable = true
            b.root.background = ThemeUtils.focusFill(parent.context)
            b.root.foreground = ThemeUtils.focusStroke(parent.context)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            holder.b.suggestionName.text = c.name
            holder.b.suggestionNumber.text = c.number
            holder.itemView.setOnClickListener { onPick(c) }
        }
    }
}
