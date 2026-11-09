package io.github.anastaci1a.localica.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.LocalicaApplication
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.browse.BrowseResult
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ArtistPhotoEnvironment(val photos: ArtistPhotos, val consent: NetworkConsent, val work: ArtistPhotoWork? = null)
internal val LocalArtistPhotoEnvironment = staticCompositionLocalOf<ArtistPhotoEnvironment?> { null }

@Composable
internal fun artistPhotoEnvironment(): ArtistPhotoEnvironment? {
    LocalArtistPhotoEnvironment.current?.let { return it }
    val app = LocalContext.current.applicationContext as? LocalicaApplication
    return remember(app) { app?.let { ArtistPhotoEnvironment(it.artistPhotos, it.onlineMetadataConsent, it.artistPhotoWork) } }
}

internal fun artistPhotoRequest(ref: MediaRef, name: String, tracks: List<Track>, fallback: String?): ArtistPhotoRequest? {
    // A cold header has no rows yet; do not spend its persisted retry window before the query returns.
    if (tracks.isEmpty() || ref.kind !in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) return null
    return ArtistPhotoRequest("${ref.kind}:${ref.id}", name, tracks.mapNotNull { it.album }.toSet(), fallback)
}

internal fun libraryArtistPhotoRequests(snapshot: LibrarySnapshot): List<ArtistPhotoRequest> =
    listOf(MediaKind.Artist to SongListView.Artists, MediaKind.AlbumArtist to SongListView.AlbumArtists).flatMap { (kind, view) ->
        LibraryViews.groups(snapshot, view, LibraryViews.defaults(view)).mapNotNull { group ->
            artistPhotoRequest(MediaRef(kind, group.id), group.title, group.tracks,
                group.tracks.maxByOrNull { it.year ?: Int.MIN_VALUE }?.uri)
        }
    }

internal data class ArtistPhotoVisual(val image: ImageBitmap, val visual: PlayerVisual, val photo: CachedArtistPhoto)

/** Shared decoded images are bounded; disk revision and file identity invalidate stale pixels. */
internal object ArtistPhotoPixels {
    private data class Key(val file: java.io.File, val fetchedAt: Long, val edge: Int, val thumbnail: Boolean)
    private fun key(photo: CachedArtistPhoto, thumbnail: Boolean, edge: Int) =
        Key(if (thumbnail && edge <= 256) photo.thumbnailFile ?: photo.file else photo.file, photo.fetchedAt, edge, thumbnail)
    private val cache = object : LruCache<Key, ArtistPhotoVisual>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ArtistPhotoVisual) = value.image.asAndroidBitmap().allocationByteCount +
            listOfNotNull(value.visual.palette.wash, value.visual.palette.softWash, value.visual.palette.collectionWash)
                .distinct().sumOf { it.allocationByteCount }
    }
    /** No stat, decode, palette work or disk access on a composition-time hit. */
    fun peek(photo: CachedArtistPhoto, thumbnail: Boolean = false, edge: Int = if (thumbnail) 256 else 1080): ArtistPhotoVisual? =
        cache.get(key(photo, thumbnail, edge))
    suspend fun read(photo: CachedArtistPhoto, thumbnail: Boolean = false, edge: Int = if (thumbnail) 256 else 1080): ArtistPhotoVisual? {
        val key = key(photo, thumbnail, edge)
        val file = key.file
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        // Sample at the visible/lazy-prefetch cell's measured size; never decode the full library.
        while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= edge) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val image = bitmap.asImageBitmap()
        val visual = if (thumbnail) PlayerVisual(image, contentKey = "artist-photo:$key")
            else PlayerVisual(image, playerPalette(bitmap), contentKey = "artist-photo:$key")
        return ArtistPhotoVisual(image, visual, photo).also { cache.put(key, it) }
    }
}

internal data class ArtistPhotoDisplay(val photo: ArtistPhotoVisual? = null, val pending: Boolean = false)

/** Consent gates even cached display. Request identity prevents a reused row showing its former artist. */
@Composable
internal fun rememberArtistPhoto(request: ArtistPhotoRequest?, environment: ArtistPhotoEnvironment? = artistPhotoEnvironment(),
    thumbnail: Boolean = false): ArtistPhotoVisual? = rememberArtistPhotoDisplay(request, environment, thumbnail).photo

@Composable
internal fun rememberArtistPhotoDisplay(request: ArtistPhotoRequest?, environment: ArtistPhotoEnvironment? = artistPhotoEnvironment(),
    thumbnail: Boolean = false, edge: Int = if (thumbnail) 256 else 1080, fetchAllowed: Boolean = true): ArtistPhotoDisplay {
    val allowed = environment?.consent?.allowed?.collectAsState()?.value == true
    val revision = environment?.photos?.changes?.collectAsState()?.value
    val indexReady = environment?.photos?.indexReady?.collectAsState()?.value
    val deferred = LocalArtworkTransit.current?.active == true
    val cacheKey = remember(request?.artistKey) { request?.artistKey?.let(::artistPhotoCacheKey) }
    val identity = listOf(environment, request?.copy(latestAlbumArt = null), thumbnail, revision, edge)
    val initial = remember(identity, allowed, indexReady) {
        val state = if (allowed && cacheKey != null) environment?.photos?.peekKey(cacheKey) else null
        val cached = state?.photo?.let { photo ->
            ArtistPhotoPixels.peek(photo, thumbnail, edge.coerceAtLeast(256))
                ?: ArtistPhotoPixels.peek(photo, thumbnail)
                ?: if (!thumbnail) ArtistPhotoPixels.peek(photo, thumbnail = true) else null
        }
        ArtistPhotoDisplay(cached, allowed && request != null && (state == null || state.photo != null && cached == null))
    }
    DisposableEffect(environment, request?.artistKey, allowed) {
        val lease = if (allowed && request != null) environment?.photos?.retainDisplay(request.artistKey) else null
        onDispose { lease?.close() }
    }
    LaunchedEffect(identity, allowed, deferred, fetchAllowed) {
        if (request != null && allowed && !deferred && fetchAllowed) environment?.work?.enqueue(request)
    }
    val loaded by produceState<Pair<List<Any?>, ArtistPhotoDisplay>?>(null, identity, allowed, indexReady, deferred, fetchAllowed) {
        if (request != null && environment != null && allowed && !deferred) {
            try {
                // Startup normally owns this. Injected/reopened environments share the same IO load.
                if (!environment.photos.indexReady.value) environment.photos.loadIndex()
                suspend fun accept(state: ArtistPhotoState) {
                    val photo = state.photo
                    if (photo == null) value = identity to ArtistPhotoDisplay()
                    else {
                        if (edge <= 0) return
                        val decoded = withContext(Dispatchers.IO) { ArtistPhotoPixels.read(photo, thumbnail, edge) }
                        if (decoded != null) {
                            value = identity to ArtistPhotoDisplay(decoded)
                            environment.photos.displayed(request.artistKey)
                        }
                    }
                }
                accept(environment.photos.ready(request))
                if (environment.work == null && fetchAllowed) environment.photos.observe(request).collect { if (it.photo != null) accept(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed replacement keeps matching ready pixels. */ }
        } else if (!allowed) value = null
    }
    return if (allowed && request != null) loaded?.takeIf { it.first == identity }?.second ?: initial else ArtistPhotoDisplay()
}

@Composable
internal fun ArtistMediaArtwork(item: BrowserItem, modifier: Modifier,
    shape: Shape = Corners.artwork, contentScale: ContentScale = ContentScale.Crop,
    textReveal: ArtworkTextReveal? = null) {
    val artist = item.ref.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)
    if (!artist) {
        MediaArtwork(item.coverUris, modifier, shape = shape, contentScale = contentScale, textReveal = textReveal)
        return
    }
    val environment = artistPhotoEnvironment()
    val allowed = environment?.consent?.allowed?.collectAsState()?.value == true
    val request = remember(item) { artistPhotoRequest(item.ref, item.title, item.songs.map { it.track }, item.coverUris.firstOrNull()) }
    var edge by remember(item.ref) { mutableIntStateOf(0) }
    val photo = rememberArtistPhotoDisplay(request, environment, thumbnail = true, edge = edge)
    // The existing ArtworkImage owns reveal/resolution blending. Revocation drops its outgoing photo immediately.
    key(item.ref, allowed) {
        MediaArtwork(item.coverUris, modifier.onSizeChanged { edge = ArtworkCache.bucket(maxOf(it.width, it.height)) }, shape = shape, contentScale = contentScale,
            textReveal = textReveal, replacementArtwork = photo.photo?.image, fallbackPending = photo.pending)
    }
}

@Composable
internal fun rememberArtistCollectionVisual(ref: MediaRef?, name: String?, result: BrowseResult,
    fallback: String?, albumVisual: PlayerVisual): PlayerVisual {
    if (ref?.kind !in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) return albumVisual
    val request = remember(ref, name, result, fallback) {
        ref?.let { ArtistPhotoRequest("${it.kind}:${it.id}", name.orEmpty(), result.rows.mapNotNull { row -> row.track.album }.toSet(), fallback) }
    }
    val edge = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val photo = rememberArtistPhotoDisplay(request, edge = ArtworkCache.bucket(edge), fetchAllowed = result.rows.isNotEmpty())
    return photo.photo?.visual ?: if (photo.pending) PlayerVisual(loading = true) else albumVisual
}
