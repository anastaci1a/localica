package io.github.anastaci1a.localica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.scanner.ScanController
import io.github.anastaci1a.localica.scanner.ScanState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class LibraryProblem { ScanFailed, LoadFailed, RemoveFailed, AddFailed }
data class LibraryUiState(
    val snapshot: LibrarySnapshot = LibrarySnapshot(),
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val metadataFailures: Int = 0,
    val problem: LibraryProblem? = null,
    val unavailableFolders: Set<String> = emptySet(),
    val scanningFolderUri: String? = null,
    val scanStatus: ScanState = ScanState(),
    val updatingFolders: Boolean = false,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
    val playback: PlaybackConnection,
    val playlists: PlaylistController,
    private val scanner: ScanController,
    statistics: Flow<io.github.anastaci1a.localica.statistics.ListeningStatistics> = flowOf(io.github.anastaci1a.localica.statistics.ListeningStatistics()),
) : ViewModel() {
    private val mutableLibrary = MutableStateFlow(LibraryUiState())
    val library = mutableLibrary.asStateFlow()
    val playlist = playlists.state
    private var folderJob: Job? = null

    init {
        viewModelScope.launch {
            combine(repository.snapshots, statistics) { snapshot, counts -> counts.applyTo(snapshot) }.catch { mutableLibrary.update { it.copy(loading = false, problem = LibraryProblem.LoadFailed) } }
                .collect { snapshot ->
                    mutableLibrary.update { it.copy(snapshot = snapshot, loading = false, metadataFailures = snapshot.metadataFailures.size) }
                    refreshAccess()
                }
        }
        viewModelScope.launch {
            scanner.state.collect { scan ->
                mutableLibrary.update { it.copy(scanning = scan.running, scannedCount = scan.count,
                    scanningFolderUri = scan.folder, scanStatus = scan) }
                if (!scan.running) refreshAccess()
            }
        }
    }

    fun scan(uri: String) { scanner.start(listOf(uri)) }
    fun scanAll() { scanner.start(library.value.snapshot.folders.map { it.uri }) }
    fun cancelScan() { scanner.cancel() }
    fun addFolder(uri: String) = changeFolder(LibraryProblem.AddFailed) { repository.addFolder(uri) }
    fun removeFolder(uri: String) = changeFolder(LibraryProblem.RemoveFailed) { repository.removeFolder(uri) }
    private fun changeFolder(failure: LibraryProblem, action: suspend () -> Unit) {
        if (folderJob?.isActive == true || scanner.state.value.running) return
        folderJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(updatingFolders = true, problem = null) }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableLibrary.update { it.copy(problem = failure) } }
            finally { mutableLibrary.update { it.copy(updatingFolders = false) }; refreshAccess() }
        }
    }
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
