package io.github.theonionsarewatching.nova.util

import android.content.Context
import androidx.preference.PreferenceManager

/** On/off for showing incoming reaction texts as marks on the original message. */
object ReactionSettings {
    const val KEY = "show_reactions"

    fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY, true)
}
