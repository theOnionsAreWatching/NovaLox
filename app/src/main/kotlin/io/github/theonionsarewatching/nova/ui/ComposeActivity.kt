package io.github.theonionsarewatching.nova.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
        binding.btnAddRecipient.setOnClickListener { addTypedRecipient() }
        binding.btnGroupMode.setOnClickListener { pickGroupMode() }
        binding.btnStart.setOnClickListener { start() }

        binding.recipientInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filterSuggestions(s?.toString().orEmpty()) }
        })
        binding.recipientInput.setOnEditorActionListener { _, _, _ -> addTypedRecipient(); true }

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.softkey_add), null, getString(R.string.softkey_start),
                onLeft = { addTypedRecipient() },
                onRight = { start() }
            )
        }

        ThemeUtils.applyFocusHighlightRound(binding.btnBack)
        ThemeUtils.applyFocusHighlightPill(binding.btnAddRecipient, binding.btnGroupMode, binding.btnStart)
        ThemeUtils.applyFocusHighlight(binding.recipientInput, binding.bodyInput, binding.recipientChips)

        lifecycleScope.launch {
            contacts = withContext(Dispatchers.IO) { ContactsHelper.loadAll(this@ComposeActivity) }
        }

        handleIncomingIntent()
        updateRecipientLabel()
        binding.recipientInput.requestFocus()
    }

    private fun handleIncomingIntent() {
        val data: Uri? = intent.data
        if (data != null && (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW)) {
            val ssp = data.schemeSpecificPart?.substringBefore('?')
            ssp?.split(";", ",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach {
                if (recipients.none { r -> PhoneUtils.normalize(r) == PhoneUtils.normalize(it) }) recipients.add(it)
            }
            data.getQueryParameter("body")?.let { binding.bodyInput.setText(it) }
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.bodyInput.setText(it) }
        intent.getStringExtra("prefill_body")?.let { binding.bodyInput.setText(it) }
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
        }.take(6)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_recipient)
            .setItems(recipients.toTypedArray()) { _, which ->
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
        binding.groupRow.visibility = if (recipients.size > 1) View.VISIBLE else View.GONE
        binding.btnGroupMode.text = getString(
            if (groupMode == GroupMode.GROUP_MMS) R.string.mode_group_mms else R.string.mode_broadcast
        )
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
            if (body.isNotBlank()) {
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
            b.root.foreground = ThemeUtils.focusForeground(parent.context)
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
