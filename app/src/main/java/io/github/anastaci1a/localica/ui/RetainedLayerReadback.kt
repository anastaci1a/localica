package io.github.anastaci1a.localica.ui

import android.os.Looper
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal enum class ReadbackStage { ACQUIRED, COPIED, RELEASED }

// Keep the suspend call out of a default-argument lambda: Kotlin's continuation
// lowering can fail for that lambda during a clean module compilation.
private suspend fun copyLayerBitmap(layer: GraphicsLayer): ImageBitmap = layer.toImageBitmap()

/** A recorded parent retains its source's native content until the copy completes. */
internal class RetainedLayerReadback(private val onStage: ((ReadbackStage) -> Unit)? = null) {
    private val completion = ReadbackCompletion()

    suspend fun capture(source: GraphicsLayer, graphicsContext: GraphicsContext, density: Density,
        layoutDirection: LayoutDirection,
        copy: suspend (GraphicsLayer) -> ImageBitmap = ::copyLayerBitmap): ImageBitmap {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Layer readbacks belong to the owning UI thread" }
        return completion.capture(
            acquire = {
                check(!source.isReleased && source.size.width > 0 && source.size.height > 0)
                graphicsContext.createGraphicsLayer()
            },
            read = { parent ->
                onStage?.invoke(ReadbackStage.ACQUIRED)
                parent.record(density, layoutDirection, source.size) { drawLayer(source) }
                copy(parent).also { onStage?.invoke(ReadbackStage.COPIED) }
            },
            release = { parent ->
                graphicsContext.releaseGraphicsLayer(parent)
                onStage?.invoke(ReadbackStage.RELEASED)
            })
    }
}
