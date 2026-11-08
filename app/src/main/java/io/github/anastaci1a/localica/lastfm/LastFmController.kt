package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LastFmController(private val store: LastFmStore, private val syncer: LastFmSync,
    library: Flow<LibrarySnapshot>, val enabled: Boolean, val networkConsent: NetworkConsent,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val background: LastFmBackground? = null) : PlayCountSource {
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
    private var workerJob: Job? = null
    init {
        scope.launch { networkConsent.allowed.collect { if (!it) {
            syncJob?.cancel(); workerJob?.cancel(); background?.cancelIfConsentDenied()
        } } }
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
    fun keepSyncingInBackground(value: Boolean) = edit { it.copy(keepSyncingInBackground = value) }
    fun cancelSync() { syncJob?.cancel(); workerJob?.cancel(); background?.cancel() }
    fun resetMappings() = edit { it.copy(mappings = emptyMap()) }
    fun disconnect() {
        cancelSync()
        scope.launch {
            ready.await()
            writes.withLock {
                generation++
                try { save(SavedLastFm(attemptedAt = state.value.saved.attemptedAt,
                    keepSyncingInBackground = state.value.saved.keepSyncingInBackground)); mutable.value = state.value.copy(syncing = false, progress = null) }
                catch (_: Exception) { mutable.value = state.value.copy(syncing = false, problem = "Could not disconnect Last.fm. Try again.") }
            }
        }
    }
    fun sync(username: String) {
        if (syncJob?.isActive == true || workerJob?.isActive == true) return
        syncJob = scope.launch {
            ready.await()
            if (state.value.saved.keepSyncingInBackground && background != null) {
                if (!enabled || !networkConsent.allowed.value) {
                    performSync(username)
                } else try { background.enqueue(username.trim()) }
                catch (_: Exception) { mutable.value = state.value.copy(problem = "Could not start background sync. Try again.") }
            } else performSync(username)
        }
    }

    /** A restarted WorkManager request keeps its attempt identity, not a partially collected page list. */
    suspend fun syncInBackground(username: String, attempt: String, progress: suspend (LastFmProgress) -> Unit = {}): Boolean {
        require(attempt.isNotBlank())
        ready.await()
        workerJob = currentCoroutineContext()[Job]
        try { return performSync(username, attempt, progress) }
        finally { workerJob = null }
    }

    private suspend fun performSync(username: String, attempt: String? = null,
        progress: suspend (LastFmProgress) -> Unit = {}): Boolean {
        ready.await()
        if (!syncs.tryLock()) return false
        var token = -1L
        try {
            val user = username.trim()
            if (!enabled) throw LastFmFailure("Last.fm is disabled in this build because it has no API key.")
            if (!networkConsent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
            if (user.isBlank() || user.length > 128 || user.any { it.isWhitespace() || it.isISOControl() })
                throw LastFmFailure("Enter a valid Last.fm username.")
            writes.withLock {
                val saved = state.value.saved
                val time = now()
                val previous = saved.attemptedAt
                val resuming = attempt != null && attempt == saved.activeAttempt && user.equals(saved.username, true)
                if (!resuming && previous != null && time >= previous && time - previous < 60_000)
                    throw LastFmFailure("Wait a minute between Last.fm syncs.")
                generation++; token = generation
                val next = if (user.equals(saved.username, true)) saved.copy(attemptedAt = time, activeAttempt = attempt)
                    else SavedLastFm(username = user, attemptedAt = time, activeAttempt = attempt,
                        keepSyncingInBackground = saved.keepSyncingInBackground)
                save(next)
                mutable.value = state.value.copy(syncing = true, progress = LastFmProgress())
            }
            progress(LastFmProgress())
            val tracks = syncer.fetch(user, onProgress = { update ->
                currentCoroutineContext().ensureActive()
                writes.withLock { if (generation == token) mutable.value = state.value.copy(progress = update) }
                progress(update)
            }) { networkConsent.allowed.value && generation == token }
            currentCoroutineContext().ensureActive()
            writes.withLock {
                currentCoroutineContext().ensureActive()
                if (generation != token || !networkConsent.allowed.value) return false
                save(state.value.saved.copy(snapshot = LastFmSnapshot(user, now(), tracks), activeAttempt = null))
            }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            writes.withLock {
                if (token == -1L || generation == token) mutable.value = state.value.copy(problem =
                    (failure as? LastFmFailure)?.message ?: "Sync failed. Your previous Last.fm snapshot is kept.")
            }
            return false
        } finally {
            withContext(NonCancellable) { writes.withLock {
                if (generation == token) mutable.value = state.value.copy(syncing = false, progress = null)
            } }
            syncs.unlock()
        }
    }
}

/** Scheduling stays Android-owned; pure matching/controller tests never initialize WorkManager. */
interface LastFmBackground {
    suspend fun enqueue(username: String)
    fun cancel()
    fun cancelIfConsentDenied() = cancel()
}
