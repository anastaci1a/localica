package io.github.anastaci1a.localica.artistphoto

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Use a stable library artist key; include Artist/Album Artist scope in that key. */
data class ArtistPhotoRequest(val artistKey: String, val name: String, val localReleases: Set<String>, val latestAlbumArt: String? = null)
enum class ArtistPhotoProvider { DEEZER, WIKIMEDIA }
data class ArtistPhotoChoice(val provider: ArtistPhotoProvider, val id: String)
data class ArtistPhotoCandidate(val choice: ArtistPhotoChoice, val name: String, val releases: List<String>, val overlapsLibrary: Boolean)
data class PhotoAttribution(val artist: String, val credit: String, val licence: String, val licenceUrl: String, val sourcePage: String)
data class CachedArtistPhoto(val file: File, val provider: ArtistPhotoProvider, val attribution: PhotoAttribution?, val fetchedAt: Long)
data class ArtistPhotoState(val albumArt: String?, val photo: CachedArtistPhoto? = null)

object ArtistPhotoMatching {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun accepts(local: ArtistPhotoRequest, providerName: String, releases: List<String>): Boolean =
        normalize(local.name).isNotEmpty() && normalize(local.name) == normalize(providerName) &&
            releases.map(::normalize).filter(String::isNotEmpty).any { release -> local.localReleases.any { normalize(it) == release } }
}

// Remote image URLs remain internal; callers receive only a local file and bounded plain-text credits.
internal class RemoteArtist(val choice: ArtistPhotoChoice, val name: String, val releases: List<String>,
    val image: String? = null, val attribution: PhotoAttribution? = null)
internal class PhotoUnavailable : Exception("Artist photo unavailable")
