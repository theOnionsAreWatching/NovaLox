package io.github.theonionsarewatching.nova.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.BinRow
import io.github.theonionsarewatching.nova.data.ChangeBus
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.KeywordEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityListBinding
import io.github.theonionsarewatching.nova.databinding.ItemSuggestionBinding
import io.github.theonionsarewatching.nova.ui.BaseActivity
import io.github.theonionsarewatching.nova.ui.ThemeUtils
import io.github.theonionsarewatching.nova.ui.ThreadActivity
import io.github.theonionsarewatching.nova.util.Formatters
import kotlinx.coroutines.launch

// ============================== Settings ==============================

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onPause() {
        super.onPause()
        // theme / accent / direction changes apply on next activity creation
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            find("open_keywords") { startActivity(Intent(requireContext(), KeywordsActivity::class.java)) }
            find("open_blocked") { startActivity(Intent(requireContext(), BlockedMessagesActivity::class.java)) }
            find("open_bin") { startActivity(Intent(requireContext(), RecycleBinActivity::class.java)) }
            find("open_hidden") { startActivity(Intent(requireContext(), HiddenConversationsActivity::class.java)) }
            find("open_softkeys") { startActivity(Intent(requireContext(), SoftkeyConfigActivity::class.java)) }
            find("open_search") {
                startActivity(Intent(requireContext(), io.github.theonionsarewatching.nova.ui.SearchActivity::class.java))
            }
            find("resync") {
                Repo.get(requireContext()).syncRecentFromTelephony()
                Toast.makeText(requireContext(), R.string.resync_started, Toast.LENGTH_SHORT).show()
            }
            find("refresh_contacts") {
                Repo.get(requireContext()).refreshContactNames(force = true)
                Toast.makeText(requireContext(), R.string.contacts_refreshing, Toast.LENGTH_SHORT).show()
            }

            // restart-sensitive prefs: recreate the settings screen so the change is visible
            for (key in listOf("theme", "accent", "ui_scale", "layout_direction")) {
                findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    requireActivity().recreate()
                    true
                }
            }
        }

        private fun find(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }
    }
}

// ============================== Shared simple list screen ==============================

abstract class SimpleListActivity : BaseActivity() {

    protected lateinit var binding: ActivityListBinding
    protected val repo: Repo by lazy { Repo.get(this) }

    abstract fun titleRes(): Int
    abstract fun load()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.listTitle.setText(titleRes())
        binding.btnBack.setOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    protected fun setEmpty(empty: Boolean) {
        binding.emptyLabel.visibility = if (empty) View.VISIBLE else View.GONE
    }
}

class RowAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var lines: List<Pair<String, String>> = emptyList()

    fun submit(list: List<Pair<String, String>>) {
        lines = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.isFocusable = true
        b.root.foreground = ThemeUtils.focusForeground(parent.context)
        return VH(b)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.b.suggestionName.text = lines[position].first
        holder.b.suggestionNumber.text = lines[position].second
        holder.itemView.setOnClickListener { onClick(position) }
    }
}

// ============================== Keywords ==============================

class KeywordsActivity : SimpleListActivity() {

    private var items: List<KeywordEntity> = emptyList()
    private val adapter = RowAdapter { pos -> confirmRemove(items[pos]) }

    override fun titleRes(): Int = R.string.keywords_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.list.adapter = adapter
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.setText(R.string.add_keyword)
        binding.btnAction.setOnClickListener { addKeyword() }
    }

    override fun load() {
        lifecycleScope.launch {
            items = repo.db.keywords().all()
            adapter.submit(items.map { it.keyword to getString(R.string.keyword_hint_row) })
            setEmpty(items.isEmpty())
        }
    }

    private fun addKeyword() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_keyword)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val k = input.text?.toString()?.trim().orEmpty()
                if (k.isNotBlank()) {
                    lifecycleScope.launch {
                        repo.db.keywords().insert(KeywordEntity(keyword = k))
                        load()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(k: KeywordEntity) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_keyword_confirm, k.keyword))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch { repo.db.keywords().delete(k.id); load() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

// ============================== Recycle bin ==============================

class RecycleBinActivity : SimpleListActivity() {

    private var items: List<BinRow> = emptyList()
    private val adapter = RowAdapter { pos -> rowOptions(items[pos]) }

    override fun titleRes(): Int = R.string.bin_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.list.adapter = adapter
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.setText(R.string.empty_bin)
        binding.btnAction.setOnClickListener { emptyBin() }
    }

    override fun load() {
        lifecycleScope.launch {
            items = repo.db.messages().binList()
            adapter.submit(items.map {
                val label = if (it.body.isNotBlank()) it.body.take(80)
                else getString(if (it.isMms) R.string.snippet_attachment else R.string.app_name)
                label to getString(
                    R.string.deleted_on, Formatters.listStamp(it.deletedAt)
                )
            })
            setEmpty(items.isEmpty())
        }
    }

    private fun rowOptions(row: BinRow) {
        val options = arrayOf(getString(R.string.restore), getString(R.string.delete_forever))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    if (which == 0) repo.restoreMessage(row.id) else repo.hardDeleteMessage(row.id)
                    load()
                }
            }
            .show()
    }

    private fun emptyBin() {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.empty_bin_confirm, items.size))
            .setPositiveButton(R.string.empty_bin) { _, _ ->
                lifecycleScope.launch {
                    items.forEach { repo.hardDeleteMessage(it.id) }
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

// ============================== Hidden conversations ==============================

class HiddenConversationsActivity : SimpleListActivity() {

    private var items: List<ConversationEntity> = emptyList()
    private val adapter = RowAdapter { pos -> rowOptions(items[pos]) }

    override fun titleRes(): Int = R.string.hidden_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.list.adapter = adapter
    }

    override fun load() {
        lifecycleScope.launch {
            items = repo.db.conversations().hiddenList()
            adapter.submit(items.map { it.displayTitle() to it.snippet.take(80) })
            setEmpty(items.isEmpty())
        }
    }

    private fun rowOptions(c: ConversationEntity) {
        val options = arrayOf(getString(R.string.open), getString(R.string.unhide))
        AlertDialog.Builder(this)
            .setTitle(c.displayTitle())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ThreadActivity::class.java)
                        .putExtra(ThreadActivity.EXTRA_CONVO_ID, c.id))
                    1 -> lifecycleScope.launch {
                        repo.db.conversations().setHidden(c.id, false)
                        ChangeBus.ping()
                        load()
                    }
                }
            }
            .show()
    }
}

// ============================== Blocked messages ==============================

class BlockedMessagesActivity : SimpleListActivity() {

    private var items: List<BinRow> = emptyList()
    private val adapter = RowAdapter { pos -> rowOptions(items[pos]) }

    override fun titleRes(): Int = R.string.blocked_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.list.adapter = adapter
    }

    override fun load() {
        lifecycleScope.launch {
            items = repo.db.messages().blockedList()
            adapter.submit(items.map {
                it.body.take(80) to (it.address + "  \u00B7  " + Formatters.listStamp(it.date))
            })
            setEmpty(items.isEmpty())
        }
    }

    private fun rowOptions(row: BinRow) {
        val options = arrayOf(getString(R.string.restore), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    if (which == 0) {
                        repo.db.messages().unblock(row.id)
                        repo.refreshConversation(row.convoId)
                        ChangeBus.ping()
                    } else {
                        repo.deleteMessage(row.id)
                    }
                    load()
                }
            }
            .show()
    }
}

// ============================== Softkey capture ==============================

class SoftkeyConfigActivity : BaseActivity() {

    private var capturing: Int = 0 // 0 = none, 1 = left, 2 = right
    private lateinit var binding: ActivityListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.listTitle.setText(R.string.softkey_config_title)
        binding.btnBack.setOnClickListener { finish() }
        binding.list.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.setText(R.string.capture_left)
        binding.btnAction.setOnClickListener { startCapture(1) }
        binding.btnAction2.visibility = View.VISIBLE
        binding.btnAction2.setText(R.string.capture_right)
        binding.btnAction2.setOnClickListener { startCapture(2) }
        updateLabel()
    }

    private fun startCapture(which: Int) {
        capturing = which
        binding.emptyLabel.visibility = View.VISIBLE
        binding.emptyLabel.setText(
            if (which == 1) R.string.press_left_softkey else R.string.press_right_softkey
        )
    }

    private fun updateLabel() {
        binding.emptyLabel.visibility = View.VISIBLE
        binding.emptyLabel.text = getString(
            R.string.softkey_current, prefs.softkeyLeftCode, prefs.softkeyRightCode
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturing != 0 && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                capturing = 0
                updateLabel()
                return true
            }
            if (capturing == 1) prefs.softkeyLeftCode = event.keyCode
            else prefs.softkeyRightCode = event.keyCode
            capturing = 0
            Toast.makeText(this, getString(R.string.captured_code, event.keyCode), Toast.LENGTH_SHORT).show()
            updateLabel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
