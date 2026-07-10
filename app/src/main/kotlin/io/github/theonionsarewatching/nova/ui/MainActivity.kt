package io.github.theonionsarewatching.nova.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ChangeBus
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ConversationAdapter
    private lateinit var repo: Repo
    private var scroller: DpadScroller? = null
    private var importing = false
    private val changeListener: () -> Unit = { loadConversations() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)

        adapter = ConversationAdapter(
            onOpen = { openThread(it.id) },
            onOptions = { convoOptions(it) }
        )
        binding.convoList.layoutManager = LinearLayoutManager(this)
        binding.convoList.adapter = adapter
        val divider = androidx.recyclerview.widget.DividerItemDecoration(
            this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
        )
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.divider_hairline)
            ?.let { divider.setDrawable(it) }
        binding.convoList.addItemDecoration(divider)
        binding.convoList.isFocusable = true
        scroller = DpadScroller(
            binding.convoList, subScrollTallItems = false, lineStepPx = { 60 },
            onEdge = { down ->
                if (down) true // bottom: stay put
                else { enterHeader(); true } // top: deliberate press enters the header
            }
        )

        binding.btnSettings.setOnClickListener { optionsMenu() }
        binding.btnCompose.setOnClickListener { startActivity(Intent(this, ComposeActivity::class.java)) }
        binding.gateButton.setOnClickListener { requestDefaultSmsRole() }

        binding.searchInput.setOnEditorActionListener { v, _, _ ->
            val q = v.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                startActivity(Intent(this, SearchActivity::class.java).putExtra("query", q))
            }
            true
        }

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.softkey_new_message), getString(R.string.softkey_open), getString(R.string.softkey_options),
                onLeft = { startActivity(Intent(this, ComposeActivity::class.java)) },
                onCenter = { (binding.convoList.focusedChild)?.performClick() },
                onRight = { optionsMenu() },
                onMenu = { optionsMenu() }
            )
        }
        ThemeUtils.applyFocusHighlightRound(binding.btnSettings, binding.btnCompose)
        ThemeUtils.applyButtonFocus(binding.gateButton)
        ThemeUtils.applyFocusHighlight(binding.searchInput)
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
        binding.searchRow.visibility = if (prefs.showSearchBar) View.VISIBLE else View.GONE
        val isDefault = isDefaultSmsApp()
        binding.gateView.visibility = if (isDefault) View.GONE else View.VISIBLE
        binding.contentView.visibility = if (isDefault) View.VISIBLE else View.GONE
        if (isDefault) {
            askFirstRunPermissions()
            runOnboardingChain()
            loadConversations()
            repo.cleanRecycleBin()
            repo.refreshContactNames()
            if (binding.gateView.visibility == View.GONE && binding.convoList.childCount == 0) {
                binding.btnCompose.requestFocus()
            }
        } else {
            binding.gateButton.requestFocus()
        }
    }

    // ============================== default role / permissions ==============================

    private fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(this) == packageName

    private fun requestDefaultSmsRole() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 101)
                    return
                }
            }
            val i = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            i.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivityForResult(i, 101)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == Activity.RESULT_OK) {
            // onResume will pick it up
        }
    }

    private fun askFirstRunPermissions() {
        if (prefs.permissionsAsked) return
        prefs.permissionsAsked = true
        val wanted = ArrayList<String>()
        for (p in arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) wanted.add(p)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 102)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) repo.refreshContactNames(force = true)
    }

    /** First-run order: battery optimization prompt -> softkey setup (skippable) -> import. */
    private fun runOnboardingChain() {
        if (!prefs.batteryPromptShown) {
            prefs.batteryPromptShown = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.battery_title)
                    .setMessage(R.string.battery_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.allow) { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:$packageName"))
                            )
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(R.string.later) { _, _ -> runOnboardingChain() }
                    .show()
                return
            }
        }
        if (!prefs.softkeySetupDone) {
            startActivity(
                Intent(this, io.github.theonionsarewatching.nova.ui.settings.SoftkeyConfigActivity::class.java)
                    .putExtra(io.github.theonionsarewatching.nova.ui.settings.SoftkeyConfigActivity.EXTRA_ONBOARDING, true)
            )
            return
        }
        maybeImport()
    }

    // ============================== first import ==============================

    private fun maybeImport() {
        if (importing) return
        if (prefs.importDone) {
            // DB may have been recreated (schema upgrade): re-import if it's empty
            lifecycleScope.launch {
                if (repo.db.messages().count() == 0) {
                    prefs.importDone = false
                    maybeImport()
                }
            }
            return
        }
        importing = true
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ThemeUtils.dp(context, 20)
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(this).apply { setText(R.string.import_running) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        view.addView(label)
        view.addView(bar)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setView(view)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                repo.importFromTelephony { p -> runOnUiThread { bar.progress = p } }
                prefs.importDone = true
                repo.runElementBacklog()
            } finally {
                importing = false
                runCatching { dialog.dismiss() }
                loadConversations()
            }
        }
    }

    // ============================== list ==============================

    private fun loadConversations() {
        lifecycleScope.launch {
            var list = repo.db.conversations().visible()
            list = when (prefs.filterMode) {
                "unread" -> list.filter { it.unreadCount > 0 }
                "unknown" -> list.filter { c -> c.nameList().all { it.isBlank() } }
                "groups" -> list.filter { it.isGroup }
                else -> list
            }
            list = when (prefs.sortMode) {
                "unread" -> list.sortedWith(
                    compareByDescending<ConversationEntity> { it.pinned }
                        .thenByDescending { it.unreadCount > 0 }
                        .thenByDescending { it.snippetDate })
                "alpha" -> list.sortedWith(
                    compareByDescending<ConversationEntity> { it.pinned }
                        .thenBy { it.displayTitle().lowercase() })
                "oldest" -> list.sortedWith(
                    compareByDescending<ConversationEntity> { it.pinned }
                        .thenBy { it.snippetDate })
                else -> list.sortedWith(
                    compareByDescending<ConversationEntity> { it.pinned }
                        .thenByDescending { it.snippetDate })
            }
            adapter.submit(list)
            binding.emptyLabel.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openThread(convoId: Long) {
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_CONVO_ID, convoId))
    }

    // ============================== menus ==============================

    private fun setHeaderFocusable(on: Boolean) {
        binding.btnSettings.isFocusable = on
        binding.btnCompose.isFocusable = on
    }

    private fun enterHeader() {
        setHeaderFocusable(true)
        binding.btnCompose.requestFocus()
    }

    private fun leaveHeader() {
        setHeaderFocusable(false)
        binding.convoList.requestFocus()
        val lm = binding.convoList.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 }
            ?: lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        scroller?.focusPosition(first)
    }

    private fun optionsMenu() {
        val items = arrayOf(
            getString(R.string.menu_search),
            getString(R.string.menu_archived),
            getString(R.string.menu_sort),
            getString(R.string.menu_filter),
            getString(R.string.menu_mark_all_read),
            getString(R.string.menu_settings)
        )
        AlertDialog.Builder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.softkey_options)))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SearchActivity::class.java))
                    1 -> showArchived()
                    2 -> pickSort()
                    3 -> pickFilter()
                    4 -> lifecycleScope.launch {
                        repo.db.conversations().visible().forEach {
                            repo.db.messages().markThreadRead(it.id)
                            repo.refreshConversation(it.id)
                        }
                        ChangeBus.ping()
                    }
                    5 -> startActivity(Intent(this, io.github.theonionsarewatching.nova.ui.settings.SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun pickSort() {
        val values = arrayOf("recent", "unread", "alpha", "oldest")
        val labels = arrayOf(
            getString(R.string.sort_recent), getString(R.string.sort_unread),
            getString(R.string.sort_alpha), getString(R.string.sort_oldest)
        )
        val current = values.indexOf(prefs.sortMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.sortMode = values[which]
                loadConversations()
                d.dismiss()
            }
            .show()
    }

    private fun pickFilter() {
        val values = arrayOf("all", "unread", "unknown", "groups")
        val labels = arrayOf(
            getString(R.string.filter_all), getString(R.string.filter_unread),
            getString(R.string.filter_unknown), getString(R.string.filter_groups)
        )
        val current = values.indexOf(prefs.filterMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_filter)
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.filterMode = values[which]
                loadConversations()
                d.dismiss()
            }
            .show()
    }

    private fun showArchived() {
        lifecycleScope.launch {
            val archived = repo.db.conversations().archivedList()
            if (archived.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.no_archived).setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val labels = archived.map { it.displayTitle() }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.menu_archived)
                .setItems(labels) { _, which -> openThread(archived[which].id) }
                .show()
        }
    }

    private fun convoOptions(c: ConversationEntity) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += getString(R.string.open) to { openThread(c.id) }
        if (!c.isGroup) {
            items += getString(R.string.call_contact, c.displayTitle()) to {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.addressList().first())))
                } catch (_: Exception) {}
            }
        }
        items += (if (c.pinned) getString(R.string.unpin) else getString(R.string.pin)) to {
            lifecycleScope.launch { repo.db.conversations().setPinned(c.id, !c.pinned); ChangeBus.ping() }
        }
        items += (if (c.archived) getString(R.string.unarchive) else getString(R.string.archive)) to {
            lifecycleScope.launch { repo.db.conversations().setArchived(c.id, !c.archived); ChangeBus.ping() }
        }
        items += (if (c.muted) getString(R.string.unmute) else getString(R.string.mute)) to {
            lifecycleScope.launch { repo.db.conversations().setMuted(c.id, !c.muted); ChangeBus.ping() }
        }
        items += (if (c.notifBlocked) getString(R.string.unblock_notifications) else getString(R.string.block_notifications)) to {
            lifecycleScope.launch { repo.db.conversations().setNotifBlocked(c.id, !c.notifBlocked); ChangeBus.ping() }
        }
        items += getString(R.string.sound_and_vibration) to { SoundDialog.show(this, c.id) }
        items += getString(R.string.hide_conversation) to {
            AlertDialog.Builder(this)
                .setMessage(R.string.hide_confirm)
                .setPositiveButton(R.string.hide) { _, _ ->
                    lifecycleScope.launch { repo.db.conversations().setHidden(c.id, true); ChangeBus.ping() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val hasUnread = c.unreadCount > 0
        items += (if (hasUnread) getString(R.string.mark_read) else getString(R.string.mark_unread)) to {
            lifecycleScope.launch {
                if (hasUnread) repo.db.messages().markThreadRead(c.id)
                else repo.db.messages().newest(c.id)?.let { repo.db.messages().setRead(it.id, false) }
                repo.refreshConversation(c.id)
                ChangeBus.ping()
            }
        }
        if (Build.VERSION.SDK_INT >= 24 && !c.isGroup) {
            items += getString(R.string.block_number) to {
                lifecycleScope.launch {
                    try {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                                c.addressList().first())
                        }
                        contentResolver.insert(
                            android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, values
                        )
                        android.widget.Toast.makeText(this@MainActivity, R.string.number_blocked,
                            android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            }
        }
        items += getString(R.string.delete_thread) to { deleteThreadFlow(c) }

        AlertDialog.Builder(this)
            .setCustomTitle(Dialogs.title(this, c.displayTitle()))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun deleteThreadFlow(c: ConversationEntity) {
        lifecycleScope.launch {
            val locked = repo.db.messages().lockedCount(c.id)
            if (locked > 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.delete_thread)
                    .setMessage(getString(R.string.delete_thread_locked, locked))
                    .setPositiveButton(R.string.delete_all) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, includeLocked = true) }
                    }
                    .setNeutralButton(R.string.keep_locked) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, includeLocked = false) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(getString(R.string.delete_thread_confirm, c.displayTitle()))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch { repo.deleteThread(c.id, includeLocked = true) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ============================== keys ==============================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (softkeys?.handleKey(event) == true) return true
        if (binding.contentView.visibility == View.VISIBLE && scroller?.onKey(event) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_CALL) {
            (binding.convoList.focusedChild)?.let { v ->
                val pos = binding.convoList.getChildAdapterPosition(v)
                if (pos >= 0) {
                    val c = adapter.items.getOrNull(pos)
                    if (c != null && !c.isGroup) {
                        try {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.addressList().first())))
                        } catch (_: Exception) {}
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
