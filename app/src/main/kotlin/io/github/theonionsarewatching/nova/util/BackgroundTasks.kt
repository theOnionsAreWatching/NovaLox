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

    /** Returns false if another task is already running. */
    fun start(context: Context, title: Int, okRes: Int, failRes: Int): Boolean {
        if (running) return false
        appContext = context.applicationContext
        titleRes = title
        toastOk = okRes
        toastFail = failRes
        percent = -1
        detail = null
        running = true
        return true
    }

    fun report(p: Int, d: String?) {
        percent = p
        if (d != null) detail = d
        val l = onUpdate ?: return
        main.post { l(p, d ?: detail) }
    }

    fun finish(ok: Boolean) {
        running = false
        val l = onDone
        val ctx = appContext
        main.post {
            if (l != null) {
                l(ok)
            } else if (ctx != null) {
                val res = if (ok) toastOk else toastFail
                if (res != 0) Toast.makeText(ctx, res, Toast.LENGTH_LONG).show()
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
