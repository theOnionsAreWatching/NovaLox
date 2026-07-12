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

    private const val LATEST_URL =
        "https://api.github.com/repos/theOnionsAreWatching/NovaLox/releases/latest"

    data class Release(val tag: String, val apkUrl: String)

    /** Blocking network call — run on Dispatchers.IO. Null = no update / unreachable. */
    fun checkLatest(currentVersionName: String): Release? {
        return try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank() || !isNewer(tag, currentVersionName)) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    return Release(tag, a.optString("browser_download_url"))
                }
            }
            null
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
