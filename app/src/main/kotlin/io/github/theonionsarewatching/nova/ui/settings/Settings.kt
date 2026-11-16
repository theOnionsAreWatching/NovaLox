package io.github.theonionsarewatching.nova.ui.settings

import io.github.theonionsarewatching.nova.ui.DsmsAlertBuilder
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
import io.github.theonionsarewatching.nova.data.BackupHelper
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
        val binding = io.github.theonionsarewatching.nova.databinding.ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // small accent header, same on every Settings screen: the title is
        // the name of the row that opened it
        val titleRes = intent.getIntExtra(SettingsFragment.ARG_TITLE, 0)
        binding.settingsTitle.text = getString(if (titleRes != 0) titleRes else R.string.st_settings)
        binding.settingsTitle.setTextColor(ThemeUtils.accentColor(this))
        // soft keys like the rest of the app: act on key-up (Softkeys),
        // Back = up one level (this screen closes, the one below shows)
        softkeys = io.github.theonionsarewatching.nova.ui.Softkeys(this, binding.softkeyBar).also {
            it.set(getString(R.string.back), getString(R.string.softkey_select), null,
                onLeft = { finish() },
                onCenter = { currentFocus?.performClick() })
        }
        ThemeUtils.styleSoftkeyBar(binding.softkeyBar)
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
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.no_file_picker,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // dark-theme background picture: stored against its own pref so the
        // light-theme background is left alone
        if (requestCode == io.github.theonionsarewatching.nova.ui.ChatBackground.REQ_BG_DARK
            && resultCode == RESULT_OK
        ) {
            val uri = data?.data
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val dir = java.io.File(filesDir, "backgrounds").apply { mkdirs() }
                        val dest = java.io.File(dir, "bg_dark")
                        contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        if (dest.exists() && dest.length() > 0) {
                            io.github.theonionsarewatching.nova.util.Prefs
                                .get(this@SettingsActivity).darkChatBg = dest.absolutePath
                        }
                    } catch (_: Exception) {}
                }
            }
            return
        }
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
        companion object {
            const val ARG_XML = "xml_res"
            const val ARG_TITLE = "title_res"
            const val REQ_PICK_BACKUP = 302
            const val REQ_PICK_SETTINGS = 303
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val xmlRes = arguments?.getInt(ARG_XML)?.takeIf { it != 0 } ?: R.xml.preferences
            setPreferencesFromResource(xmlRes, rootKey)
            // Preserve explanations while restoring the current value preview.
            fun previews(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    val pref = group.getPreference(i)
                    val explanation = pref.summary?.toString().orEmpty().let {
                        if (pref is androidx.preference.ListPreference && (it == pref.entry?.toString() || it == "%s")) "" else it
                    }
                    when (pref) {
                        is androidx.preference.ListPreference -> pref.summaryProvider = Preference.SummaryProvider<androidx.preference.ListPreference> {
                            listOf(it.entry?.toString().orEmpty(), explanation).filter { t -> t.isNotBlank() }.distinct().joinToString(" — ")
                        }
                        is androidx.preference.TwoStatePreference -> if (pref.key != "save_to_sd") pref.summaryProvider = Preference.SummaryProvider<androidx.preference.TwoStatePreference> {
                            listOf(if (it.isChecked) "On" else "Off", explanation).filter { t -> t.isNotBlank() }.joinToString(" — ")
                        }
                    }
                    if (!pref.isSelectable && pref.summary != null && pref !is androidx.preference.PreferenceCategory) {
                        // Bottom help must be reachable by keypad, not stranded below
                        // the last focusable setting. OK opens a scrollable full copy.
                        pref.isSelectable = true
                        pref.setOnPreferenceClickListener {
                            DsmsAlertBuilder(requireContext())
                                .setTitle(pref.title ?: "Help")
                                .setView(io.github.theonionsarewatching.nova.ui.Dialogs.scrollableMessageText(requireActivity(), pref.summary.toString()))
                                .setPositiveButton(android.R.string.ok, null).show()
                            true
                        }
                    }
                    if (pref is androidx.preference.PreferenceGroup) previews(pref)
                }
            }
            previews(preferenceScreen)
            findPreference<Preference>("open_search")?.summary = "Find a setting and see its current value"
            findPreference<Preference>("open_notifications")?.summary = "Sound, vibration, clear notifications"
            findPreference<Preference>("open_messages")?.summary = "SMS, MMS, drafts and reports"
            findPreference<Preference>("open_appearance")?.summary = "Theme, size, colours and backgrounds"
            findPreference<Preference>("open_keys")?.summary = "Softkeys, Send key and keypad help"
            findPreference<Preference>("open_storage")?.summary = "SIM messages, saved files and retention"


            find("open_keywords") { startActivity(Intent(requireContext(), KeywordsActivity::class.java)) }
            find("open_blocked") { startActivity(Intent(requireContext(), BlockedMessagesActivity::class.java)) }
            find("open_sim_messages") { startActivity(Intent(requireContext(), io.github.theonionsarewatching.nova.ui.SimMessagesActivity::class.java)) }
            find("open_bin") { startActivity(Intent(requireContext(), RecycleBinActivity::class.java)) }
            find("open_hidden") { startActivity(Intent(requireContext(), HiddenConversationsActivity::class.java)) }
            find("open_softkeys") { startActivity(Intent(requireContext(), SoftkeyConfigActivity::class.java)) }
            // every sub-screen is its own activity (the fragment backstack
            // left the restored list unfocusable for the D-pad); hardware
            // Back / left soft key close it = up one level
            fun openXml(xml: Int, title: Int) = startActivity(
                Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsFragment.ARG_XML, xml)
                    .putExtra(SettingsFragment.ARG_TITLE, title)
            )
            find("open_notifications") { openXml(R.xml.preferences_notifications, R.string.st_notifications) }
            find("open_messages") { openXml(R.xml.preferences_messages, R.string.st_messages) }
            find("open_appearance") { openXml(R.xml.preferences_appearance, R.string.st_appearance) }
            find("open_keys") { openXml(R.xml.preferences_softkeys, R.string.st_keys) }
            find("open_blocked_hidden") { openXml(R.xml.preferences_blocked, R.string.st_blocked) }
            find("open_storage") { openXml(R.xml.preferences_storage, R.string.st_storage) }
            find("open_backup") { openXml(R.xml.preferences_backup, R.string.st_backup) }
            find("open_advanced") { openXml(R.xml.preferences_advanced, R.string.st_advanced) }
            find("open_carrier") { openXml(R.xml.preferences_mms_advanced, R.string.st_carrier) }
            find("open_delivery") { openXml(R.xml.preferences_delivery, R.string.st_reports) }
            find("open_advanced_display") { openXml(R.xml.preferences_advanced_display, R.string.st_more_display) }
            find("open_message_color") { openXml(R.xml.preferences_message_color, R.string.st_message_colors) }
            find("clear_mms_cache") {
                val ctx = requireContext()
                DsmsAlertBuilder(ctx)
                    .setTitle(R.string.st_clear_cache)
                    .setMessage(R.string.clear_cache_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.github.theonionsarewatching.nova.data.Repo.get(ctx).clearMmsCache()
                            }
                            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.clear_cache_done, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            // Keep per chat: one list over the SAME two saved keys as the
            // old switch + number (auto_delete_old / auto_delete_keep), so a
            // user's setting, backups and settings files carry over as-is
            find("keep_per_chat") { showKeepPerChat() }
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
            findPreference<Preference>("vibrate_mode")
                ?.setOnPreferenceChangeListener { _, _ ->
                    // channels embed the vibrate flag; rebuild them after the pref lands
                    view?.post {
                        io.github.theonionsarewatching.nova.notify.NotificationHelper
                            .refreshAllChannels(requireContext())
                    }
                    true
                }
            find("open_search") {
                startActivity(Intent(requireContext(), io.github.theonionsarewatching.nova.ui.SettingsSearchActivity::class.java))
            }
            find("backup_now") {
                if (io.github.theonionsarewatching.nova.util.BackgroundTasks.running) {
                    attachTaskDialog(); return@find
                }
                // what to back up; the file goes straight to D-SMS/Backups (Saver)
                // (no "save as" picker: it is missing or awkward on keypad ROMs)
                val choices = arrayOf(
                    getString(R.string.backup_sms_only),
                    getString(R.string.backup_mms_only),
                    getString(R.string.backup_both)
                )
                DsmsAlertBuilder(requireContext())
                    .setTitle(R.string.backup_what)
                    .setItems(choices) { _, which ->
                        val content = when (which) {
                            0 -> BackupHelper.Content.SMS
                            1 -> BackupHelper.Content.MMS
                            else -> BackupHelper.Content.ALL
                        }
                        backupToDownloads(content)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            find("restore_backup") {
                if (io.github.theonionsarewatching.nova.util.BackgroundTasks.running) {
                    attachTaskDialog(); return@find
                }
                // usual places -> whole phone -> "Could not find it" / Select manually
                startRestoreSearch(io.github.theonionsarewatching.nova.data.BackupHelper.FileKind.BACKUP)
            }
            find("save_settings") {
                val ctx = requireContext().applicationContext
                lifecycleScope.launch {
                    val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.theonionsarewatching.nova.data.BackupHelper.saveSettingsToDownloads(ctx)
                    }
                    if (!isAdded) return@launch
                    DsmsAlertBuilder(requireContext())
                        .setMessage(out.message ?: getString(
                            if (out.ok) R.string.settings_saved else R.string.err_unknown))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            find("restore_settings") {
                if (io.github.theonionsarewatching.nova.util.BackgroundTasks.running) {
                    attachTaskDialog(); return@find
                }
                // usual places -> whole phone -> "Could not find it" / Select manually
                startRestoreSearch(io.github.theonionsarewatching.nova.data.BackupHelper.FileKind.SETTINGS)
            }
            find("reimport") {
                DsmsAlertBuilder(requireContext())
                    .setTitle(R.string.st_rebuild)
                    .setView(io.github.theonionsarewatching.nova.ui.Dialogs.scrollableMessage(
                        requireActivity(), R.string.reimport_warning))
                    .setPositiveButton(R.string.st_rebuild) { _, _ ->
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
                    DsmsAlertBuilder(requireContext())
                        .setTitle(R.string.st_app_size)
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
            find("key_guide") { showKeyGuide() }
            find("about_footer") {
                // OK opens About (version, license, key guide). Never wired
                // before, so Settings had no way to show it.
                showAboutDetails()
                // seven taps in a row, each within 3 seconds of the last
                val now = System.currentTimeMillis()
                if (now - lastAboutTap > 3000L) aboutTaps = 0
                lastAboutTap = now
                aboutTaps++
                if (aboutTaps >= 7) {
                    aboutTaps = 0
                    // themed like every other toast
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                        requireContext(), getString(R.string.onions_toast), Toast.LENGTH_LONG
                    ).apply { setGravity(android.view.Gravity.CENTER, 0, 0) }.show()
                }
            }
            // About: app name + version (credit/license in the details pop-up).
            // Version comes from the installed package (= versionName in
            // app/build.gradle.kts), so it always matches the build.
            findPreference<androidx.preference.Preference>("about_footer")?.let { pref ->
                val v = try {
                    requireContext().packageManager
                        .getPackageInfo(requireContext().packageName, 0).versionName
                } catch (_: Exception) { "" }
                // short, one line: app name + version (details on OK)
                pref.title = getString(R.string.st_about)
                pref.summary = getString(R.string.about_app_name) + " " + v
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
                        DsmsAlertBuilder(requireContext())
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
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(act, R.string.sent_color_set, Toast.LENGTH_SHORT).show()
                    }
                ) { hex ->
                    io.github.theonionsarewatching.nova.util.Prefs.get(act).sentColor = hex
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(act, R.string.sent_color_set, Toast.LENGTH_SHORT).show()
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
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(act, R.string.incoming_color_set, Toast.LENGTH_SHORT).show()
                    }
                ) { hex ->
                    io.github.theonionsarewatching.nova.util.Prefs.get(act).incomingColor = hex
                    ChangeBus.ping()
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(act, R.string.incoming_color_set, Toast.LENGTH_SHORT).show()
                }
            }
            find("export_contacts") {
                val ctx = requireContext()
                DsmsAlertBuilder(ctx)
                    .setTitle(R.string.pref_export_contacts)
                    .setMessage(R.string.export_contacts_confirm)
                    .setPositiveButton(R.string.export_go) { _, _ ->
                        io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.exporting_contacts, Toast.LENGTH_SHORT).show()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val vcf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.github.theonionsarewatching.nova.util.ContactVcf.exportAll(ctx)
                            }
                            if (vcf.isBlank()) {
                                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.export_contacts_empty, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date())
                            val loc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 
                                val tmp = java.io.File(ctx.cacheDir, "contacts-export.vcf")
                                tmp.writeText(vcf)
                                val r = io.github.theonionsarewatching.nova.ui.Saver.save(ctx, tmp,
                                    "contacts-$stamp.vcf", "text/x-vcard",
                                    io.github.theonionsarewatching.nova.ui.Saver.Kind.CONTACTS)
                                tmp.delete()
                                r
                            }
                            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx,
                                if (loc != null) ctx.getString(R.string.export_contacts_done, loc)
                                else ctx.getString(R.string.export_contacts_failed),
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
                    val loc = if (!f.exists()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.theonionsarewatching.nova.ui.Saver
                        .save(ctx, f, "novalox-log.txt", "text/plain",
                            io.github.theonionsarewatching.nova.ui.Saver.Kind.LOGS) }
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx,
                        if (loc != null) ctx.getString(R.string.log_saved, loc)
                        else ctx.getString(R.string.log_empty),
                        Toast.LENGTH_LONG).show()
                }
            }
            find("check_updates") {
                val ctx = requireContext()
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.checking_updates, Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val current = try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
                    } catch (_: Exception) { "0" }
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.theonionsarewatching.nova.util.UpdateChecker.check(current)
                    }
                    when (result) {
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.UpToDate ->
                            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx,
                                getString(R.string.up_to_date_detail, current, result.latestTag),
                                Toast.LENGTH_LONG).show()
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.Failed ->
                            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                        is io.github.theonionsarewatching.nova.util.UpdateChecker.Check.UpdateAvailable -> {
                            val release = result.release
                            DsmsAlertBuilder(ctx)
                                .setTitle(getString(R.string.update_available, release.tag))
                                .setMessage(R.string.update_prompt)
                                .setPositiveButton(R.string.download) { _, _ ->
                                    io.github.theonionsarewatching.nova.util.UpdateChecker.download(ctx, release)
                                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, R.string.update_downloading, Toast.LENGTH_LONG).show()
                                }
                                .setNegativeButton(R.string.later, null)
                                .show()
                        }
                    }
                }
            }
            find("resync") {
                Repo.get(requireContext()).syncRecentFromTelephony()
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(requireContext(), R.string.resync_started, Toast.LENGTH_SHORT).show()
            }
            find("refresh_contacts") {
                Repo.get(requireContext()).refreshContactNames(force = true)
                io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(requireContext(), R.string.contacts_refreshing, Toast.LENGTH_SHORT).show()
            }

            // restart-sensitive prefs: recreate the settings screen so the change is visible
            for (key in listOf("layout_direction", "ui_scale", "focus_stroke")) {
                findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    view?.post { activity?.recreate() }
                    true
                }
            }
            // theme: apply the new light/dark mode at once. AppCompat then
            // rebuilds EVERY open screen (this one included) when the mode
            // really changes; the generation bump makes any screen it missed
            // rebuild when it comes back to the front.
            find("theme") { showThemePicker() }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // rows carry their own accent focus outline; no grey lines
            setDivider(null)
            val pad = (4 * resources.displayMetrics.density).toInt()
            listView.setPadding(0, pad, 0, pad)
            listView.clipToPadding = false
            // smooth D-pad glide, no flicker animations
            listView.layoutManager = io.github.theonionsarewatching.nova.ui.SteadyLinearLayoutManager(requireContext())
            listView.itemAnimator = null
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
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(requireContext(),
                        getString(R.string.setting_updated_fmt, title), Toast.LENGTH_SHORT).show()
                }
            }

        override fun onResume() {
            preferenceManager.sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(changeToast)
            super.onResume()
            activity?.intent?.getStringExtra("focus_preference")?.let { key ->
                view?.post {
                    scrollToPreference(key)
                    val pos = (listView.adapter as? androidx.preference.PreferenceGroup.PreferencePositionCallback)
                        ?.getPreferenceAdapterPosition(key) ?: -1
                    if (pos >= 0) listView.post { listView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                }
                activity?.intent?.removeExtra("focus_preference")
            }
            // back from the all-files access screen during a restore search
            resumePendingWholeSearch()
            updateAccentSummary()
            updateThemeSummary()
            updateToneSummary()
            updateKeepSummary()
            updateBackupSummaries()
            // "Save to SD card" is only selectable while a card is mounted;
            // re-checked here so inserting/removing a card takes effect on
            // return without reopening settings. The pref VALUE is left alone —
            // the save path falls back to phone storage when the card is gone
            // and resumes SD saving when it comes back.
            findPreference<androidx.preference.SwitchPreferenceCompat>("save_to_sd")?.apply {
                val present = io.github.theonionsarewatching.nova.ui.Saver
                    .sdCardPresent(requireContext())
                isEnabled = present
                summary = getString(
                    if (present) R.string.pref_save_to_sd_summary
                    else R.string.pref_save_to_sd_none
                )
            }
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
                Triple("gray", getString(R.string.accent_gray_name), c(R.color.accent_gray)),
                Triple("amber", getString(R.string.accent_amber_name), c(R.color.accent_amber)),
                Triple("gold", getString(R.string.accent_gold_name), c(R.color.accent_gold)),
                Triple("coral", getString(R.string.accent_coral_name), c(R.color.accent_coral)),
                Triple("crimson", getString(R.string.accent_crimson_name), c(R.color.accent_crimson)),
                Triple("rose", getString(R.string.accent_rose_name), c(R.color.accent_rose)),
                Triple("violet", getString(R.string.accent_violet_name), c(R.color.accent_violet)),
                Triple("indigo", getString(R.string.accent_indigo_name), c(R.color.accent_indigo)),
                Triple("sky", getString(R.string.accent_sky_name), c(R.color.accent_sky)),
                Triple("cyan", getString(R.string.accent_cyan_name), c(R.color.accent_cyan)),
                Triple("mint", getString(R.string.accent_mint_name), c(R.color.accent_mint)),
                Triple("lime", getString(R.string.accent_lime_name), c(R.color.accent_lime)),
                Triple("olive", getString(R.string.accent_olive_name), c(R.color.accent_olive)),
                Triple("brown", getString(R.string.accent_brown_name), c(R.color.accent_brown)),
                Triple("slate", getString(R.string.accent_slate_name), c(R.color.accent_slate))
            )
        }

        /** (value, label, dot colour, page colour) for every theme. */
        private fun themeOptions(): List<Pair<String, String>> {
            val labels = resources.getStringArray(R.array.theme_entries)
            val values = resources.getStringArray(R.array.theme_values)
            return values.indices.map { values[it] to labels[it] }
        }

        private fun themeDot(v: String): Pair<Int, Int> = when (v) {
            // (fill, ring): page colour of the theme, ring = its surface/text
            "light" -> 0xFFF4F4F6.toInt() to 0xFF9E9E9E.toInt()
            "dark" -> 0xFF2A2A2E.toInt() to 0xFF9E9E9E.toInt()
            "graphite" -> 0xFF191F27.toInt() to 0xFFB6C3D3.toInt()
            "dusk" -> 0xFF24212C.toInt() to 0xFFC5B7CE.toInt()
            "linen" -> 0xFFF6F2EA.toInt() to 0xFF6C6357.toInt()
            else -> 0xFF9E9E9E.toInt() to 0xFF616161.toInt()   // follow system
        }

        private fun updateThemeSummary() {
            val cur = io.github.theonionsarewatching.nova.util.Prefs.get(requireContext()).theme
            findPreference<Preference>("theme")?.summary =
                themeOptions().firstOrNull { it.first == cur }?.second ?: cur
        }

        /** Theme picker: a dot in each theme's colours, opens on the current
         *  one. Applies at once: AppCompat rebuilds every screen when light/
         *  dark changes; the generation bump rebuilds the rest. */
        private fun showThemePicker() {
            val ctx = requireContext()
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(ctx)
            val options = themeOptions()
            val checked = options.indexOfFirst { it.first == prefs.theme }.coerceAtLeast(0)
            val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
            val adapter = object : android.widget.ArrayAdapter<Pair<String, String>>(ctx, 0, options) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val item = options[position]
                    val row = (convertView as? android.widget.LinearLayout)
                        ?: android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            addView(View(ctx).apply {
                                layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22))
                                    .apply { marginEnd = dp(16) }
                            })
                            addView(android.widget.TextView(ctx).apply { textSize = 16f; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) })
                        }
                    val (fill, ring) = themeDot(item.first)
                    row.getChildAt(0).background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(fill); setStroke(dp(2), ring)
                    }
                    (row.getChildAt(1) as android.widget.TextView).text =
                        if (position == checked) "${item.second}  \u2713" else item.second
                    ThemeUtils.applyDialogRowFocusGroup(row)
                    return row
                }
            }
            DsmsAlertBuilder(ctx)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs.title(
                    requireActivity(), getString(R.string.pref_theme)))
                .setAdapter(adapter) { _, which ->
                    val v = options[which].first
                    if (v == prefs.theme) return@setAdapter
                    prefs.sp.edit().putString("theme", v).apply()
                    val act = requireActivity()
                    io.github.theonionsarewatching.nova.ui.ThemeUtils.bumpThemeGeneration()
                    val before = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
                    io.github.theonionsarewatching.nova.ui.ThemeUtils.applyNightModeValue(v)
                    if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == before) act.recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { it.window?.decorView?.setTag(R.id.dsms_default_row, checked) }
        }

        private fun updateAccentSummary() {
            val current = io.github.theonionsarewatching.nova.util.Prefs.get(requireContext()).accent
            findPreference<Preference>("accent_picker")?.summary =
                accentOptions().firstOrNull { it.first == current }?.second ?: current
        }

        /** Accent picker with a color swatch beside each name. */
        private var aboutTaps = 0
        private var lastAboutTap = 0L

        private fun showAboutDetails() {
            val ctx = requireContext()
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) { "" }
            val msg = getString(R.string.about_details, version) + "\n\n" + getString(R.string.keys_help)
            val view = io.github.theonionsarewatching.nova.ui.Dialogs
                .scrollableMessageText(requireActivity(), msg)
            // D-pad: the text takes focus so UP/DOWN scroll it
            view.isFocusable = true
            DsmsAlertBuilder(ctx)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs
                    .title(requireActivity(), getString(R.string.about_app_name)))
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

        /** Settings > About > Key guide: the scroll keys, D-pad scrollable. */
        private fun showKeyGuide() {
            val view = io.github.theonionsarewatching.nova.ui.Dialogs
                .scrollableMessageText(requireActivity(), getString(R.string.keys_help))
            view.isFocusable = true
            DsmsAlertBuilder(requireContext())
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs
                    .title(requireActivity(), getString(R.string.key_guide_title)))
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            view.requestFocus()
        }

        /** Backup & restore subtitles: the real folder in use right now
         *  (top of storage, Download/D-SMS, or the SD card). */
        private fun updateBackupSummaries() {
            val ctx = context ?: return
            val S = io.github.theonionsarewatching.nova.ui.Saver
            findPreference<Preference>("backup_now")?.summary =
                getString(R.string.st_backup_now_sum, S.currentFolder(ctx, io.github.theonionsarewatching.nova.ui.Saver.Kind.BACKUPS))
            findPreference<Preference>("save_settings")?.summary =
                getString(R.string.st_save_settings_sum, S.currentFolder(ctx, io.github.theonionsarewatching.nova.ui.Saver.Kind.SETTINGS))
            findPreference<Preference>("export_contacts")?.summary =
                getString(R.string.st_export_contacts_sum, S.currentFolder(ctx, io.github.theonionsarewatching.nova.ui.Saver.Kind.CONTACTS))
        }

        private val keepCounts = intArrayOf(100, 500, 1000, 2000, 5000)

        /** "All messages" when auto-delete is off, else "Last N messages". */
        private fun updateKeepSummary() {
            val pref = findPreference<Preference>("keep_per_chat") ?: return
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(requireContext())
            pref.summary = if (!prefs.autoDeleteOld) getString(R.string.st_keep_all)
                else getString(R.string.st_keep_n, prefs.autoDeleteKeep)
        }

        /** Keep per chat: All / 100 … 5000 / Other. Reads and writes the old
         *  keys (auto_delete_old + auto_delete_keep): All = off, a number =
         *  on with that count. The cursor starts on the current choice. */
        private fun showKeepPerChat() {
            val ctx = requireContext()
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(ctx)
            val on = prefs.autoDeleteOld
            val keep = prefs.autoDeleteKeep
            val custom = on && keep !in keepCounts
            val labels = ArrayList<String>()
            labels.add(getString(R.string.st_keep_all))
            keepCounts.forEach { labels.add(getString(R.string.st_keep_n, it)) }
            labels.add(if (custom) getString(R.string.st_keep_other_n, keep) else getString(R.string.st_keep_other))
            val last = labels.size - 1
            val checked = when {
                !on -> 0
                custom -> last
                else -> keepCounts.indexOf(keep) + 1
            }
            DsmsAlertBuilder(ctx)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs.title(
                    requireActivity(), getString(R.string.st_keep)))
                .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                    d.dismiss()
                    when (which) {
                        checked -> if (which == last) askKeepCount(keep)
                        0 -> { prefs.autoDeleteOld = false; keepChanged() }
                        last -> askKeepCount(keep)
                        else -> applyKeep(keepCounts[which - 1])
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun keepChanged() {
            updateKeepSummary()
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(requireContext(),
                getString(R.string.setting_updated_fmt, getString(R.string.st_keep)),
                Toast.LENGTH_SHORT).show()
        }

        /** Turning it on warns first (old messages are deleted for good). */
        private fun applyKeep(n: Int) {
            val ctx = requireContext()
            val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(ctx)
            if (prefs.autoDeleteOld) {
                prefs.autoDeleteKeep = n
                keepChanged()
                askApplyNow(n)
                return
            }
            DsmsAlertBuilder(ctx)
                .setTitle(R.string.st_keep)
                .setMessage(R.string.auto_delete_warning)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // count first, then on: never enforce the old count
                    prefs.autoDeleteKeep = n
                    prefs.autoDeleteOld = true
                    keepChanged()
                    askApplyNow(n)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun askKeepCount(current: Int) {
            val ctx = requireContext()
            val input = android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(current.toString())
                setSelection(text.length)
            }
            DsmsAlertBuilder(ctx)
                .setTitle(R.string.auto_delete_count_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = input.text.toString().toIntOrNull()?.coerceAtLeast(1)
                        ?: return@setPositiveButton
                    applyKeep(n)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Same "Apply now?" step as before: trims long chats right away. */
        private fun askApplyNow(keep: Int) {
            val ctx = requireContext()
            DsmsAlertBuilder(ctx)
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
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            addView(View(ctx).apply {
                                layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22))
                                    .apply { marginEnd = dp(16) }
                            })
                            addView(android.widget.TextView(ctx).apply { textSize = 16f; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) })  // Settings-size text
                        }
                    val swatch = row.getChildAt(0)
                    val label = row.getChildAt(1) as android.widget.TextView
                    swatch.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(item.third)
                    }
                    label.text = if (position == checked) "${item.second}  \u2713" else item.second
                    ThemeUtils.applyDialogRowFocusGroup(row)
                    return row
                }
            }

            DsmsAlertBuilder(ctx)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs.title(
                    requireActivity(), getString(R.string.pref_accent)))
                .setAdapter(adapter) { _, which ->
                    prefs.sp.edit().putString("accent", options[which].first).apply()
                    io.github.theonionsarewatching.nova.ui.ThemeUtils.bumpThemeGeneration()
                    requireActivity().recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                // cursor starts on the chosen color (DialogPolish.startFocus)
                .also { it.window?.decorView?.setTag(R.id.dsms_default_row, checked) }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            val uri = data?.data ?: return
            if (resultCode != android.app.Activity.RESULT_OK) return
            when (requestCode) {
                // 301 (system "save as") is no longer started; kept for a
                // result that arrives after an app update
                301 -> runBackupTask(exporting = true, uri = uri)
                REQ_PICK_BACKUP -> confirmRestore(BackupHelper.FileKind.BACKUP, uri, displayName(uri))
                REQ_PICK_SETTINGS -> confirmRestore(BackupHelper.FileKind.SETTINGS, uri, displayName(uri))
            }
        }

        private fun displayName(uri: android.net.Uri): String {
            try {
                requireContext().contentResolver.query(uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
            } catch (_: Exception) {}
            return uri.lastPathSegment ?: uri.toString()
        }

        private fun backupToDownloads(
            content: io.github.theonionsarewatching.nova.data.BackupHelper.Content
        ) {
            val ctx = requireContext()
            val bt = io.github.theonionsarewatching.nova.util.BackgroundTasks
            if (bt.running) { attachTaskDialog(); return }
            if (!bt.start(ctx, R.string.backing_up, R.string.backup_done, R.string.backup_failed)) return
            bt.cancellable = true
            attachTaskDialog()
            bt.scope.launch {
                val out = try {
                    io.github.theonionsarewatching.nova.data.BackupHelper
                        .exportToDownloads(ctx.applicationContext, content) { p, d ->
                            if (bt.cancelRequested) throw io.github.theonionsarewatching.nova.data.BackupHelper.Cancelled()
                            bt.report(p, d)
                        }
                } catch (e: io.github.theonionsarewatching.nova.data.BackupHelper.Cancelled) {
                    io.github.theonionsarewatching.nova.data.BackupHelper.Outcome(true,
                        ctx.getString(R.string.backup_cancelled), cancelled = true)
                } catch (e: Throwable) {
                    io.github.theonionsarewatching.nova.data.BackupHelper.Outcome(false,
                        io.github.theonionsarewatching.nova.data.BackupHelper
                            .reasonFor(ctx.applicationContext, e, reading = false))
                }
                bt.finish(out.ok, out.message, cancelled = out.cancelled)
            }
        }

        /** Like openAllFilesAccess, but reports whether the screen opened. */
        private fun openAllFilesAccessOk(): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 30) return false
            val ctx = requireContext()
            val app = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName))
            val all = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            for (i in listOf(app, all)) {
                try { startActivity(i); return true } catch (_: Exception) {}
            }
            return false
        }

        private fun openAllFilesAccess() {
            if (android.os.Build.VERSION.SDK_INT < 30) return
            val ctx = requireContext()
            val app = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName))
            val all = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            for (i in listOf(app, all)) {
                try { startActivity(i); return } catch (_: Exception) {}
            }
            DsmsAlertBuilder(ctx)
                .setMessage(R.string.no_access_screen)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // ---------------- restore: find the file ----------------
        // 1) usual places (D-SMS/Backups or Settings, old Download/D-SMS, Download) -> list + "Select manually"
        // 2) none: whole-phone search (needs all-files access on Android 11+;
        //    "Allow access" is offered first, refusing skips to 3)
        // 3) still none: "Could not find it" -> Select manually / Cancel

        /** Kind waiting for the user to come back from the all-files access screen. */
        private var pendingWholeSearch: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind? = null

        private fun startRestoreSearch(kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { BackupHelper.findLocal(ctx, kind) } catch (_: Exception) { null }
                }
                if (!isAdded) return@launch
                if (found != null && found.files.isNotEmpty()) showFoundList(kind, found.files)
                else askAccessThenSearchPhone(kind)
            }
        }

        private fun showFoundList(
            kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind,
            files: List<BackupHelper.LocalBackup>
        ) {
            if (!isAdded) return
            val title = if (kind == BackupHelper.FileKind.BACKUP) R.string.pick_backup else R.string.pick_settings_file
            // name, with the folder underneath when it's not the usual one
            val labels = files.map { f ->
                if (f.where.isBlank() || f.where.startsWith("Download")) f.displayName
                else f.displayName + "\n" + f.where
            } + getString(R.string.select_manually)
            DsmsAlertBuilder(requireContext())
                .setTitle(title)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < files.size) confirmRestore(kind, files[which].uri, files[which].displayName)
                    else browseFor(kind)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun askAccessThenSearchPhone(kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind) {
            if (!isAdded) return
            val needAccess = android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            if (!needAccess) { searchWholePhone(kind); return }
            DsmsAlertBuilder(requireContext())
                .setTitle(R.string.search_phone_title)
                .setMessage(R.string.search_phone_need_access)
                .setPositiveButton(R.string.allow_access) { _, _ ->
                    pendingWholeSearch = kind
                    if (!openAllFilesAccessOk()) { pendingWholeSearch = null; showNotFound(kind) }
                }
                .setNegativeButton(R.string.skip) { _, _ -> showNotFound(kind) }
                // BACK = refused too: still offer manual selection
                .setOnCancelListener { showNotFound(kind) }
                .show()
        }

        /** Called from onResume: back from the all-files access screen. */
        private fun resumePendingWholeSearch() {
            val kind = pendingWholeSearch ?: return
            pendingWholeSearch = null
            val granted = android.os.Build.VERSION.SDK_INT < 30 ||
                android.os.Environment.isExternalStorageManager()
            if (granted) searchWholePhone(kind) else showNotFound(kind)
        }

        private fun searchWholePhone(kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind) {
            if (!isAdded) return
            val act = requireActivity()
            val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
            // fixed height: the two count lines are reserved, and the
            // current folder is ONE middle-cut line, so the pop-up never resizes
            val status = android.widget.TextView(act).apply {
                setPadding(dp(22), dp(4), dp(22), 0)
                textSize = 14f
                setLines(2)
                text = getString(R.string.search_progress, 0, 0)
            }
            val curPath = android.widget.TextView(act).apply {
                setPadding(dp(22), 0, dp(22), dp(8))
                textSize = 12f
                isSingleLine = true
                setLines(1)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                textDirection = android.view.View.TEXT_DIRECTION_LTR   // a path
                text = " "
            }
            val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                val tint = android.content.res.ColorStateList.valueOf(
                    io.github.theonionsarewatching.nova.ui.ThemeUtils.accentColor(act))
                indeterminateTintList = tint
                setPadding(dp(22), 0, dp(22), 0)
            }
            val box = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(bar)
                addView(status)
                addView(curPath)
            }
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val dialog = DsmsAlertBuilder(act)
                .setCustomTitle(io.github.theonionsarewatching.nova.ui.Dialogs.title(
                    act, getString(R.string.searching_whole_phone)))
                .setView(box)
                .setNegativeButton(android.R.string.cancel) { _, _ -> stop.set(true) }
                .setOnCancelListener { stop.set(true) }   // BACK
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            val ctx = act.applicationContext
            lifecycleScope.launch {
                var lastUi = 0L
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        BackupHelper.searchWholePhone(ctx, kind, { stop.get() }) { dirs, found, cur ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastUi > 250) {
                                lastUi = now
                                status.post {
                                    if (isAdded) {
                                        status.text = getString(R.string.search_progress, dirs, found)
                                        curPath.text = cur.ifEmpty { " " }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { emptyList() }
                }
                if (!isAdded) return@launch
                try { dialog.dismiss() } catch (_: Exception) {}
                if (stop.get()) return@launch
                if (result.isNotEmpty()) showFoundList(kind, result) else showNotFound(kind)
            }
        }

        private fun showNotFound(kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind) {
            if (!isAdded) return
            val what = getString(if (kind == BackupHelper.FileKind.BACKUP) R.string.what_backups
                else R.string.what_settings_files)
            DsmsAlertBuilder(requireContext())
                .setTitle(R.string.could_not_find_it)
                .setMessage(getString(R.string.not_found_on_phone, what))
                .setPositiveButton(R.string.select_manually) { _, _ -> browseFor(kind) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Restore entry point: the system file picker when the phone has
         *  one (opening in the D-SMS backup folder where supported);
         *  otherwise the app's own keypad file browser, starting there. */
        private fun browseFor(kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind) {
            @Suppress("DEPRECATION")
            val downloads = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            // first existing folder of: D-SMS/<Backups|Settings> at the top,
            // under Download, SD card, then the old Download/D-SMS
            val sk = if (kind == BackupHelper.FileKind.BACKUP)
                io.github.theonionsarewatching.nova.ui.Saver.Kind.BACKUPS
                else io.github.theonionsarewatching.nova.ui.Saver.Kind.SETTINGS
            val ourDir = io.github.theonionsarewatching.nova.ui.Saver.lookIn(requireContext(), sk)
                .map { it.first }.firstOrNull { it.isDirectory }
                ?: java.io.File(downloads, BackupHelper.FOLDER)
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                // start in that folder (Android 8+; pickers that don't
                // support it just open their usual place)
                @Suppress("DEPRECATION")
                val top = android.os.Environment.getExternalStorageDirectory().absolutePath
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val docId = if (ourDir.isDirectory && ourDir.absolutePath.startsWith("$top/"))
                        "primary:" + ourDir.absolutePath.removePrefix("$top/")
                        else "primary:" + android.os.Environment.DIRECTORY_DOWNLOADS
                    try {
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            android.provider.DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", docId))
                    } catch (_: Exception) {}
                }
            }
            val req = if (kind == BackupHelper.FileKind.BACKUP) REQ_PICK_BACKUP else REQ_PICK_SETTINGS
            val hasPicker = try {
                i.resolveActivity(requireContext().packageManager) != null
            } catch (_: Exception) { false }
            if (hasPicker) {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, req)
                    return
                } catch (_: Exception) { /* fall through to our own browser */ }
            }
            // no system picker: our own browser. On Android 11+ it can only
            // see files from an earlier install with all-files access, so
            // offer that first when it is off.
            val needAccess = android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            if (needAccess) {
                DsmsAlertBuilder(requireContext())
                    .setMessage(R.string.all_files_access_off)
                    .setPositiveButton(R.string.allow_access) { _, _ -> openAllFilesAccess() }
                    .setNeutralButton(R.string.browse_anyway) { _, _ -> openOwnBrowser(kind, ourDir, downloads) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else openOwnBrowser(kind, ourDir, downloads)
        }

        private fun openOwnBrowser(
            kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind,
            ourDir: java.io.File, downloads: java.io.File
        ) {
            if (!isAdded) return
            // settings can also be read from a backup zip
            val suffixes = if (kind == BackupHelper.FileKind.BACKUP) listOf(".zip") else listOf(".json", ".zip")
            io.github.theonionsarewatching.nova.ui.FileBrowserDialog.show(requireActivity(), suffixes,
                onPick = { uri, name -> if (isAdded) confirmRestore(kind, uri, name) },
                start = if (ourDir.isDirectory) ourDir else downloads)
        }

        private fun confirmRestore(
            kind: io.github.theonionsarewatching.nova.data.BackupHelper.FileKind,
            uri: android.net.Uri, name: String
        ) {
            if (!isAdded) return
            if (kind == BackupHelper.FileKind.BACKUP) {
                val text = getString(R.string.restore_confirm_file, name) + "\n\n" +
                    getString(R.string.restore_warning)
                DsmsAlertBuilder(requireContext())
                    .setTitle(R.string.restore_title)
                    .setView(io.github.theonionsarewatching.nova.ui.Dialogs.scrollableMessageText(
                        requireActivity(), text))
                    .setPositiveButton(R.string.restore) { _, _ -> runBackupTask(exporting = false, uri = uri) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                DsmsAlertBuilder(requireContext())
                    .setTitle(R.string.pref_restore_settings)
                    .setMessage(getString(R.string.restore_settings_confirm, name))
                    .setPositiveButton(R.string.restore) { _, _ -> restoreSettingsFrom(uri) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun restoreSettingsFrom(uri: android.net.Uri) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.theonionsarewatching.nova.data.BackupHelper.restoreSettings(ctx, uri)
                }
                if (!isAdded) return@launch
                val act = requireActivity()
                DsmsAlertBuilder(act)
                    .setMessage(out.message ?: getString(
                        if (out.ok) R.string.settings_restored else R.string.err_unknown))
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener {
                        // rebuild the screen so theme / sizes / keys take effect
                        if (out.ok && !act.isFinishing) act.recreate()
                    }
                    .show()
            }
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
            bt.cancellable = true
            attachTaskDialog()
            bt.scope.launch {
                val reporter = io.github.theonionsarewatching.nova.data.BackupHelper.Progress { p, d ->
                    if (exporting && bt.cancelRequested) throw BackupHelper.Cancelled()
                    bt.report(p, d)
                }
                val out = try {
                    if (exporting) BackupHelper.export(ctx.applicationContext, uri, BackupHelper.Content.ALL, reporter)
                    else BackupHelper.restore(ctx.applicationContext, uri, reporter,
                        cancelled = { bt.cancelRequested }, stopNotAllowed = { bt.cancellable = false })
                } catch (e: BackupHelper.Cancelled) {
                    BackupHelper.Outcome(true, ctx.getString(R.string.backup_cancelled), cancelled = true)
                } catch (e: Throwable) {
                    BackupHelper.Outcome(false, BackupHelper.reasonFor(ctx.applicationContext, e, reading = !exporting))
                }
                bt.finish(out.ok, out.message, out.settingsApplied, out.cancelled)
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
        binding.list.layoutManager = io.github.theonionsarewatching.nova.ui.SteadyLinearLayoutManager(this)
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
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
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
            items = io.github.theonionsarewatching.nova.data.NotesChat.filterOff(this@HiddenConversationsActivity, repo.db.conversations().hiddenList())
            adapter.submit(items.map { it.displayTitle() to it.snippet.take(80) })
            setEmpty(items.isEmpty())
        }
    }

    private fun rowOptions(c: ConversationEntity) {
        val options = arrayOf(getString(R.string.open), getString(R.string.unhide))
        DsmsAlertBuilder(this)
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
        DsmsAlertBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> DsmsAlertBuilder(this)
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
            hint = getString(R.string.code_short_hint)
        }
        DsmsAlertBuilder(this)
            .setTitle(R.string.enter_code_manually)
            .setView(io.github.theonionsarewatching.nova.ui.Dialogs.inputPanel(this, input, getString(R.string.key_code_hint)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                if (code <= 0 || code in BLOCKED_KEYS) {
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.not_a_softkey, Toast.LENGTH_SHORT).show()
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
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.same_key_twice, Toast.LENGTH_SHORT).show()
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
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.softkeys_saved, Toast.LENGTH_SHORT).show()
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
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(this, R.string.not_a_softkey, Toast.LENGTH_SHORT).show()
            return true
        }
        acceptCode(code)
        return true
    }
}
