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

    suspend fun scan(uri: String, onProgress: (Int) -> Unit = {}): ScanResult = withContext(io) {
        scanLock.withLock {
            val previous = store.current().folder
            val alreadyGranted = permissions.hasReadAccess(uri)
            var committed = false
            try {
                permissions.acquire(uri)
                val result = scanner.scan(uri, onProgress)
                currentCoroutineContext().ensureActive()
                // Once commit begins, finish the short transaction and grant handoff together.
                withContext(NonCancellable) {
                    store.replace(LibrarySnapshot(result.folder, result.tracks))
                    committed = true
                    if (previous != null && previous.uri != uri) permissions.release(previous.uri)
                }
                result
            } finally {
                if (!committed && !alreadyGranted) permissions.release(uri)
            }
        }
    }
}
