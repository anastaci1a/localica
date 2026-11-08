package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LastFmController(private val store: LastFmStore, private val syncer: LastFmSync,
    library: Flow<LibrarySnapshot>, val enabled: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis) : PlayCountSource {
    private val mutable = MutableStateFlow(LastFmState())
    val state = mutable.asStateFlow()
    val settings: Flow<RankingSettings> = state.map { RankingSettings(enabled && it.saved.useCounts) }.distinctUntilChanged()
    val matches: StateFlow<LastFmMatches> = combine(state.map { it.saved }.distinctUntilChanged(), library) { saved, snapshot ->
        LastFmMatcher.match(saved.snapshot, snapshot.tracks, saved.mappings)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, LastFmMatches(emptyMap(), emptyList()))
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex(); private val syncs = Mutex()
    private var generation = 0L
    private var syncJob: Job? = null
    init {
        scope.launch {
            try { mutable.value = LastFmState(saved = store.read(), loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = LastFmState(loading = false, problem = "Saved Last.fm data could not be read. Local counts are in use.") }
            finally { ready.complete(Unit) }
        }
    }
    override fun counts(window: ListeningWindow): Flow<Map<String, Long>> =
        if (window.from == Long.MIN_VALUE && enabled) matches.map { it.counts }.distinctUntilChanged() else flowOf(emptyMap())

    /** Entire-list totals remain external; recent windows deliberately use local events. */
    fun applyTo(local: Flow<ListeningStatistics>): Flow<ListeningStatistics> = combine(local, matches, settings) { own, remote, options ->
        if (!options.useExternalCounts) own else {
            val selected = RankingCounts(own.tracks, remote.counts, options)
            own.copy(tracks = (own.tracks.keys + remote.counts.keys).associateWith(selected::plays))
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    private suspend fun save(value: SavedLastFm) = withContext(NonCancellable) {
        store.write(value)
        mutable.value = mutable.value.copy(saved = value, problem = null)
    }
    private fun edit(change: (SavedLastFm) -> SavedLastFm) { scope.launch {
        ready.await()
        writes.withLock {
            try { save(change(state.value.saved)) }
            catch (_: Exception) { mutable.value = state.value.copy(problem = "Could not save Last.fm settings. Try again.") }
        }
    } }
    fun useCounts(value: Boolean) = edit { it.copy(useCounts = value && enabled && it.snapshot != null) }
    fun map(remoteKey: String, trackId: String) = edit { it.copy(mappings = it.mappings + (remoteKey to trackId)) }
    fun resetMappings() = edit { it.copy(mappings = emptyMap()) }
    fun disconnect() {
        syncJob?.cancel()
        scope.launch {
            ready.await()
            writes.withLock {
                generation++
                try { save(SavedLastFm(attemptedAt = state.value.saved.attemptedAt)); mutable.value = state.value.copy(syncing = false) }
                catch (_: Exception) { mutable.value = state.value.copy(syncing = false, problem = "Could not disconnect Last.fm. Try again.") }
            }
        }
    }
    fun sync(username: String) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            ready.await()
            if (!syncs.tryLock()) return@launch
            var token = -1L
            try {
                val user = username.trim()
                if (!enabled) throw LastFmFailure("Last.fm is disabled in this build because it has no API key.")
                if (user.isBlank() || user.length > 128 || user.any { it.isWhitespace() || it.isISOControl() })
                    throw LastFmFailure("Enter a valid Last.fm username.")
                writes.withLock {
                    val saved = state.value.saved
                    val time = now()
                    val previous = saved.attemptedAt
                    if (previous != null && time >= previous && time - previous < 60_000)
                        throw LastFmFailure("Wait a minute between Last.fm syncs.")
                    generation++; token = generation
                    val next = if (user.equals(saved.username, true)) saved.copy(attemptedAt = time)
                        else SavedLastFm(username = user, attemptedAt = time)
                    save(next)
                    mutable.value = state.value.copy(syncing = true)
                }
                val tracks = syncer.fetch(user)
                writes.withLock {
                    if (generation == token) save(state.value.saved.copy(snapshot = LastFmSnapshot(user, now(), tracks)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                writes.withLock {
                    if (token == -1L || generation == token) mutable.value = state.value.copy(problem =
                        (failure as? LastFmFailure)?.message ?: "Sync failed. Your previous Last.fm snapshot is kept.")
                }
            } finally {
                withContext(NonCancellable) { writes.withLock {
                    if (generation == token) mutable.value = state.value.copy(syncing = false)
                } }
                syncs.unlock()
            }
        }
    }
}
