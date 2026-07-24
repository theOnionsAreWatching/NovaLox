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
            val frag = SettingsFragment().apply {
                val xml = intent.getIntExtra(SettingsFragment.ARG_XML, 0)
                if (xml != 0) arguments = Bundle().apply { putInt(SettingsFragment.ARG_XML, xml) }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, frag)
                .commit()
        }
    }

    override fun onPause() {
        super.onPause()
        // theme / accent / direction changes apply on next activity creation
    }

    /** Launch a document picker whose result is copied into the app-wide
     *  "all chats" background file. */
    fun pickAllThreadsBackground() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }
            startActivityForResult(i, io.github.theonionsarewatching.nova.ui.ChatBackground.REQ_BG_DOC_ALL)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.no_file_picker,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == io.github.theonionsarewatching.nova.ui.ChatBackground.REQ_BG_DOC_ALL ||
             requestCode == io.github.theonionsarewatching.nova.ui.ChatBackground.REQ_BG_GALLERY_ALL)
            && resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                try {
                    val dir = java.io.File(filesDir, "backgrounds").apply { mkdirs() }
                    val dest = java.io.File(dir, "bg_-1")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    if (dest.exists() && dest.length() > 0) {
                        io.github.theonionsarewatching.nova.util.Prefs.get(this@SettingsActivity)
                            .setChatBg(io.github.theonionsarewatching.nova.ui.ChatBackground.ALL_THREADS,
                                dest.absolutePath)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object { const val ARG_XML = "xml_res" }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val xmlRes = arguments?.getInt(ARG_XML)?.takeIf { it != 0 } ?: R.xml.preferences
            setPreferencesFromResource(xmlRes, rootKey)

            find("open_customize") {
                // its own activity, like the other sub-screens — the fragment
                // backstack left the restored list unfocusable for the D-pad
                startActivity(
                    Intent(requireContext(), SettingsActivity::class.java)
                        .putExtra(SettingsFragment.ARG_XML, R.xml.preferences_customize)
                )
            }

            find("open_keywords") { startActivity(Intent(requireContext(), KeywordsActivity::class.java)) }
            find("open_blocked") { startActivity(Intent(requireContext(), BlockedMessagesActivity::class.java)) }
            find("open_bin") { startActivity(Intent(requireContext(), RecycleBinActivity::class.java)) }
            find("open_hidden") { startActivity(Intent(requireContext(), HiddenConversationsActivity::class.java)) }
            find("open_softkeys") { startActivity(Intent(requireContext(), SoftkeyConfigActivity::class.java)) }
            find("open_sizes") { startActivity(Intent(requireContext(), SizeSettingsActivity::class.java)) }
            fun openXml(xml: Int) = startActivity(
                Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsFragment.ARG_XML, xml)
            )
            find("open_mms_advanced") { openXml(R.xml.preferences_mms_advanced) }
            find("open_delivery") { openXml(R.xml.preferences_delivery) }
            find("open_softkeys_settings") { openXml(R.xml.preferences_softkeys) }
            find("open_advanced") { openXml(R.xml.preferences_advanced) }
            find("open_advanced_display") { openXml(R.xml.preferences_advanced_display) }
            find("open_message_color") { openXml(R.xml.preferences_message_color) }
            find("clear_mms_cache") {
                val ctx = requireContext()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_clear_cache)
                    .setMessage(R.string.clear_cache_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.github.theonionsarewatching.nova.data.Repo.get(ctx).clearMmsCache()
                            }
                            Toast.makeText(ctx, R.string.clear_cache_done, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>("auto_delete_old")
                ?.setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue == true) {
                        val ctx = requireContext()
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.pref_auto_delete)
                            .setMessage(R.string.auto_delete_warning)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                (pref as androidx.preference.SwitchPreferenceCompat).isChecked = true
                                promptAutoDeleteCount()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        false
                    } else true
                }
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
                if (io.github.theonionsarewatching.nova.util.BackgroundTasks.running) {
                    attachTaskDialog(); return@find
                }
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
                if (io.github.theonionsarewatching.nova.util.BackgroundTasks.running) {
                    attachTaskDialog(); return@find
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.restore_title)
                    .setView(io.github.theonionsarewatching.nova.ui.Dialogs.scrollableMessage(
                        requireActivity(), R.string.restore_warning))
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
            find("reimport") {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_reimport)
                    .setView(io.github.theonionsarewatching.nova.ui.Dialogs.scrollableMessage(
                        requireActivity(), R.string.reimport_warning))
                    .setPositiveButton(R.string.pref_reimport) { _, _ ->
                        val ctx = requireContext()
                        val bt = io.github.theonionsarewatching.nova.util.BackgroundTasks
                        if (bt.running) { attachTaskDialog(); return@setPositiveButton }
                        if (!bt.start(ctx, R.string.reimporting,
                                R.string.reimport_done, R.string.reimport_done)) return@setPositiveButton
                        attachTaskDialog()
                        bt.scope.launch {
                            try {
                                io.github.theonionsarewatching.nova.data.Repo.get(ctx.applicationContext)
                                    .reimportAll { pct -> bt.report(pct, null) }
                                bt.finish(true)
                            } catch (_: Exception) {
                                bt.finish(false)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            findPreference<androidx.preference.ListPreference>("app_zoom")
                ?.setOnPreferenceChangeListener { pref, newValue ->
                    val entryIdx = (pref as androidx.preference.ListPreference)
                        .findIndexOfValue(newValue.toString())
                    val label = pref.entries.getOrNull(entryIdx) ?: newValue.toString()
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_resize)
                        .setMessage(getString(R.string.resize_restart_confirm, label))
                        .setPositiveButton(R.string.resize_and_restart) { _, _ ->
                            // commit(), not apply(): apply() writes to disk on a
                            // background thread and the restart kills the process
                            // before the write lands — the classic reason the new
                            // size "didn't take"
                            io.github.theonionsarewatching.nova.util.Prefs.get(requireContext())
                                .sp.edit().putString("app_zoom", newValue.toString()).commit()
                            restartApp()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    false // we persist manually after the confirmation
                }

            find("all_backgrounds") {
                val act = requireActivity()
                val host = object : io.github.theonionsarewatching.nova.ui.ChatBackground.Host {
                    override fun applyBackgroundForCurrent() {}
                    override fun startPicturePickerForBackground(convoId: Long) {
                        // route through the hosting activity's picker (SettingsActivity
                        // forwards the result to copy into bg_all)
                        (act as? io.github.theonionsarewatching.nova.ui.settings.SettingsActivity)
                            ?.pickAllThreadsBackground()
                    }
                }
                io.github.theonionsarewatching.nova.ui.ChatBackground.show(
                    act, io.github.theonionsarewatching.nova.util.Prefs.get(act),
                    io.github.theonionsarewatching.nova.ui.ChatBackground.ALL_THREADS, host
                )
            }
            find("about_footer") { showAboutDetails() }
            // fill the About summary with the live version name
            findPreference<androidx.preference.Preference>("about_footer")?.let { pref ->
                val v = try {
                    requireContext().packageManager
                        .getPackageInfo(requireContext().packageName, 0).versionName
                } catch (_: Exception) { "" }
                pref.summary = getString(R.string.about_summary_fmt, v)
            }
            val customUaPref =
                findPreference<androidx.preference.EditTextPreference>("mms_custom_ua")
            val customUaProfPref =
                findPreference<androidx.preference.EditTextPreference>("mms_custom_uaprof")
            val profilePref =
                findPreference<androidx.preference.ListPreference>("mms_client_profile")
            // the custom fields belong to the picker: shown only when Custom is chosen
            fun showCustomFields(value: String?) {
                val custom = value == "custom"
                customUaPref?.isVisible = custom
                customUaProfPref?.isVisible = custom
            }
            showCustomFields(profilePref?.value)
            val reseedIdentity = androidx.preference.Preference.OnPreferenceChangeListener { pref, newValue ->
                if (pref.key == "mms_client_profile") showCustomFields(newValue as? String)
                // re-seed the MMS config immediately; also applied at startup
                view?.post {
                    io.github.theonionsarewatching.nova.util.MmsUserAgent
                        .applyToConfig(requireContext())
                }
                true
            }
            profilePref?.onPreferenceChangeListener = reseedIdentity
            customUaPref?.onPreferenceChangeListener = reseedIdentity
            customUaProfPref?.onPreferenceChangeListener = reseedIdentity
            findPreference<androidx.preference.SwitchPreferenceCompat>("softkeys_focusable")
                ?.setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue == true) {
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(R.string.pref_softkeys_focusable)
                            .setMessage(R.string.softkeys_focusable_warning)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                (pref as androidx.preference.SwitchPreferenceCompat).isChecked = true
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        false
                    } else true
                }
            find("sent_color") {
                val act = requireActivity()
                io.github.theonionsarewatching.nova.ui.ChatBackground.chooseColor(
                    act,
                    topOptionRes = R.string.sent_color_accent,
                    onTop = {
                        // default: follow the accent (clears any custom color)
                        io.github.theonionsarewatching.nova.util.Prefs.get(act).sentColor = ""
                        Toast.makeText(act, R.string.sent_color_set, Toast.LENGTH_SHORT).show()
                    }
                ) { hex ->
                    io.github.theonionsarewatching.nova.util.Prefs.get(act).sentColor = hex
                    Toast.makeText(act, R.string.sent_color_set, Toast.LENGTH_SHORT).show()
                }
            }
            find("incoming_color") {
                val act = requireActivity()
                io.github.theonionsarewatching.nova.ui.ChatBackground.chooseColor(
                    act,
                    topOptionRes = R.string.incoming_color_accent,
                    onTop = {
                        // accent-colored incoming bubbles (a lighter accent tint)
                        io.github.theonionsarewatching.nova.util.Prefs.get(act).incomingColor = "accent"
                        ChangeBus.ping()
                        Toast.makeText(act, R.string.incoming_color_set, Toast.LENGTH_SHORT).show()
                    }
                ) { hex ->
                    io.github.theonionsarewatching.nova.util.Prefs.get(act).incomingColor = hex
                    ChangeBus.ping()
                    Toast.makeText(act, R.string.incoming_color_set, Toast.LENGTH_SHORT).show()
                }
            }
            find("export_contacts") {
                val ctx = requireContext()
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_export_contacts)
                    .setMessage(R.string.export_contacts_confirm)
                    .setPositiveButton(R.string.export_go) { _, _ ->
                        Toast.makeText(ctx, R.string.exporting_contacts, Toast.LENGTH_SHORT).show()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val vcf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.github.theonionsarewatching.nova.util.ContactVcf.exportAll(ctx)
                            }
                            if (vcf.isBlank()) {
                                Toast.makeText(ctx, R.string.export_contacts_empty, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val tmp = java.io.File(ctx.cacheDir, "contacts-export.vcf")
                            tmp.writeText(vcf)
                            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date())
                            val ok = io.github.theonionsarewatching.nova.ui.Saver
                                .saveToDownloads(ctx, tmp, "contacts-$stamp.vcf", "text/x-vcard")
                            Toast.makeText(ctx,
                                if (ok) R.string.export_contacts_done else R.string.export_contacts_failed,
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            find("save_diag_log") {
                val ctx = requireContext()
                viewLifecycleOwner.lifecycleScope.launch {
                    val f = io.github.theonionsarewatching.nova.util.DiagLog.file(ctx)
                    val ok = f.exists() && io.github.theonionsarewatching.nova.ui.Saver
                        .saveToDownloads(ctx, f, "novalox-log.txt", "text/plain")
                    Toast.makeText(ctx,
                        if (ok) R.string.log_saved else R.string.log_empty,
                        Toast.LENGTH_LONG).show()
                }
            }
            find("check_updates") {
                val ctx = requireContext()
                Toast.makeText(ctx, R.string.checking_updates, Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val current = try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
                    } catch (_: Exception) { "0" }
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.theonionsarewatching.nova.util.UpdateChecker.check(current)
                    }
                    when (result) {
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.UpToDate ->
                            Toast.makeText(ctx,
                                getString(R.string.up_to_date_detail, current, result.latestTag),
                                Toast.LENGTH_LONG).show()
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.Failed ->
                            Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.UpdateAvailable -> {
                            val release = result.release
                            AlertDialog.Builder(ctx)
                                .setTitle(getString(R.string.update_available, release.tag))
                                .setMessage(R.string.update_prompt)
                                .setPositiveButton(R.string.download) { _, _ ->
                                    io.github.theonionsarewatching.nova.util.UpdateChecker.download(ctx, release)
                                    Toast.makeText(ctx, R.string.update_downloading, Toast.LENGTH_LONG).show()
                                }
                                .setNegativeButton(R.string.later, null)
                                .show()
                        }
                    }
                }
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

        /** Kill and relaunch so the new size applies to EVERY screen at once. */
        private fun restartApp() {
            val ctx = requireContext().applicationContext
            val intent = android.content.Intent(ctx,
                io.github.theonionsarewatching.nova.ui.MainActivity::class.java)
            val restart = android.content.Intent.makeRestartActivityTask(intent.component)
            ctx.startActivity(restart)
            Runtime.getRuntime().exit(0)
        }

        private fun find(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }

        private val changeToast =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                if (key == null) return@OnSharedPreferenceChangeListener
                val pref = findPreference<Preference>(key) ?: return@OnSharedPreferenceChangeListener
                val title = pref.title?.toString().orEmpty()
                if (title.isNotBlank()) {
                    Toast.makeText(requireContext(),
                        getString(R.string.setting_updated_fmt, title), Toast.LENGTH_SHORT).show()
                }
            }

        override fun onResume() {
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(changeToast)
            super.onResume()
            updateAccentSummary()
            updateToneSummary()
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(changeToast)
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
        private fun showAboutDetails() {
            val ctx = requireContext()
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) { "" }
            val msg = getString(R.string.about_details, version)
            val view = io.github.theonionsarewatching.nova.ui.Dialogs
                .scrollableMessageText(requireActivity(), msg)
            AlertDialog.Builder(ctx)
                .setTitle(R.string.about_app_name)
                .setView(view)
                .setPositiveButton(R.string.about_open_repo) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                            "https://github.com/theOnionsAreWatching/NovaLox")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }

        private fun promptAutoDeleteCount() {
            val ctx = requireContext()
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(ctx)
            val input = android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(prefs.autoDeleteKeep.toString())
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.auto_delete_count_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val keep = input.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1000
                    prefs.autoDeleteKeep = keep
                    AlertDialog.Builder(ctx)
                        .setMessage(getString(R.string.auto_delete_apply_warning, keep))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    io.github.theonionsarewatching.nova.data.Repo.get(ctx)
                                        .enforceAutoDelete(keep)
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

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

        private fun backupToDownloads() {
            val ctx = requireContext()
            val bt = io.github.theonionsarewatching.nova.util.BackgroundTasks
            if (bt.running) { attachTaskDialog(); return }
            if (!bt.start(ctx, R.string.backing_up, R.string.backup_done, R.string.backup_failed)) return
            attachTaskDialog()
            bt.scope.launch {
                val name = try {
                    io.github.theonionsarewatching.nova.data.BackupHelper
                        .exportToDownloads(ctx.applicationContext) { p, d -> bt.report(p, d) }
                } catch (_: Exception) { null }
                bt.finish(name != null)
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

        /** Progress dialog for the app-wide task, detachable to the background. */
        private fun attachTaskDialog() {
            io.github.theonionsarewatching.nova.ui.TaskDialogs.attach(requireActivity())
        }

        private fun runBackupTask(exporting: Boolean, uri: android.net.Uri) {
            val ctx = requireContext()
            val bt = io.github.theonionsarewatching.nova.util.BackgroundTasks
            if (bt.running) { attachTaskDialog(); return }
            val okRes = if (exporting) R.string.backup_done else R.string.restore_done
            val failRes = if (exporting) R.string.backup_failed else R.string.restore_failed
            if (!bt.start(ctx, if (exporting) R.string.backing_up else R.string.restoring, okRes, failRes)) return
            attachTaskDialog()
            bt.scope.launch {
                val reporter = io.github.theonionsarewatching.nova.data.BackupHelper.Progress { p, d ->
                    bt.report(p, d)
                }
                val ok = try {
                    if (exporting) io.github.theonionsarewatching.nova.data.BackupHelper.export(ctx.applicationContext, uri, reporter)
                    else io.github.theonionsarewatching.nova.data.BackupHelper.restore(ctx.applicationContext, uri, reporter)
                } catch (_: Exception) { false }
                bt.finish(ok)
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
    private val adapter = RowAdapter { pos -> keywordOptions(items[pos]) }

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
            adapter.submit(items.map { it.keyword to keywordSubtitle(it) })
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

    private fun keywordSubtitle(k: KeywordEntity): String {
        val numberCount = k.numbers.split(',', ';', '\n')
            .map { it.trim() }.count { it.isNotBlank() }
        val mode = when (k.mode) {
            1 -> getString(R.string.kw_sub_non_contacts)
            3 -> getString(R.string.kw_sub_only, numberCount)
            else -> getString(R.string.kw_sub_everyone) // 0 and legacy 2
        }
        val withAllow = if (k.mode != 3 && numberCount > 0)
            "$mode \u00B7 " + getString(R.string.kw_sub_allows, numberCount)
        else mode
        return if (k.caseSensitive) "$withAllow \u00B7 Aa" else withAllow
    }

    private fun keywordOptions(k: KeywordEntity) {
        val caseLabel = getString(
            if (k.caseSensitive) R.string.kw_case_on else R.string.kw_case_off
        )
        val options = arrayOf(
            getString(R.string.kw_mode_everyone),
            getString(R.string.kw_mode_non_contacts),
            getString(R.string.kw_mode_only),
            getString(R.string.kw_mode_except),
            caseLabel,
            getString(R.string.remove)
        )
        AlertDialog.Builder(this)
            .setTitle(k.keyword)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveKeyword(k.copy(mode = 0))
                    1 -> saveKeyword(k.copy(mode = 1))
                    2 -> pickNumbers(k, 3)
                    3 -> {
                        // ALLOWED numbers: an exception layered on the current
                        // mode (block-everyone or block-non-contacts stays);
                        // from block-specific mode, block-everyone is the base
                        val base = if (k.mode == 3) 0 else k.mode
                        pickNumbers(k, base)
                    }
                    4 -> saveKeyword(k.copy(caseSensitive = !k.caseSensitive))
                    5 -> confirmRemove(k)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickNumbers(k: KeywordEntity, mode: Int) {
        val current = k.numbers.split(',', ';', '\n')
            .map { it.trim() }.filter { it.isNotBlank() }
        io.github.theonionsarewatching.nova.ui.NumberListPicker.show(
            this,
            if (mode == 3) R.string.kw_mode_only else R.string.kw_mode_except,
            current
        ) { picked ->
            saveKeyword(k.copy(mode = mode, numbers = picked.joinToString(",")))
        }
    }

    private fun saveKeyword(k: KeywordEntity) {
        lifecycleScope.launch {
            repo.db.keywords().update(k)
            load()
        }
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
        val options = arrayOf(
            getString(R.string.view_message),
            getString(R.string.restore),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle(row.address + "  \u00B7  " + Formatters.listStamp(row.date))
                        .setMessage(row.body.ifBlank { getString(R.string.attach_vcard) })
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    else -> lifecycleScope.launch {
                        if (which == 1) {
                            repo.db.messages().unblock(row.id)
                            repo.refreshConversation(row.convoId)
                            ChangeBus.ping()
                        } else {
                            repo.deleteMessage(row.id)
                        }
                        load()
                    }
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
    private var lastSeenCode = -1
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
                binding.emptyLabel.text = getString(R.string.press_left_softkey) +
                    lastSeenLine() + "\n\n" + getString(R.string.softkey_trouble_hint)
                binding.btnAction.setText(R.string.skip)
                binding.btnAction.setOnClickListener { finishFlow(save = false) }
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction2.setText(R.string.enter_code_manually)
                binding.btnAction2.setOnClickListener { manualCodeDialog() }
            }
            Step.RIGHT -> {
                binding.emptyLabel.text = getString(R.string.press_right_softkey) + lastSeenLine()
                binding.btnAction.setText(R.string.start_over)
                binding.btnAction.setOnClickListener { restart() }
                binding.btnAction2.visibility = View.VISIBLE
                binding.btnAction2.setText(R.string.enter_code_manually)
                binding.btnAction2.setOnClickListener { manualCodeDialog() }
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

    /** Shows the code of the last key press, even rejected ones — this is how a
     *  user (or someone helping them over adb) can identify an unusual softkey. */
    private fun lastSeenLine(): String =
        if (lastSeenCode > 0) "\n\n" + getString(R.string.last_key_seen, lastSeenCode) else ""

    /** For phones whose softkeys aren't delivered normally: type the key code in. */
    private fun manualCodeDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.key_code_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_code_manually)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                if (code <= 0 || code in BLOCKED_KEYS) {
                    Toast.makeText(this, R.string.not_a_softkey, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                acceptCode(code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun acceptCode(code: Int) {
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
        val navKeys = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
        if (code != KeyEvent.KEYCODE_BACK && code !in navKeys) {
            lastSeenCode = code
            render()
        }
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
        acceptCode(code)
        return true
    }
}
