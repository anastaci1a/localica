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
    val query: LibraryQuery = LibraryQuery(),
    val visibleTracks: List<Track> = snapshot.tracks,
    val view: SongListView = SongListView.Songs,
    val groups: List<LibraryGroup> = emptyList(),
    val groupId: String? = null,
    val scanningFolderUri: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    val playback: PlaybackConnection,
    private val preferences: LibraryQueryPreferences,
    val playlists: PlaylistController,
) : ViewModel() {
    private val mutableLibrary = MutableStateFlow(LibraryUiState())
    val library = mutableLibrary.asStateFlow()
    private val queries = MutableStateFlow(BrowseRequest(query = preferences.read()))
    private val playlistRequest = MutableStateFlow(PlaylistRequest(query = preferences.read(SongListView.Playlists)))
    val playlist = combine(playlists.state, repository.snapshots, playlistRequest) { saved, snapshot, request ->
        Triple(saved, snapshot, request)
    }.mapLatest { (saved, snapshot, request) ->
        withContext(Dispatchers.Default) {
            val selected = saved.playlists.firstOrNull { it.id == request.id }
            val query = request.query.let { if (it.folderUri != null && snapshot.folders.none { folder -> folder.uri == it.folderUri })
                it.copy(folderUri = null) else it }
            PlaylistUiState(saved, selected, query, selected?.let { PlaylistEdits.rows(it, snapshot, query) }.orEmpty())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistUiState())
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(repository.snapshots, queries) { snapshot, request -> snapshot to request }
                .mapLatest { (snapshot, request) -> withContext(Dispatchers.Default) {
                    val query = request.query.let { if (it.folderUri != null && snapshot.folders.none { folder -> folder.uri == it.folderUri })
                        it.copy(folderUri = null) else it }
                    val groups = LibraryViews.groups(snapshot, request.view, query)
                    val tracks = if (request.view == SongListView.Songs) LibraryQueries.tracks(snapshot, query)
                        else groups.firstOrNull { it.id == request.groupId }?.tracks.orEmpty()
                    EvaluatedLibrary(snapshot, request, query, groups, tracks)
                } }.catch { mutableLibrary.update { it.copy(loading = false, problem = LibraryProblem.LoadFailed) } }
                .collect { result ->
                    if (result.request != queries.value) return@collect
                    mutableLibrary.update { it.copy(snapshot = result.snapshot, loading = false, query = result.query,
                        visibleTracks = result.tracks, view = result.request.view, groupId = result.request.groupId, groups = result.groups) }
                    if (result.request.query != result.query) setQuery(result.query)
                    refreshAccess()
                }
        }
    }

    fun setQuery(query: LibraryQuery) {
        queries.update { it.copy(query = query) }
        preferences.write(query, queries.value.view)
    }
    fun setView(view: SongListView) {
        queries.value = BrowseRequest(view, preferences.read(view))
    }
    fun openGroup(id: String?) { queries.update { it.copy(groupId = id) } }
    fun openPlaylist(id: String?) { playlistRequest.update { it.copy(id = id) } }
    fun setPlaylistQuery(query: LibraryQuery) {
        playlistRequest.update { it.copy(query = query) }
        preferences.write(query, SongListView.Playlists)
    }
    fun playPlaylist(entryId: String) {
        val displayed = playlist.value
        val index = displayed.rows.indexOfFirst { it.id == entryId }
        if (index >= 0) playback.queues.tap(displayed.rows.map { it.track }, index,
            if (displayed.query.text.isBlank()) SongListView.Playlists else SongListView.Search)
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
                if (queries.value.query.folderUri == uri) setQuery(queries.value.query.copy(folderUri = null))
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
        val index = visible.indexOfFirst { it.id == track.id }
        if (index >= 0) playback.queues.tap(visible, index,
            if (library.value.query.text.isBlank()) library.value.view else SongListView.Search)
    }
    fun refreshAccess() {
        val missing = library.value.snapshot.folders.filterNot(repository::hasAccess).map { it.uri }.toSet()
        mutableLibrary.update { it.copy(unavailableFolders = missing) }
    }
    override fun onCleared() { playback.close() }
}

private data class BrowseRequest(val view: SongListView = SongListView.Songs, val query: LibraryQuery, val groupId: String? = null)
private data class EvaluatedLibrary(val snapshot: LibrarySnapshot, val request: BrowseRequest,
    val query: LibraryQuery, val groups: List<LibraryGroup>, val tracks: List<Track>)
private data class PlaylistRequest(val id: String? = null, val query: LibraryQuery)
data class PlaylistUiState(val saved: PlaylistState = PlaylistState(), val selected: ManualPlaylist? = null,
    val query: LibraryQuery = LibraryViews.defaults(SongListView.Playlists), val rows: List<SongRow> = emptyList())
