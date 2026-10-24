package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class ArtworkViewerState(restoredScale: Float = 1f, restoredCenter: Offset = Offset.Zero) {
    var transform by mutableStateOf(ArtworkTransform(restoredScale.coerceIn(1f, ArtworkMaximumZoom)))
        private set
    var viewport by mutableStateOf(Size.Zero)
        private set
    private var image = Size.Zero
    private var pendingCenter: Offset? = restoredCenter
    private var motion: Job? = null
    var generation = 0L
        private set

    fun updateGeometry(viewport: Size = this.viewport, image: Size) {
        if (viewport == this.viewport && image == this.image) return
        val center = normalizedCenter()
        cancelContact()
        this.viewport = viewport
        this.image = image
        val fitted = fittedArtworkSize(viewport, image)
        if (fitted == Size.Zero) {
            pendingCenter = center
            transform = transform.copy(offset = Offset.Zero)
        } else {
            transform = transform.copy(offset = artworkPanBounds(viewport, image, transform.scale).clamp(
                Offset(center.x * fitted.width * transform.scale, center.y * fitted.height * transform.scale)))
            pendingCenter = null
        }
    }

    private fun normalizedCenter(): Offset {
        pendingCenter?.let { return it }
        val fitted = fittedArtworkSize(viewport, image)
        return if (fitted == Size.Zero) Offset.Zero else Offset(
            transform.offset.x / (fitted.width * transform.scale),
            transform.offset.y / (fitted.height * transform.scale))
    }

    fun savedTransform(): List<Float> = normalizedCenter().let { listOf(transform.scale, it.x, it.y) }

    fun stopMotion() { motion?.cancel(); motion = null }
    fun cancelContact() { generation++; stopMotion() }
    fun reset() { cancelContact(); pendingCenter = null; transform = ArtworkTransform() }
    fun zoomTo(scale: Float) {
        cancelContact()
        pendingCenter = null
        transform = transformArtwork(transform, viewport, image, scale / transform.scale,
            Offset.Zero, Offset(viewport.width / 2f, viewport.height / 2f))
    }
    fun drag(zoom: Float, pan: Offset, centroid: Offset) {
        transform = transformArtwork(transform, viewport, image, zoom, pan, centroid)
    }

    fun release(velocity: Offset, scope: CoroutineScope, reducedMotion: Boolean, stopSpeed: Float) {
        stopMotion()
        if (reducedMotion || transform.scale == 1f) return
        // Remove blocked components before starting a frame clock.
        var pan = advanceArtworkPan(ArtworkPanMotion(transform.offset, velocity),
            artworkPanBounds(viewport, image, transform.scale), 0f, stopSpeed)
        if (!pan.moving) return
        motion = scope.launch {
            var previous = withFrameNanos { it }
            while (pan.moving) {
                val now = withFrameNanos { it }
                pan = advanceArtworkPan(pan, artworkPanBounds(viewport, image, transform.scale),
                    (now - previous) / 1_000_000_000f, stopSpeed)
                transform = transform.copy(offset = pan.offset)
                previous = now
            }
        }
    }

    companion object {
        val Saver = listSaver<ArtworkViewerState, Float>(
            save = { it.savedTransform() },
            restore = { ArtworkViewerState(it[0], Offset(it[1], it[2])) })
    }
}

@Composable
internal fun rememberArtworkViewerState(): ArtworkViewerState =
    rememberSaveable(saver = ArtworkViewerState.Saver) { ArtworkViewerState() }
