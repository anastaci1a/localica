package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*

/** Value-independent envelope. Selectable values and legacy display summaries are distinct. */
internal data class BrowseOptionsDeclaration(
    val sortFields: List<TrackSort>,
    val sizes: List<ListSize>,
    val information: List<ExtraInfo>,
    val quickSearch: Boolean,
    val mutable: Boolean,
    val randomize: Boolean,
    val reverse: Boolean,
    val overridden: Boolean,
    val footerScope: Boolean,
) {
    val sortSummaries: List<TrackSort> get() = TrackSort.entries
    val sizeSummaries: List<ListSize> get() = ListSize.entries
    val informationSummaries: List<ExtraInfo> get() = ExtraInfo.entries
    val sliderTitles: List<String> get() = (1..5).map(::itemsPerRowTitle)
    val reserveError: Boolean get() = mutable || randomize
    fun topology(style: BrowseStyle) = BrowseOptionsTopology(
        gallery = style.presentation != Presentation.List, manual = style.sort == TrackSort.Manual)
}

internal data class BrowseOptionsTopology(val gallery: Boolean, val manual: Boolean)

internal fun browseOptionsDeclaration(route: BrowseRoute, mutable: Boolean = false,
    randomize: Boolean = false, overridden: Boolean = false, reverse: Boolean = false): BrowseOptionsDeclaration {
    val songs = route.collection != null || route.tab in setOf(MainTab.Songs, MainTab.MostPlayed) || route.filter == MediaKind.Song
    val artist = route.collection?.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)
    val fields = if (songs) TrackSort.entries.filter {
        it != TrackSort.Count && (!artist || it != TrackSort.Album) &&
            (it != TrackSort.Manual || route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue))
    } else TrackSort.entries.filterNot { it in setOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder) }
    return BrowseOptionsDeclaration(fields, route.listSizes(), route.informationChoices(),
        !route.search && route.collection?.kind != MediaKind.Album && (route.collection != null || route.tab != MainTab.Library),
        mutable, randomize, randomize && reverse, overridden,
        !mutable && (route.collection != null || route.search))
}

internal fun itemsPerRowTitle(count: Int) = "Items per row: $count"

internal fun sortLabel(sort: TrackSort) = when (sort) {
    TrackSort.Album -> "Album / disc / track"
    TrackSort.AlbumArtist -> "Album Artist"
    TrackSort.Added -> "Date added"
    TrackSort.Modified -> "Date modified"
    TrackSort.MostPlayed -> "Most played"
    TrackSort.Count -> "Number of songs"
    else -> sort.name
}

internal fun extraInfoLabel(info: ExtraInfo) = when (info) {
    ExtraInfo.Inherited -> "Automatic"
    ExtraInfo.Automatic -> "Follow sort order"
    ExtraInfo.Count -> "Number of songs"
    ExtraInfo.Added -> "Date added"
    ExtraInfo.MostPlayed -> "Play count"
    ExtraInfo.Modified -> "Date modified"
    ExtraInfo.AlbumArtist -> "Album Artist"
    else -> info.name
}
