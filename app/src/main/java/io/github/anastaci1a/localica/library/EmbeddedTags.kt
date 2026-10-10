package io.github.anastaci1a.localica.library

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the same container readers as playback, without decoding audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun embeddedTags(context: Context, uri: String): EmbeddedTags = withTimeoutOrNull(15_000) {
    MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
        val groups = retriever.retrieveTrackGroups().awaitResult()
        val builder = MediaMetadata.Builder()
        var comment: String? = null
        val lyrics = mutableListOf<String>()
        for (groupIndex in 0 until groups.length) {
            val group = groups[groupIndex]
            for (formatIndex in 0 until group.length) {
                val metadata = group.getFormat(formatIndex).metadata ?: continue
                builder.populateFromMetadata(metadata)
                for (entryIndex in 0 until metadata.length()) {
                    val entry = metadata[entryIndex]
                    if (entry is VorbisComment) {
                        if (entry.key.uppercase(java.util.Locale.ROOT) in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "SYNCHRONIZEDLYRICS")) lyrics += entry.value
                        val number = entry.value.substringBefore('/').toIntOrNull()?.takeIf { it > 0 }
                        when (entry.key.uppercase(java.util.Locale.ROOT)) {
                            "DATE", "YEAR" -> entry.value.take(4).toIntOrNull()?.let { builder.setRecordingYear(it) }
                            "TRACKNUMBER", "TRACK" -> number?.let { builder.setTrackNumber(it) }
                            "DISCNUMBER", "DISC" -> number?.let { builder.setDiscNumber(it) }
                            "ALBUMARTIST", "ALBUM_ARTIST" -> builder.setAlbumArtist(entry.value)
                        }
                    }
                    val text = when (entry) {
                        is TextInformationFrame -> entry.values.firstOrNull().takeIf { entry.id == "TXXX" && entry.description.equals("comment", true) }
                        is CommentFrame -> entry.text.takeUnless { entry.description.startsWith("iTun") }
                        is VorbisComment -> entry.value.takeIf { entry.key.equals("COMMENT", true) || entry.key.equals("DESCRIPTION", true) }
                        else -> null
                    }
                    if (comment == null && !text.isNullOrBlank()) comment = text
                }
            }
        }
        EmbeddedTags(builder.build(), comment, lyrics.distinct())
    }
} ?: EmbeddedTags(MediaMetadata.EMPTY, null)

internal data class EmbeddedTags(val metadata: MediaMetadata, val comment: String?, val lyrics: List<String> = emptyList())

private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try { continuation.resume(get()) }
        catch (failure: Exception) { continuation.resumeWithException(failure) }
    }, Executor { it.run() })
}
