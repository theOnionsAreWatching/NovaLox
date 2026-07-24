package io.github.theonionsarewatching.nova.ui

import android.content.Context
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.ConversationEntity
import io.github.theonionsarewatching.nova.data.Repo

/**
 * The single notification-state badge for a conversation.
 *
 * These states are mutually exclusive in practice — a blocked number delivers
 * nothing whose notification could be muted, and a muted thread never gets to
 * vibrate — so exactly one icon is shown, strongest first. The conversation
 * list and the thread's top bar both resolve it here so they can't drift.
 *
 * Returns 0 when the conversation has no special state.
 */
object ConvoStatusIcon {

    fun forConversation(context: Context, c: ConversationEntity): Int {
        val numberBlocked = !c.isGroup &&
            c.addressList().firstOrNull()?.let { Repo.get(context).isNumberBlocked(it) } == true
        return when {
            numberBlocked -> R.drawable.ic_blocked
            c.notifBlocked -> R.drawable.ic_notif_blocked
            c.muted -> R.drawable.ic_muted
            c.vibrateMode == 1 -> R.drawable.ic_vibrate
            else -> 0
        }
    }
}
