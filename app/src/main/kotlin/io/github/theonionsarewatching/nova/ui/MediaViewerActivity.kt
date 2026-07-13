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
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.data.PartEntity
import io.github.theonionsarewatching.nova.data.Repo
import io.github.theonionsarewatching.nova.databinding.ActivityMediaBinding
import io.github.theonionsarewatching.nova.databinding.ItemMediaPageBinding
import kotlinx.coroutines.launch
import java.io.File

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

        softkeys = Softkeys(this, binding.softkeyBar).also {
            it.set(
                getString(R.string.back), getString(R.string.play), getString(R.string.save),
                onLeft = { finish() },
                onCenter = { playCurrent() },
                onRight = { saveCurrent() }
            )
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                stopPlayback()
                updateCounter(position)
                updateMediaSoftkeys()
            }
        })

        lifecycleScope.launch {
            parts = repo.db.parts().mediaForConvo(convoId)
            if (parts.isEmpty()) { finish(); return@launch }
            binding.pager.adapter = PageAdapter(parts) { playCurrent() }
            val start = parts.indexOfFirst { it.id == partId }.coerceAtLeast(0)
            binding.pager.setCurrentItem(start, false)
            updateCounter(start)
            updateMediaSoftkeys()
        }
    }

    private fun updateCounter(position: Int) {
        binding.mediaCounter.text = getString(R.string.media_counter, position + 1, parts.size)
        val p = parts.getOrNull(position)
        binding.mediaName.text = p?.fileName ?: ""
    }

    private fun currentPart(): PartEntity? = parts.getOrNull(binding.pager.currentItem)

    /** id of the part whose video session is active (playing OR paused) */
    private var activeVideoPartId = -1L

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

    /** Center softkey reads Play for a stopped/paused video, Pause while playing,
     *  and nothing at all on images. */
    private fun updateMediaSoftkeys(forcePlaying: Boolean? = null) {
        val p = currentPart()
        val playing = forcePlaying ?: isVideoPlaying()
        val center = when {
            p != null && p.isVideo() && playing -> getString(R.string.pause)
            p != null && p.isVideo() -> getString(R.string.play)
            else -> ""
        }
        softkeys?.set(
            getString(R.string.back), center, getString(R.string.save),
            onLeft = { finish() },
            onCenter = { playCurrent() },
            onRight = { saveCurrent() }
        )
    }

    private fun playCurrent() {
        val p = currentPart() ?: return
        if (!p.isVideo()) return
        val vv = binding.pager.findViewWithTag<android.widget.VideoView>("video_${p.id}") ?: return
        val poster = binding.pager.findViewWithTag<android.widget.ImageView>("poster_${p.id}")
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
            vv.setOnPreparedListener {
                // the still frame sits ABOVE the video surface — hide it now
                poster?.visibility = View.GONE
                updateMediaSoftkeys(forcePlaying = true)
            }
            vv.setOnCompletionListener {
                poster?.visibility = View.VISIBLE
                activeVideoPartId = -1L
                updateMediaSoftkeys()
                binding.videoProgressRow.visibility = View.GONE
            }
            vv.setOnErrorListener { _, _, _ ->
                poster?.visibility = View.VISIBLE
                activeVideoPartId = -1L
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
            vv.seekTo(target)
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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
        updateMediaSoftkeys()
        progressHandler.removeCallbacks(progressTicker)
        binding.videoProgressRow.visibility = View.GONE
    }

    private fun saveCurrent() {
        val p = currentPart() ?: return
        lifecycleScope.launch {
            val loc = Saver.save(this@MediaViewerActivity, File(p.filePath), p.fileName, p.mimeType)
            android.widget.Toast.makeText(
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
        if (softkeys?.handleKey(event) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isVideoPlaying()) {
                        // tap = skip back a bit; hold = rewind
                        seekBy(forward = false, repeatCount = event.repeatCount)
                        return true
                    }
                    binding.pager.setCurrentItem((binding.pager.currentItem - 1).coerceAtLeast(0), true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isVideoPlaying()) {
                        // tap = skip forward a bit; hold = fast-forward
                        seekBy(forward = true, repeatCount = event.repeatCount)
                        return true
                    }
                    binding.pager.setCurrentItem(
                        (binding.pager.currentItem + 1).coerceAtMost((parts.size - 1).coerceAtLeast(0)), true
                    )
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    viewerOptions()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    playCurrent()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    class PageAdapter(
        private val parts: List<PartEntity>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<PageAdapter.VH>() {

        class VH(val b: ItemMediaPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMediaPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = parts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = parts[position]
            holder.b.pageImage.visibility = View.GONE
            holder.b.pageVideo.visibility = View.GONE
            holder.b.pageLabel.visibility = View.GONE
            when {
                p.isImage() -> {
                    holder.b.pageImage.visibility = View.VISIBLE
                    holder.b.pageImage.load(File(p.filePath))
                }
                p.isVideo() -> {
                    holder.b.pageVideo.visibility = View.VISIBLE
                    holder.b.pageVideo.tag = "video_${p.id}"
                    holder.b.pageImage.visibility = View.VISIBLE
                    holder.b.pageImage.tag = "poster_${p.id}"
                    holder.b.pageImage.load(File(p.filePath)) // first frame via VideoFrameDecoder
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
