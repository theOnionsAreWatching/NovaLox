package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.telephony.PhoneStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.telephony.ServiceState
import android.telephony.TelephonyManager

/**
 * "Retry when service is available" for failed sends.
 *
 * Message ids are parked in prefs; a service-state listener (voice service,
 * not data — flip phones often have no data plan) plus an app-resume sweep
 * fire the retries. Ids are removed once retried or once the message is no
 * longer FAILED (sent some other way, deleted, and so on).
 */
object AutoRetry {

    private var listening = false
    private var listener: PhoneStateListener? = null

    fun pending(context: Context): Set<Long> =
        Prefs.get(context).autoRetryIds.mapNotNull { it.toLongOrNull() }.toSet()

    fun add(context: Context, messageId: Long) {
        val p = Prefs.get(context)
        p.autoRetryIds = p.autoRetryIds + messageId.toString()
        DiagLog.log(context, "sms-send", "auto-retry armed for msg=$messageId")
        register(context.applicationContext)
    }

    private fun remove(context: Context, messageId: Long) {
        val p = Prefs.get(context)
        p.autoRetryIds = p.autoRetryIds - messageId.toString()
    }

    /** Try every parked message that is still FAILED. Safe to call often. */
    fun sweep(context: Context) {
        val ids = pending(context)
        if (ids.isEmpty()) { unregister(context); return }
        val repo = io.github.theonionsarewatching.nova.data.Repo.get(context)
        repo.scope.launch(Dispatchers.IO) {
            for (id in ids) {
                val m = repo.db.messages().byId(id)
                if (m == null || m.status != io.github.theonionsarewatching.nova.data.MsgStatus.FAILED) {
                    remove(context, id); continue
                }
                DiagLog.log(context, "sms-send", "auto-retry firing for msg=$id")
                remove(context, id)
                try { repo.retry(id) } catch (_: Exception) {}
            }
            if (pending(context).isEmpty()) unregister(context)
        }
    }

    /** Start watching for service coming back. No-op when nothing is parked
     *  or the listener is already up; permission problems degrade to the
     *  resume-sweep path silently. */
    @Synchronized
    fun register(context: Context) {
        if (listening || pending(context).isEmpty()) return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(state: ServiceState?) {
                    if (state?.state == ServiceState.STATE_IN_SERVICE) sweep(context)
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE)
            listener = l
            listening = true
        } catch (_: Exception) { /* resume sweep still covers us */ }
    }

    @Synchronized
    private fun unregister(context: Context) {
        if (!listening) return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}
        listener = null
        listening = false
    }
}
