package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.content.ContentUris
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import java.util.concurrent.Executors
import coil.load
import coil.dispose
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * our own Photo/Video picker (no gallery app, no chooser).
 *
 * - MediaStore, stock TCL single-media album order (modified time, ID descending); Photos / Videos tabs at the top (UP from the
 *   first grid row reaches them, LEFT/RIGHT switches, OK picks a tab).
 * - OK on a thumbnail toggles it; a numbered accent badge shows the order.
 *   Limits: [maxItems] in total and [maxVideos] video (one per message).
 * - full screen, 2 columns of bigger, sharper
 *   thumbnails. RIGHT soft key "Done (n)" adds them; LEFT soft key
 *   "Gallery" opens the phone's gallery app (multi-select) instead; BACK
 *   cancels. Soft keys act on key-up.
 * - Only ids are kept in memory (thousands of items are fine); thumbnails
 *   are made on 2 background threads when a cell is shown, and kept in a
 *   cancellable, view-owned requests with no stale ID-only memory cache.
 */
object MediaPickerDialog {

    private class Item(val id: Long, val video: Boolean, val durationMs: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val thumbnailDispatcher = pool.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())

    fun show(
        activity: Activity,
        maxItems: Int,
        maxVideos: Int,
        onOtherGallery: () -> Unit,
        onDone: (List<Uri>) -> Unit
    ) {
        InAppPickers.withMediaPermission(activity, video = true) {
            open(activity, maxItems.coerceAtLeast(0), maxVideos.coerceAtLeast(0), onOtherGallery, onDone)
        }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun open(
        activity: Activity, maxItems: Int, maxVideos: Int,
        onOtherGallery: () -> Unit, onDone: (List<Uri>) -> Unit
    ) {
        if (maxItems == 0) {
            io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity, R.string.media_limit_full, Toast.LENGTH_SHORT).show(); return
        }
        val accent = ThemeUtils.accentColor(activity)
        val selected = ArrayList<Item>()            // in pick order
        var items: List<Item> = emptyList()
        var videoTab = false
        var generation = 0
        var querySignal: android.os.CancellationSignal? = null

        // 2 columns (was 3): bigger thumbnails on the 240px-wide screen
        val cell = (activity.resources.displayMetrics.widthPixels - dp(activity, 8)) / 2

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int): Any = items[p]
            override fun getItemId(p: Int) = items[p].id
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val root = (convert as? FrameLayout) ?: FrameLayout(activity).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(cell, cell)
                    val img = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0x22888888)
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: Outline) =
                                o.setRoundRect(0, 0, v.width, v.height, dp(activity, 10).toFloat())
                        }
                    }
                    addView(img, FrameLayout.LayoutParams(cell - dp(activity, 6), cell - dp(activity, 6), Gravity.CENTER))
                    val vid = TextView(activity).apply {
                        textSize = 10f; setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
                        background = GradientDrawable().apply {
                            setColor(0x99000000.toInt()); cornerRadius = dp(activity, 6).toFloat() }
                    }
                    addView(vid, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                        setMargins(dp(activity, 7), 0, 0, dp(activity, 7)) })
                    val badge = TextView(activity).apply {
                        textSize = 11f; setTextColor(ThemeUtils.onColor(accent)); gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(accent)
                            setStroke(dp(activity, 2), 0xFFFFFFFF.toInt()) }
                    }
                    addView(badge, FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(activity, 5), dp(activity, 5), 0) })
                    // ticked: accent frame round the whole thumbnail
                    val frame = View(activity).apply {
                        background = GradientDrawable().apply {
                            setColor(0); setStroke(dp(activity, 3), accent)
                            cornerRadius = dp(activity, 10).toFloat() }
                    }
                    addView(frame, FrameLayout.LayoutParams(cell - dp(activity, 6), cell - dp(activity, 6), Gravity.CENTER))
                }
                val img = root.getChildAt(0) as ImageView
                val vid = root.getChildAt(1) as TextView
                val badge = root.getChildAt(2) as TextView
                val frame = root.getChildAt(3)
                val it = items[p]
                val n = selected.indexOfFirst { s -> s.id == it.id && s.video == it.video }
                badge.visibility = if (n >= 0) View.VISIBLE else View.GONE
                // a tick, like the phone's Gallery (the count is in the title
                // and on the Done key)
                badge.text = "\u2713"
                frame.visibility = badge.visibility
                img.alpha = if (n >= 0) 0.75f else 1f
                vid.visibility = if (it.video) View.VISIBLE else View.GONE
                if (it.video) {
                    val s = (it.durationMs / 1000).toInt()
                    vid.text = "\u25B6 %d:%02d".format(s / 60, s % 60)
                }
                img.dispose()
                img.setImageDrawable(null)
                img.load(MediaThumbnailRequest(it.uri, cell, it.video)) {
                    fetcherDispatcher(thumbnailDispatcher)
                    memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                    diskCachePolicy(coil.request.CachePolicy.DISABLED)
                    crossfade(false)
                    error(android.R.drawable.ic_menu_report_image)
                }

                return root
            }
        }

        val grid = GridView(activity).apply {
            numColumns = 2
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 0; horizontalSpacing = 0
            setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), dp(activity, 2))
            clipToPadding = true   // rows cut at the list edge, never under the title
            this.adapter = adapter
            // focus = accent rounded outline drawn over the thumbnail
            setDrawSelectorOnTop(true)
            selector = android.graphics.drawable.StateListDrawable().apply {
                val ring = GradientDrawable().apply {
                    setColor(0); setStroke(dp(activity, 3), accent)
                    cornerRadius = dp(activity, 12).toFloat() }
                addState(intArrayOf(android.R.attr.state_focused), ring)
                addState(intArrayOf(android.R.attr.state_selected), ring)
                addState(intArrayOf(android.R.attr.state_pressed), ring)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            isFastScrollEnabled = true
        }
        val empty = TextView(activity).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(activity, 16), dp(activity, 24), dp(activity, 16), dp(activity, 24))
            text = activity.getString(R.string.media_loading)
        }

        fun tab(label: Int) = TextView(activity).apply {
            text = activity.getString(label); textSize = 14f
            maxLines = 1
            gravity = Gravity.CENTER; isFocusable = true
            minHeight = dp(activity, 32)
            setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 4))
        }
        val photosTab = tab(R.string.media_tab_photos)
        val videosTab = tab(R.string.media_tab_videos)
        fun styleTabs() {
            for ((t, on) in listOf(photosTab to !videoTab, videosTab to videoTab)) {
                t.background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 16).toFloat()
                    if (on) setColor(accent) else { setColor(0); setStroke(dp(activity, 1), accent) }
                }
                t.setTextColor(if (on) ThemeUtils.onColor(accent) else accent)
                // focus: larger pill with a thick ring in the text colour
                if (t.isFocused) (t.background as GradientDrawable).setStroke(dp(activity, 3),
                    ThemeUtils.attrColor(activity, R.attr.dsmsText, R.color.text_primary))
                t.scaleX = if (t.isFocused) 1.1f else 1f; t.scaleY = t.scaleX
            }
        }
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(activity, 2), 0, dp(activity, 4))
            addView(photosTab, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(activity, 10), 0, dp(activity, 4), 0) })
            addView(videosTab, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(activity, 4), 0, dp(activity, 10), 0) })
        }
        // say how to switch (LEFT / RIGHT at the grid edge)
        val hint = TextView(activity).apply {
            text = activity.getString(R.string.media_tab_hint)
            textSize = 14f; alpha = 0.85f
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            setPadding(dp(activity, 6), 0, dp(activity, 6), dp(activity, 3))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs)
            addView(hint)
            addView(empty)
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val panel = FullScreenPanel(activity,
            activity.getString(R.string.attach_menu_photo_video), box)
        val baseTitle = activity.getString(R.string.attach_menu_photo_video)
        panel.bar.softRight.setTextColor(accent)
        fun updateDone() {
            panel.bar.softRight.text =
                if (selected.isEmpty()) activity.getString(R.string.emoji_done)
                else activity.getString(R.string.media_done_n, selected.size)
            panel.setTitle(if (selected.isEmpty()) baseTitle
                else activity.getString(R.string.media_picked_n, selected.size))
        }

        fun load(video: Boolean) {
            videoTab = video; styleTabs()
            val gen = ++generation
            querySignal?.cancel()
            val signal = android.os.CancellationSignal().also { querySignal = it }
            empty.visibility = View.VISIBLE; empty.text = activity.getString(R.string.media_loading)
            items = emptyList(); adapter.notifyDataSetChanged()
            pool.execute {
                if (signal.isCanceled) return@execute
                val list = query(activity, video, signal)
                main.post {
                    if (gen != generation) return@post
                    items = list.orEmpty()
                    adapter.notifyDataSetChanged()
                    if (list == null) {
                        empty.text = activity.getString(R.string.media_read_error)
                    } else if (list.isEmpty()) {
                        empty.text = activity.getString(
                            if (video) R.string.media_no_videos else R.string.media_no_photos)
                    } else empty.visibility = View.GONE
                }
            }
        }

        photosTab.setOnClickListener { if (videoTab) load(false) }
        videosTab.setOnClickListener { if (!videoTab) load(true) }
        // on the tab row LEFT/RIGHT switches straight away (focus = that tab)
        val tabFocus = View.OnFocusChangeListener { v, has ->
            if (has && v === videosTab && !videoTab) load(true)
            else if (has && v === photosTab && videoTab) load(false)
            else styleTabs()
        }
        photosTab.onFocusChangeListener = tabFocus
        videosTab.onFocusChangeListener = tabFocus

        // '0' toggles Photos / Videos (key-up, anywhere in the picker).
        // In the grid LEFT/RIGHT only move between cells; UP from the top
        // row goes to the tabs, where LEFT/RIGHT switch and DOWN goes back.
        fun starPound(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_0) return false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val onTabs = photosTab.isFocused || videosTab.isFocused
                load(!videoTab)
                if (onTabs) (if (videoTab) videosTab else photosTab).requestFocus()
            }
            return true
        }
        grid.setOnKeyListener { _, keyCode, event ->
            if (starPound(keyCode, event)) return@setOnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN &&
                grid.selectedItemPosition.let { it < 0 || it < grid.numColumns.coerceAtLeast(1) }) {
                (if (videoTab) videosTab else photosTab).requestFocus(); styleTabs()
                return@setOnKeyListener true
            }
            false
        }
        val tabKeys = View.OnKeyListener { _, keyCode, event ->
            if (starPound(keyCode, event)) return@OnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) { grid.requestFocus(); styleTabs() }
                return@OnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return@OnKeyListener true
            false
        }
        photosTab.setOnKeyListener(tabKeys)
        videosTab.setOnKeyListener(tabKeys)

        grid.setOnItemClickListener { _, _, pos, _ ->
            val it = items[pos]
            val i = selected.indexOfFirst { s -> s.id == it.id && s.video == it.video }
            if (i >= 0) selected.removeAt(i)
            else when {
                selected.size >= maxItems ->
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity, activity.getString(R.string.media_limit_items, maxItems), Toast.LENGTH_SHORT).show()
                it.video && selected.count { s -> s.video } >= maxVideos ->
                    io.github.theonionsarewatching.nova.ui.DsmsToast.makeText(activity, R.string.media_limit_video, Toast.LENGTH_SHORT).show()
                else -> selected.add(it)
            }
            // redraw only the cells on screen (focus stays put)
            adapter.notifyDataSetChanged()
            updateDone()
        }

        // RIGHT soft key: Done (adds the picks; with none it just closes).
        // LEFT soft key: the phone's gallery app. BACK cancels. All on key-up
        // (FullScreenPanel); MENU is swallowed there.
        panel.setRight(activity.getString(R.string.emoji_done)) {
            val picks = selected.map { it.uri }
            panel.dismiss()
            if (picks.isNotEmpty()) onDone(picks)
        }
        panel.setLeft(activity.getString(R.string.media_other_gallery)) {
            panel.dismiss()
            onOtherGallery()
        }
        val d = panel.dialog
        d.setOnShowListener { updateDone(); grid.requestFocus() }
        grid.setRecyclerListener { view -> (view as? ViewGroup)?.getChildAt(0)?.let { (it as? ImageView)?.dispose() } }
        d.setOnDismissListener {
            generation++; querySignal?.cancel()
            for (i in 0 until grid.childCount) (grid.getChildAt(i) as? ViewGroup)?.getChildAt(0)?.let { (it as? ImageView)?.dispose() }
        }   // drop late loads
        panel.show()
        load(false)
    }

    private fun query(activity: Activity, video: Boolean, signal: android.os.CancellationSignal): List<Item>? {
        val out = ArrayList<Item>()
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = if (video) arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION)
        else arrayOf(MediaStore.Images.Media._ID)
        try {
            activity.contentResolver.query(uri, proj, null, null,
                // Verified TCL LocalAlbum.mOrderClause (both image/video buckets).
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC, " + MediaStore.MediaColumns._ID + " DESC", signal)?.use { c ->
                while (!signal.isCanceled && c.moveToNext()) {
                    out.add(Item(c.getLong(0), video, if (video) c.getLong(1) else 0L))
                }
            } ?: return null
        } catch (_: Exception) { return null }
        return if (signal.isCanceled) null else out
    }

}
