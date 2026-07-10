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
            find("open_sizes") { startActivity(Intent(requireContext(), SizeSettingsActivity::class.java)) }
            find("accent_picker") { showAccentPicker() }
            find("default_tone") {
                val act = requireActivity() as BaseActivity
                val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(act)
                io.github.theonionsarewatching.nova.ui.TonePicker.pick(
                    act, prefs.defaultTone, R.string.tone_system_default
                ) { tone ->
                    prefs.defaultTone = tone
                    io.github.theonionsarewatching.nova.notify.NotificationHelper.refreshAllChannels(act)
                    updateToneSummary()
                }
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>("vibrate")
                ?.setOnPreferenceChangeListener { _, _ ->
                    // channels embed the vibrate flag; rebuild them after the pref lands
                    view?.post {
                        io.github.theonionsarewatching.nova.notify.NotificationHelper
                            .refreshAllChannels(requireContext())
                    }
                    true
                }
            find("open_search") {
                startActivity(Intent(requireContext(), io.github.theonionsarewatching.nova.ui.SearchActivity::class.java))
            }
            find("backup_now") {
                val name = "nova-backup-" + java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmm", java.util.Locale.US
                ).format(java.util.Date()) + ".zip"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, 301)
                } catch (_: Exception) {
                    // no system file picker on this device (common on stripped ROMs):
                    // write straight to Downloads/D-SMS instead
                    backupToDownloads()
                }
            }
            find("restore_backup") {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.restore_title)
                    .setMessage(R.string.restore_warning)
                    .setPositiveButton(R.string.restore) { _, _ ->
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                        }
                        try {
                            @Suppress("DEPRECATION")
                            startActivityForResult(i, 302)
                        } catch (_: Exception) {
                            restoreFromDownloads()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
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
            for (key in listOf("theme", "layout_direction")) {
                findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    requireActivity().recreate()
                    true
                }
            }
        }

        private fun find(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }

        override fun onResume() {
            super.onResume()
            updateAccentSummary()
            updateToneSummary()
        }

        private fun updateToneSummary() {
            val act = activity as? BaseActivity ?: return
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(act)
            findPreference<Preference>("default_tone")?.summary =
                io.github.theonionsarewatching.nova.ui.TonePicker.toneName(
                    act, prefs.defaultTone, R.string.tone_system_default
                )
        }

        private fun accentOptions(): List<Triple<String, String, Int>> {
            val ctx = requireContext()
            fun c(res: Int) = androidx.core.content.ContextCompat.getColor(ctx, res)
            return listOf(
                Triple("system", getString(R.string.accent_system),
                    io.github.theonionsarewatching.nova.ui.ThemeUtils.accentColor(ctx)),
                Triple("blue", getString(R.string.accent_blue_name), c(R.color.accent_blue)),
                Triple("teal", getString(R.string.accent_teal_name), c(R.color.accent_teal)),
                Triple("green", getString(R.string.accent_green_name), c(R.color.accent_green)),
                Triple("orange", getString(R.string.accent_orange_name), c(R.color.accent_orange)),
                Triple("red", getString(R.string.accent_red_name), c(R.color.accent_red)),
                Triple("purple", getString(R.string.accent_purple_name), c(R.color.accent_purple)),
                Triple("pink", getString(R.string.accent_pink_name), c(R.color.accent_pink)),
                Triple("gray", getString(R.string.accent_gray_name), c(R.color.accent_gray))
            )
        }

        private fun updateAccentSummary() {
            val current = io.github.theonionsarewatching.nova.util.Prefs.get(requireContext()).accent
            findPreference<Preference>("accent_picker")?.summary =
                accentOptions().firstOrNull { it.first == current }?.second ?: current
        }

        /** Accent picker with a color swatch beside each name. */
        private fun showAccentPicker() {
            val ctx = requireContext()
            val options = accentOptions()
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(ctx)
            val checked = options.indexOfFirst { it.first == prefs.accent }.coerceAtLeast(0)
            val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

            val adapter = object : android.widget.ArrayAdapter<Triple<String, String, Int>>(
                ctx, 0, options
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val item = options[position]
                    val row = (convertView as? android.widget.LinearLayout)
                        ?: android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(20), dp(12), dp(20), dp(12))
                            addView(View(ctx).apply {
                                layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22))
                                    .apply { marginEnd = dp(16) }
                            })
                            addView(android.widget.TextView(ctx).apply { textSize = 15f })
                        }
                    val swatch = row.getChildAt(0)
                    val label = row.getChildAt(1) as android.widget.TextView
                    swatch.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(item.third)
                    }
                    label.text = if (position == checked) "${item.second}  \u2713" else item.second
                    return row
                }
            }

            AlertDialog.Builder(ctx)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs.title(
                    requireActivity(), getString(R.string.pref_accent)))
                .setAdapter(adapter) { _, which ->
                    prefs.sp.edit().putString("accent", options[which].first).apply()
                    requireActivity().recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            val uri = data?.data ?: return
            if (resultCode != android.app.Activity.RESULT_OK) return
            when (requestCode) {
                301 -> runBackupTask(exporting = true, uri = uri)
                302 -> runBackupTask(exporting = false, uri = uri)
            }
        }

        /** Progress dialog with a determinate bar + a small detail line. */
        private class ProgressUi(ctx: android.content.Context, titleRes: Int) {
            val bar = android.widget.ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal
            ).apply { max = 100; isIndeterminate = true }
            val detail = android.widget.TextView(ctx).apply {
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val dialog: AlertDialog = AlertDialog.Builder(ctx)
                .setTitle(titleRes)
                .setView(android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (18 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                    addView(bar)
                    addView(detail)
                })
                .setCancelable(false)
                .show()

            fun update(percent: Int, text: String?) {
                if (percent < 0) {
                    bar.isIndeterminate = true
                } else {
                    bar.isIndeterminate = false
                    bar.progress = percent
                }
                if (text != null) detail.text = text
            }

            fun dismiss() = runCatching { dialog.dismiss() }
        }

        private fun progressReporter(ui: ProgressUi) =
            io.github.theonionsarewatching.nova.data.BackupHelper.Progress { pct, det ->
                ui.bar.post { ui.update(pct, det) }
            }

        private fun backupToDownloads() {
            val ctx = requireContext()
            val ui = ProgressUi(ctx, R.string.backing_up)
            viewLifecycleOwner.lifecycleScope.launch {
                val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.theonionsarewatching.nova.data.BackupHelper
                        .exportToDownloads(ctx, progressReporter(ui))
                }
                ui.dismiss()
                Toast.makeText(
                    ctx,
                    if (name != null) getString(R.string.backup_saved_to, name)
                    else getString(R.string.backup_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun restoreFromDownloads() {
            val ctx = requireContext()
            val backups = io.github.theonionsarewatching.nova.data.BackupHelper.findLocalBackups(ctx)
            if (backups.isEmpty()) {
                Toast.makeText(ctx, R.string.no_backups_found, Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.restore_title)
                .setItems(backups.map { it.displayName }.toTypedArray()) { _, which ->
                    runBackupTask(exporting = false, uri = backups[which].uri)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun runBackupTask(exporting: Boolean, uri: android.net.Uri) {
            val ctx = requireContext()
            val ui = ProgressUi(ctx, if (exporting) R.string.backing_up else R.string.restoring)
            viewLifecycleOwner.lifecycleScope.launch {
                val reporter = progressReporter(ui)
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (exporting) io.github.theonionsarewatching.nova.data.BackupHelper.export(ctx, uri, reporter)
                    else io.github.theonionsarewatching.nova.data.BackupHelper.restore(ctx, uri, reporter)
                }
                ui.dismiss()
                Toast.makeText(
                    ctx,
                    when {
                        exporting && ok -> R.string.backup_done
                        exporting -> R.string.backup_failed
                        ok -> R.string.restore_done
                        else -> R.string.restore_failed
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

// ============================== Size sub-settings ==============================

class SizeSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SizeFragment())
                .commit()
        }
    }

    class SizeFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_size, rootKey)
            // recreate so scale / outline changes are visible immediately
            for (key in listOf("ui_scale", "focus_stroke")) {
                findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    requireActivity().recreate()
                    true
                }
            }
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
        ThemeUtils.applyFocusHighlightRound(binding.btnBack)
        ThemeUtils.applyButtonFocus(binding.btnAction, binding.btnAction2)
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
        b.root.background = ThemeUtils.focusFill(parent.context)
        b.root.foreground = ThemeUtils.focusStroke(parent.context)
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

/**
 * D-Mail-style sequential setup: press LEFT, press RIGHT, then Save.
 * Regular keys (D-pad, numbers, letters, call keys, volume...) are rejected so a
 * mis-press can't hijack normal navigation. Shown once on first run (skippable)
 * and available any time from Settings.
 */
class SoftkeyConfigActivity : BaseActivity() {

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"

        // keycodes that must never be mapped as softkeys
        private val BLOCKED_KEYS: Set<Int> = buildSet {
            addAll(
                listOf(
                    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
                    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND,
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA,
                    KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SYM,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD,
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT
                )
            )
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)   // number pad
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)   // letters
        }
    }

    private enum class Step { LEFT, RIGHT, CONFIRM }

    private var step = Step.LEFT
    private var capturedLeft = -1
    private var capturedRight = -1
    private var onboarding = false
    private lateinit var binding: ActivityListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)
        binding.listTitle.setText(R.string.softkey_config_title)
        binding.btnBack.setOnClickListener { finishFlow(save = false) }
        ThemeUtils.applyFocusHighlightRound(binding.btnBack)
        ThemeUtils.applyButtonFocus(binding.btnAction, binding.btnAction2)
        binding.list.visibility = View.GONE
        binding.emptyLabel.visibility = View.VISIBLE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction2.visibility = View.VISIBLE
        render()
    }

    private fun render() {
        when (step) {
            Step.LEFT -> {
                binding.emptyLabel.setText(R.string.press_left_softkey)
                binding.btnAction.setText(R.string.skip)
                binding.btnAction.setOnClickListener { finishFlow(save = false) }
                binding.btnAction2.visibility = View.GONE
            }
            Step.RIGHT -> {
                binding.emptyLabel.setText(R.string.press_right_softkey)
                binding.btnAction.setText(R.string.start_over)
                binding.btnAction.setOnClickListener { restart() }
                binding.btnAction2.visibility = View.GONE
            }
            Step.CONFIRM -> {
                binding.emptyLabel.text =
                    getString(R.string.softkey_captured_both, capturedLeft, capturedRight)
                binding.btnAction.setText(R.string.save)
                binding.btnAction.setOnClickListener { finishFlow(save = true) }
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction2.setText(R.string.start_over)
                binding.btnAction2.setOnClickListener { restart() }
                binding.btnAction.requestFocus()
            }
        }
    }

    private fun restart() {
        capturedLeft = -1
        capturedRight = -1
        step = Step.LEFT
        render()
    }

    private fun finishFlow(save: Boolean) {
        if (save && capturedLeft > 0 && capturedRight > 0) {
            prefs.softkeyLeftCode = capturedLeft
            prefs.softkeyRightCode = capturedRight
            prefs.softkeysMapped = true
            Toast.makeText(this, R.string.softkeys_saved, Toast.LENGTH_SHORT).show()
        }
        if (onboarding) prefs.softkeySetupDone = true
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (step == Step.CONFIRM) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) {
            // swallow the UP of a captured key
            return if (event.keyCode == capturedLeft || event.keyCode == capturedRight)
                true else super.dispatchKeyEvent(event)
        }
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            finishFlow(save = false)
            return true
        }
        // let the D-pad still reach the Skip button; block it from being captured
        if (code in BLOCKED_KEYS) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
            ) {
                return super.dispatchKeyEvent(event)
            }
            Toast.makeText(this, R.string.not_a_softkey, Toast.LENGTH_SHORT).show()
            return true
        }
        when (step) {
            Step.LEFT -> {
                capturedLeft = code
                step = Step.RIGHT
                render()
            }
            Step.RIGHT -> {
                if (code == capturedLeft) {
                    Toast.makeText(this, R.string.same_key_twice, Toast.LENGTH_SHORT).show()
                } else {
                    capturedRight = code
                    step = Step.CONFIRM
                    render()
                }
            }
            else -> {}
        }
        return true
    }
}
