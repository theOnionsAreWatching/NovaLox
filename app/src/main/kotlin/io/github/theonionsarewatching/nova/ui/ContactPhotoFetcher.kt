package io.github.theonionsarewatching.nova.ui

import android.graphics.BitmapFactory
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import io.github.theonionsarewatching.nova.util.ContactsHelper
import java.io.FileNotFoundException
import java.io.IOException
import okio.buffer
import okio.source
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Identity is a phone address, never an old database photo/contact ID. */
data class ContactPhotoRequest(val address: String) {
    override fun toString() = "ContactPhotoRequest" // no phone numbers in diagnostic descriptions
}
class ContactPhotoFetcher(private val data: ContactPhotoRequest, private val options: Options) : Fetcher {
    override suspend fun fetch(): SourceResult {
        // Fetchers run on Coil's IO dispatcher. No Contacts queries on row bind/main.
        for (uri in ContactsHelper.lookupPhotoCandidates(options.context, data.address)) {
            currentCoroutineContext().ensureActive()
            var source: okio.BufferedSource? = null
            try {
                val stream = options.context.contentResolver.openInputStream(Uri.parse(uri)) ?: continue
                source = stream.source().buffer()
                // Open-but-corrupt/stale photo endpoints must not stop other
                // current-contact fallbacks. Peek preserves the decoder's stream.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                source.peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { source.close(); continue }
                return SourceResult(ImageSource(source, options.context), null, DataSource.DISK)
            } catch (_: SecurityException) {
                source?.close(); throw FileNotFoundException("Contact photo access unavailable")
            } catch (_: IOException) { source?.close() }
              catch (_: IllegalArgumentException) { source?.close() }
        }
        throw FileNotFoundException("No readable saved-contact photo")
    }
    class Factory : Fetcher.Factory<ContactPhotoRequest> {
        override fun create(data: ContactPhotoRequest, options: Options, imageLoader: ImageLoader) = ContactPhotoFetcher(data, options)
    }
}
