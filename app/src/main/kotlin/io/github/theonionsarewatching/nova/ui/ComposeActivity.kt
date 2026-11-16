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
import io.github.theonionsarewatching.nova.util.Prefs
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
        repo = Repo.get(this)
        // Dialer "send message" hand-off: a single-recipient sms:/smsto: with no
        // body should go straight to that conversation. Decide this BEFORE
        // inflating the compose layout — inflating first showed the new-message
        // screen for a split second before the thread opened.
        if (routeDialerHandoffEarly()) return
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        groupMode = if (prefs.defaultGroupMode == "group_mms") GroupMode.GROUP_MMS else GroupMode.BROADCAST

        suggestionAdapter = SuggestionAdapter { pick(it) }
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnGroupMode.setOnClickListener { pickGroupMode() }
        binding.btnStart.setOnClickListener { start() }
        binding.btnSchedule.setOnClickListener { startScheduled() }
        binding.btnComposeAttach.setOnClickListener {
            AttachOrPaste.open(this, binding.bodyInput,
                onAttach = { pickAttachment() },
                onRecord = { recordAudioAttachment() })
        }
        binding.attachChip.setOnClickListener { manageAttachments() }

        // notice the system's own paste into both boxes
        io.github.theonionsarewatching.nova.util.ClipboardGuard.watch(binding.recipientInput)
        io.github.theonionsarewatching.nova.util.ClipboardGuard.watch(binding.bodyInput)
        binding.recipientInput.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filterSuggestions(s?.toString().orEmpty()) }
        })
        binding.recipientInput.setOnEditorActionListener { _, _, _ -> addTypedRecipient(); true }

        softkeys = Softkeys(this, binding.softkeyBar).also { updateComposeSoftkeys(it) }
        if (softkeys?.shouldShow() == true) {
            // softkeys carry Attach + Send — hide only those; the schedule
            // button has no softkey slot, so it stays visible for everyone
            binding.btnComposeAttach.visibility = View.GONE
            binding.btnStart.visibility = View.GONE
        }

        ThemeUtils.applyFocusHighlightRound(
            binding.btnBack, binding.btnComposeAttach, binding.btnSchedule
        )
        binding.recipientChips.isFocusable = true
        binding.recipientChips.isFocusableInTouchMode = false
        ThemeUtils.applyButtonFocus(binding.btnStart)
        ThemeUtils.applyFocusHighlight(
            binding.recipientInput, binding.recipientChips,
            binding.btnGroupMode, binding.attachChip
        )
        // compose body: NO focus ring at all (it never tracked the text
        // reliably). The shaded box background is the field's own visual and
        // grows with the text; the blinking cursor marks focus.

        lifecycleScope.launch {
            contacts = withContext(Dispatchers.IO) { ContactsHelper.loadAll(this@ComposeActivity) }
            if (pickMode) filterSuggestions(binding.recipientInput.text?.toString().orEmpty())
        }

        handleIncomingIntent()
        // a plain New message brings back the last unsent one (no recipient)
        if (savedInstanceState == null && intent.action == null && recipients.isEmpty() &&
            binding.bodyInput.text.isNullOrBlank() && pendingAttachments.isEmpty()) {
            val p = Prefs.get(this)
            val d = p.draft(NEW_DRAFT_ID)
            val a = DraftPrompt.decode(p.draftAttachments(NEW_DRAFT_ID))
            if (d.isNotBlank()) binding.bodyInput.setText(d)
            if (a.isNotEmpty()) { pendingAttachments.addAll(a); updateAttachChip() }
        }
        // a plain "New message" opens as ONE pick screen: the
        // name/number box on top, every contact below, filtered as he types.
        // Picking a contact (or confirming a typed number) opens the chat.
        pickMode = recipients.isEmpty() && !isShareIntent() &&
            pendingAttachments.isEmpty() &&
            binding.bodyInput.text.isNullOrBlank()
        updateComposeSoftkeys()
        // the pick screen's final layout (list area + its
        // soft keys) from the very first frame; the contacts fill in when
        // loaded. Before, the first frames had the message-box layout and
        // the soft-key bar appeared only when the list came up.
        if (pickMode) setSuggestionsShown(true)
        softkeys?.refreshVisibility()
        updateRecipientLabel()
        binding.recipientInput.requestFocus()
        // shared from another app (gallery etc.) with no number given: let him
        // pick an existing chat first, or "New number…" to stay on this screen
        if (savedInstanceState == null && isShareIntent() && recipients.isEmpty()) showShareTarget()
    }

    private fun isShareIntent() =
        intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

    /** Copy job for shared media; a chosen chat waits for it to finish. */
    private var stagingJob: kotlinx.coroutines.Job? = null

    /** Share target: full-screen chat list, "New number…"
     *  first, Cancel on the RIGHT soft key. */
    private fun showShareTarget() {
        ChatTargetPicker.show(
            this, getString(R.string.share_to),
            // stay here: type a number / pick a contact as before
            onNew = { binding.recipientInput.requestFocus() },
            onCancel = { cancelShare() },
        ) { c -> shareInto(c.id) }
    }

    private fun cancelShare() {
        draftDone = true
        stagingJob?.cancel()
        pendingAttachments.forEach { runCatching { java.io.File(it.first).delete() } }
        pendingAttachments.clear()
        finish()
    }

    /** Open the chosen chat with the shared text + (already copied and
     *  limit-checked) attachments waiting in its compose box. */
    private fun shareInto(convoId: Long) {
        draftDone = true
        lifecycleScope.launch {
            try { stagingJob?.join() } catch (_: Exception) {}
            val att = ArrayList(pendingAttachments)
            pendingAttachments.clear()        // the thread owns the files now
            val text = binding.bodyInput.text?.toString().orEmpty()
            startActivity(Intent(this@ComposeActivity, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_CONVO_ID, convoId)
                .putExtra(ThreadActivity.EXTRA_SHARED_TEXT, text)
                .putStringArrayListExtra(ThreadActivity.EXTRA_SHARED_PATHS, ArrayList(att.map { it.first }))
                .putStringArrayListExtra(ThreadActivity.EXTRA_SHARED_MIMES, ArrayList(att.map { it.second }))
                .putStringArrayListExtra(ThreadActivity.EXTRA_SHARED_NAMES, ArrayList(att.map { it.third })))
            finish()
        }
    }

    /** Returns true (and finishes) if we handed off to a thread. */
    /** Runs before setContentView. Parses the sms:/smsto: intent directly (no
     *  UI state yet) and, if it's a single-recipient dialer send with no body
     *  or attachment, launches that thread and finishes without ever showing
     *  the compose screen. Returns true if it handed off. */
    private fun routeDialerHandoffEarly(): Boolean {
        val fromDialer = intent.action == Intent.ACTION_SENDTO ||
            intent.action == Intent.ACTION_VIEW
        if (!fromDialer) return false
        val data = intent.data ?: return false
        val scheme = data.scheme?.lowercase()
        if (scheme != "sms" && scheme != "smsto" && scheme != "mms" && scheme != "mmsto") return false
        val raw = data.schemeSpecificPart.orEmpty()
        val numbersPart = raw.substringBefore('?')
        val nums = numbersPart.split(";", ",").map {
            try { java.net.URLDecoder.decode(it.trim(), "UTF-8") } catch (_: Exception) { it.trim() }
        }.filter { it.isNotBlank() }
        if (nums.size != 1) return false
        // a body= in the URI or a shared EXTRA_TEXT means the user has content
        // to review — let the full compose screen handle those
        val query = raw.substringAfter('?', "")
        val hasBody = query.split('&').any { it.startsWith("body=") && it.length > 5 }
        if (hasBody) return false
        if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) return false
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) return false
        val number = nums.first()
        lifecycleScope.launch {
            val convo = repo.getOrCreateConversation(listOf(number))
            startActivity(Intent(this@ComposeActivity, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_CONVO_ID, convo.id))
            finish()
            overridePendingTransition(0, 0)
        }
        return true
    }

    /** A free-typed recipient must look like a phone number (3+ digits,
     *  optional +()-. separators) or an email — random letters are refused. */
    private fun isValidRecipient(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains("@")) {
            return Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(t)
        }
        if (!Regex("^[+]?[0-9()\\-\\s.]+$").matches(t)) return false
        return t.count { it.isDigit() } >= 3
    }

    private fun isChildOf(v: android.view.View, parent: android.view.View): Boolean {
        var cur: android.view.ViewParent? = v.parent
        while (cur != null) {
            if (cur === parent) return true
            cur = (cur as? android.view.View)?.parent ?: return false
        }
        return false
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
            (intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())?.let { binding.bodyInput.setText(it) }
            intent.getStringExtra("prefill_body")?.let { binding.bodyInput.setText(it) }
            // forwarded from a thread (several messages): files already copied
            intent.getStringArrayListExtra(ThreadActivity.EXTRA_SHARED_PATHS)?.let { paths ->
                val mimes = intent.getStringArrayListExtra(ThreadActivity.EXTRA_SHARED_MIMES)
                val names = intent.getStringArrayListExtra(ThreadActivity.EXTRA_SHARED_NAMES)
                var skipped = 0
                paths.forEachIndexed { i, path ->
                    val f = java.io.File(path)
                    val mime = mimes?.getOrNull(i) ?: "application/octet-stream"
                    if (f.exists() && io.github.theonionsarewatching.nova.util.AttachLimits
                            .fits(this, pendingAttachments, mime, f.length())) {
                        pendingAttachments.add(Triple(path, mime, names?.getOrNull(i) ?: f.name))
                    } else { runCatching { f.delete() }; skipped++ }
                }
                // consume once, so a recreate does not add them twice
                intent.removeExtra(ThreadActivity.EXTRA_SHARED_PATHS)
                intent.removeExtra(ThreadActivity.EXTRA_SHARED_MIMES)
                intent.removeExtra(ThreadActivity.EXTRA_SHARED_NAMES)
                intent.removeExtra("prefill_body")
                updateAttachChip()
                if (skipped > 0) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this,
                    getString(R.string.attach_skipped, skipped), android.widget.Toast.LENGTH_LONG).show()
            }

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
            // some galleries put the shared items only in ClipData
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { if (it !in streams) streams.add(it) }
            }
            if (streams.isNotEmpty()) stageIncomingUris(streams)
        } catch (_: Exception) {
            // never crash on a malformed external intent — open blank instead
        }
        updateRecipientLabel()
    }

    /** true while the screen is the one-step contact picker
     *  (no recipient chosen yet). "Add more" leaves it for a group. */
    private var pickMode = false

    private fun focusedSuggestion(): ContactsHelper.Contact? {
        val f = currentFocus ?: return null
        if (!isChildOf(f, binding.suggestionList)) return null
        val pos = binding.suggestionList.findContainingViewHolder(f)?.bindingAdapterPosition ?: -1
        return suggestionAdapter.itemAt(pos)
    }

    /** OK / Start in the box: a typed number (or e-mail) starts that chat;
     *  a typed name starts the chat with the best-matching contact. */
    private fun confirmTypedInPick() {
        val typed = binding.recipientInput.text?.toString()?.trim().orEmpty()
        if (typed.isBlank()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.pick_type_first,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (isValidRecipient(typed)) {
            recipients.add(typed)
            binding.recipientInput.setText("")
            start()
            return
        }
        val first = suggestionAdapter.itemAt(0)
        if (first != null) pick(first) else {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                this, getString(R.string.invalid_recipient, typed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startFromPick() {
        val c = focusedSuggestion()
        if (c != null) pick(c) else confirmTypedInPick()
    }

    /** Left soft key "Add more": keep this person and add others (group). */
    private fun addMoreFromPick() {
        val c = focusedSuggestion()
        val typed = binding.recipientInput.text?.toString()?.trim().orEmpty()
        pickMode = false
        when {
            c != null -> pick(c)
            typed.isNotBlank() -> addTypedRecipient()
        }
        updateComposeSoftkeys()
        filterSuggestions(binding.recipientInput.text?.toString().orEmpty())
        binding.recipientInput.requestFocus()
    }

    private fun filterSuggestions(q: String) {
        if (q.isBlank() && pickMode && contacts.isNotEmpty()) {
            suggestionAdapter.hlQuery = ""
            suggestionAdapter.hlDigits = ""
            suggestionAdapter.submit(contacts.sortedBy { it.name.lowercase() })
            setSuggestionsShown(true)
            return
        }
        if (q.isBlank()) {
            suggestionAdapter.submit(emptyList())
            setSuggestionsShown(false)
            return
        }
        val lower = q.lowercase()
        val qDigits = q.filter { it.isDigit() }
        // T9: digits match the letters on the keypad keys, so "323" finds
        // "Dad" — alongside plain name and number-substring matching. ALL
        // matches show; the list scrolls. Ordering: whole-name prefix first,
        // then any WORD prefix ("ma" ranks "Sir Manfield Barker" up), then
        // plain contains.
        val qd = qDigits.length >= 2
        val t9on = prefs.t9ContactMatch
        fun rank(c: ContactsHelper.Contact): Int {
            val n = c.name.lowercase()
            val t = t9Of(c.name)
            val num = c.number.filter { it.isDigit() }
            val words = n.split(wordSplit).filter { it.isNotBlank() }
            val tWords = t.split(wordSplit).filter { it.isNotBlank() }
            return when {
                n.startsWith(lower) ||
                    (qd && ((t9on && t.startsWith(qDigits)) ||
                        num.startsWith(qDigits))) -> 0
                words.any { it.startsWith(lower) } ||
                    (qd && t9on && tWords.any { it.startsWith(qDigits) }) -> 1
                else -> 2
            }
        }
        val base = contacts.filter { c ->
            c.name.lowercase().contains(lower) ||
                (qd && (
                    c.number.filter { it.isDigit() }.contains(qDigits) ||
                        (t9on && t9Of(c.name).contains(qDigits))))
        }.sortedBy { rank(it) }
        suggestionAdapter.hlQuery = lower
        suggestionAdapter.hlDigits = qDigits
        suggestionAdapter.hlT9 = t9on
        // the typed value itself is offered as a row even when it matches no
        // contact — so raw numbers can be added and the field freed for the next
        val typed = q.trim()
        val typedOk = typed.contains("@") || typed.count { it.isDigit() } >= 3
        val matches = if (typedOk && base.none {
                PhoneUtils.normalize(it.number) == PhoneUtils.normalize(typed)
            }) {
            listOf(ContactsHelper.Contact(getString(R.string.add_number_row), typed)) + base
        } else base
        suggestionAdapter.submit(matches)
        // BACK-minimized stays minimized only while the typed query is
        // unchanged; editing it brings the list back
        if (suggestionsSuppressed &&
            binding.recipientInput.text?.toString().orEmpty() != suppressedQuery
        ) suggestionsSuppressed = false
        setSuggestionsShown(!(matches.isEmpty() || suggestionsSuppressed))
    }

    private fun pick(c: ContactsHelper.Contact) {
        if (recipients.none { PhoneUtils.normalize(it) == PhoneUtils.normalize(c.number) }) {
            recipients.add(c.number)
        }
        if (pickMode) {
            // one step: the chosen person's chat opens right away
            binding.recipientInput.setText("")
            start()
            return
        }
        binding.recipientInput.setText("")
        suggestionAdapter.submit(emptyList())
        binding.suggestionList.visibility = View.GONE
        updateRecipientLabel()
        // keep typing where the user was — not on the back arrow
        binding.recipientInput.requestFocus()
    }

    private fun addTypedRecipient() {
        val typed = binding.recipientInput.text?.toString()?.trim().orEmpty()
        if (typed.isBlank()) return
        if (recipients.none { PhoneUtils.normalize(it) == PhoneUtils.normalize(typed) }) {
            if (!isValidRecipient(typed)) {
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                    this, getString(R.string.invalid_recipient, typed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
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
        DsmsAlertBuilder(this)
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
                    5 -> EmojiPicker.show(this, binding.bodyInput)
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
     *  Results come back through the same 211 handler as pickMedia.
     *  (Copied from ThreadActivity for the one "Photo/Video" item.) */
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
            startActivityForResult(i, 211)
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
        try {
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mime
                InAppPickers.askMultiple(this)
            }
            val chooser = Intent.createChooser(pick, getString(R.string.softkey_attach))
            if (capture != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(chooser, 211)
        } catch (_: Exception) {}
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
            pendingAttachments.add(Triple(path, mime, name)); updateAttachChip()
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
                startActivityForResult(direct, 211)
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
        onActivityResult(211, RESULT_OK, data)
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
     *  normal attach handler, 211). Fallback: our own list. */
    private fun pickAudioFromMusicApp() {
        val music = InAppPickers.musicIntent(this)
        if (music != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(music, 211)
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
                    .buildFromContactUri(this@ComposeActivity, contactUri)
            } ?: return@launch
            try {
                val dir = java.io.File(filesDir, "parts").apply { mkdirs() }
                val safe = card.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
                    .ifBlank { "contact" }.replace(' ', '_')
                val out = java.io.File(dir, "${safe}_out_${System.currentTimeMillis()}.vcf")
                out.writeText(card.vcf)
                pendingAttachments.add(Triple(out.absolutePath, "text/x-vcard", out.name))
                updateAttachChip()
            } catch (_: Exception) {}
        }
    }

    private var suggestionsSuppressed = false
    private var suppressedQuery = ""

    private var savedGroupModeVis = View.VISIBLE
    private var savedAttachChipVis = View.GONE

    /** The suggestion list takes the screen down to the BOTTOM edge: message
     *  box, action row, group-mode button, attachment chip, and softkey bar
     *  all step aside while it's up, and return to their prior state after. */
    private fun setSuggestionsShown(shown: Boolean) {
        val wasShown = binding.suggestionList.visibility == View.VISIBLE
        binding.suggestionList.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown && !wasShown) {
            savedGroupModeVis = binding.btnGroupMode.visibility
            savedAttachChipVis = binding.attachChip.visibility
            // a fresh entry starts from the TOP of the results
            binding.suggestionList.post { binding.suggestionList.scrollToPosition(0) }
        }
        if (shown) {
            // the ScrollView carries layout_weight=1 — IT is what held half
            // the screen while its content hid; the whole thing steps aside
            binding.bodyScroll.visibility = View.GONE
            binding.sendRow.visibility = View.GONE
            binding.btnGroupMode.visibility = View.GONE
            binding.attachChip.visibility = View.GONE
            // the pick screen keeps its labelled soft keys (Add more / Start)
            binding.softkeyBar.root.visibility =
                if (pickMode && softkeys?.shouldShow() == true) View.VISIBLE else View.GONE
        } else {
            binding.bodyScroll.visibility = View.VISIBLE
            binding.sendRow.visibility = View.VISIBLE
            binding.btnGroupMode.visibility = savedGroupModeVis
            binding.attachChip.visibility = savedAttachChipVis
            binding.softkeyBar.root.visibility =
                if (softkeys?.shouldShow() == true) View.VISIBLE else View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pickMode) {
            // BACK leaves the pick screen (there is no list to minimize here)
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goBack()
                return true
            }
            // OK in the box confirms what was typed (acts on key-up)
            if (binding.recipientInput.hasFocus() &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                if (event.action == KeyEvent.ACTION_UP) confirmTypedInPick()
                return true
            }
            // DOWN from the box goes into the list, first row
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                binding.recipientInput.hasFocus() &&
                binding.suggestionList.visibility == View.VISIBLE &&
                suggestionAdapter.itemCount > 0
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    binding.suggestionList.scrollToPosition(0)
                    binding.suggestionList.post {
                        binding.suggestionList.findViewHolderForAdapterPosition(0)
                            ?.itemView?.requestFocus()
                    }
                }
                return true
            }
        }
        // BACK while suggestions are up: minimize the list, stay on the screen
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            binding.suggestionList.visibility == View.VISIBLE
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                suggestionsSuppressed = true
                suppressedQuery = binding.recipientInput.text?.toString().orEmpty()
                setSuggestionsShown(false)
                // focus returns to the typing box, not the back arrow
                binding.recipientInput.requestFocus()
            }
            return true
        }
        // D-pad-up from the button below the list re-enters it at the LAST row
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            binding.suggestionList.visibility == View.VISIBLE &&
            currentFocus === binding.btnGroupMode
        ) {
            val last = (binding.suggestionList.adapter?.itemCount ?: 0) - 1
            if (last >= 0) {
                binding.suggestionList.scrollToPosition(last)
                fun focusLast(attempt: Int) {
                    val v = binding.suggestionList
                        .findViewHolderForAdapterPosition(last)?.itemView
                    if (v != null) {
                        v.requestFocus()
                        v.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, v.width, v.height), false
                        )
                    } else if (attempt < 3) {
                        binding.suggestionList.post { focusLast(attempt + 1) }
                    }
                }
                binding.suggestionList.post { focusLast(0) }
                return true
            }
        }
        // D-pad through the suggestion list: default focus search only reaches
        // laid-out children, so focus escaped to the group-mode button after
        // the last VISIBLE row and the rest of the list was unreachable. Move
        // selection manually and scroll as needed.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
            binding.suggestionList.visibility == View.VISIBLE
        ) {
            val focused = currentFocus
            if (focused != null && isChildOf(focused, binding.suggestionList)) {
                val holder = binding.suggestionList.findContainingViewHolder(focused)
                val pos = holder?.bindingAdapterPosition ?: -1
                val last = (binding.suggestionList.adapter?.itemCount ?: 0) - 1
                val next = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) pos + 1 else pos - 1
                if (pos == 0 && next < 0) {
                    binding.recipientInput.requestFocus()
                    return true
                }
                if (pos >= 0 && next in 0..last) {
                    binding.suggestionList.scrollToPosition(next)
                    // requestFocus races the layout pass after the scroll: if
                    // the row isn't laid out yet the request silently no-ops
                    // and the viewport never follows — retry a frame later,
                    // and explicitly pull the row's rectangle on screen so any
                    // ancestor scroll container moves too
                    fun focusRow(attempt: Int) {
                        val v = binding.suggestionList
                            .findViewHolderForAdapterPosition(next)?.itemView
                        if (v != null) {
                            v.requestFocus()
                            v.requestRectangleOnScreen(
                                android.graphics.Rect(0, 0, v.width, v.height), false
                            )
                        } else if (attempt < 3) {
                            binding.suggestionList.post { focusRow(attempt + 1) }
                        }
                    }
                    binding.suggestionList.post { focusRow(0) }
                    return true
                }
            }
        }
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
        if (requestCode == 231 && resultCode == RESULT_OK) {
            data?.data?.let { stageContactVcf(it) }
            return
        }
        if (requestCode == CameraCapture.REQ) {
            CameraCapture.onResult(this, resultCode, data, pendingAttachments,
                add = { p, m, n -> pendingAttachments.add(Triple(p, m, n)); updateAttachChip() },
                fromUri = { stageIncomingUris(listOf(it)) })
            return
        }
        if (requestCode != 211 || resultCode != RESULT_OK) return
        val uris = ArrayList<Uri>()
        uris.addAll(InAppPickers.resultUris(this, data))
        if (uris.isEmpty()) {
            val cam = cameraOutPath?.let { java.io.File(it) }
            cameraOutPath = null
            if (cam != null && cam.exists() && cam.length() > 0) {
                pendingAttachments.add(Triple(cam.absolutePath, "image/jpeg", cam.name))
                updateAttachChip()
            }
            return
        }
        stageIncomingUris(uris)
    }

    /** Copies content URIs into app storage and stages them as attachments.
     *  Used by the attach picker AND by media shared from other apps. */
    private fun stageIncomingUris(uris: List<Uri>) {
        stagingJob = lifecycleScope.launch {
            var skipped = 0
            for (uri in uris) {
                if (pendingAttachments.size >= io.github.theonionsarewatching.nova.util.AttachLimits.MAX_ITEMS) { skipped++; continue }
                try {
                    val mime = contentResolver.getType(uri)
                            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                    // */* share: only media and contact cards can go in an MMS
                    if (!(mime.startsWith("image/") || mime.startsWith("video/") ||
                            mime.startsWith("audio/") || mime.contains("vcard"))) { skipped++; continue }
                    val ext = io.github.theonionsarewatching.nova.util.MimeExt.forMime(mime)
                    // carry the source's display name (song title, photo name)
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
                    val dir = java.io.File(filesDir, "parts").apply { mkdirs() }
                    val out = java.io.File(dir, "${baseName}new_${System.currentTimeMillis()}_${uris.indexOf(uri)}$ext")
                    // copy off the main thread: several photos would freeze the screen
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    if (out.exists() && out.length() > 0 &&
                    !io.github.theonionsarewatching.nova.util.AttachLimits.fits(this@ComposeActivity, pendingAttachments, mime, out.length())
                ) {
                    // over the MMS limits (multi-select): drop it, tell the user below
                    out.delete(); skipped++
                } else if (out.exists() && out.length() > 0) {
                        pendingAttachments.add(Triple(out.absolutePath, mime, out.name))
                    }
                } catch (e: Exception) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            this@ComposeActivity, "attach",
                            "stage failed: ${e.message} uri=$uri"
                        )
                    }
            }
            updateAttachChip()
            if (skipped > 0) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                this@ComposeActivity, getString(R.string.attach_skipped, skipped),
                android.widget.Toast.LENGTH_LONG
            ).show()
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
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
            .setTitle(R.string.group_send_mode)
            .setSingleChoiceItems(labels, current) { d, which ->
                groupMode = if (which == 1) GroupMode.GROUP_MMS else GroupMode.BROADCAST
                updateRecipientLabel()
                d.dismiss()
            }
            .show()
    }

    private fun updateComposeSoftkeys(sk: Softkeys? = softkeys) {
        if (pickMode) {
            sk?.set(
                getString(R.string.softkey_add_more), null, getString(R.string.softkey_start),
                onLeft = { addMoreFromPick() },
                onCenter = null,
                onRight = { startFromPick() }
            )
            return
        }
        val attachLabel = if (clipboardText().isNullOrBlank())
            getString(R.string.softkey_attach) else getString(R.string.softkey_options)
        val attachAction = { AttachOrPaste.open(this, binding.bodyInput,
            onAttach = { pickAttachment() },
            onRecord = { recordAudioAttachment() },
            extra = if (!hasUnsent()) emptyList() else listOf(
                getString(R.string.draft_save_exit) to { saveNewDraft(); draftDone = true; goBack() },
                getString(R.string.draft_discard_item) to { discardNewDraft() })) }
        if (Prefs.get(this).sendOnLeft) {
            // send-on-left: mirrored — Send on the left softkey, attach on the
            // right; the on-screen + also remains next to the schedule button
            sk?.set(
                getString(R.string.softkey_send), null, attachLabel,
                onLeft = { start() },
                onCenter = null,
                onRight = attachAction
            )
        } else {
            sk?.set(
                attachLabel, null, getString(R.string.softkey_send),
                onLeft = attachAction,
                onCenter = null,
                onRight = { start() }
            )
        }
    }

    override fun onClipboardChanged() {
        runOnUiThread { updateComposeSoftkeys() }
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

    // ---- drafts on the New message screen ----
    /** set once sent / saved / discarded: onPause then saves nothing */
    private var draftDone = false

    private fun hasUnsent() = !binding.bodyInput.text.isNullOrBlank() || pendingAttachments.isNotEmpty()

    /** Save what is typed: to the recipient's chat (it shows in the list as
     *  "Draft: …"), or, with no recipient yet, as the New message draft that
     *  comes back next time. Empty boxes save nothing (no ghost chats). */
    private fun saveNewDraft() {
        if (draftDone || pickMode) return
        val prefs = Prefs.get(this)
        val body = binding.bodyInput.text?.toString().orEmpty()
        val atts = pendingAttachments.toList()
        if (body.isBlank() && atts.isEmpty()) return
        addTypedRecipient()
        val to = ArrayList(recipients)
        if (to.isEmpty()) {
            prefs.setDraft(NEW_DRAFT_ID, body)
            prefs.setDraftAttachments(NEW_DRAFT_ID, DraftPrompt.encode(atts))
            return
        }
        prefs.setDraft(NEW_DRAFT_ID, ""); prefs.setDraftAttachments(NEW_DRAFT_ID, "")
        val app = applicationContext
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch {
            val r = io.github.theonionsarewatching.nova.data.Repo.get(app)
            val convo = r.getOrCreateConversation(to)
            prefs.setDraft(convo.id, body)
            prefs.setDraftAttachments(convo.id, DraftPrompt.encode(atts))
            r.db.conversations().setDraft(convo.id, body.ifBlank { atts.firstOrNull()?.third ?: "" })
            io.github.theonionsarewatching.nova.data.ChangeBus.ping()
        }
    }

    private fun discardNewDraft() {
        draftDone = true
        val prefs = Prefs.get(this)
        prefs.setDraft(NEW_DRAFT_ID, ""); prefs.setDraftAttachments(NEW_DRAFT_ID, "")
        pendingAttachments.forEach { runCatching { java.io.File(it.first).delete() } }
        pendingAttachments.clear()
        binding.bodyInput.setText("")
        goBack()
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) saveNewDraft()
    }

    private fun startScheduled() {
        draftDone = true
        addTypedRecipient()
        if (recipients.isEmpty()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_recipients, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val body = binding.bodyInput.text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.schedule_needs_text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        io.github.theonionsarewatching.nova.ui.ScheduleTimePicker.show(this) { at ->
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
        }
    }

    private fun start() {
        addTypedRecipient()
        if (recipients.isEmpty()) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_recipients, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val body = binding.bodyInput.text?.toString()?.trim().orEmpty()
        draftDone = true
        Prefs.get(this).let { it.setDraft(NEW_DRAFT_ID, ""); it.setDraftAttachments(NEW_DRAFT_ID, "") }
        io.github.theonionsarewatching.nova.util.ClipboardGuard.clearIfStillPasted(applicationContext)
        lifecycleScope.launch {
            val convo = repo.getOrCreateConversation(recipients)
            if (convo.isGroup && convo.groupMode != groupMode) {
                repo.db.conversations().setGroupMode(convo.id, groupMode)
            }
            // a draft saved while this screen was paused (picker, Home) is used up
            Prefs.get(this@ComposeActivity).let { it.setDraft(convo.id, ""); it.setDraftAttachments(convo.id, "") }
            repo.db.conversations().setDraft(convo.id, "")
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

        fun itemAt(i: Int): ContactsHelper.Contact? = items.getOrNull(i)

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

        var hlQuery = ""
        var hlDigits = ""
        var hlT9 = true

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            HL_COLOR = ThemeUtils.accentColor(holder.itemView.context)
            holder.b.suggestionName.text =
                highlightName(c.name, hlQuery, if (hlT9) hlDigits else "")
            holder.b.suggestionNumber.text = highlightNumber(c.number, hlDigits)
            holder.itemView.setOnClickListener { onPick(c) }
        }
    }
}

// ---------- suggestion matching helpers ----------

private val wordSplit = Regex("[\\s,.\\-]+")

/** Keypad T9 digits for a name, char-aligned with the input string. */
private fun t9Of(t: String): String = t.lowercase().map { ch ->
    when (ch) {
        in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
        in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> ch
    }
}.joinToString("")

/** match highlight = the user's current accent (set on each bind) */
private var HL_COLOR = 0xFF3D74FF.toInt()

/** Bold + color the matched stretch (letters or T9 digits) inside a name. */
private fun highlightName(name: String, q: String, digits: String): CharSequence {
    var start = -1
    var len = 0
    if (q.isNotBlank()) {
        val i = name.lowercase().indexOf(q)
        if (i >= 0) { start = i; len = q.length }
    }
    if (start < 0 && digits.length >= 2) {
        // t9Of is char-aligned, so an index there is an index in the name
        val i = t9Of(name).indexOf(digits)
        if (i >= 0) { start = i; len = digits.length }
    }
    if (start < 0) return name
    return android.text.SpannableString(name).apply {
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start, start + len, 0)
        setSpan(android.text.style.ForegroundColorSpan(HL_COLOR), start, start + len, 0)
    }
}

/** Highlight the typed digits inside a possibly formatted number. */
private fun highlightNumber(number: String, digits: String): CharSequence {
    if (digits.length < 2) return number
    val raw = StringBuilder()
    val idx = ArrayList<Int>()
    number.forEachIndexed { i, ch -> if (ch.isDigit()) { raw.append(ch); idx.add(i) } }
    val pos = raw.indexOf(digits)
    if (pos < 0 || pos + digits.length > idx.size) return number
    val s2 = idx[pos]
    val e2 = idx[pos + digits.length - 1] + 1
    return android.text.SpannableString(number).apply {
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s2, e2, 0)
        setSpan(android.text.style.ForegroundColorSpan(HL_COLOR), s2, e2, 0)
    }
}

/** prefs key id of the New message draft that has no recipient yet */
private const val NEW_DRAFT_ID = -77L
