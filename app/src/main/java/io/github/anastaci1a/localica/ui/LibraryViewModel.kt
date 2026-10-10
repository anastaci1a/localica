package io.github.anastaci1a.localica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anastaci1a.localica.library.*
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
    val query: LibraryQuery = LibraryQuery(),
    val visibleTracks: List<Track> = snapshot.tracks,
    val scanningFolderUri: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    val playback: PlaybackConnection,
    private val preferences: LibraryQueryPreferences,
) : ViewModel() {
    private val mutableLibrary = MutableStateFlow(LibraryUiState())
    val library = mutableLibrary.asStateFlow()
    private val queries = MutableStateFlow(preferences.read())
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(repository.snapshots, queries) { snapshot, query -> snapshot to query }
                .mapLatest { (snapshot, query) ->
                    val effective = if (query.folderUri != null && snapshot.folders.none { it.uri == query.folderUri })
                        query.copy(folderUri = null) else query
                    val tracks = withContext(Dispatchers.Default) { LibraryQueries.tracks(snapshot, effective) }
                    EvaluatedLibrary(snapshot, query, effective, tracks)
                }.catch { mutableLibrary.update { it.copy(loading = false, problem = LibraryProblem.LoadFailed) } }
                .collect { (snapshot, requested, query, tracks) ->
                    if (requested != queries.value) return@collect
                    mutableLibrary.update { it.copy(snapshot = snapshot, loading = false, query = query, visibleTracks = tracks) }
                    if (queries.value != query) setQuery(query)
                    refreshAccess()
                }
        }
    }

    fun setQuery(query: LibraryQuery) {
        mutableLibrary.update { it.copy(query = query) }
        queries.value = query
        preferences.write(query)
    }

    fun scan(uri: String) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(scanning = true, scanningFolderUri = uri, scannedCount = 0, problem = null, metadataFailures = 0) }
            try {
                val result = repository.scan(uri) { count -> mutableLibrary.update { it.copy(scannedCount = count) } }
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
                if (queries.value.folderUri == uri) setQuery(queries.value.copy(folderUri = null))
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
        val visible = library.value.visibleTracks
        if (visible.any { it.id == track.id }) playback.playFromList(visible, track.id)
    }
    fun refreshAccess() {
        val missing = library.value.snapshot.folders.filterNot(repository::hasAccess).map { it.uri }.toSet()
        mutableLibrary.update { it.copy(unavailableFolders = missing) }
    }
    override fun onCleared() { playback.close() }
}

private data class EvaluatedLibrary(val snapshot: LibrarySnapshot, val requested: LibraryQuery,
    val effective: LibraryQuery, val tracks: List<Track>)
