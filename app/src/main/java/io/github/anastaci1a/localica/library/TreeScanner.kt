package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Pure scan rules. The caller supplies an IO dispatcher for blocking document providers. */
class TreeScanner(private val source: DocumentSource) {
    suspend fun scan(treeUri: String, onProgress: (Int) -> Unit = {}): ScanResult {
        val root = source.root(treeUri)
        require(root.isDirectory) { "The selected document is not a folder." }
        val pending = ArrayDeque<Pair<ScanDocument, String>>()
        pending.add(root to "")
        val visited = mutableSetOf<String>()
        val tracks = linkedMapOf<String, Track>()
        var metadataFailures = 0
        onProgress(0)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, path) = pending.removeFirst()
            if (!visited.add(directory.id)) continue
            // A directory access failure aborts the scan, preserving the previous snapshot.
            for (document in source.children(treeUri, directory.id)) {
                currentCoroutineContext().ensureActive()
                val relativePath = if (path.isEmpty()) document.name else "$path/${document.name}"
                if (document.isDirectory) {
                    pending.add(document to relativePath)
                } else if (AudioFiles.accepts(document.name, document.mimeType) && document.identity !in tracks) {
                    val metadata = try {
                        source.metadata(document.uri, document.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        metadataFailures++
                        AudioMetadata()
                    }
                    tracks[document.identity] = Track(
                        uri = document.uri,
                        id = document.identity,
                        title = metadata.title.clean() ?: document.name.substringBeforeLast('.').ifBlank { document.name },
                        artist = metadata.artist.clean(),
                        album = metadata.album.clean(),
                        albumArtist = metadata.albumArtist.clean(),
                        trackNumber = metadata.trackNumber?.takeIf { it > 0 },
                        discNumber = metadata.discNumber?.takeIf { it > 0 },
                        year = metadata.year?.takeIf { it > 0 },
                        genre = metadata.genre.clean(),
                        comment = metadata.comment.clean(),
                        durationMs = metadata.durationMs?.takeIf { it >= 0 },
                        fileName = document.name,
                        relativePath = relativePath,
                    )
                    if (tracks.size % 25 == 0) onProgress(tracks.size)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        onProgress(tracks.size)
        return ScanResult(FolderSelection(treeUri, root.name), tracks.values.sortedWith(trackOrder), metadataFailures)
    }
}

internal val trackOrder = compareBy<Track> { it.title.lowercase(Locale.ROOT) }.thenBy { it.relativePath }.thenBy { it.uri }
private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }

object AudioFiles {
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus")
    fun accepts(name: String, mimeType: String): Boolean =
        !mimeType.startsWith("video/") &&
            (mimeType.startsWith("audio/") || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions)
}
