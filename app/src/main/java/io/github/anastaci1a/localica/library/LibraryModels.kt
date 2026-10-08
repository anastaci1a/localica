package io.github.anastaci1a.localica.library

data class Track(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val fileName: String,
    val relativePath: String,
)

data class FolderSelection(val uri: String, val name: String)
data class LibrarySnapshot(val folder: FolderSelection? = null, val tracks: List<Track> = emptyList())

data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

data class ScanDocument(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
)

interface DocumentSource {
    fun root(treeUri: String): ScanDocument
    fun children(treeUri: String, documentId: String): List<ScanDocument>
    fun metadata(uri: String): AudioMetadata
}

data class ScanResult(val folder: FolderSelection, val tracks: List<Track>, val metadataFailures: Int)
