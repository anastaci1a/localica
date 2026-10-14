package io.github.anastaci1a.localica.browse

/** One gesture advances one adjacent supported view, including the List endpoint. */
fun BrowseStyle.zoomStep(zoomIn: Boolean, widthDp: Float, albumSongs: Boolean = false): BrowseStyle {
    if (albumSongs) return this
    val level = if (presentation == Presentation.List) listSize.ordinal else 8 - galleryColumns(presentation, widthDp, itemsPerRow).coerceIn(1, 5)
    val next = (level + if (zoomIn) 1 else -1).coerceIn(0, 7)
    if (level == next) return this
    return copy(presentation = if (next < 3) Presentation.List else Presentation.Standard,
        itemsPerRow = if (next < 3) itemsPerRow else 8 - next,
        listSize = if (next < 3) ListSize.entries[next] else listSize)
}
