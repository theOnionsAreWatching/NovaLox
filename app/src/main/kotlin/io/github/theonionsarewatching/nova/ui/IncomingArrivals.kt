package io.github.theonionsarewatching.nova.ui

/** Durable, separate from outgoing/read/reload change notifications. */
object IncomingArrivals {
    @Synchronized fun record(context: android.content.Context) {
        val sp = context.getSharedPreferences("arrivals", android.content.Context.MODE_PRIVATE)
        sp.edit().putLong("generation", sp.getLong("generation", 0L) + 1L).apply()
    }
}
