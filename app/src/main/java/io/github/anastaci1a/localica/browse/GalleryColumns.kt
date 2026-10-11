package io.github.anastaci1a.localica.browse

/** Each width band keeps gallery densities distinct, including wide phones and tablets. */
fun galleryColumns(presentation: Presentation, widthDp: Float, itemsPerRow: Int? = null): Int {
    if (presentation == Presentation.List) return 1
    if (itemsPerRow != null) return itemsPerRow.coerceIn(1, 5)
    val large = (widthDp / 360f).toInt().coerceAtLeast(1)
    return when (presentation) {
        Presentation.List -> 1
        Presentation.Large -> large
        Presentation.Standard -> large * 2
        Presentation.Compact -> large * 3
    }
}
