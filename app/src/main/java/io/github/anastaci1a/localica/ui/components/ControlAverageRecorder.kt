package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import io.github.anastaci1a.localica.ui.engine.ControlAverageRecording
import io.github.anastaci1a.localica.ui.engine.rememberControlAverageRecording

/** Borrow the one Engine-owned buffer; this facade neither allocates a layer nor registers a surface. */
internal class ControlAverageRecorder(private val recording: ControlAverageRecording) {
    fun DrawScope.record(source: GraphicsLayer, effect: RenderEffect?, opacity: Float, blending: Boolean) {
        with(recording) { this@record.record(source, effect, opacity, blending) }
    }
    fun DrawScope.draw(bounds: Rect, silhouette: Path) {
        with(recording) { this@draw.draw(bounds, silhouette) }
    }
}

@Composable internal fun rememberControlAverageRecorder(): ControlAverageRecorder {
    val recording = rememberControlAverageRecording()
    return remember(recording) { ControlAverageRecorder(recording) }
}
