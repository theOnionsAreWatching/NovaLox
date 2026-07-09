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
            }
        })

        lifecycleScope.launch {
            parts = repo.db.parts().mediaForConvo(convoId)
            if (parts.isEmpty()) { finish(); return@launch }
            binding.pager.adapter = PageAdapter(parts) { playCurrent() }
            val start = parts.indexOfFirst { it.id == partId }.coerceAtLeast(0)
            binding.pager.setCurrentItem(start, false)
            updateCounter(start)
        }
    }

    private fun updateCounter(position: Int) {
        binding.mediaCounter.text = getString(R.string.media_counter, position + 1, parts.size)
        val p = parts.getOrNull(position)
        binding.mediaName.text = p?.fileName ?: ""
    }

    private fun currentPart(): PartEntity? = parts.getOrNull(binding.pager.currentItem)

    private fun playCurrent() {
        val p = currentPart() ?: return
        if (!p.isVideo()) return
        val vv = binding.pager.findViewWithTag<android.widget.VideoView>("video_${p.id}") ?: return
        if (vv.isPlaying) { vv.stopPlayback(); return }
        try {
            vv.setVideoPath(p.filePath)
            vv.start()
        } catch (_: Exception) {}
    }

    private fun stopPlayback() {
        // stop whichever page's VideoView is playing
        for (p in parts) {
            val vv = binding.pager.findViewWithTag<android.widget.VideoView>("video_${p.id}") ?: continue
            if (vv.isPlaying) try { vv.stopPlayback() } catch (_: Exception) {}
        }
    }

    private fun saveCurrent() {
        val p = currentPart() ?: return
        lifecycleScope.launch {
            val ok = Saver.saveToDownloads(this@MediaViewerActivity, File(p.filePath), p.fileName, p.mimeType)
            android.widget.Toast.makeText(
                this@MediaViewerActivity,
                if (ok) R.string.saved_to_downloads else R.string.save_failed,
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
                    binding.pager.setCurrentItem((binding.pager.currentItem - 1).coerceAtLeast(0), true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    binding.pager.setCurrentItem(
                        (binding.pager.currentItem + 1).coerceAtMost((parts.size - 1).coerceAtLeast(0)), true
                    )
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
