package io.github.anastaci1a.localica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryProblem { AccessLost, ScanFailed, LoadFailed }
data class LibraryUiState(
    val snapshot: LibrarySnapshot = LibrarySnapshot(),
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val metadataFailures: Int = 0,
    val problem: LibraryProblem? = null,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
    val playback: PlaybackConnection,
) : ViewModel() {
    private val mutableLibrary = MutableStateFlow(LibraryUiState())
    val library = mutableLibrary.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repository.snapshots.catch {
                mutableLibrary.update { it.copy(loading = false, problem = LibraryProblem.LoadFailed) }
            }.collect { snapshot ->
                mutableLibrary.update { it.copy(snapshot = snapshot, loading = false) }
                refreshAccess()
            }
        }
    }

    fun scan(uri: String) {
        if (scanJob?.isActive == true || !playback.state.value.connected) return
        if (uri != library.value.snapshot.folder?.uri) playback.clear()
        scanJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(scanning = true, scannedCount = 0, problem = null, metadataFailures = 0) }
            try {
                val result = repository.scan(uri) { count -> mutableLibrary.update { it.copy(scannedCount = count) } }
                mutableLibrary.update { it.copy(metadataFailures = result.metadataFailures) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                mutableLibrary.update { it.copy(problem = LibraryProblem.AccessLost) }
            } catch (_: Exception) {
                mutableLibrary.update { it.copy(problem = LibraryProblem.ScanFailed) }
            } finally {
                mutableLibrary.update { it.copy(scanning = false) }
            }
        }
    }

    fun rescan() { library.value.snapshot.folder?.let { scan(it.uri) } }
    fun cancelScan() { scanJob?.cancel() }
    fun play(track: Track) { if (!library.value.scanning) playback.play(track) }
    fun refreshAccess() {
        val folder = library.value.snapshot.folder ?: return
        if (!repository.hasAccess(folder)) {
            mutableLibrary.update { it.copy(problem = LibraryProblem.AccessLost) }
        } else if (library.value.problem == LibraryProblem.AccessLost) {
            mutableLibrary.update { it.copy(problem = null) }
        }
    }

    override fun onCleared() { playback.close() }
}
