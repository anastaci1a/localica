package io.github.anastaci1a.localica.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import kotlinx.coroutines.CancellationException

class AndroidDocuments(private val context: Context) : DocumentSource {
    private val resolver = context.contentResolver
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    override fun root(treeUri: String): ScanDocument {
        val tree = Uri.parse(treeUri)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return query(tree, uri).singleOrNull() ?: throw IOException("The selected folder is unavailable.")
    }

    override fun children(treeUri: String, documentId: String): List<ScanDocument> {
        val tree = Uri.parse(treeUri)
        return query(tree, DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId))
    }

    private fun query(tree: Uri, uri: Uri): List<ScanDocument> {
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IOException("The folder provider did not respond.")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: throw IOException("A document has no identifier.")
                    val name = it.getString(1) ?: "Untitled"
                    val mime = it.getString(2).orEmpty()
                    add(ScanDocument(id, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name,
                        mime, mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        DocumentsContract.buildDocumentUri(requireNotNull(tree.authority), id).toString()))
                }
            }
        }
    }

    override suspend fun metadata(uri: String): AudioMetadata = metadata(uri, Uri.parse(uri).lastPathSegment.orEmpty())
    override suspend fun metadata(uri: String, fileName: String): AudioMetadata {
        val wav = if (fileName.endsWith(".wav", ignoreCase = true)) runCatching { ContainerTags.read(context, uri, fileName) }.getOrNull() else null
        fun wavText(key: org.jaudiotagger.tag.FieldKey) = wav?.getFirst(key)?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() }
        val extra = try { embeddedTags(context, uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            return AudioMetadata(
                title = wavText(org.jaudiotagger.tag.FieldKey.TITLE) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: extra?.metadata?.title?.toString(),
                artist = wavText(org.jaudiotagger.tag.FieldKey.ARTIST) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: extra?.metadata?.artist?.toString(),
                album = wavText(org.jaudiotagger.tag.FieldKey.ALBUM) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: extra?.metadata?.albumTitle?.toString(),
                albumArtist = wavText(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: extra?.metadata?.albumArtist?.toString(),
                trackNumber = wavText(org.jaudiotagger.tag.FieldKey.TRACK).tagNumber() ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).tagNumber() ?: extra?.metadata?.trackNumber,
                discNumber = wavText(org.jaudiotagger.tag.FieldKey.DISC_NO).tagNumber() ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).tagNumber() ?: extra?.metadata?.discNumber,
                year = wavText(org.jaudiotagger.tag.FieldKey.YEAR).tagNumber() ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).tagNumber() ?: extra?.metadata?.recordingYear ?: extra?.metadata?.releaseYear,
                genre = wavText(org.jaudiotagger.tag.FieldKey.GENRE) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: extra?.metadata?.genre?.toString(),
                comment = wavText(org.jaudiotagger.tag.FieldKey.COMMENT) ?: extra?.comment,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } finally {
            retriever.release()
        }
    }
}

interface FolderPermissions {
    fun hasReadAccess(uri: String): Boolean
    fun acquire(uri: String)
    fun release(uri: String)
}

class PersistedFolderPermissions(private val resolver: ContentResolver) : FolderPermissions {
    override fun hasReadAccess(uri: String) = resolver.persistedUriPermissions.any {
        it.uri.toString() == uri && it.isReadPermission
    }

    override fun acquire(uri: String) {
        require(Uri.parse(uri).scheme == "content") { "Select a folder with the system picker." }
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun release(uri: String) {
        try {
            val grant = resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri } ?: return
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.releasePersistableUriPermission(Uri.parse(uri), flags)
        } catch (_: SecurityException) {
            // The user or provider may already have revoked this grant.
        }
    }
}

private fun String?.tagNumber(): Int? = this?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
