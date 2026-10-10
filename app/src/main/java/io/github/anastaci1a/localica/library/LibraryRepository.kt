package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val store: LibraryStore,
    private val scanner: TreeScanner,
    private val permissions: FolderPermissions,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val snapshots = store.snapshots
    private val scanLock = Mutex()

    fun hasAccess(folder: FolderSelection) = permissions.hasReadAccess(folder.uri)

    fun playableTrack(track: Track): Track = playableTrack(track, permissions::hasReadAccess)

    fun playableTracks(tracks: List<Track>): List<Track> {
        val grants = mutableMapOf<String, Boolean>()
        return tracks.map { track -> playableTrack(track) { root -> grants.getOrPut(root) { permissions.hasReadAccess(root) } } }
    }

    private fun playableTrack(track: Track, hasAccess: (String) -> Boolean): Track {
        val location = track.locations.firstOrNull { it.uri == track.uri && hasAccess(it.folderUri) }
            ?: track.locations.firstOrNull { hasAccess(it.folderUri) }
        return location?.let { track.copy(uri = it.uri, relativePath = it.relativePath) } ?: track
    }

    suspend fun scan(uri: String, onProgress: (Int) -> Unit = {}): ScanResult = withContext(io) {
        scanLock.withLock {
            val alreadyGranted = permissions.hasReadAccess(uri)
            var committed = false
            try {
                permissions.acquire(uri)
                val result = scanner.scan(uri, onProgress)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    store.replaceRoot(result)
                    committed = true
                }
                result
            } finally {
                if (!committed && !alreadyGranted) permissions.release(uri)
            }
        }
    }

    suspend fun removeFolder(uri: String) = withContext(io) {
        scanLock.withLock {
            withContext(NonCancellable) {
                store.removeRoot(uri)
                permissions.release(uri)
            }
        }
    }
}
