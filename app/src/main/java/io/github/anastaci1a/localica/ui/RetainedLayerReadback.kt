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
// Below API 28, avoid Compose's LayerSnapshotV22 ImageReader lifecycle (#501): same pixel
// conversion, but the Image and reader are owned and closed in order.
private suspend fun copyLayerBitmap(layer: GraphicsLayer, density: Density, direction: LayoutDirection): ImageBitmap =
    if (android.os.Build.VERSION.SDK_INT >= 28) layer.toImageBitmap()
    else layer.legacyLayerSnapshot(density, direction, straightAlpha = true)

/** A recorded parent retains its source's native content until the copy completes. */
internal class RetainedLayerReadback(private val onStage: ((ReadbackStage) -> Unit)? = null) {
    private val completion = ReadbackCompletion()

    suspend fun capture(source: GraphicsLayer, graphicsContext: GraphicsContext, density: Density,
        layoutDirection: LayoutDirection, copy: (suspend (GraphicsLayer) -> ImageBitmap)? = null): ImageBitmap {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Layer readbacks belong to the owning UI thread" }
        val readLayer: suspend (GraphicsLayer) -> ImageBitmap = copy ?: { copyLayerBitmap(it, density, layoutDirection) }
        return completion.capture(
            acquire = {
                check(!source.isReleased && source.size.width > 0 && source.size.height > 0)
                graphicsContext.createGraphicsLayer()
            },
            read = { parent ->
                onStage?.invoke(ReadbackStage.ACQUIRED)
                parent.record(density, layoutDirection, source.size) { drawLayer(source) }
                readLayer(parent).also { onStage?.invoke(ReadbackStage.COPIED) }
            },
            release = { parent ->
                graphicsContext.releaseGraphicsLayer(parent)
                onStage?.invoke(ReadbackStage.RELEASED)
            })
    }
}
