package io.github.anastaci1a.localica.browse

/** One gesture advances one adjacent supported view, including the List endpoint. */
fun BrowseStyle.zoomStep(zoomIn: Boolean, widthDp: Float, albumSongs: Boolean = false): BrowseStyle {
    val level = if (presentation == Presentation.List) 0 else 6 - galleryColumns(presentation, widthDp, itemsPerRow).coerceIn(1, 5)
    val next = (level + if (zoomIn) 1 else -1).coerceIn(0, 5)
    if (level == next) return this
    return copy(presentation = if (next == 0) Presentation.List else Presentation.Standard,
        itemsPerRow = if (next == 0) itemsPerRow else 6 - next,
        songGrouping = if (next > 0 && albumSongs) SongGrouping.None else songGrouping)
}
