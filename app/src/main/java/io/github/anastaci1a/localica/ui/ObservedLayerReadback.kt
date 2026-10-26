package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Diagnostic scalars only. Invocation/record return are not native posting or consumption. */
internal data class LayerReadbackObservation(val stage: String, val source: Int, val parent: Int,
    val sourceWidth: Int, val sourceHeight: Int, val sourceReleased: Boolean,
    val parentWidth: Int, val parentHeight: Int, val parentReleased: Boolean)

internal val LocalLayerReadbackObservation = staticCompositionLocalOf<((String) -> Unit)?> { null }

private suspend fun observedCopy(layer: GraphicsLayer): ImageBitmap = layer.toImageBitmap()

/** Uses the original lease implementation unchanged; delegates each resource operation once. */
internal suspend fun RetainedLayerReadback.captureObserved(source: GraphicsLayer,
    graphicsContext: GraphicsContext, density: Density, direction: LayoutDirection,
    observe: (LayerReadbackObservation) -> Unit,
    copy: suspend (GraphicsLayer) -> ImageBitmap = ::observedCopy): ImageBitmap {
    fun event(stage: String, parent: GraphicsLayer) {
        // Observation failure cannot replace capture/release failure or alter resource ownership.
        try {
            observe(LayerReadbackObservation(stage, System.identityHashCode(source), System.identityHashCode(parent),
                source.size.width, source.size.height, source.isReleased,
                parent.size.width, parent.size.height, parent.isReleased))
        } catch (_: Throwable) { }
    }
    val context = object : GraphicsContext by graphicsContext {
        override fun createGraphicsLayer(): GraphicsLayer = graphicsContext.createGraphicsLayer().also {
            event("parent-created", it)
        }
        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
            event("release-invoked", layer)
            graphicsContext.releaseGraphicsLayer(layer)
            event("release-returned", layer)
        }
    }
    return capture(source, context, density, direction) { parent ->
        event("record-returned-copy-invoked", parent)
        copy(parent).also { event("copy-returned", parent) }
    }
}
