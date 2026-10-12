package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.max

/** Keep a bounded set of source drawings alive even when the destination virtualizes them out. */
internal fun Modifier.retainPinchItem(key: Any, motion: BrowsePinchMotion, graphics: GraphicsContext): Modifier = drawWithContent {
    if (motion.source == null) {
        val layer = motion.retained.getOrPut(key) {
            if (motion.retained.size >= 96) motion.retained.keys.firstOrNull()?.let { oldest ->
                motion.retained.remove(oldest)?.let(graphics::releaseGraphicsLayer)
            }
            graphics.createGraphicsLayer()
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    } else drawContent()
}

internal fun Modifier.pinchRetainedOverlay(motion: BrowsePinchMotion, grid: LazyGridState, keys: List<String>): Modifier = drawWithContent {
    drawContent()
    if (motion.source == null) return@drawWithContent
    val visible = grid.layoutInfo.visibleItemsInfo
    val liveKeys = visible.map { it.key }.toSet()
    val p = motion.progress.value.coerceIn(0f, 1f)
    val row = visible.firstOrNull { it.key != "browser-notice" } ?: return@drawWithContent
    val rowIndex = keys.indexOf(row.key)
    val targetHeight = max(row.size.height.toFloat(), 1f)
    clipRect {
        motion.bounds.forEach { (key, old) ->
            if (key in liveKeys) return@forEach
            val layer = motion.retained[key]?.takeUnless { it.isReleased } ?: return@forEach
            val index = keys.indexOf(key)
            if (index < 0) return@forEach
            val width = motion.viewportWidth / motion.targetColumns
            val x = index % motion.targetColumns * width
            val y = row.offset.y + (index / motion.targetColumns - rowIndex / motion.targetColumns) * targetHeight
            translate(old.left + (x - old.left) * p, old.top + (y - old.top) * p) {
                scale(1f + (width / old.width - 1f) * p, 1f + (targetHeight / old.height - 1f) * p, Offset.Zero) {
                    drawLayer(layer)
                }
            }
        }
    }
}
