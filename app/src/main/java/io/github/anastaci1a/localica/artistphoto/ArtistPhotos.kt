package io.github.anastaci1a.localica.artistphoto

import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Lazy data API for #661. Collection emits album art before disk/network work; all work runs on IO. */
class ArtistPhotos internal constructor(private val consent: NetworkConsent, private val disk: ArtistPhotoDisk,
    private val http: PhotoTransport, private val now: () -> Long = System::currentTimeMillis) {
    constructor(consent: NetworkConsent, cacheDirectory: File, matchDirectory: File, maxCacheBytes: Long = 64L * 1024 * 1024) :
        this(consent, ArtistPhotoDisk(cacheDirectory, matchDirectory, maxCacheBytes), ArtistPhotoHttp(consent))
    private val providers = ArtistPhotoProviders(http)
    private val lock = Mutex()

    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) { lock.withLock { disk.cacheBytes() } }
    suspend fun clearCache() = withContext(Dispatchers.IO) { lock.withLock {
        disk.clearCache(); mutableChanges.update { it + 1 }
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
        emit(ArtistPhotoState(request.latestAlbumArt))
        lock.withLock {
            disk.trim()
            val saved = disk.choice(request.artistKey)
            if (saved.mode == "cleared") return@withLock
            val time = now()
            val cached = disk.cached(request.artistKey, time)
            if (cached != null) emit(ArtistPhotoState(request.latestAlbumArt, cached))
            if (!consent.allowed.value || (!refresh && cached != null && time >= cached.fetchedAt && time - cached.fetchedAt < PHOTO_REFRESH_MS)) return@withLock
            if (!refresh && saved.attemptedAt?.let { time < it || time - it < PHOTO_RETRY_MS } == true) return@withLock
            val refreshed = safely {
                disk.saveChoice(request.artistKey, saved.copy(attemptedAt = time))
                val selected = saved.choice?.let { choice -> providers.resolve(choice)?.takeIf {
                    saved.mode == "manual" || ArtistPhotoMatching.accepts(request, it.name, it.releases)
                } }
                val fetched = selected?.let { download(it) } ?: if (saved.mode == "manual") null else find(request, saved.choice)
                if (fetched == null || !consent.allowed.value) return@safely null
                val (remote, bytes) = fetched
                // A successful match is stable across reopen and cache eviction, even without user overrides.
                val photo = disk.savePhoto(request.artistKey, remote, bytes, time)
                disk.saveChoice(request.artistKey, SavedPhotoChoice(saved.mode, remote.choice, time))
                photo
            }
            if (refreshed != null) emit(ArtistPhotoState(request.latestAlbumArt, refreshed))
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

    /** Explicit review API. Names and releases are bounded; remote image URLs are never returned. */
    suspend fun candidates(request: ArtistPhotoRequest): List<ArtistPhotoCandidate> = withContext(Dispatchers.IO) { lock.withLock {
        if (!consent.allowed.value) return@withLock emptyList()
        ArtistPhotoProvider.entries.flatMap { provider -> safely { providers.search(provider, request) }.orEmpty().map {
            ArtistPhotoCandidate(it.choice, plainPhotoText(it.name).orEmpty(), it.releases.map { title -> plainPhotoText(title).orEmpty() },
                ArtistPhotoMatching.accepts(request, it.name, it.releases))
        } }
    } }
    /** A deliberate re-pick may override the automatic release check. Refetch happens on next collection. */
    suspend fun pick(artistKey: String, choice: ArtistPhotoChoice) = withContext(Dispatchers.IO) { lock.withLock {
        require(if (choice.provider == ArtistPhotoProvider.DEEZER) choice.id.matches(Regex("[1-9][0-9]{0,18}"))
            else choice.id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        disk.saveChoice(artistKey, SavedPhotoChoice("manual", choice)); disk.removePhoto(artistKey); mutableChanges.update { it + 1 }
    } }
    /** Clear means album-art-only until reset or a deliberate re-pick, including after process death. */
    suspend fun clear(artistKey: String) = withContext(Dispatchers.IO) { lock.withLock {
        disk.saveChoice(artistKey, SavedPhotoChoice("cleared")); disk.removePhoto(artistKey); mutableChanges.update { it + 1 }
    } }
    suspend fun reset(artistKey: String) = withContext(Dispatchers.IO) { lock.withLock { disk.reset(artistKey); mutableChanges.update { it + 1 } } }
}
