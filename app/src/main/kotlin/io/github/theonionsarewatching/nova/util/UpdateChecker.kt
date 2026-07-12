package io.github.theonionsarewatching.nova.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub releases feed for a newer signed build and downloads it with
 * the system download manager. No Google services needed; works offline-gracefully.
 */
object UpdateChecker {

    private const val REPO = "theOnionsAreWatching/NovaLox"

    data class Release(val tag: String, val apkUrl: String)

    sealed class Check {
        data class UpdateAvailable(val release: Release) : Check()
        data class UpToDate(val latestTag: String) : Check()
        object Failed : Check()
    }

    /** Blocking network call — run on Dispatchers.IO.
     *  Primary path uses the release page's REDIRECT (no API, no rate limit —
     *  the API allows only 60 requests/hour per IP, and users behind a shared
     *  filtering proxy exhaust that together). API JSON is the fallback. */
    fun check(currentVersionName: String): Check {
        val tag = latestTagViaRedirect() ?: latestTagViaAtom() ?: latestTagViaApi()
            ?: return Check.Failed
        return if (isNewer(tag, currentVersionName)) {
            Check.UpdateAvailable(Release(tag, apkUrlFor(tag)))
        } else {
            Check.UpToDate(tag)
        }
    }

    /** Our release workflow names the asset deterministically. */
    private fun apkUrlFor(tag: String): String =
        "https://github.com/$REPO/releases/download/v$tag/NovaLox-v$tag.apk"

    private fun tagFromUrl(url: String?): String? =
        url?.takeIf { it.contains("/releases/tag/") }
            ?.substringAfter("/releases/tag/")?.trim('/')?.substringBefore('?')
            ?.removePrefix("v")?.takeIf { it.isNotBlank() }

    /** github.com/<repo>/releases/latest 302-redirects to /releases/tag/vX.Y.Z.
     *  Some filtering proxies follow the redirect THEMSELVES and hand us the final
     *  page as a 200 — in that case the final URL still carries the tag. */
    private fun latestTagViaRedirect(): String? {
        return try {
            val conn = URL("https://github.com/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "NovaLox-Updater")
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            val finalUrl = conn.url?.toString()
            conn.disconnect()
            tagFromUrl(location) ?: if (code == 200) tagFromUrl(finalUrl) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Releases Atom feed: plain XML, no rate limit — third path for odd proxies. */
    private fun latestTagViaAtom(): String? {
        return try {
            val conn = URL("https://github.com/$REPO/releases.atom")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NovaLox-Updater")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            Regex("/releases/tag/(v?[0-9][^\"'<]*)").find(body)
                ?.groupValues?.get(1)?.removePrefix("v")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun latestTagViaApi(): String? {
        return try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "NovaLox-Updater")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Dotted-number comparison: 0.9.1 > 0.9.0 > 0.8.12 etc. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".", "-")
            .mapNotNull { it.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Download via the system's download manager (visible in the notification
     *  shade) and prompt the install screen when it lands. */
    fun download(context: Context, release: Release) {
        try {
            val fileName = "NovaLox-v${release.tag}.apk"
            val req = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle(fileName)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)

            // when it finishes, hand the APK to the installer
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                    runCatching { ctx.unregisterReceiver(this) }
                    try {
                        val uri = dm.getUriForDownloadedFile(id) ?: return
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        })
                    } catch (_: Exception) {
                        // fall back to the system Downloads notification
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.applicationContext.registerReceiver(
                    receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.applicationContext.registerReceiver(
                    receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (_: Exception) {
        }
    }
}
