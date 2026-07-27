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
     * Main-thread stall watchdog. A heartbeat is posted every 250ms; if it
     * comes back late, the main thread was blocked for that long — which is
     * exactly what "the app freezes" means. Logs at 500ms and above so normal
     * jitter stays out of the file.
     */
    private var watchdogStarted = false

    fun startWatchdog(context: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val interval = 250L
        var expected = SystemClock.uptimeMillis() + interval
        lateinit var beat: Runnable
        beat = Runnable {
            val now = SystemClock.uptimeMillis()
            val late = now - expected
            if (late >= 500) {
                log(app, "STALL", "main thread blocked ${late + interval}ms")
            }
            expected = now + interval
            handler.postDelayed(beat, interval)
        }
        handler.postDelayed(beat, interval)
    }
}
