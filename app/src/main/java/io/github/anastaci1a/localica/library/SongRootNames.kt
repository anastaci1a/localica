package io.github.anastaci1a.localica.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read-only presentation metadata. Call on IO; never acquire or widen a grant. */
internal suspend fun resolveSongRootNames(context: Context, folders: List<FolderSelection>): List<SongRootName> {
    val resolver = context.contentResolver
    val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
    fun name(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
    return folders.map { folder ->
        currentCoroutineContext().ensureActive()
        val tree = Uri.parse(folder.uri)
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val rootName = id?.let { name(DocumentsContract.buildDocumentUriUsingTree(tree, it)) } ?: folder.name
        var components = listOf(rootName)
        if (id != null) for (grant in grants.filter { it.authority == tree.authority && DocumentsContract.isTreeUri(it) }) {
            currentCoroutineContext().ensureActive()
            val candidate = runCatching {
                val target = DocumentsContract.buildDocumentUriUsingTree(grant, id)
                val ids = DocumentsContract.findDocumentPath(resolver, target)?.path ?: return@runCatching null
                // Reject incomplete/unrelated responses rather than displaying guessed ancestors.
                if (ids.lastOrNull() != id) return@runCatching null
                val names = ids.map { part -> name(DocumentsContract.buildDocumentUriUsingTree(grant, part)) ?: return@runCatching null }
                names.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (candidate != null && candidate.size > components.size) components = candidate
        }
        val provider = tree.authority?.let { authority -> runCatching {
            context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull() }
        SongRootName(folder.uri, components, provider)
    }
}
