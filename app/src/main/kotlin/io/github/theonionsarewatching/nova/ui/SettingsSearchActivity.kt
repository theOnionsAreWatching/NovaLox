package io.github.theonionsarewatching.nova.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.preference.PreferenceManager
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.ui.settings.SettingsActivity

/** A keypad-first setting index, independent of private message search. */
class SettingsSearchActivity : BaseActivity() {
    data class Entry(val xml: Int, val key: String, val title: String, val detail: String)
    private val entries = ArrayList<Entry>()
    private var openedSetting = false
    private var queryBeforeEdit = ""
    override fun onRestart() {
        super.onRestart()
        if (openedSetting) recreate()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("query", queryBeforeEdit)
        super.onSaveInstanceState(out)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val b = io.github.theonionsarewatching.nova.databinding.ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.searchInput.hint = "Find a setting"
        b.btnBack.visibility = View.GONE
        b.btnSearchGo.visibility = View.GONE
        val list = ListView(this).apply { divider = null }
        val parent = b.resultList.parent as android.view.ViewGroup
        parent.addView(list, android.view.ViewGroup.LayoutParams(-1, -1))
        b.resultList.visibility = View.GONE
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val ns = "http://schemas.android.com/apk/res/android"
        val screens = listOf(R.xml.preferences, R.xml.preferences_advanced, R.xml.preferences_advanced_display, R.xml.preferences_messages, R.xml.preferences_delivery, R.xml.preferences_message_color, R.xml.preferences_mms_advanced, R.xml.preferences_softkeys, R.xml.preferences_notifications, R.xml.preferences_appearance, R.xml.preferences_blocked, R.xml.preferences_storage, R.xml.preferences_backup)
        for (id in screens) resources.getXml(id).use { parser ->
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                val key = parser.getAttributeValue(ns, "key") ?: continue
                fun text(attr: String): String {
                    val res = parser.getAttributeResourceValue(ns, attr, 0)
                    return if (res != 0) runCatching { getString(res) }.getOrDefault("") else parser.getAttributeValue(ns, attr).orEmpty()
                }
                val title = text("title")
                if (title.isBlank() || key.startsWith("open_") || key == "about_footer") continue
                val stored = when (key) {
                    "theme" -> io.github.theonionsarewatching.nova.util.Prefs.get(this).theme
                    "accent_picker" -> sp.getString("accent", "system").orEmpty()
                    else -> sp.all[key]?.toString() ?: text("defaultValue")
                }
                val labels = parser.getAttributeResourceValue(ns, "entries", 0)
                val values = parser.getAttributeResourceValue(ns, "entryValues", 0)
                val value = if (key == "theme") {
                    val i = resources.getStringArray(R.array.theme_values).indexOf(stored)
                    resources.getStringArray(R.array.theme_entries).getOrNull(i) ?: stored
                } else if (labels != 0 && values != 0) {
                    val i = resources.getStringArray(values).indexOf(stored)
                    resources.getStringArray(labels).getOrNull(i) ?: stored
                } else when (stored) { "true" -> "On"; "false" -> "Off"; else -> stored }
                entries.add(Entry(id, key, title, listOf(value, text("summary").replace("%s", value)).filter { it.isNotBlank() }.distinct().joinToString(" — ")))
            }
        }
        var shown = emptyList<Entry>()
        fun search() {
            val q = b.searchInput.text.toString().trim()
            shown = entries.filter { q.isBlank() || (it.title + " " + it.detail).contains(q, true) }.distinctBy { it.key }
            list.adapter = object : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
                override fun getView(pos: Int, old: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(pos, old, parent)
                    v.findViewById<TextView>(android.R.id.text1).apply { text = shown[pos].title; textSize = 16f }
                    v.findViewById<TextView>(android.R.id.text2).apply { text = shown[pos].detail; textSize = 13f; maxLines = 3 }
                    v.background = ThemeUtils.dialogRowBackground(this@SettingsSearchActivity)
                    return v
                }
            }
            if (shown.isNotEmpty()) { list.requestFocus(); list.setSelection(0) }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = shown[pos]
            queryBeforeEdit = b.searchInput.text.toString()
            openedSetting = true
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.SettingsFragment.ARG_XML, e.xml)
                .putExtra("focus_preference", e.key))
        }
        b.searchInput.setOnEditorActionListener { _, _, _ -> search(); true }
        softkeys = Softkeys(this, b.softkeyBar).also { it.set("Back", "Select", "Search",
            onLeft = { finish() }, onCenter = { if (list.hasFocus() && list.selectedItemPosition >= 0) list.performItemClick(list.selectedView, list.selectedItemPosition, 0) else search() }, onRight = { search() }) }
        ThemeUtils.applyFocusHighlight(b.searchInput)
        val restored = state?.getString("query").orEmpty()
        if (restored.isNotEmpty()) { b.searchInput.setText(restored); search() }
        else b.searchInput.requestFocus()
    }
}
