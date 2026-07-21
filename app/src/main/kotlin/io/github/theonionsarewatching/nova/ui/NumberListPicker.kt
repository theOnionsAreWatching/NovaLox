package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.ContactsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A "pick some numbers" dialog matching the new-message recipient flow: type a
 * number or name, matching contacts appear to tap, entries collect in a
 * removable list. Returns the chosen numbers via [onDone].
 */
object NumberListPicker {

    fun show(
        activity: Activity,
        titleRes: Int,
        initial: List<String>,
        onDone: (List<String>) -> Unit
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val chosen = ArrayList(initial.filter { it.isNotBlank() })
        var contacts: List<ContactsHelper.Contact> = emptyList()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.np_hint)
            setSingleLine(true)
        }
        column.addView(input)

        val suggestions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(suggestions)

        val listHeader = TextView(activity).apply {
            text = activity.getString(R.string.np_chosen)
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        }
        column.addView(listHeader)

        val chosenList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(chosenList)

        fun labelFor(number: String): String {
            val match = contacts.firstOrNull {
                it.number.filter { c -> c.isDigit() } == number.filter { c -> c.isDigit() } &&
                    number.any { c -> c.isDigit() }
            }
            return if (match != null) "${match.name}  ($number)" else number
        }

        fun refreshChosen() {
            chosenList.removeAllViews()
            listHeader.visibility = if (chosen.isEmpty()) View.GONE else View.VISIBLE
            for (num in chosen) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(activity).apply {
                    text = labelFor(num)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                row.addView(TextView(activity).apply {
                    text = "\u2715"  // ✕ remove
                    textSize = 16f
                    setPadding(dp(12), dp(2), dp(4), dp(2))
                    isFocusable = true
                    ThemeUtils.applyFocusHighlight(this)
                    setOnClickListener { chosen.remove(num); refreshChosen() }
                })
                chosenList.addView(row)
            }
        }

        fun addNumber(raw: String) {
            val v = raw.trim()
            if (v.isBlank()) return
            if (chosen.none { it.equals(v, ignoreCase = true) }) chosen.add(v)
            input.setText("")
            suggestions.removeAllViews()
            refreshChosen()
            input.requestFocus()
        }

        fun refreshSuggestions(q: String) {
            suggestions.removeAllViews()
            if (q.isBlank()) return
            val lower = q.lowercase()
            val qDigits = q.filter { it.isDigit() }
            val typedOk = q.trim().contains("@") || qDigits.length >= 3
            if (typedOk) {
                // the raw typed value is always addable, contact or not
                suggestions.addView(TextView(activity).apply {
                    text = activity.getString(R.string.add_number_row) + "  " + q.trim()
                    textSize = 14f
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isFocusable = true
                    ThemeUtils.applyFocusHighlight(this)
                    setOnClickListener { addNumber(q.trim()) }
                })
            }
            val matches = contacts.filter { c ->
                c.name.lowercase().contains(lower) ||
                    (qDigits.length >= 2 && c.number.filter { it.isDigit() }.contains(qDigits))
            }.take(4)
            for (m in matches) {
                suggestions.addView(TextView(activity).apply {
                    text = "${m.name}  ${m.number}"
                    textSize = 14f
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isFocusable = true
                    ThemeUtils.applyFocusHighlight(this)
                    setOnClickListener { addNumber(m.number) }
                })
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshSuggestions(s?.toString().orEmpty())
            }
        })

        // paste straight into the field when the clipboard holds something
        val clip = try {
            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(activity)?.toString()
        } catch (_: Exception) { null }
        if (!clip.isNullOrBlank()) {
            column.addView(TextView(activity).apply {
                text = activity.getString(R.string.paste) + ": " +
                    clip.take(30) + if (clip.length > 30) "\u2026" else ""
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isFocusable = true
                ThemeUtils.applyFocusHighlight(this)
                setOnClickListener { input.setText(clip.trim()); input.setSelection(input.length()) }
            }, 1)
        }

        refreshChosen()

        // contacts load off the main thread, then labels/suggestions refresh
        GlobalScope.launch(Dispatchers.Main) {
            contacts = withContext(Dispatchers.IO) { ContactsHelper.loadAll(activity) }
            refreshChosen()
            refreshSuggestions(input.text?.toString().orEmpty())
        }

        val scroll = ScrollView(activity).apply { addView(column) }
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ -> onDone(chosen.toList()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
