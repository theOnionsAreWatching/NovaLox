package io.github.theonionsarewatching.nova.ui

import android.content.ContentUris
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Each view owns a cancellable Coil request; no ID-only stale bitmap cache. */
data class MediaThumbnailRequest(val uri: Uri, val edge: Int, val video: Boolean)
class MediaThumbnailFetcher(private val data: MediaThumbnailRequest, private val options: Options) : Fetcher {
    override suspend fun fetch(): DrawableResult {
        currentCoroutineContext().ensureActive()
        val resolver = options.context.contentResolver
        val px = data.edge.coerceIn(96,512)
        val bitmap = try { if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(data.uri,Size(px,px),null)
            else if (data.video) {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(resolver,ContentUris.parseId(data.uri),MediaStore.Video.Thumbnails.MINI_KIND,null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(resolver,ContentUris.parseId(data.uri),MediaStore.Images.Thumbnails.MINI_KIND,null)
            } } catch (_: OutOfMemoryError) { throw java.io.IOException("Thumbnail memory unavailable") }
        currentCoroutineContext().ensureActive()
        return DrawableResult(BitmapDrawable(options.context.resources,bitmap ?: throw java.io.IOException("Thumbnail unavailable")),false,DataSource.DISK)
    }
    class Factory : Fetcher.Factory<MediaThumbnailRequest> {
        override fun create(data: MediaThumbnailRequest, options: Options, imageLoader: ImageLoader) = MediaThumbnailFetcher(data,options)
    }
}
