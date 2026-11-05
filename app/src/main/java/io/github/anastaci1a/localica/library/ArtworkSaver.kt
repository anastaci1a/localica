package io.github.anastaci1a.localica.library

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ArtworkSaveResult {
    data class Saved(val name: String) : ArtworkSaveResult
    data object NoArtwork : ArtworkSaveResult
    data class Failed(val reason: String = "") : ArtworkSaveResult
}

/** Saves the original cover bytes (never re-encoded) to the public Downloads folder. All I/O runs on [Dispatchers.IO]. */
object ArtworkSaver {
    /** API 29+ saves through MediaStore with no permission; API 26-28 need WRITE_EXTERNAL_STORAGE. */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun save(context: Context, uri: String, artist: String?, album: String?, legacyDirectory: File? = null): ArtworkSaveResult = withContext(Dispatchers.IO) {
        try {
            val bytes = ArtworkReader(context).originalBytes(uri) ?: return@withContext ArtworkSaveResult.NoArtwork
            val format = sniff(bytes)
            val (a, b) = if (artist.isNullOrBlank() && album.isNullOrBlank()) tagNames(context, uri) else artist to album
            val name = fileName(a, b, format.extension)
            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(context, name, format.mime, bytes)
                else saveLegacy(context, name, format.mime, bytes, legacyDirectory)
            saved?.let { ArtworkSaveResult.Saved(it) } ?: ArtworkSaveResult.Failed("no target")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { ArtworkSaveResult.Failed(e.toString()) }
    }

    internal class Format(val extension: String, val mime: String)

    internal fun sniff(b: ByteArray): Format = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> Format("png", "image/png")
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Format("webp", "image/webp")
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> Format("gif", "image/gif")
        else -> Format("jpg", "image/jpeg")
    }

    /** `<Artist> - <Album>.<ext>` with path and reserved characters replaced. */
    internal fun fileName(artist: String?, album: String?, extension: String): String {
        val base = listOfNotNull(artist, album).map(::sanitize).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Album artwork" }
        return "${base.take(120).trim().trimEnd('.')}.$extension"
    }

    private fun sanitize(part: String) = part.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()

    private fun tagNames(context: Context, uri: String): Pair<String?, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)) to
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        } catch (_: Exception) { null to null } finally { retriever.release() }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveMediaStore(context: Context, name: String, mime: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(target)?.use { it.write(bytes) } ?: error("No output stream")
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // MediaStore may have de-duplicated the name ("x (1).jpg"); report what it chose.
            return resolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, mime: String, bytes: ByteArray, directory: File?): String? {
        val dir = directory ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) error("Downloads unavailable: ${dir.path}")
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$stem (${n++}).$ext")
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
        return file.name
    }
}
