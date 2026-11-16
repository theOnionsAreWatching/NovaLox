package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One long-running data task (backup / restore / re-import) that keeps going even
 * if the user leaves the screen. The progress dialog can detach ("run in
 * background") and re-attach later when the user returns to that Settings row.
 */
object BackgroundTasks {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false; private set
    @Volatile var titleRes = 0; private set
    @Volatile var percent = -1; private set
    @Volatile var detail: String? = null; private set

    private var appContext: Context? = null
    private var onUpdate: ((Int, String?) -> Unit)? = null
    private var onDone: ((Boolean) -> Unit)? = null
    @Volatile var toastOk = 0; private set
    @Volatile var toastFail = 0; private set
    /** Optional plain-English result text (e.g. where a backup was saved, or
     *  why it failed). Shown instead of the stock ok/fail text when set. */
    @Volatile var resultMessage: String? = null; private set
    /** True when the task put settings back: the screen should be rebuilt. */
    @Volatile var settingsApplied = false; private set

    /** Set by the Cancel button; the task checks it between batches. */
    @Volatile var cancelRequested = false; private set
    /** False once the task reached a step that must not be cut short. */
    @Volatile var cancellable = false
    /** True when the task stopped early because of Cancel. */
    @Volatile var wasCancelled = false; private set

    /** Asks the task to stop; false when it can't stop any more. */
    fun cancel(): Boolean {
        if (!running || !cancellable) return false
        cancelRequested = true
        return true
    }

    /** Returns false if another task is already running. */
    fun start(context: Context, title: Int, okRes: Int, failRes: Int): Boolean {
        if (running) return false
        appContext = context.applicationContext
        titleRes = title
        toastOk = okRes
        toastFail = failRes
        percent = -1
        detail = null
        resultMessage = null
        settingsApplied = false
        cancelRequested = false
        cancellable = false   // backup/restore turn this on after start()
        wasCancelled = false
        running = true
        return true
    }

    fun report(p: Int, d: String?) {
        percent = p
        if (d != null) detail = d
        val l = onUpdate ?: return
        main.post { l(p, d ?: detail) }
    }

    fun finish(ok: Boolean, message: String? = null, settingsWereApplied: Boolean = false,
               cancelled: Boolean = false) {
        wasCancelled = cancelled
        resultMessage = message
        settingsApplied = settingsWereApplied
        running = false
        val l = onDone
        val ctx = appContext
        main.post {
            if (l != null) {
                l(ok)
            } else if (ctx != null) {
                val res = if (ok) toastOk else toastFail
                if (message != null) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                else if (res != 0) io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(ctx, res, Toast.LENGTH_LONG).show()
            }
        }
        onUpdate = null
        onDone = null
    }

    /** A progress dialog subscribes here; detach(null, null) to run headless. */
    fun attach(update: ((Int, String?) -> Unit)?, done: ((Boolean) -> Unit)?) {
        onUpdate = update
        onDone = done
        if (update != null) main.post { update(percent, detail) }
    }
}
