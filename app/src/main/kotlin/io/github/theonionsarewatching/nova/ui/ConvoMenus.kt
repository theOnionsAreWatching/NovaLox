package io.github.theonionsarewatching.nova.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.data.ChangeBus
import kotlinx.coroutines.launch

/**
 * The Notifications submenu, shared by BOTH conversation menus (list
 * long-press and in-thread options). One body means the menu-parity
 * invariant (INVARIANTS #10) is structural here, not a rule to remember.
 *
 * The Customize submenus deliberately stay in their activities: they look
 * alike but their guts (background targets, style/color dialogs) are
 * activity-bound — extracting the three-line shells would add indirection
 * without removing duplication.
 *
 * [onChanged] runs (in the launch scope) after every state change —
 * MainActivity pings the ChangeBus, ThreadActivity refreshes its convo
 * state. [onHidden] runs after Hide is confirmed (the thread finishes).
 */
object ConvoMenus {

    fun notifications(
        activity: BaseActivity,
        repo: Repo,
        c: ConversationEntity,
        onChanged: suspend () -> Unit,
        onHidden: () -> Unit = {}
    ) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (c.muted) activity.getString(R.string.unmute)
        else activity.getString(R.string.mute)) to {
            activity.lifecycleScope.launch { repo.setMuted(c.id, !c.muted); onChanged() }
        }
        items += (if (c.notifBlocked) activity.getString(R.string.unblock_notifications)
        else activity.getString(R.string.block_notifications)) to {
            activity.lifecycleScope.launch {
                repo.setNotifBlocked(c.id, !c.notifBlocked); onChanged()
            }
        }
        items += activity.getString(R.string.sound_and_vibration) to {
            SoundDialog.show(activity, c.id)
        }
        items += activity.getString(R.string.hide_conversation) to {
            AlertDialog.Builder(activity)
                .setMessage(R.string.hide_confirm)
                .setPositiveButton(R.string.hide) { _, _ ->
                    activity.lifecycleScope.launch {
                        repo.db.conversations().setHidden(c.id, true)
                        ChangeBus.ping()
                        onHidden()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        if (!c.isGroup) {
            val number = c.addressList().firstOrNull().orEmpty()
            val blocked = number.isNotBlank() && repo.isNumberBlocked(number)
            items += (if (blocked) activity.getString(R.string.unblock_number)
            else activity.getString(R.string.block_number)) to {
                if (blocked) {
                    activity.lifecycleScope.launch {
                        repo.unblockNumber(number)
                        android.widget.Toast.makeText(activity, R.string.number_unblocked,
                            android.widget.Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.block_number)
                        .setMessage(R.string.block_number_warning)
                        .setPositiveButton(R.string.block_number) { _, _ ->
                            activity.lifecycleScope.launch {
                                val systemOk = repo.blockNumber(number)
                                android.widget.Toast.makeText(activity,
                                    if (systemOk) R.string.number_blocked
                                    else R.string.number_blocked_local,
                                    android.widget.Toast.LENGTH_SHORT).show()
                                onChanged()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.block_and_menu)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }
}
