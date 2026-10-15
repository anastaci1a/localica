package io.github.anastaci1a.localica.library

/** The document identity is independent of the tree through which it was granted. */
data class Track(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val fileName: String,
    val relativePath: String,
    val id: String = uri,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val comment: String? = null,
    val locations: List<TrackLocation> = emptyList(),
    val composer: String? = null,
    val lyricist: String? = null,
    val modifiedAt: Long? = null,
    val addedAt: Long? = null,
    /** Live local statistics; never written into audio files or saved track snapshots. */
    val playCount: Long = 0,
)

data class TrackLocation(val folderUri: String, val uri: String, val relativePath: String)
data class FolderSelection(val uri: String, val name: String)
data class MetadataFailure(val trackId: String, val folderUri: String, val path: String)
data class LibrarySnapshot(val folders: List<FolderSelection> = emptyList(), val tracks: List<Track> = emptyList(),
    val metadataFailures: List<MetadataFailure> = emptyList(),
    val playlistPlays: Map<String, Long> = emptyMap(), val trackPlays: Map<String, Long> = emptyMap(),
    /** Preserved by statistics copies; source identity separately invalidates ordering/results. */
    val searchIndex: LibrarySearchIndex? = null)

data class AudioMetadata(
    val composer: String? = null,
    val lyricist: String? = null,
    val modifiedAt: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val comment: String? = null,
)

data class ScanDocument(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val identity: String = uri,
    val modifiedAt: Long? = null,
)

interface DocumentSource {
    fun root(treeUri: String): ScanDocument
    fun children(treeUri: String, documentId: String): List<ScanDocument>
    suspend fun metadata(uri: String): AudioMetadata
    suspend fun metadata(uri: String, fileName: String): AudioMetadata = metadata(uri)
}

data class ScanResult(val folder: FolderSelection, val tracks: List<Track>, val metadataFailures: Int,
    val failedMetadataIds: Set<String> = emptySet())

data class ScanOptions(val includeSubfolders: Boolean = true, val minimumDurationSeconds: Int = 0,
    val excludedPaths: Set<String> = emptySet()) {
    fun excludes(path: String) = excludedPaths.any { path == it || path.startsWith("$it/") }
}
