package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Performance instrumentation for the "opening a thread freezes" problem.
 *
 * Everything here answers a specific question with a measurement instead of a
 * hypothesis:
 *
 *  - `perf load`    where the open time actually goes (DB query / row build /
 *                   adapter notify / first frame), plus how many pictures are
 *                   in the page and how many BYTES of image they represent.
 *  - `perf refresh` how often the live-tail refresh fires and what it costs.
 *                   A storm of these (each rebuilding every row) is a prime
 *                   freeze candidate, and the count makes it undeniable.
 *  - `perf STALL`   the main thread stopped responding for this many ms.
 *                   This is the freeze itself, caught in the act.
 *
 * The log is the capped on-device DiagLog: Settings → Save diagnostic log.
 */
object Perf {

    fun log(context: Context, tag: String, message: String) =
        DiagLog.log(context, "perf", "$tag: $message")

    /** Phase breakdown for the initial page, plus the image census. */
    fun logLoad(
        context: Context,
        rows: List<io.github.theonionsarewatching.nova.ui.MessageRow>,
        tQuery: Long, tBuild: Long, tNotify: Long, t0: Long
    ) {
        var images = 0
        var bytes = 0L
        rows.forEach { r ->
            r.parts.forEach { p ->
                if (p.mimeType.startsWith("image/") || p.mimeType.startsWith("video/")) {
                    images++
                    bytes += try { File(p.filePath).length() } catch (_: Exception) { 0L }
                }
            }
        }
        log(
            context, "load",
            "rows=${rows.size} media=$images mediaBytes=${bytes / 1024}KB " +
                "query=${tQuery}ms build=${tBuild}ms notify=${tNotify}ms " +
                "total=${System.currentTimeMillis() - t0}ms"
        )
    }

    /** Live-tail refresh: cost AND frequency (the storm shows up as a count). */
    private var refreshCount = 0
    private var refreshWindowStart = 0L

    fun logRefresh(
        context: Context, rowCount: Int, requested: Int,
        tQuery: Long, tBuild: Long, tDiff: Long, t0: Long
    ) {
        val now = System.currentTimeMillis()
        if (now - refreshWindowStart > 10_000) {
            refreshWindowStart = now
            refreshCount = 0
        }
        refreshCount++
        log(
            context, "refresh",
            "#$refreshCount/10s rows=$rowCount requested=$requested " +
                "query=${tQuery}ms build=${tBuild}ms diff=${tDiff}ms " +
                "total=${now - t0}ms"
        )
    }

    /**
     * Main-thread stall watchdog. The main thread updates a heartbeat stamp
     * every 100ms; a background thread checks it every 500ms. If the stamp is
     * stale the main thread is blocked RIGHT NOW — so its stack trace is
     * captured mid-stall and written to the log. That stack names the exact
     * blocking call; no interpretation needed. Recovery logs the total time.
     */
    @Volatile private var lastBeat = 0L
    private var watchdogStarted = false

    fun startWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        lastBeat = SystemClock.uptimeMillis()
        lateinit var beat: Runnable
        beat = Runnable {
            lastBeat = SystemClock.uptimeMillis()
            main.postDelayed(beat, 100L)
        }
        main.postDelayed(beat, 100L)

        Thread {
            var inStall = false
            var stallStart = 0L
            while (true) {
                try { Thread.sleep(500L) } catch (_: InterruptedException) { return@Thread }
                val stale = SystemClock.uptimeMillis() - lastBeat
                if (stale >= 1000L && !inStall) {
                    inStall = true
                    stallStart = lastBeat
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(16)
                        .joinToString("\n") { "      at $it" }
                    log(app, "STALL", "main blocked ${stale}ms so far — stack:\n$stack")
                } else if (stale < 300L && inStall) {
                    inStall = false
                    log(
                        app, "STALL",
                        "recovered — total ${SystemClock.uptimeMillis() - stallStart}ms"
                    )
                }
            }
        }.apply { name = "nova-watchdog"; isDaemon = true }.start()
    }
}
