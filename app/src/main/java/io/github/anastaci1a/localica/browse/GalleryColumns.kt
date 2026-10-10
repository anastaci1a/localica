package io.github.anastaci1a.localica.browse

/** Each width band keeps gallery densities distinct, including wide phones and tablets. */
fun galleryColumns(presentation: Presentation, widthDp: Float): Int {
    val large = (widthDp / 360f).toInt().coerceAtLeast(1)
    return when (presentation) {
        Presentation.List -> 1
        Presentation.Large -> large
        Presentation.Standard -> large * 2
        Presentation.Compact -> large * 3
    }
}
