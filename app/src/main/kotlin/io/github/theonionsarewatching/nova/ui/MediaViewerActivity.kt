package io.github.theonionsarewatching.nova.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.request.videoFrameMillis
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.PartEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityMediaBinding
import io.github.theonionsarewatching.nova.databinding.ItemMediaPageBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * the picture viewer, redone.
 *
 * - The picture fills the whole screen (status bar hidden). It is decoded
 *   from the original file at up to 4x the screen size, and drawn through
 *   a matrix ([ZoomImageView]), so it is sharp at every zoom step.
 * - No big bars: a thin see-through counter strip on top and the soft-key
 *   strip at the bottom float over the picture and fade out after 2.5 s.
 *   Any key brings them back; while hidden, a soft-key press only shows
 *   them (so the labels are seen before acting).
 * - Keys (actions on key-up): * zoom in, # zoom out, OK zoom 2x / back.
 *   Not zoomed: Left / Right = previous / next picture. Zoomed: the D-pad
 *   pans (on key-down, repeats while held). BACK leaves zoom first, then
 *   the viewer.
 * - Soft keys: left "All" (choose a picture from a list, when there are
 *   several; else Back), center Play/Pause for videos, right "Save".
 */
class MediaViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_CONVO_ID = "convo_id"
        const val EXTRA_PART_ID = "part_id"
    }

    private lateinit var binding: ActivityMediaBinding
    private lateinit var repo: Repo
    private var parts: List<PartEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repo.get(this)

        val convoId = intent.getLongExtra(EXTRA_CONVO_ID, -1L)
        val partId = intent.getLongExtra(EXTRA_PART_ID, -1L)

        binding.pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        // keep the neighbours ready: Left / Right shows the next picture at once
        binding.pager.offscreenPageLimit = 1
        goFullScreen()

        // the soft-key strip is see-through over the picture: white labels
        // with a soft shadow read on any photo (the viewer stays dark on
        // purpose, whatever the app theme)
        val bar = binding.softkeyBar
        bar.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        for (t in listOf(bar.softLeft, bar.softCenter, bar.softRight)) {
            t.setTextColor(android.graphics.Color.WHITE)
            t.setShadowLayer(3f * resources.displayMetrics.density, 0f, 0f, android.graphics.Color.BLACK)
            t.gravity = android.view.Gravity.CENTER
        }
        softkeys = Softkeys(this, binding.softkeyBar)

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                stopPlayback()
                // a picture left zoomed is shown at its start size next time
                resetOtherPages()
                updateCounter(position)
                updateMediaSoftkeys()
            }
        })

        lifecycleScope.launch {
            parts = repo.db.parts().mediaForConvo(convoId)
            if (parts.isEmpty()) { finish(); return@launch }
            binding.pager.adapter = PageAdapter(parts) { showBarsBriefly() }
            val start = parts.indexOfFirst { it.id == partId }.coerceAtLeast(0)
            binding.pager.setCurrentItem(start, false)
            updateCounter(start)
            updateMediaSoftkeys()
        }
    }

    // ---------------- thin auto-hiding bars ----------------
    private val barsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var barsShown = true
    private val hideBarsRunnable = Runnable { setBars(false) }
    private var revealSwallowCode = 0
    private var revealAt = 0L

    private fun goFullScreen() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        scheduleHideBars()
    }

    private fun setBars(show: Boolean) {
        barsShown = show
        for (v in listOf(binding.mediaTopBar, binding.mediaBottomBar)) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(120).start()
            } else {
                v.animate().alpha(0f).setDuration(250)
                    .withEndAction { if (!barsShown) v.visibility = View.INVISIBLE }.start()
            }
        }
    }

    private fun scheduleHideBars() {
        barsHandler.removeCallbacks(hideBarsRunnable)
        // a playing video keeps its progress strip up
        barsHandler.postDelayed(hideBarsRunnable, 2500)
    }

    private fun showBarsBriefly() {
        if (!barsShown) setBars(true)
        scheduleHideBars()
    }

    private fun isRevealOnlyKey(code: Int): Boolean = code != KeyEvent.KEYCODE_BACK && (
        code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT ||
            code == KeyEvent.KEYCODE_SOFT_RIGHT ||
            (code == prefs.softkeyLeftCode && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) ||
            (code == prefs.softkeyRightCode && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER))

    override fun onDestroy() {
        barsHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateCounter(position: Int) {
        val p = parts.getOrNull(position)
        val iv = if (p?.isImage() == true) currentImageView() else null
        // the zoom level shows next to the counter while zoomed
        val zoom = if (iv != null && (iv.isZoomedIn || iv.percent < 100))
            "  \u00B7  " + getString(R.string.viewer_zoom_pct, iv.percent) else ""
        binding.mediaCounter.text = getString(R.string.media_counter, position + 1, parts.size) + zoom
        binding.mediaName.text = p?.fileName ?: ""
    }

    private fun currentPart(): PartEntity? = parts.getOrNull(binding.pager.currentItem)

    /** id of the part whose video session is active (playing OR paused) */
    private var activeVideoPartId = -1L

    /** the live player behind the VideoView — needed for PRECISE seeking:
     *  default seeks snap BACKWARD to the previous keyframe, so a forward hop
     *  smaller than the keyframe gap lands where it started (looked dead) */
    private var activeMediaPlayer: android.media.MediaPlayer? = null

    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updateVideoProgress()
            if (isVideoPlaying()) progressHandler.postDelayed(this, 500)
        }
    }

    private fun fmtTime(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun updateVideoProgress() {
        val vv = currentVideoView()
        if (vv == null || activeVideoPartId == -1L) {
            binding.videoProgressRow.visibility = View.GONE
            return
        }
        try {
            val dur = vv.duration
            if (dur <= 0) return
            binding.videoProgressRow.visibility = View.VISIBLE
            binding.videoSeekBar.max = dur
            binding.videoSeekBar.progress = vv.currentPosition.coerceIn(0, dur)
            binding.videoElapsed.text = fmtTime(vv.currentPosition)
            binding.videoTotal.text = fmtTime(dur)
        } catch (_: Exception) {}
    }

    private fun startProgressTicker() {
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

    private fun currentVideoView(): android.widget.VideoView? {
        val p = currentPart() ?: return null
        if (!p.isVideo()) return null
        return binding.pager.findViewWithTag("video_${p.id}")
    }

    private fun isVideoPlaying(): Boolean =
        try { currentVideoView()?.isPlaying == true } catch (_: Exception) { false }


    /** Soft keys: left "All" (several pictures) or Back, center Play/Pause
     *  for videos, right Save. */
    private fun updateMediaSoftkeys(forcePlaying: Boolean? = null) {
        val p = currentPart()
        val playing = forcePlaying ?: isVideoPlaying()
        val center = when {
            p != null && p.isVideo() && playing -> getString(R.string.pause)
            p != null && p.isVideo() -> getString(R.string.play)
            else -> ""
        }
        val several = parts.size > 1
        softkeys?.set(
            if (several) getString(R.string.viewer_all) else getString(R.string.back),
            center, getString(R.string.save),
            onLeft = { if (several) chooseMedia() else finish() },
            onCenter = { if (currentPart()?.isVideo() == true) playCurrent() },
            onRight = { saveCurrent() },
            onMenu = { viewerOptions() }
        )
        // the viewer's bar shows even with softkeys off
        binding.softkeyBar.root.visibility =
            if (io.github.theonionsarewatching.nova.util.Prefs.get(this).touchMode)
                View.GONE else View.VISIBLE
        updateCounter(binding.pager.currentItem)
    }

    /** "All": pick which picture / video to show, as a themed list with
     *  small previews; the current one is preselected. */
    private fun chooseMedia() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val labels = parts.mapIndexed { i, p ->
            getString(R.string.media_counter, i + 1, parts.size) +
                (if (p.isVideo()) "  \u25B6" else "")
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = parts.size
            override fun getItem(i: Int) = parts[i]
            override fun getItemId(i: Int) = parts[i].id
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val row = (convert as? android.widget.LinearLayout) ?: android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(3), dp(12), dp(3))
                    background = androidx.core.content.ContextCompat.getDrawable(parent.context, R.drawable.bg_dialog_item)
                    addView(android.widget.ImageView(parent.context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(android.widget.TextView(parent.context).apply {
                        textSize = 15f
                        setPadding(dp(10), 0, 0, 0)
                        androidx.core.content.ContextCompat.getColorStateList(parent.context, R.color.dialog_item_text)
                            ?.let { setTextColor(it) }
                    })
                }
                val p = parts[i]
                (row.getChildAt(0) as android.widget.ImageView).load(File(p.filePath)) {
                    size(dp(40), dp(40))
                    if (p.isVideo()) videoFrameMillis(1000)
                }
                (row.getChildAt(1) as android.widget.TextView).text = labels[i]
                return row
            }
        }
        val d = DsmsAlertBuilder(this)
            .setCustomTitle(Dialogs.title(this, getString(R.string.viewer_all_title)))
            .setAdapter(adapter) { _, which -> binding.pager.setCurrentItem(which, false) }
            .create()
        // the cursor starts on the picture being viewed (DialogPolish)
        d.window?.decorView?.setTag(R.id.dsms_default_row, binding.pager.currentItem)
        d.show()
    }

    // ---------------- zoom ----------------
    private fun currentImageView(): ZoomImageView? {
        val rv = binding.pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            ?: return null
        val page = rv.layoutManager?.findViewByPosition(binding.pager.currentItem) ?: return null
        return page.findViewById(R.id.pageImage)
    }

    private fun currentPicture(): ZoomImageView? =
        if (currentPart()?.isImage() == true) currentImageView() else null

    private fun resetOtherPages() {
        val rv = binding.pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val iv = rv.getChildAt(i).findViewById<ZoomImageView>(R.id.pageImage) ?: continue
            if (iv !== currentImageView()) iv.reset()
        }
    }

    /** zoomed pictures own the swipe (touch phones pan instead) */
    private fun onZoomChanged() {
        val z = currentPicture()?.isZoomedIn == true
        binding.pager.isUserInputEnabled = !z
        updateCounter(binding.pager.currentItem)
    }

    // touch phones: pinch / double tap zoom, drag pans while zoomed
    private var panLastX = 0f
    private var panLastY = 0f
    private val tapDetector by lazy {
        android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    currentPicture()?.toggle(); onZoomChanged(); return true
                }
            })
    }
    private val pinchDetector by lazy {
        android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    currentPicture()?.scaleBy(d.scaleFactor); onZoomChanged(); return true
                }
            })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) showBarsBriefly()
        if (io.github.theonionsarewatching.nova.util.Prefs.get(this).touchMode) {
            tapDetector.onTouchEvent(ev)
            pinchDetector.onTouchEvent(ev)
            if (pinchDetector.isInProgress) return true
            val iv = currentPicture()
            if (iv != null && iv.isZoomedIn) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { panLastX = ev.x; panLastY = ev.y }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        iv.pan(ev.x - panLastX, ev.y - panLastY)
                        panLastX = ev.x; panLastY = ev.y
                    }
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun playCurrent() {
        val p = currentPart() ?: return
        if (!p.isVideo()) return
        val vv = binding.pager.findViewWithTag<android.widget.VideoView>("video_${p.id}") ?: return
        val poster = binding.pager.findViewWithTag<android.widget.ImageView>("poster_${p.id}")
        val badge = binding.pager.findViewWithTag<android.widget.ImageView>("badge_${p.id}")
        try {
            if (vv.isPlaying) {
                // PAUSE, not stop: position is kept, poster stays hidden so the
                // paused frame remains visible
                vv.pause()
                updateMediaSoftkeys(forcePlaying = false)
                updateVideoProgress() // freeze the bar at the paused position
                return
            }
            if (activeVideoPartId == p.id) {
                // resume a paused session
                vv.start()
                updateMediaSoftkeys(forcePlaying = true)
                startProgressTicker()
                return
            }
            vv.setVideoPath(p.filePath)
            vv.setOnPreparedListener { mp ->
                activeMediaPlayer = mp
                // the still frame sits ABOVE the video surface — hide it now
                poster?.visibility = View.GONE
                badge?.visibility = View.GONE
                updateMediaSoftkeys(forcePlaying = true)
            }
            vv.setOnCompletionListener {
                poster?.visibility = View.VISIBLE
                badge?.visibility = View.VISIBLE
                activeVideoPartId = -1L
                activeMediaPlayer = null
                updateMediaSoftkeys()
                binding.videoProgressRow.visibility = View.GONE
            }
            vv.setOnErrorListener { _, _, _ ->
                poster?.visibility = View.VISIBLE
                activeVideoPartId = -1L
                activeMediaPlayer = null
                updateMediaSoftkeys()
                openWithSystemPlayer(p)
                true
            }
            activeVideoPartId = p.id
            vv.start()
            updateMediaSoftkeys(forcePlaying = true)
            startProgressTicker()
        } catch (_: Exception) {
            openWithSystemPlayer(p)
        }
    }

    /** Skip a bit on a tap; hold for continuous fast-forward / rewind. */
    private fun seekBy(forward: Boolean, repeatCount: Int) {
        val vv = currentVideoView() ?: return
        try {
            // taps use a large stride because seeking snaps to keyframes — a
            // small hop can land back on the same frame and look like nothing
            val delta = if (repeatCount == 0) 10_000 else 15_000
            val dur = vv.duration
            if (dur <= 0) return
            // clamp short of the end: seeking TO the end fires the completion
            // handler, which resets to the poster — it looked like a restart
            val ceiling = (dur - 1500).coerceAtLeast(0)
            val target = (vv.currentPosition + if (forward) delta else -delta)
                .coerceIn(0, ceiling)
            val mp = activeMediaPlayer
            if (mp != null && android.os.Build.VERSION.SDK_INT >= 26) {
                // SEEK_CLOSEST decodes forward from the prior keyframe to land
                // exactly where asked — forward taps actually move now
                mp.seekTo(target.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
            } else {
                vv.seekTo(target)
            }
            updateVideoProgress()
        } catch (_: Exception) {}
    }

    private fun openWithSystemPlayer(p: io.github.theonionsarewatching.nova.data.PartEntity) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(p.filePath)
            )
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, p.mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {}
    }

    /** MENU: fallback actions for files this phone's codecs struggle with. */
    private fun viewerOptions() {
        val p = currentPart() ?: return
        DsmsAlertBuilder(this)
            .setItems(arrayOf(
                getString(R.string.open_with_system), getString(R.string.save)
            )) { _, which ->
                when (which) {
                    0 -> openWithSystemPlayer(p)
                    1 -> saveCurrent()
                }
            }
            .show()
    }

    private fun stopPlayback() {
        // stop whichever page's VideoView is active, restore its poster frame
        for (p in parts) {
            val vv = binding.pager.findViewWithTag<android.widget.VideoView>("video_${p.id}") ?: continue
            try { vv.stopPlayback() } catch (_: Exception) {}
            binding.pager.findViewWithTag<android.widget.ImageView>("poster_${p.id}")
                ?.visibility = View.VISIBLE
        }
        activeVideoPartId = -1L
        activeMediaPlayer = null
        updateMediaSoftkeys()
        progressHandler.removeCallbacks(progressTicker)
        binding.videoProgressRow.visibility = View.GONE
    }

    private fun saveCurrent() {
        val p = currentPart() ?: return
        lifecycleScope.launch {
            val loc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Saver.save(this@MediaViewerActivity, File(p.filePath), p.fileName, p.mimeType) }
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(
                this@MediaViewerActivity,
                if (loc != null) getString(R.string.saved_to, loc) else getString(R.string.save_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
            return super.dispatchKeyEvent(event)
        // bars: any key shows them; while hidden a soft key only shows them
        if (event.action == KeyEvent.ACTION_DOWN) {
            val wasHidden = !barsShown
            showBarsBriefly()
            if (wasHidden && isRevealOnlyKey(code) && event.repeatCount == 0) {
                revealSwallowCode = code; revealAt = System.currentTimeMillis()
                return true   // this press only shows the bars
            }
            // the MENU echo some flips send with a soft key
            if (code == KeyEvent.KEYCODE_MENU && System.currentTimeMillis() - revealAt < 400) {
                revealSwallowCode = code; return true
            }
        } else if (event.action == KeyEvent.ACTION_UP && code == revealSwallowCode) {
            revealSwallowCode = 0
            return true
        }

        val iv = currentPicture()
        val zoomed = iv?.isZoomedIn == true
        val step = 48 * resources.displayMetrics.density
        when (code) {
            // * zoom in, # zoom out — on key-up
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND -> {
                if (event.action == KeyEvent.ACTION_UP && iv != null) {
                    if (code == KeyEvent.KEYCODE_STAR) iv.zoomIn() else iv.zoomOut()
                    onZoomChanged()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (zoomed) {
                // BACK leaves zoom first (key-up), then the viewer
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { iv!!.reset(); onZoomChanged() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (iv != null && iv.canPanY()) {
                // pan (held key keeps moving)
                if (event.action == KeyEvent.ACTION_DOWN)
                    iv.pan(0f, if (code == KeyEvent.KEYCODE_DPAD_UP) step else -step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val back = code == KeyEvent.KEYCODE_DPAD_LEFT
                if (zoomed) {
                    if (event.action == KeyEvent.ACTION_DOWN) iv!!.pan(if (back) step else -step, 0f)
                    return true
                }
                if (isVideoPlaying()) {
                    // tap = skip a bit; hold = fast seek
                    if (event.action == KeyEvent.ACTION_DOWN) seekBy(forward = !back, repeatCount = event.repeatCount)
                    return true
                }
                // not zoomed: previous / next picture, on key-up
                if (event.action == KeyEvent.ACTION_UP) {
                    val next = (binding.pager.currentItem + if (back) -1 else 1)
                        .coerceIn(0, (parts.size - 1).coerceAtLeast(0))
                    if (next != binding.pager.currentItem) binding.pager.setCurrentItem(next, true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (iv != null) { iv.toggle(); onZoomChanged() } else playCurrent()
                }
                return true
            }
        }
        if (softkeys?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    inner class PageAdapter(
        private val parts: List<PartEntity>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<PageAdapter.VH>() {

        inner class VH(val b: ItemMediaPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMediaPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = parts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = parts[position]
            val img = holder.b.pageImage as ZoomImageView
            img.onChanged = null
            img.tag = null
            holder.b.pageImage.visibility = View.GONE
            holder.b.pageVideo.visibility = View.GONE
            holder.b.pageLabel.visibility = View.GONE
            when {
                p.isImage() -> {
                    holder.b.pagePlayBadge.visibility = View.GONE
                    img.visibility = View.VISIBLE
                    img.fillAllowed = true
                    // sharp — decode from the original file
                    // at up to 4x the screen's long side (room for full
                    // zoom), never upscaled, full colour, no crossfade blur
                    val dm = holder.itemView.resources.displayMetrics
                    val maxPx = (maxOf(dm.widthPixels, dm.heightPixels) * 4).coerceIn(960, 1600)
                    // SHARPNESS FIX. Real cause of the blurry,
                    // jagged look: the big decode (up to 1280 px, kept for
                    // zoom) was drawn at ~1/4-1/5 size through the image
                    // matrix as a HARDWARE bitmap. The GPU shrinks that with
                    // plain bilinear filtering and no mipmaps, which reads
                    // only 4 of every ~25 source pixels: thin text breaks
                    // up and edges stair-step. The phone's Gallery decodes
                    // to the screen size instead. Now: a normal (software)
                    // bitmap with MIPMAPS, so the GPU shrinks it from
                    // properly averaged smaller copies (sharp at the start
                    // size, like Gallery) and still has every original
                    // pixel when zooming in. The stored file is the
                    // original part as received (no recompression here).
                    img.load(File(p.filePath)) {
                        size(maxPx, maxPx)
                        scale(coil.size.Scale.FIT)
                        precision(coil.size.Precision.INEXACT)
                        allowRgb565(false)
                        allowHardware(false)          // mipmaps need a normal bitmap
                        crossfade(false)
                        // the thread's small thumbnail must not stand in
                        memoryCacheKey("viewer:${p.id}:$maxPx")
                        listener(onSuccess = { _, r ->
                            (r.drawable as? android.graphics.drawable.BitmapDrawable)?.let { bd ->
                                bd.bitmap?.setHasMipMap(true)
                                bd.setFilterBitmap(true)
                                bd.setAntiAlias(true)
                                img.invalidate()
                            }
                        })
                    }
                    img.onChanged = {
                        if (binding.pager.currentItem == holder.bindingAdapterPosition) onZoomChanged()
                    }
                }
                p.isVideo() -> {
                    holder.b.pageVideo.visibility = View.VISIBLE
                    holder.b.pageVideo.tag = "video_${p.id}"
                    img.visibility = View.VISIBLE
                    img.tag = "poster_${p.id}"
                    img.fillAllowed = false
                    img.load(File(p.filePath)) {
                        videoFrameMillis(1000) // frame zero is often black
                        crossfade(false)
                    }
                    // play badge on a DEDICATED overlay view, tagged so
                    // playback can hide it
                    holder.b.pagePlayBadge.visibility = View.VISIBLE
                    holder.b.pagePlayBadge.tag = "badge_${p.id}"
                }
                else -> {
                    holder.b.pageLabel.visibility = View.VISIBLE
                    holder.b.pageLabel.text = p.fileName
                }
            }
            holder.itemView.setOnClickListener { onTap() }
        }
    }
}
