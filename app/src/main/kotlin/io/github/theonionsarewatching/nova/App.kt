package io.github.theonionsarewatching.nova

import android.app.Application
import kotlinx.coroutines.launch
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
        // catches the freeze in the act: logs whenever the main thread stops
        // responding, with the duration
        io.github.theonionsarewatching.nova.util.Perf.startWatchdog(this)
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            // main-thread disk/DB work is the usual cause of a UI stall — have
            // the platform name the offender instead of us guessing
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
        }
        // boot-aware components can spin this process up BEFORE the user
        // unlocks, when credential-protected storage (database, preferences,
        // files) is still locked. Do nothing in that state — the guarded
        // receivers ignore the event, and normal startup happens post-unlock.
        val um = getSystemService(android.os.UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        // set the app's light/dark mode before ANY screen is created
        try { io.github.theonionsarewatching.nova.ui.ThemeUtils.applyNightMode(this) } catch (_: Exception) {}
        io.github.theonionsarewatching.nova.util.Formatters.init(this)
        // soft keys work from the first start on the TCL Flip 2 (no setup)
        try { io.github.theonionsarewatching.nova.util.SoftkeyAuto.applyIfNeeded(this) } catch (_: Exception) {}
        io.github.theonionsarewatching.nova.data.Repo.get(this).backfillPartDims()
        io.github.theonionsarewatching.nova.util.MmsUserAgent.applyToConfig(this)
        // pre-0.9.41 conversations stored "[Photo]"-style snippets; recompute
        // them once so the new italic words show everywhere
        val prefs0 = io.github.theonionsarewatching.nova.util.Prefs.get(this)
        // Resize labels moved one step (the old "90%" look is
        // now "100%", the new default). Saved values keep the same real size,
        // so a saved 90% simply reads 100% now. Someone upgrading who never
        // picked a size had the old default (real 1.0, now "110%"): write it
        // down so their look doesn't change. Fresh installs get the new default.
        if (!prefs0.sp.getBoolean("zoom_labels_v2", false)) {
            val upgrading = prefs0.sp.contains("bg_legacy_normalized")
            val e = prefs0.sp.edit()
            if (upgrading && !prefs0.sp.contains("app_zoom")) e.putString("app_zoom", "1.0")
            e.putBoolean("zoom_labels_v2", true).commit()
        }
        // Size now only 80%..120%: snap a saved value outside the list
        prefs0.sp.getString("app_zoom", null)?.let { z ->
            val snapped = io.github.theonionsarewatching.nova.util.ZoomSteps.snapZoom(z)
            if (snapped != z) prefs0.sp.edit().putString("app_zoom", snapped).commit()
        }
        // an older version stored the LIGHT app-default background as a
        // literal near-white hex; with dark="same as light" that near-white
        // then carried into dark theme forever. Normalize the legacy
        // constants back to true app default, once.
        if (!prefs0.sp.getBoolean("bg_legacy_normalized", false)) {
            val g = prefs0.chatBg(-1L).trim().uppercase()
            if (g == "#F7F7F7" || g == "#F2F2F3") {
                prefs0.setChatBg(-1L, "")
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    this, "theme", "migrated legacy global light bg '$g' -> app default"
                )
            }
            prefs0.sp.edit().putBoolean("bg_legacy_normalized", true).apply()
        }
        if (!prefs0.phantomsPurged) {
            val repoP = io.github.theonionsarewatching.nova.data.Repo.get(this)
            repoP.scope.launch {
                try {
                    repoP.purgePhantomPlaceholders()
                    prefs0.phantomsPurged = true
                } catch (_: Exception) {}
            }
        }
        if (!prefs0.snippetWordsFixed) {
            val repo0 = io.github.theonionsarewatching.nova.data.Repo.get(this)
            repo0.scope.launch {
                try {
                    repo0.refreshAllConversationSummaries()
                    prefs0.snippetWordsFixed = true
                } catch (_: Exception) {}
            }
        }
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

        // Photo changes may keep the SAME URI. Debounce provider notifications,
        // but do not drop the final change behind the normal 60-second throttle.
        val refreshContacts = Runnable { Repo.get(this@App).refreshContactNames(force = true) }
        val contactsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(refreshContacts)
                handler.postDelayed(refreshContacts, 400L)
            }
        }
        try {
            contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI, true, contactsObserver
            )
        } catch (_: Exception) {}
    }

    /** Coil loader: disk/decode only, NO in-memory bitmap cache (low-RAM keypad devices). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(io.github.theonionsarewatching.nova.ui.ContactPhotoFetcher.Factory())
                add(io.github.theonionsarewatching.nova.ui.MediaThumbnailFetcher.Factory())
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
