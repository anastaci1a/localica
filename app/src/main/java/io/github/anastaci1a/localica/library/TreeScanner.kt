package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Pure scan rules. The caller supplies an IO dispatcher for blocking document providers. */
class TreeScanner(private val source: DocumentSource) {
    fun folder(treeUri: String): FolderSelection {
        val root = source.root(treeUri)
        require(root.isDirectory) { "The selected document is not a folder." }
        return FolderSelection(treeUri, root.name)
    }
    suspend fun scan(treeUri: String, options: ScanOptions = ScanOptions(),
        knownTracks: Map<String, Track> = emptyMap(), onProgress: (Int) -> Unit = {}): ScanResult {
        val started = System.nanoTime()
        val root = source.root(treeUri)
        require(root.isDirectory) { "The selected document is not a folder." }
        val pending = ArrayDeque<Pair<ScanDocument, String>>()
        pending.add(root to "")
        val visited = mutableSetOf<String>()
        val tracks = linkedMapOf<String, Track>()
        val seen = mutableSetOf<String>()
        val failedMetadataIds = mutableSetOf<String>()
        var inspected = 0
        var directoryQueries = 0
        var metadataReads = 0
        onProgress(0)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, path) = pending.removeFirst()
            if (!visited.add(directory.id)) continue
            // A directory access failure aborts the scan, preserving the previous snapshot.
            directoryQueries++
            for (document in source.children(treeUri, directory.id)) {
                currentCoroutineContext().ensureActive()
                val relativePath = if (path.isEmpty()) document.name else "$path/${document.name}"
                if (options.excludes(relativePath)) continue
                if (document.isDirectory) {
                    if (options.includeSubfolders) pending.add(document to relativePath)
                } else if (AudioFiles.accepts(document.name, document.mimeType) && seen.add(document.identity)) {
                    inspected++
                    if (inspected % 25 == 0) onProgress(inspected)
                    val known = knownTracks[document.identity]
                    if (known != null) {
                        if (known.durationMs?.let { it >= 0 && it < options.minimumDurationSeconds * 1000L } == true) continue
                        // Preserve indexed metadata; only discover access through this root.
                        tracks[document.identity] = known.copy(uri = document.uri, relativePath = relativePath)
                        continue
                    }
                    metadataReads++
                    val metadata = try {
                        source.metadata(document.uri, document.name, document.modifiedAt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedMetadataIds += document.identity
                        AudioMetadata()
                    }
                    if (metadata.durationMs?.let { it >= 0 && it < options.minimumDurationSeconds * 1000L } == true) continue
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
                        composer = metadata.composer.clean(), lyricist = metadata.lyricist.clean(),
                        modifiedAt = document.modifiedAt ?: metadata.modifiedAt,
                        durationMs = metadata.durationMs?.takeIf { it >= 0 },
                        fileName = document.name,
                        relativePath = relativePath,
                    )
                }
            }
        }
        currentCoroutineContext().ensureActive()
        onProgress(inspected)
        return ScanResult(FolderSelection(treeUri, root.name), tracks.values.sortedWith(trackOrder), failedMetadataIds.size,
            failedMetadataIds, ScanMetrics(directoryQueries, metadataReads, System.nanoTime() - started))
    }
}

internal val trackOrder = compareBy<Track> { it.title.lowercase(Locale.ROOT) }.thenBy { it.relativePath }.thenBy { it.uri }
private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }

object AudioFiles {
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus")
    fun accepts(name: String, mimeType: String): Boolean =
        !mimeType.startsWith("video/") &&
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("m3u", "m3u8", "pls", "xspf") &&
            mimeType.lowercase(Locale.ROOT) !in setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls") &&
            (mimeType.startsWith("audio/") || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions)
}
