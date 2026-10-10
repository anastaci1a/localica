package io.github.anastaci1a.localica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryProblem { ScanFailed, LoadFailed, RemoveFailed }
data class LibraryUiState(
    val snapshot: LibrarySnapshot = LibrarySnapshot(),
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val metadataFailures: Int = 0,
    val problem: LibraryProblem? = null,
    val unavailableFolders: Set<String> = emptySet(),
    val scanningFolderUri: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    val playback: PlaybackConnection,
    val playlists: PlaylistController,
) : ViewModel() {
    private val mutableLibrary = MutableStateFlow(LibraryUiState())
    val library = mutableLibrary.asStateFlow()
    val playlist = playlists.state
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repository.snapshots.catch { mutableLibrary.update { it.copy(loading = false, problem = LibraryProblem.LoadFailed) } }
                .collect { snapshot ->
                    mutableLibrary.update { it.copy(snapshot = snapshot, loading = false) }
                    refreshAccess()
                }
        }
    }

    fun scan(uri: String) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(scanning = true, scanningFolderUri = uri, scannedCount = 0, problem = null, metadataFailures = 0) }
            try {
                val result = repository.scan(uri) { count -> mutableLibrary.update { it.copy(scannedCount = count) } }
                ArtworkCache.invalidate()
                mutableLibrary.update { it.copy(metadataFailures = result.metadataFailures) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableLibrary.update { it.copy(problem = LibraryProblem.ScanFailed) }
            } finally {
                mutableLibrary.update { it.copy(scanning = false, scanningFolderUri = null) }
                refreshAccess()
            }
        }
    }

    fun removeFolder(uri: String) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(scanning = true, problem = null) }
            try {
                repository.removeFolder(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableLibrary.update { it.copy(problem = LibraryProblem.RemoveFailed) }
            } finally {
                mutableLibrary.update { it.copy(scanning = false) }
                refreshAccess()
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }
    fun play(track: Track) {
        val tracks = library.value.snapshot.tracks
        val index = tracks.indexOfFirst { it.id == track.id }
        if (index >= 0) playback.queues.tap(tracks, index)
    }
    fun refreshAccess() {
        val missing = library.value.snapshot.folders.filterNot(repository::hasAccess).map { it.uri }.toSet()
        mutableLibrary.update { it.copy(unavailableFolders = missing) }
    }
    override fun onCleared() { playback.close() }
}
