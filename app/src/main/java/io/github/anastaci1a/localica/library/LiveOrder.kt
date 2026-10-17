package io.github.anastaci1a.localica.library

/** Resolve sort keys without replacing persisted occurrences or their saved metadata. */
class LiveOrder(private val snapshot: LibrarySnapshot) {
    private val tracks by lazy(LazyThreadSafetyMode.NONE) { snapshot.tracks.associateBy { it.id } }

    private fun resolve(rows: List<SongRow>): List<SongRow> = rows.map { row ->
        row.copy(track = tracks[row.track.id]
            ?: row.track.copy(playCount = snapshot.trackPlays[row.track.id] ?: 0))
    }

    fun target(order: SavedOrder, rows: List<SongRow>, method: TrackSort, ascending: Boolean): List<String> =
        order.apply(if (method == TrackSort.Manual) rows else resolve(rows), method, ascending)

    fun isApplied(order: SavedOrder, rows: List<SongRow>, method: TrackSort, ascending: Boolean): Boolean =
        order.sort == method && (method == TrackSort.Manual || order.ascending == ascending) &&
            target(order, rows, method, ascending) == rows.map { it.id }
}
