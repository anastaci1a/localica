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
    /** Average colour from a tiny decode, without a GPU upload; [ArtworkColors.MISSING] when absent. */
    suspend fun averageColor(uri: String): Int = read(uri, 64, prewarm = false)?.let { bitmap ->
        averageArtworkColor(bitmap).also { bitmap.recycle() }
    } ?: ArtworkColors.MISSING

    suspend fun read(uri: String, edge: Int = 768, prewarm: Boolean = true): Bitmap? = withContext(Dispatchers.IO) {
        val upload: (Bitmap) -> Unit = if (prewarm) ::prewarmUpload else { _ -> }
        try { originalBytes(uri)?.let { decodeArtwork(it, edge) }?.also(upload) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** The full-resolution cover exactly as stored (embedded picture or playlist cover file); null when absent. */
    suspend fun originalBytes(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (io.github.anastaci1a.localica.ui.NoneSamplingDiagnostic.artwork) {
            // Agent80 synthetic artwork fixture, identical across all visual arms.
            val fixtureName = Uri.decode(uri)
            if (fixtureName.contains("A35_Runner_")) {
                val number = Regex("A35_Runner_(\\d+)").find(fixtureName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val bright = number % 2 == 1
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(256 * 256) { i ->
                    val x = i % 256; val y = i / 256
                    if ((x / 32 + y / 32) % 2 == 0) {
                        if (bright) 0xFFFF2D55.toInt() else 0xFF071D3F.toInt()
                    } else { if (bright) 0xFFFFF2D8.toInt() else 0xFF266B88.toInt() }
                }
                bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 256)
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                return@withContext bytes
            }
            }
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") {
                val file = java.io.File(parsed.path.orEmpty())
                if (file.canonicalFile.parentFile == java.io.File(context.filesDir, "playlist-covers").canonicalFile && file.length() <= 16 * 1024 * 1024)
                    return@withContext file.readBytes()
            }
            val platform = platformPicture(uri)
            if (platform == null) {
                // Missing/revoked sources cannot benefit from another decoder. Media3
                // retries their I/O failures, occupying the shared artwork permits and
                // leaving newly scrolled rows on the shimmer. Keep fallback decoding
                // for readable formats that the platform retriever cannot handle.
                context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { }
                    ?: return@withContext null
            }
            platform ?: supplementalPicture(uri)
                ?: runCatching { ContainerTags.read(context, uri)?.firstArtwork?.binaryData }.getOrNull()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun supplementalPicture(uri: String): ByteArray? = try { embeddedTags(context, uri).metadata.artworkData }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun platformPicture(uri: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.embeddedPicture
        } catch (_: Exception) { null }
        finally { retriever.release() }
    }
}

/** Kick the GPU upload here, off Main, so the first draw does not pay for it. Android 8.x's
 * OpenGL TextureCache prefetch adds a cross-thread native reference to each decoded heap bitmap;
 * keep the prewarm to the Skia pipeline (API 28+), where its benefit was measured (#501). */
private fun prewarmUpload(bitmap: Bitmap) {
    if (android.os.Build.VERSION.SDK_INT >= 28) bitmap.prepareToDraw()
}

internal fun decodeArtwork(bytes: ByteArray, edge: Int = 768): Bitmap? {
    if (bytes.size > 16 * 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val bound = edge.coerceIn(32, 2048)
    while (bounds.outWidth / sample > bound || bounds.outHeight / sample > bound) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
