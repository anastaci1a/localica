package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Passive receipt from the sampling Canvas, not the independently cached coating. */
internal data class BackdropSamplingObservation(val rootCorners: List<Offset>?,
    val consumerCornersInSource: List<Offset>)
internal val LocalBackdropSamplingObserver =
    staticCompositionLocalOf<((BackdropSamplingObservation) -> Unit)?> { null }

internal fun observeBackdropSampling(observer: (BackdropSamplingObservation) -> Unit,
    location: BackdropLocation, consumerToSource: BackdropRegistration, bounds: Rect) {
    // Reuse the registration already selected by the real renderer. Diagnostics
    // must neither subscribe drawing to new state nor affect material rendering.
    try { Snapshot.withoutReadObservation {
        val coordinates = location.coordinates?.takeIf { it.isAttached }
        val local = listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft)
        observer(BackdropSamplingObservation(coordinates?.let { c -> local.map(c::localToRoot) },
            consumerToSource.corners(bounds)))
    } } catch (_: Throwable) { /* Observation cannot break rendering. */ }
}
