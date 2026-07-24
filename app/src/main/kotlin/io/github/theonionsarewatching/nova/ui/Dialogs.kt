package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.theonionsarewatching.nova.R

object Dialogs {
    /** Long dialog text on tiny screens: guaranteed scrollable, never cut off. */
    fun scrollableMessage(activity: Activity, textRes: Int): View =
        scrollableMessageText(activity, activity.getString(textRes))

    fun scrollableMessageText(activity: Activity, text: CharSequence): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val scroll = android.widget.ScrollView(activity)
        val tv = TextView(activity).apply {
            setText(text)
            textSize = 14f
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        scroll.addView(tv)
        return scroll
    }

    /** Dialog title with a hairline underneath, separating it from the list. */
    fun title(activity: Activity, text: String): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            setText(text)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val line = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
        }
        box.addView(title)
        box.addView(line)
        return box
    }
}

/** "Participants (N)" for group conversations: names + numbers, with quick
 *  message-privately / call actions on each. */
object GroupParticipants {
    fun show(activity: BaseActivity, convo: io.github.theonionsarewatching.nova.data.ConversationEntity) {
        val addresses = convo.addressList()
        val names = convo.cachedNames.split("|")
        val labels = addresses.mapIndexed { i, addr ->
            val name = names.getOrNull(i)?.takeIf { it.isNotBlank() }
            if (name != null) "$name  ($addr)" else addr
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setCustomTitle(Dialogs.title(activity,
                activity.getString(R.string.participants_title, addresses.size)))
            .setItems(labels.toTypedArray()) { _, which ->
                val addr = addresses[which]
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(labels[which])
                    .setItems(arrayOf(
                        activity.getString(R.string.message_privately),
                        activity.getString(R.string.call)
                    )) { _, action ->
                        when (action) {
                            0 -> activity.lifecycleScope.launch {
                                val c = io.github.theonionsarewatching.nova.data.Repo
                                    .get(activity).getOrCreateConversation(listOf(addr))
                                activity.startActivity(
                                    android.content.Intent(activity, ThreadActivity::class.java)
                                        .putExtra(ThreadActivity.EXTRA_CONVO_ID, c.id)
                                )
                            }
                            1 -> try {
                                activity.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    android.net.Uri.parse("tel:$addr")
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
