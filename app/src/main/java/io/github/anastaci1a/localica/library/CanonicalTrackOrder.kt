package io.github.anastaci1a.localica.library

import java.util.Locale

private fun compareCanonicalTracks(a: Track, b: Track, titleA: String, titleB: String): Int =
    titleA.compareTo(titleB).takeIf { it != 0 }
        ?: a.relativePath.compareTo(b.relativePath).takeIf { it != 0 }
        ?: a.uri.compareTo(b.uri)

/** Canonical source order deliberately has no track-ID tiebreak. */
internal val trackOrder = Comparator<Track> { a, b ->
    compareCanonicalTracks(a, b, a.title.lowercase(Locale.ROOT), b.title.lowercase(Locale.ROOT))
}

/** Prepare titles once for this read, keeping original track objects and stable ties. */
internal fun Iterable<Track>.inCanonicalOrder(): List<Track> {
    class Prepared(val track: Track, val title: String)
    return map { Prepared(it, it.title.lowercase(Locale.ROOT)) }
        .sortedWith { a, b -> compareCanonicalTracks(a.track, b.track, a.title, b.title) }
        .map { it.track }
}
