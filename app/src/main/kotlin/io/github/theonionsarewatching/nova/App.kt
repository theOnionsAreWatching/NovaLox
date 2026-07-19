package io.github.theonionsarewatching.nova

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.notify.NotificationHelper

class App : Application(), ImageLoaderFactory {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSync: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // boot-aware components can spin this process up BEFORE the user
        // unlocks, when credential-protected storage (database, preferences,
        // files) is still locked. Do nothing in that state — the guarded
        // receivers ignore the event, and normal startup happens post-unlock.
        val um = getSystemService(android.os.UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        io.github.theonionsarewatching.nova.util.Formatters.init(this)
        io.github.theonionsarewatching.nova.util.DiagLog.installCrashHandler(this)
        NotificationHelper.createChannels(this)

        val repo = Repo.get(this)
        repo.cleanRecycleBin()
        repo.rescheduleAllAlarms()
        repo.runElementBacklog()

        // reconciliation safety net: another app wrote to the telephony store
        val telephonyObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                pendingSync?.let { handler.removeCallbacks(it) }
                val r = Runnable { Repo.get(this@App).syncRecentFromTelephony() }
                pendingSync = r
                handler.postDelayed(r, 2500)
            }
        }
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, telephonyObserver)
            contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, telephonyObserver)
        } catch (_: Exception) {}

        // contact rename/add: refresh the cached names (throttled inside)
        val contactsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Repo.get(this@App).refreshContactNames()
            }
        }
        try {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, contactsObserver
            )
        } catch (_: Exception) {}
    }

    /** Coil loader: disk/decode only, NO in-memory bitmap cache (low-RAM keypad devices). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                // animated GIFs in bubbles and the viewer
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
            .crossfade(false)
            .build()
}
