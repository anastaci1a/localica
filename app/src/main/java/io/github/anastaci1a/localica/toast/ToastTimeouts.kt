package io.github.anastaci1a.localica.toast

/** Compose uses MAX_VALUE for an indefinite accessibility timeout. Toasts remain finite while
 * actual accessibility focus independently pauses dwell. Every finite recommendation is retained. */
internal fun toastRecommendedDwell(requestedMillis: Long, recommendedMillis: Long): Long =
    if (recommendedMillis == Long.MAX_VALUE) maxOf(requestedMillis, 60_000L) else recommendedMillis
