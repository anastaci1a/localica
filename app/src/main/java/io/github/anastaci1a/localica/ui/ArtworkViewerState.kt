package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class ArtworkViewerState {
    var transform by mutableStateOf(ArtworkTransform())
        private set
    var viewport by mutableStateOf(Size.Zero)
        private set
    private var image = Size.Zero
    private var motion: Job? = null
    var generation = 0L
        private set

    fun updateGeometry(viewport: Size = this.viewport, image: Size) {
        if (viewport == this.viewport && image == this.image) return
        cancelContact()
        this.viewport = viewport
        this.image = image
        transform = transform.copy(offset = artworkPanBounds(viewport, image, transform.scale).clamp(transform.offset))
    }

    fun stopMotion() { motion?.cancel(); motion = null }
    fun cancelContact() { generation++; stopMotion() }
    fun reset() { cancelContact(); transform = ArtworkTransform() }
    fun zoomTo(scale: Float) {
        cancelContact()
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
}
