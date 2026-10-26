package io.github.anastaci1a.localica.ui

/** Shared title/body/footer chrome, independent of scrolling or window ownership.
 * Try Regular first. Compact changes only the perimeter and chrome gaps when a complete
 * measured layout needs it; titles retain titleLarge and system font scaling in both profiles.
 * Integers are dp so measurement, live frames and pure contracts share the same policy. */
internal enum class DialogChrome(val perimeterDp: Int, val gapDp: Int) {
    Regular(24, 16),
    Compact(16, 8);

    val totalPerimeterDp: Int get() = perimeterDp * 2

    /** The footer is always present; an absent header or body does not reserve its gap. */
    fun gapCount(hasHeader: Boolean, hasBody: Boolean): Int =
        (if (hasHeader) 1 else 0) + (if (hasBody) 1 else 0)
}
