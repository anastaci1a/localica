package io.github.anastaci1a.localica.artistphoto

import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Disk and network work runs on IO; already resolved photos are also available synchronously. */
class ArtistPhotos internal constructor(private val consent: NetworkConsent, private val disk: ArtistPhotoDisk,
    private val http: PhotoTransport, private val now: () -> Long = System::currentTimeMillis,
    private val prepare: (ByteArray) -> PreparedArtistPhoto = { PreparedArtistPhoto(it) }) {
    constructor(consent: NetworkConsent, cacheDirectory: File, matchDirectory: File, maxCacheBytes: Long = 512L * 1024 * 1024,
        prepare: (ByteArray) -> PreparedArtistPhoto = { PreparedArtistPhoto(it) }) :
        this(consent, ArtistPhotoDisk(cacheDirectory, matchDirectory, maxCacheBytes), ArtistPhotoHttp(consent), prepare = prepare)
    private val providers = ArtistPhotoProviders(http)
    private val lock = Mutex()
    private val fetches = ConcurrentHashMap<String, Mutex>()
    private val candidateCache = ConcurrentHashMap<ArtistPhotoRequest, Pair<Long, ArtistPhotoCandidates>>()
    private val remoteCandidates = ConcurrentHashMap<ArtistPhotoChoice, RemoteArtist>()
    private val previewFetches = ConcurrentHashMap<ArtistPhotoChoice, Mutex>()
    private val readyPhotos = ConcurrentHashMap<String, ArtistPhotoState>()
    private val mutableIndexReady = MutableStateFlow(false)
    val indexReady = mutableIndexReady.asStateFlow()
    private data class Snapshot(val saved: SavedPhotoChoice, val cached: CachedArtistPhoto?, val time: Long)

    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    /** Memory only. Callers must still enforce display consent. */
    internal fun peekKey(cacheKey: String): ArtistPhotoState? = readyPhotos[cacheKey]
        ?: if (indexReady.value) ArtistPhotoState(null) else null
    /** Load metadata, never pixels, before publishing startup readiness. */
    suspend fun loadIndex() = withContext(Dispatchers.IO) { lock.withLock {
        if (!mutableIndexReady.value) {
            readyPhotos.clear()
            disk.photoIndex().forEach { (key, photo) -> readyPhotos[key] = ArtistPhotoState(null, photo) }
            disk.takeChangedKeys()
            mutableIndexReady.value = true
            mutableChanges.update { it + 1 }
        }
    } }
    private fun rememberReady(artistKey: String, photo: CachedArtistPhoto?): CachedArtistPhoto? {
        readyPhotos[artistPhotoCacheKey(artistKey)] = ArtistPhotoState(null, photo)
        return photo
    }
    private fun readReady(artistKey: String): CachedArtistPhoto? = rememberReady(artistKey,
        if (disk.choice(artistKey).mode == "cleared") null else disk.cached(artistKey, now()))
    private fun refreshReady() {
        for (key in disk.takeChangedKeys()) readyPhotos[key] = ArtistPhotoState(null, disk.indexedPhoto(key))
    }
    suspend fun selection(artistKey: String): ArtistPhotoSelection = withContext(Dispatchers.IO) { lock.withLock {
        val saved = disk.choice(artistKey)
        ArtistPhotoSelection(saved.choice, saved.pendingChoice, saved.pendingChoice != null && saved.attemptedAt != null)
    } }
    suspend fun ready(request: ArtistPhotoRequest): ArtistPhotoState = withContext(Dispatchers.IO) { lock.withLock {
        ArtistPhotoState(request.latestAlbumArt, readReady(request.artistKey))
    } }
    suspend fun needsFetch(request: ArtistPhotoRequest): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val saved = disk.choice(request.artistKey)
        val time = now()
        val cached = disk.cached(request.artistKey, time)
        consent.allowed.value && saved.mode != "cleared" &&
            saved.attemptedAt?.let { time < it || time - it < PHOTO_RETRY_MS } != true &&
            (saved.pendingChoice != null || cached == null || time < cached.fetchedAt || time - cached.fetchedAt >= PHOTO_REFRESH_MS)
    } }
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) { lock.withLock { disk.cacheBytes() } }
    suspend fun chosenBytes(): Long = withContext(Dispatchers.IO) { lock.withLock { disk.chosenBytes() } }
    suspend fun cacheBudget(): Long = withContext(Dispatchers.IO) { lock.withLock { disk.budget() } }
    suspend fun setCacheBudget(bytes: Long) = withContext(Dispatchers.IO) { lock.withLock {
        disk.setBudget(bytes); refreshReady(); mutableChanges.update { it + 1 }
    } }
    fun retainDisplay(artistKey: String): AutoCloseable = disk.retainDisplay(artistKey)
    fun retainPreview(choice: ArtistPhotoChoice): AutoCloseable = disk.retainPreview(choice)
    suspend fun displayed(artistKey: String) = withContext(Dispatchers.IO) { lock.withLock { disk.displayed(artistKey, now()) } }
    suspend fun clearCache() = withContext(Dispatchers.IO) { lock.withLock {
        disk.clearCache(); refreshReady(); mutableChanges.update { it + 1 }
    } }
    /** Refresh keeps manual matches and deliberate album-art-only choices. */
    suspend fun refreshAll(requests: List<ArtistPhotoRequest>) {
        try {
            for (request in requests) {
                if (!consent.allowed.value) break
                observe(request, refresh = true).collect { }
            }
        } finally { mutableChanges.update { it + 1 } }
    }

    fun observe(request: ArtistPhotoRequest, refresh: Boolean = false): Flow<ArtistPhotoState> = flow {
        emit(ArtistPhotoState(request.latestAlbumArt, peekKey(artistPhotoCacheKey(request.artistKey))?.photo))
        fetches.computeIfAbsent(request.artistKey) { Mutex() }.withLock fetch@{
            val snapshot = lock.withLock {
                disk.trim()
                refreshReady()
                val time = now()
                Snapshot(disk.choice(request.artistKey), readReady(request.artistKey), time)
            }
            val (saved, cached, time) = snapshot
            if (saved.mode == "cleared") return@fetch
            if (cached != null) emit(ArtistPhotoState(request.latestAlbumArt, cached))
            if (!consent.allowed.value || (!refresh && saved.pendingChoice == null && cached != null &&
                    time >= cached.fetchedAt && time - cached.fetchedAt < PHOTO_REFRESH_MS)) return@fetch
            if (!refresh && saved.attemptedAt?.let { time < it || time - it < PHOTO_RETRY_MS } == true) return@fetch
            val fetched = safely {
                val preview = saved.pendingChoice?.let { choice -> lock.withLock { disk.preview(choice, time) } }
                if (preview != null) {
                    val choice = requireNotNull(saved.pendingChoice)
                    RemoteArtist(choice, request.name, request.localReleases.toList(), attribution = preview.attribution) to preview.file.readBytes()
                } else {
                val selected = (saved.pendingChoice ?: saved.choice)?.let { choice -> providers.resolve(choice)?.takeIf {
                    saved.mode == "manual" || ArtistPhotoMatching.accepts(request, it.name, it.releases)
                } }
                selected?.let { download(it) } ?: if (saved.mode == "manual") null else find(request, saved.choice)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!consent.allowed.value) return@fetch
            val encoded = fetched?.let { safely { prepare(it.second) } }
            var superseded = false
            val refreshed = safely { lock.withLock {
                // A pick/clear/reset arriving during IO owns the next commit, even if this read could not stop.
                if (disk.choice(request.artistKey) != saved) { superseded = true; return@withLock null }
                if (fetched == null || encoded == null) {
                    disk.saveChoice(request.artistKey, saved.copy(attemptedAt = time))
                    return@withLock null
                }
                val (remote, bytes) = fetched
                val photo = disk.savePhoto(request.artistKey, remote, encoded.full, time, encoded.thumbnail) {
                    disk.saveChoice(request.artistKey, SavedPhotoChoice(saved.mode, remote.choice, time))
                }
                rememberReady(request.artistKey, photo)
                refreshReady()
            } }
            if (superseded) return@fetch
            if (refreshed != null) emit(ArtistPhotoState(request.latestAlbumArt, refreshed))
            else emit(ArtistPhotoState(request.latestAlbumArt, cached, failed = true))
            mutableChanges.update { it + 1 }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun download(remote: RemoteArtist): Pair<RemoteArtist, ByteArray>? = safely {
        val image = remote.image ?: return@safely null
        if (!consent.allowed.value) return@safely null
        val bytes = http.get(image, PHOTO_MAX_BYTES)
        if (!consent.allowed.value || !photoHeader(bytes)) return@safely null
        remote to bytes
    }

    private suspend fun find(request: ArtistPhotoRequest, exclude: ArtistPhotoChoice?): Pair<RemoteArtist, ByteArray>? {
        for (provider in ArtistPhotoProvider.entries) {
            if (!consent.allowed.value) return null
            val candidates = safely { providers.search(provider, request) }.orEmpty()
            for (candidate in candidates) {
                if (candidate.choice == exclude || !ArtistPhotoMatching.accepts(request, candidate.name, candidate.releases)) continue
                val resolved = if (provider == ArtistPhotoProvider.DEEZER) candidate else providers.resolve(candidate.choice)
                val photo = resolved?.let { download(it) }
                if (photo != null) return photo
            }
        }
        return null
    }

    /** First emission is immediate; each provider match appears without waiting for the next match. */
    fun candidatePages(request: ArtistPhotoRequest): Flow<ArtistPhotoCandidates> = flow {
        val identity = request.copy(latestAlbumArt = null)
        val cached = candidateCache[identity]
        if (cached != null && now() - cached.first in 0 until PHOTO_RETRY_MS && !cached.second.loading) {
            emit(cached.second); return@flow
        }
        val items = mutableListOf<ArtistPhotoCandidate>()
        emit(ArtistPhotoCandidates())
        if (!consent.allowed.value) return@flow
        var failed = false
        for (provider in ArtistPhotoProvider.entries) {
            if (safely { providers.search(provider, request) { remote ->
                remoteCandidates[remote.choice] = remote
                items += ArtistPhotoCandidate(remote.choice, plainPhotoText(remote.name).orEmpty(),
                    remote.releases.map { plainPhotoText(it).orEmpty() },
                    ArtistPhotoMatching.accepts(request, remote.name, remote.releases))
                emit(ArtistPhotoCandidates(items.toList()))
            } } == null) failed = true
        }
        val result = ArtistPhotoCandidates(items.toList(), loading = false, failed = failed)
        if (!failed) {
            if (candidateCache.size >= 100) candidateCache.clear()
            candidateCache[identity] = now() to result
        }
        emit(result)
    }.flowOn(Dispatchers.IO)

    suspend fun preview(choice: ArtistPhotoChoice): CachedArtistPhoto? = withContext(Dispatchers.IO) {
        if (!consent.allowed.value) return@withContext null
        previewFetches.computeIfAbsent(choice) { Mutex() }.withLock {
            lock.withLock { disk.preview(choice, now()) }?.let { return@withLock it }
            val remote = remoteCandidates[choice]?.takeIf { it.image != null } ?: providers.resolve(choice)
                ?: return@withLock null
            val (_, bytes) = download(remote) ?: return@withLock null
            val encoded = prepare(bytes)
            lock.withLock { if (consent.allowed.value) safely { disk.savePreview(remote, encoded, now()) } else null }
        }
    }

    /** Explicit review API. Names and releases are bounded; remote image URLs are never returned. */
    suspend fun candidates(request: ArtistPhotoRequest): List<ArtistPhotoCandidate> = withContext(Dispatchers.IO) {
        if (!consent.allowed.value) return@withContext emptyList()
        ArtistPhotoProvider.entries.flatMap { provider -> safely { providers.search(provider, request) }.orEmpty().map {
            ArtistPhotoCandidate(it.choice, plainPhotoText(it.name).orEmpty(), it.releases.map { title -> plainPhotoText(title).orEmpty() },
                ArtistPhotoMatching.accepts(request, it.name, it.releases))
        } }
    }
    /** A pending deliberate choice can override matching, while ready pixels remain until replacement succeeds. */
    suspend fun pick(artistKey: String, choice: ArtistPhotoChoice) = withContext(Dispatchers.IO) { lock.withLock {
        require(if (choice.provider == ArtistPhotoProvider.DEEZER) choice.id.matches(Regex("[1-9][0-9]{0,18}"))
            else choice.id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        disk.saveChoice(artistKey, disk.choice(artistKey).copy(mode = "manual", attemptedAt = null, pendingChoice = choice))
        mutableChanges.update { it + 1 }
    } }
    /** Clear means album-art-only until reset or a deliberate re-pick, including after process death. */
    suspend fun clear(artistKey: String) = withContext(Dispatchers.IO) { lock.withLock {
        disk.saveChoice(artistKey, SavedPhotoChoice("cleared")); disk.removePhoto(artistKey); rememberReady(artistKey, null); mutableChanges.update { it + 1 }
    } }
    suspend fun reset(artistKey: String) = withContext(Dispatchers.IO) { lock.withLock {
        disk.reset(artistKey); rememberReady(artistKey, null); mutableChanges.update { it + 1 }
    } }
}
