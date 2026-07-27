package io.github.theonionsarewatching.nova.util

import android.content.Context
import java.io.File

/**
 * A tiny on-device diagnostic log. Records crashes and key send/receive events
 * so a problem on a user's phone can be diagnosed from a saved file instead of
 * guesswork. Capped small; no network; saved only when the user asks.
 */
object DiagLog {

    private const val MAX_BYTES = 64 * 1024

    fun file(context: Context): File = File(context.filesDir, "novalox-log.txt")

    // writes happen off the calling thread — the diagnostics must not add
    // the very main-thread I/O jank they exist to find. Crashes still write
    // synchronously (the process is about to die; a posted write would be
    // lost).
    private val writer = android.os.HandlerThread("nova-diaglog")
        .apply { isDaemon = true; start() }
    private val writeHandler = android.os.Handler(writer.looper)

    fun log(context: Context, tag: String, message: String) {
        val app = context.applicationContext
        writeHandler.post { logSync(app, tag, message) }
    }

    @Synchronized
    fun logSync(context: Context, tag: String, message: String) {
        try {
            val f = file(context)
            val stamp = android.text.format.DateFormat.format(
                "MM-dd HH:mm:ss", System.currentTimeMillis()
            )
            f.appendText("[$stamp] $tag: $message\n")
            if (f.length() > MAX_BYTES) {
                // keep the newest half
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
        } catch (_: Exception) {
        }
    }

    /** Uncaught crashes land in the log before the app dies. */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logSync(appContext, "CRASH", "${thread.name}: " +
                    android.util.Log.getStackTraceString(throwable).take(4000))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
