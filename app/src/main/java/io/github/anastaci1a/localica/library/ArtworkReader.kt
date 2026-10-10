package io.github.anastaci1a.localica.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Artwork stays out of Room. Decode only the current cover at a bounded display size. */
class ArtworkReader(private val context: Context) {
    suspend fun read(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = platformPicture(uri) ?: embeddedTags(context, uri).metadata.artworkData
            bytes?.let(::decodeArtwork)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun platformPicture(uri: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.embeddedPicture
        } catch (_: Exception) { null }
        finally { retriever.release() }
    }
}

internal fun decodeArtwork(bytes: ByteArray): Bitmap? {
    if (bytes.size > 16 * 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
