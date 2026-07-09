package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ElementEntity
import io.github.theonionsarewatching.nova.data.ElementType
import io.github.theonionsarewatching.nova.util.Prefs

object ElementActions {

    fun label(context: Context, e: ElementEntity): String = when (e.type) {
        ElementType.PHONE -> context.getString(R.string.el_phone, e.value)
        ElementType.EMAIL -> context.getString(R.string.el_email, e.value)
        ElementType.URL -> context.getString(R.string.el_link, e.value.take(48))
        ElementType.ADDRESS -> context.getString(R.string.el_address, e.value.take(48))
        else -> e.value
    }

    fun copy(context: Context, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("D-SMS", value))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun canResolve(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /** Dialog title with a hairline underneath, separating it from the options list. */
    private fun titleWithDivider(activity: Activity, text: String): android.view.View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val title = android.widget.TextView(activity).apply {
            setText(text)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val line = android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(activity, R.color.divider)
            )
        }
        box.addView(title)
        box.addView(line)
        return box
    }

    fun show(activity: Activity, e: ElementEntity, onMessageNumber: ((String) -> Unit)? = null) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        when (e.type) {
            ElementType.PHONE -> {
                items += activity.getString(R.string.act_call) to {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + e.value)))
                    } catch (_: Exception) {}
                }
                if (onMessageNumber != null) {
                    items += activity.getString(R.string.act_message) to { onMessageNumber(e.value) }
                }
                items += activity.getString(R.string.act_copy) to { copy(activity, e.value) }
            }
            ElementType.EMAIL -> {
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + e.value))
                if (canResolve(activity, mail)) {
                    items += activity.getString(R.string.act_email) to {
                        try { activity.startActivity(mail) } catch (_: Exception) {}
                    }
                }
                items += activity.getString(R.string.act_copy) to { copy(activity, e.value) }
            }
            ElementType.URL -> {
                val url = if (e.value.startsWith("http", true)) e.value else "https://" + e.value
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val mode = Prefs.get(activity).linkOpening
                val hasBrowser = canResolve(activity, view)
                if (mode != "never" && hasBrowser) {
                    items += activity.getString(R.string.act_open_link) to {
                        if (mode == "ask") {
                            AlertDialog.Builder(activity)
                                .setMessage(activity.getString(R.string.open_link_confirm, url.take(80)))
                                .setPositiveButton(R.string.open) { _, _ ->
                                    try { activity.startActivity(view) } catch (_: Exception) {}
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            try { activity.startActivity(view) } catch (_: Exception) {}
                        }
                    }
                }
                items += activity.getString(R.string.act_copy) to { copy(activity, e.value) }
            }
            ElementType.ADDRESS -> {
                val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(e.value)))
                if (canResolve(activity, geo)) {
                    items += activity.getString(R.string.act_open_maps) to {
                        try { activity.startActivity(geo) } catch (_: Exception) {}
                    }
                }
                items += activity.getString(R.string.act_copy) to { copy(activity, e.value) }
            }
        }
        if (items.isEmpty()) {
            items += activity.getString(R.string.act_copy) to { copy(activity, e.value) }
        }
        AlertDialog.Builder(activity)
            .setCustomTitle(titleWithDivider(activity, label(activity, e)))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** One element: straight to its options. Several: list first. */
    fun showForMessage(activity: Activity, elements: List<ElementEntity>, onMessageNumber: ((String) -> Unit)? = null) {
        when {
            elements.isEmpty() -> {}
            elements.size == 1 -> show(activity, elements[0], onMessageNumber)
            else -> AlertDialog.Builder(activity)
                .setCustomTitle(titleWithDivider(activity, activity.getString(R.string.elements_title)))
                .setItems(elements.map { label(activity, it) }.toTypedArray()) { _, which ->
                    show(activity, elements[which], onMessageNumber)
                }
                .show()
        }
    }
}
