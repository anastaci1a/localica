package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LastFmController(private val store: LastFmStore, private val syncer: LastFmSync,
    library: Flow<LibrarySnapshot>, val enabled: Boolean, val networkConsent: NetworkConsent,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis, private val background: LastFmBackground? = null,
    private val localStatistics: Flow<ListeningStatistics> = flowOf(ListeningStatistics()),
    private val matchCache: LastFmMatchCacheStore = NoLastFmMatchCacheStore,
    /** Tests inject a gated matcher to prove cancellation and publication ownership. */
    private val matcher: (LastFmSnapshot, List<io.github.anastaci1a.localica.library.Track>, Map<String, String>, () -> Unit) -> LastFmMatches =
        { snapshot, tracks, mappings, checkActive -> LastFmMatcher.match(snapshot, tracks, mappings, checkActive) }) : PlayCountSource {
    private val mutable = MutableStateFlow(LastFmState())
    val state = mutable.asStateFlow()
    private val local = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val localReady = CompletableDeferred<Unit>()
    private val requestedActive = MutableStateFlow<Boolean?>(null)
    val active = combine(state.map { it.saved.active }.distinctUntilChanged(), requestedActive) { stored, requested -> requested ?: stored }
        .stateIn(scope, SharingStarted.Eagerly, true)
    private fun featureActive() = requestedActive.value ?: state.value.saved.active
    val settings: Flow<RankingSettings> = combine(state, networkConsent.allowed, requestedActive) { state, allowed, requested ->
        val saved = state.saved
        RankingSettings(enabled && allowed && (requested ?: saved.active) && saved.useCounts && saved.snapshot != null &&
            saved.username.equals(saved.snapshot.username, true), authoritativeZero = true)
    }.distinctUntilChanged()
    /** One library subscription serves the settings page and matching; each is one database read per change. */
    private val sharedLibrary: SharedFlow<LibrarySnapshot> = library.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    val catalog: StateFlow<LibrarySnapshot> = sharedLibrary.stateIn(scope, SharingStarted.Eagerly, LibrarySnapshot())
    private class MatchInput(val snapshot: LastFmSnapshot?, val mappings: Map<String, String>, val catalog: LibrarySnapshot) {
        fun sameAs(other: MatchInput?) = other != null && snapshot === other.snapshot && mappings == other.mappings && catalog === other.catalog
    }
    private val matchInputs: Flow<MatchInput> = combine(state.map { it.saved.snapshot to it.saved.mappings }.distinctUntilChanged(), sharedLibrary) {
        (snapshot, mappings), catalog -> MatchInput(snapshot, mappings, catalog)
    }
    private val detailDemand = MutableStateFlow(0)
    private val mutableMatches = MutableStateFlow(LastFmMatches(emptyMap(), emptyList()))
    private val countSource = MutableStateFlow(LastFmMatches(emptyMap(), emptyList()))
    /**
     * The latest COMPLETE match of the current archive, mappings and library, or the empty result until one
     * exists (#779). A launch whose inputs equal the persisted cache does not match: counts flow from the
     * cache through [counts], and this stays empty (no source snapshot) until [details] is collected or the
     * inputs change. Observing this flow never starts matching; only collecting [details] does.
     */
    val matches: StateFlow<LastFmMatches> = mutableMatches.asStateFlow()
    /** Current per-song counts and recording groups: complete, or restored from the cache (`complete = false`). */
    val counts: StateFlow<LastFmMatches> = countSource.asStateFlow()
    /** Collecting this is the only demand for review and resolution lists; the full match runs while collected. */
    val details: Flow<LastFmMatches> = flow {
        detailDemand.update { it + 1 }
        try { emitAll(mutableMatches) } finally { detailDemand.update { it - 1 } }
    }
    private val cacheLock = Mutex()
    private var cache: LastFmMatchCache? = null
    private var cacheLoaded = false
    /** Written only by the request owner; the last complete result and its inputs. */
    private var complete: Pair<MatchInput, LastFmMatches>? = null
    private suspend fun loadedCache(): LastFmMatchCache? = cacheLock.withLock {
        if (!cacheLoaded) {
            cache = try { matchCache.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            cacheLoaded = true
        }
        cache
    }
    private suspend fun cachedMatches(input: MatchInput): LastFmMatches? {
        val snapshot = input.snapshot ?: return null
        val current = loadedCache() ?: return null
        val key = withContext(Dispatchers.Default) { LastFmMatchKey.of(input.catalog, snapshot, input.mappings) }
        return current.takeIf { it.matches(key) }?.toMatches(snapshot)
    }
    /** Runs only inside the request owner, so a newer input or demand change cancels it before any publication. */
    private suspend fun fullMatch(input: MatchInput): LastFmMatches {
        val snapshot = input.snapshot ?: return LastFmMatches(emptyMap(), emptyList())
        val started = now()
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            matcher(snapshot, input.catalog.tracks, input.mappings) { context.ensureActive() }
        }
        io.github.anastaci1a.localica.diagnostics.Diagnostics.info("LastFm",
            "Matched ${snapshot.tracks.size} archive songs against ${input.catalog.tracks.size} library songs in ${now() - started} ms")
        val key = withContext(Dispatchers.Default) { LastFmMatchKey.of(input.catalog, snapshot, input.mappings) }
        currentCoroutineContext().ensureActive()
        complete = input to result
        val entry = LastFmMatchCache(key.library, key.archive, key.mappings, result.counts, result.recordingGroups,
            result.matched, result.notInLibrary, result.dismissed)
        cacheLock.withLock {
            cache = entry; cacheLoaded = true
            try { matchCache.write(entry) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A lost cache only costs one full match on the next launch. */ }
        }
        return result
    }
    private fun publish(countsValue: LastFmMatches, matchesValue: LastFmMatches) {
        countSource.value = countsValue
        mutableMatches.value = matchesValue
    }
    val overlay: StateFlow<LastFmOverlay> = combine(countSource, local) { matched, counts ->
        matched.withLocalOverlay(matched.sourceSnapshot, counts)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, LastFmOverlay(emptyMap(), 0))
    private val selection = combine(state, requestedActive) { current, requested ->
        Triple(requested ?: current.saved.active, current.saved.useCounts, current.saved.username)
    }.distinctUntilChanged()
    private val selectedCounts: Flow<Map<String, Long>> = combine(countSource, local, selection, networkConsent.allowed) { matched, localCounts, selected, allowed ->
        if (enabled && allowed && selected.first && selected.second && matched.sourceSnapshot != null &&
            selected.third.equals(matched.sourceSnapshot.username, true)) matched.withLocalOverlay(matched.sourceSnapshot, localCounts).counts else emptyMap()
    }.flowOn(Dispatchers.Default).distinctUntilChanged()
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex(); private val syncs = Mutex()
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var generation = 0L
    private var syncJob: Job? = null
    private var workerJob: Job? = null
    init {
        scope.launch {
            // The single request owner: a newer input or a demand change cancels the running match whole,
            // so nothing stale is ever published or persisted (#779).
            combine(matchInputs, detailDemand.map { it > 0 }.distinctUntilChanged()) { input, demand -> input to demand }
                .collectLatest { (input, demand) ->
                    val empty = LastFmMatches(emptyMap(), emptyList())
                    val memo = complete?.takeIf { input.sameAs(it.first) }?.second
                    when {
                        input.snapshot == null -> publish(empty, empty)
                        memo != null -> publish(memo, memo)
                        demand -> fullMatch(input).let { publish(it, it) }
                        else -> {
                            val cached = cachedMatches(input)
                            // Restored counts: review lists do not exist yet, so matches is explicitly incomplete and sourceless.
                            if (cached != null) publish(cached, LastFmMatches(emptyMap(), emptyList(), complete = false))
                            else fullMatch(input).let { publish(it, it) }
                        }
                    }
                }
        }
        scope.launch { ready.await(); for (edit in edits) {
            try { edit() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(problem = "Could not save Last.fm data. Your archive is kept.") }
        } }
        scope.launch {
            try { localStatistics.collect { local.value = it.tracks; localReady.complete(Unit) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { localReady.completeExceptionally(failure) }
        }
        scope.launch { networkConsent.allowed.collect { if (!it) {
            syncJob?.cancel(); workerJob?.cancel(); background?.cancelIfConsentDenied()
        } } }
        if (background != null) scope.launch {
            ready.await(); var observedWork = false
            background.updates.collect { work -> writes.withLock {
                if (work.active) {
                    observedWork = true
                    mutable.value = state.value.copy(syncing = true, progress = work.progress ?: state.value.progress ?: LastFmProgress())
                } else if (observedWork) {
                    observedWork = false
                    if (workerJob?.isActive != true) mutable.value = state.value.copy(syncing = false, progress = null,
                        problem = work.problem ?: state.value.problem)
                }
            } }
        }
        scope.launch {
            try {
                localReady.await()
                var saved = store.read()
                // Old archives have no safe counter boundary. Adopt today's committed local counts once, without a network request.
                val archive = saved.snapshot
                if (archive != null && archive.localBaseline == null) {
                    saved = saved.copy(snapshot = archive.copy(localBaseline = local.value.toMap()))
                    store.write(saved)
                }
                mutable.value = LastFmState(saved = saved, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = LastFmState(loading = false, problem = "Saved Last.fm data or local counts could not be read. Local counts are in use.") }
            finally { ready.complete(Unit) }
        }
    }
    override fun counts(window: ListeningWindow): Flow<Map<String, Long>> =
        if (window.from == Long.MIN_VALUE) selectedCounts else flowOf(emptyMap())

    fun applyTo(local: Flow<ListeningStatistics>): Flow<ListeningStatistics> = combine(local, selectedCounts, settings) { own, remote, options ->
        if (!options.useExternalCounts) own else {
            io.github.anastaci1a.localica.diagnostics.WorkCounters.lastFmApplications.incrementAndGet()
            val selected = RankingCounts(own.tracks, remote, options)
            own.copy(tracks = (own.tracks.keys + remote.keys).associateWith(selected::plays))
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    private suspend fun save(value: SavedLastFm) = withContext(NonCancellable) {
        store.write(value); mutable.value = mutable.value.copy(saved = value, problem = null)
    }
    private fun edit(change: (SavedLastFm) -> SavedLastFm) {
        edits.trySend { writes.withLock {
            try { save(change(state.value.saved)) }
            catch (_: Exception) { mutable.value = state.value.copy(problem = "Could not save Last.fm settings. Try again.") }
        } }
    }
    private suspend fun settingsSettled() {
        val settled = CompletableDeferred<Unit>()
        edits.send { settled.complete(Unit) }; settled.await()
    }
    fun useCounts(value: Boolean) {
        if (!value) cancelSync()
        edit { it.copy(useCounts = value && enabled && it.snapshot != null) }
    }
    fun setActive(value: Boolean) {
        requestedActive.value = value
        if (!value) cancelSync()
        edit { it.copy(active = value, username = if (value && it.username.isBlank()) it.snapshot?.username.orEmpty() else it.username) }
    }
    fun map(remoteKey: String, trackId: String) = edit { it.copy(mappings = it.mappings + (remoteKey to trackId)) }
    /** A review popup stays open until its serialized choice has reached durable storage. */
    suspend fun mapAndAwait(remoteKey: String, trackId: String): Boolean {
        ready.await()
        val result = CompletableDeferred<Boolean>()
        edits.send { writes.withLock {
            try { save(state.value.saved.copy(mappings = state.value.saved.mappings + (remoteKey to trackId))); result.complete(true) }
            catch (_: Exception) {
                mutable.value = state.value.copy(problem = "Could not save Last.fm match. Try again.")
                result.complete(false)
            }
        } }
        return result.await()
    }
    fun undoJudgment(remoteKey: String) = map(remoteKey, LastFmMatcher.REVIEW)
    fun resetMapping(remoteKey: String) = edit { it.copy(mappings = it.mappings - remoteKey) }
    fun resetMappings() = edit { it.copy(mappings = emptyMap()) }
    fun keepSyncingInBackground(value: Boolean) = edit { it.copy(keepSyncingInBackground = value) }
    fun cancelSync() { syncJob?.cancel(); workerJob?.cancel(); background?.cancel() }
    fun disconnect() {
        requestedActive.value = false
        changeArchive { it.copy(username = "", active = false, activeAttempt = null) }
    }
    fun deleteSyncedData() = changeArchive { it.copy(snapshot = null, mappings = emptyMap(), useCounts = false, activeAttempt = null) }
    private fun changeArchive(change: (SavedLastFm) -> SavedLastFm) {
        cancelSync()
        edits.trySend {
            background?.cancelAndAwait()
            writes.withLock {
                generation++
                try {
                    save(change(state.value.saved)); mutable.value = state.value.copy(syncing = false, progress = null)
                    if (state.value.saved.snapshot == null) cacheLock.withLock {
                        cache = null; cacheLoaded = true; complete = null
                        try { matchCache.write(null) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = state.value.copy(syncing = false, progress = null, problem = "Could not save Last.fm data. Try again.") }
            }
        }
    }
    fun sync(username: String, fullRescan: Boolean = false) {
        if (syncJob?.isActive == true || workerJob?.isActive == true) return
        syncJob = scope.launch {
            ready.await(); settingsSettled()
            if (state.value.saved.keepSyncingInBackground && background != null && enabled && networkConsent.allowed.value && featureActive()) {
                try { if (fullRescan) background.enqueueFullRescan(username.trim()) else background.enqueue(username.trim()) }
                catch (_: Exception) { mutable.value = state.value.copy(problem = "Could not start background sync. Try again.") }
            } else performSync(username, fullRescan = fullRescan)
        }
    }
    suspend fun syncInBackground(username: String, attempt: String, fullRescan: Boolean = false,
        progress: suspend (LastFmProgress) -> Unit = {}): Boolean {
        require(attempt.isNotBlank()); ready.await(); settingsSettled(); workerJob = currentCoroutineContext()[Job]
        try { return performSync(username, attempt, fullRescan, progress) }
        finally { workerJob = null }
    }
    private fun carryChoices(old: LastFmSnapshot?, tracks: List<LastFmTrack>, choices: Map<String, String>): Map<String, String> {
        fun identity(track: LastFmTrack) = normalize(track.artist) to normalize(track.title)
        val previous = old?.tracks.orEmpty().groupBy(::identity)
        val incoming = tracks.groupBy(::identity)
        return choices.toMutableMap().apply {
            incoming.forEach { (name, rows) ->
                val before = previous[name]?.singleOrNull()
                if (before != null && rows.size == 1 && !containsKey(rows.single().key)) {
                    choices[before.key]?.let { put(rows.single().key, it) }
                }
            }
        }
    }
    private suspend fun performSync(username: String, attempt: String? = null, fullRescan: Boolean = false,
        progress: suspend (LastFmProgress) -> Unit = {}): Boolean {
        ready.await(); if (!syncs.tryLock()) return false
        var token = -1L; var startedAt = 0L
        try {
            val user = username.trim()
            if (!enabled) throw LastFmFailure("Last.fm is disabled in this build because it has no API key.")
            if (!networkConsent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
            if (!featureActive()) throw LastFmFailure("Last.fm is off. Your saved archive is kept.")
            if (user.isBlank() || user.length > 128 || user.any { it.isWhitespace() || it.isISOControl() }) throw LastFmFailure("Enter a valid Last.fm username.")
            writes.withLock {
                val saved = state.value.saved; val time = now(); val previous = saved.attemptedAt
                val resuming = attempt != null && attempt == saved.activeAttempt && user.equals(saved.username, true)
                if (!resuming && previous != null && time >= previous && time - previous < 60_000) throw LastFmFailure("Wait a minute between Last.fm syncs.")
                generation++; token = generation; startedAt = time
                save(saved.copy(username = user, attemptedAt = time, activeAttempt = attempt))
                mutable.value = state.value.copy(syncing = true, progress = LastFmProgress())
            }
            progress(LastFmProgress())
            val previous = state.value.saved.snapshot?.takeIf { it.username.equals(user, true) }
            val tracks = syncer.refresh(user, previous, fullRescan, startedAt / 1000, onProgress = { update ->
                currentCoroutineContext().ensureActive()
                writes.withLock { if (generation == token) mutable.value = state.value.copy(progress = update) }
                progress(update)
            }) { networkConsent.allowed.value && state.value.saved.active && generation == token }
            val baseline = localStatistics.first().tracks.toMap()
            currentCoroutineContext().ensureActive()
            writes.withLock {
                currentCoroutineContext().ensureActive()
                if (generation != token || !networkConsent.allowed.value || !featureActive()) return false
                val saved = state.value.saved
                val choices = carryChoices(saved.snapshot, tracks, saved.mappings)
                save(saved.copy(snapshot = LastFmSnapshot(user, now(), tracks, baseline, startedAt / 1000), mappings = choices, activeAttempt = null))
            }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            writes.withLock { if (token == -1L || generation == token) mutable.value = state.value.copy(problem =
                (failure as? LastFmFailure)?.message ?: "Sync failed. Your previous Last.fm archive is kept.") }
            return false
        } finally {
            withContext(NonCancellable) { writes.withLock { if (generation == token) mutable.value = state.value.copy(syncing = false, progress = null) } }
            syncs.unlock()
        }
    }
}

data class LastFmBackgroundState(val active: Boolean, val progress: LastFmProgress? = null, val problem: String? = null)
interface LastFmBackground {
    val updates: Flow<LastFmBackgroundState> get() = emptyFlow()
    suspend fun enqueue(username: String)
    suspend fun enqueueFullRescan(username: String) = enqueue(username)
    fun cancel()
    suspend fun cancelAndAwait() = cancel()
    fun cancelIfConsentDenied() = cancel()
}
