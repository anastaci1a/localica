package io.github.anastaci1a.localica.browse

/** One gesture advances one adjacent supported view, including the List endpoint. */
fun BrowseStyle.zoomStep(zoomIn: Boolean, widthDp: Float, albumSongs: Boolean = false): BrowseStyle {
    val sizes = ListSize.densityOrder.filter { it != ListSize.Tracklist || albumSongs || listSize == ListSize.Tracklist }
    val levels = sizes.size + 5
    val level = if (presentation == Presentation.List) sizes.indexOf(listSize) else levels - galleryColumns(presentation, widthDp, itemsPerRow).coerceIn(1, 5)
    val next = (level + if (zoomIn) 1 else -1).coerceIn(0, levels - 1)
    if (level == next) return this
    return copy(presentation = if (next < sizes.size) Presentation.List else Presentation.Standard,
        itemsPerRow = if (next < sizes.size) itemsPerRow else levels - next,
        listSize = if (next < sizes.size) sizes[next] else listSize)
}
