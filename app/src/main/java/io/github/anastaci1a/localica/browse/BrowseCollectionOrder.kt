package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.LibraryQuery
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.library.compareKnown
import java.util.Locale

/** Request-local keys avoid rescanning collection members during comparator calls. */
internal fun orderCollectionItems(items: List<BrowserItem>, query: LibraryQuery,
    checkActive: () -> Unit = {}): List<BrowserItem> {
    data class Key(val item: BrowserItem, val title: String, val identity: String,
        val number: Long?, val text: String?)
    val numeric = query.sort in setOf(TrackSort.Count, TrackSort.Duration, TrackSort.Year,
        TrackSort.Modified, TrackSort.Added, TrackSort.MostPlayed)
    val prepared = items.mapIndexed { index, item ->
        if (index % 128 == 0) checkActive()
        val title = item.title.lowercase(Locale.ROOT)
        Key(item, title, item.key, when (query.sort) {
            TrackSort.Count -> item.songs.size.toLong()
            TrackSort.Duration -> item.duration()
            TrackSort.Year -> item.songs.mapNotNull { it.track.year }.minOrNull()?.toLong()
            TrackSort.Modified -> item.modifiedAt
            TrackSort.Added -> item.addedAt
            TrackSort.MostPlayed -> item.playCount
            else -> null
        }, when (query.sort) {
            TrackSort.Artist, TrackSort.AlbumArtist, TrackSort.Composer, TrackSort.Genre ->
                item.metadataText(query.sort)?.lowercase(Locale.ROOT)
            else -> title
        })
    }
    var comparisons = 0
    val ordered = prepared.sortedWith { a, b ->
        if (comparisons++ % 128 == 0) checkActive()
        val primary = if (numeric) compareKnown(a.number, b.number, query.ascending)
            else compareKnown(a.text, b.text, query.ascending)
        primary.takeIf { it != 0 } ?: a.title.compareTo(b.title).takeIf { it != 0 }
            ?: a.identity.compareTo(b.identity)
    }
    checkActive()
    return ordered.map { it.item }
}
