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
import androidx.compose.ui.platform.LocalContext
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

internal data class ArtistPhotoEnvironment(val photos: ArtistPhotos, val consent: NetworkConsent)
internal val LocalArtistPhotoEnvironment = staticCompositionLocalOf<ArtistPhotoEnvironment?> { null }

@Composable
internal fun artistPhotoEnvironment(): ArtistPhotoEnvironment? {
    LocalArtistPhotoEnvironment.current?.let { return it }
    val app = LocalContext.current.applicationContext as? LocalicaApplication
    return remember(app) { app?.let { ArtistPhotoEnvironment(it.artistPhotos, it.onlineMetadataConsent) } }
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
private object ArtistPhotoPixels {
    private val cache = object : LruCache<String, ArtistPhotoVisual>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ArtistPhotoVisual) = value.image.asAndroidBitmap().allocationByteCount +
            listOfNotNull(value.visual.palette.wash, value.visual.palette.softWash, value.visual.palette.collectionWash)
                .distinct().sumOf { it.allocationByteCount }
    }
    suspend fun read(photo: CachedArtistPhoto): ArtistPhotoVisual? {
        val file = photo.file
        val key = "${file.absolutePath}:${file.length()}:${photo.fetchedAt}"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 768) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val image = bitmap.asImageBitmap()
        val visual = PlayerVisual(image, playerPalette(bitmap), contentKey = "artist-photo:$key")
        return ArtistPhotoVisual(image, visual, photo).also { cache.put(key, it) }
    }
}

/** Consent gates even cached display. Request identity prevents a reused row showing its former artist. */
@Composable
internal fun rememberArtistPhoto(request: ArtistPhotoRequest?, environment: ArtistPhotoEnvironment? = artistPhotoEnvironment()): ArtistPhotoVisual? {
    val allowed = environment?.consent?.allowed?.collectAsState()?.value == true
    val revision = environment?.photos?.changes?.collectAsState()?.value
    val deferred = LocalArtworkTransit.current?.active == true
    val identity = listOf(environment, request?.copy(latestAlbumArt = null), allowed, revision)
    val loaded by produceState<Pair<List<Any?>, ArtistPhotoVisual?>?>(null, identity, deferred) {
        if (request != null && environment != null && allowed && !deferred) {
            try {
                environment.photos.observe(request).collect { state ->
                    state.photo?.let { photo ->
                        val decoded = withContext(Dispatchers.IO) { ArtistPhotoPixels.read(photo) }
                        value = identity to decoded
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = identity to null }
        }
    }
    return if (allowed && request != null && loaded?.first == identity) loaded?.second else null
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
    val photo = rememberArtistPhoto(request, environment)
    // The existing ArtworkImage owns reveal/resolution blending. Revocation drops its outgoing photo immediately.
    key(item.ref, allowed) {
        MediaArtwork(item.coverUris, modifier, shape = shape, contentScale = contentScale,
            textReveal = textReveal, replacementArtwork = photo?.image)
    }
}

@Composable
internal fun rememberArtistCollectionVisual(ref: MediaRef?, name: String?, result: BrowseResult,
    fallback: String?, albumVisual: PlayerVisual): PlayerVisual {
    if (ref?.kind !in setOf(MediaKind.Artist, MediaKind.AlbumArtist)) return albumVisual
    val request = remember(ref, name, result, fallback) {
        ref?.let { artistPhotoRequest(it, name.orEmpty(), result.rows.map { row -> row.track }, fallback) }
    }
    return rememberArtistPhoto(request)?.visual ?: albumVisual
}
