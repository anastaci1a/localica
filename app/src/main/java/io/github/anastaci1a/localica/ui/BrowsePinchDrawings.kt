package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.browse.*
import kotlin.math.max
import kotlin.math.roundToInt

internal data class BrowsePinchContent(
    val measure: (BrowseStyle, Int, Int) -> BrowsePinchEndpoint,
    val foreground: Color, val secondary: Color, val textOpacity: () -> Float = { 1f },
)
internal data class FrozenBrowsePart(val image: androidx.compose.ui.graphics.ImageBitmap,
    val origin: IntOffset, val size: IntSize)
internal data class BrowsePinchLayer(val layer: GraphicsLayer, var coordinates: LayoutCoordinates? = null)
internal data class FrozenBrowseContent(val source: BrowsePinchEndpoint, val target: BrowsePinchEndpoint,
    val foreground: Color, val secondary: Color, val opacity: Float = 1f, val textOpacity: Float = 1f)
internal data class FrozenBrowseItem(val image: androidx.compose.ui.graphics.ImageBitmap,
    val origin: androidx.compose.ui.unit.IntOffset, val size: androidx.compose.ui.unit.IntSize,
    val highlight: RetainedCurrentSongHighlight? = null,
    val content: FrozenBrowseContent? = null, val artwork: FrozenBrowsePart? = null, val text: FrozenBrowsePart? = null)

/** Keep a bounded set of source drawings alive even when the destination virtualizes them out. */
@Composable
internal fun Modifier.retainPinchItem(key: Any, motion: BrowsePinchMotion,
    highlight: androidx.compose.ui.graphics.Color? = null): Modifier {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    SideEffect {
        if (highlight == null) motion.currentHighlights.remove(key)
        else motion.currentHighlights[key] = highlight
    }
    DisposableEffect(motion, key, layer) {
        motion.retained[key] = layer
        onDispose {
            if (motion.retained[key] === layer) {
                motion.retained.remove(key)
                motion.currentHighlights.remove(key)
                motion.itemAlpha.remove(key)
            }
        }
    }
    return drawWithContent {
        // Keep one drawing owner through recognition, interpolation and release.
        // In particular, do not reparent nested artwork layers for a single frame.
        // The source tree stays intact while its asynchronous atlas readback runs.
        // A newly attached row was not included in the atlas and still needs its
        // first recording (for example if an already-running scroll exposes it).
        if (!motion.capturing || layer.size.width == 0 || layer.size.height == 0)
            layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** Artwork has one stable recording owner; the viewport paints every cover after
 * every row's text. Keeping this topology at rest avoids reparenting at recognition. */
@Composable
internal fun Modifier.retainPinchArtwork(key: Any, motion: BrowsePinchMotion): Modifier = retainPinchPass(key, motion, true)

@Composable
internal fun Modifier.retainPinchText(key: Any, motion: BrowsePinchMotion): Modifier = retainPinchPass(key, motion, false)

@Composable
private fun Modifier.retainPinchPass(key: Any, motion: BrowsePinchMotion, upper: Boolean): Modifier {
    val pass = if (upper) motion.artwork else motion.text
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val recording = remember(layer) { BrowsePinchLayer(layer) }
    DisposableEffect(motion, key, recording, pass) {
        pass[key] = recording
        onDispose { if (pass[key] === recording) pass.remove(key) }
    }
    return onGloballyPositioned { recording.coordinates = it }.drawWithContent {
        if (!motion.capturing || layer.size.width == 0 || layer.size.height == 0)
            layer.record { this@drawWithContent.drawContent() }
        // Standalone row/geometry previews have no viewport compositor.
        if (motion.viewportCoordinates?.isAttached != true) drawLayer(layer)
    }
}

internal fun pinchArtworkRect(source: BrowsePinchEndpoint, target: BrowsePinchEndpoint, progress: Float): Rect {
    return pinchElementRect(Rect(source.artX.toFloat(), source.artY.toFloat(),
        (source.artX + source.art).toFloat(), (source.artY + source.art).toFloat()),
        Rect(target.artX.toFloat(), target.artY.toFloat(),
            (target.artX + target.art).toFloat(), (target.artY + target.art).toFloat()), progress)
}

internal fun pinchElementRect(source: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * p
    return Rect(mix(source.left, target.left), mix(source.top, target.top),
        mix(source.right, target.right), mix(source.bottom, target.bottom))
}

private data class RetainedPinchPlacement(val item: FrozenBrowseItem, val origin: Offset,
    val targetWidth: Float, val targetHeight: Float, val alpha: Float, val index: Int)

internal fun Modifier.pinchRetainedOverlay(motion: BrowsePinchMotion, grid: LazyGridState,
    keys: List<String>): Modifier = onGloballyPositioned { motion.viewportCoordinates = it }.drawWithContent {
    // Pass one: all live and retained text (including track numbers and menus).
    drawContent()
    val visible = grid.layoutInfo.visibleItemsInfo
    val liveKeys = visible.map { it.key }.toSet()
    val p = motion.progress.value.coerceIn(0f, 1f)
    val retained = linkedMapOf<Any, RetainedPinchPlacement>()
    val row = visible.firstOrNull { it.key != "browser-notice" }
    if (motion.visualSource != null && row != null) {
        val rowIndex = keys.indexOf(row.key)
        val pitch = max(row.size.height.toFloat(), 1f)
        val geometry = BrowseCellGeometry(motion.targetColumns)
        val sourceInset = (BrowseCellGeometry(motion.sourceColumns).cardInset * motion.pixelDensity).roundToInt()
        val targetInset = (geometry.cardInset * motion.pixelDensity).roundToInt()
        val sourceVerticalInset = (BrowseCellGeometry(motion.sourceColumns).cardVerticalInset(requireNotNull(motion.visualSource)) * motion.pixelDensity).roundToInt()
        val targetVerticalInset = (geometry.cardVerticalInset(requireNotNull(motion.target)) * motion.pixelDensity).roundToInt()
        motion.bounds.forEach { (key, old) ->
            if (key in liveKeys) return@forEach
            val image = motion.frozen[key] ?: return@forEach
            val index = keys.indexOf(key)
            if (index < 0) return@forEach
            val column = index % motion.targetColumns
            val width = geometry.cellWidth(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
            val leading = geometry.cellLeft(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
            val x = if (motion.rightToLeft) motion.viewportWidth - leading - width else leading
            val y = row.offset.y + (index / motion.targetColumns - rowIndex / motion.targetColumns) * pitch
            val origin = Offset(old.left + sourceInset + (x + targetInset - old.left - sourceInset) * p,
                old.top + sourceVerticalInset + (y + targetVerticalInset - old.top - sourceVerticalInset) * p)
            retained[key] = RetainedPinchPlacement(image, origin, (width - 2 * targetInset).coerceAtLeast(0f),
                image.content?.target?.height?.toFloat() ?: (pitch - 2 * targetVerticalInset).coerceAtLeast(0f),
                browseViewVisibility(true, false, p), index)
        }
    }
    fun drawLive(part: BrowsePinchLayer, key: Any) {
        val viewport = motion.viewportCoordinates
        val coordinates = part.coordinates ?: return
        if (viewport?.isAttached != true || !coordinates.isAttached || part.layer.isReleased ||
            part.layer.size.width <= 0 || part.layer.size.height <= 0) return
        val bounds = viewport.localBoundingBoxOf(coordinates, clipBounds = false)
        part.layer.alpha = motion.itemAlpha[key] ?: 1f
        translate(bounds.left, bounds.top) {
            scale(bounds.width / part.layer.size.width, bounds.height / part.layer.size.height, Offset.Zero) { drawLayer(part.layer) }
        }
    }
    clipRect {
        visible.forEach { entry -> motion.text[entry.key]?.let { drawLive(it, entry.key) } }
        retained.values.forEach { placed ->
            val image = placed.item
            val content = image.content
            translate(placed.origin.x, placed.origin.y) {
                image.highlight?.let { highlight ->
                    val original = androidx.compose.ui.geometry.Size(image.size.width.toFloat(), image.size.height.toFloat())
                    val size = androidx.compose.ui.geometry.Size(original.width + (placed.targetWidth - original.width) * p,
                        original.height + (placed.targetHeight - original.height) * p)
                    with(highlight.renderer) { draw(Rect(Offset.Zero, size), highlight.color, original, placed.alpha * (content?.opacity ?: 1f)) }
                }
                if (content == null) {
                    // Non-browser callers can still retain an arbitrary source drawing.
                    val fit = minOf(placed.targetWidth / image.size.width, placed.targetHeight / image.size.height)
                    val factor = 1f + (fit - 1f) * p
                    scale(factor, factor, Offset.Zero) {
                        drawImage(image.image, srcOffset = image.origin, srcSize = image.size, dstSize = image.size, alpha = placed.alpha)
                    }
                } else {
                    // Preserve the captured selection decoration independently of
                    // fixed endpoint glyphs; current-song frost was drawn beneath it.
                    val width = image.size.width + (placed.targetWidth - image.size.width) * p
                    val height = image.size.height + (placed.targetHeight - image.size.height) * p
                    scale(width / image.size.width, height / image.size.height, Offset.Zero) {
                        drawImage(image.image, srcOffset = image.origin, srcSize = image.size, dstSize = image.size, alpha = placed.alpha * content.opacity)
                    }
                    val source = content.source; val target = content.target
                    fun mix(a: Int, b: Int) = a + (b - a) * p
                    val cardWidth = image.size.width + (placed.targetWidth - image.size.width) * p
                    fun left(x: Float, width: Float) = if (motion.rightToLeft) cardWidth - x - width else x
                    fun paragraph(a: androidx.compose.ui.text.TextLayoutResult?, b: androidx.compose.ui.text.TextLayoutResult?,
                        ax: Int, ay: Int, bx: Int, by: Int, color: Color) {
                        val width = mix(a?.size?.width ?: 0, b?.size?.width ?: 0)
                        val height = mix(a?.size?.height ?: 0, b?.size?.height ?: 0)
                        translate(left(mix(ax, bx), width), mix(ay, by)) {
                            clipRect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f)) {
                                drawBrowsePinchParagraph(a, b, p, width, color, content.foreground, placed.alpha * content.opacity * content.textOpacity)
                            }
                        }
                    }
                    paragraph(source.title, target.title, source.titleX, source.titleY, target.titleX, target.titleY, Color.Unspecified)
                    val targetStyle = motion.target
                    val infoInk = if (targetStyle?.presentation == Presentation.List && targetStyle.listSize.trailingInformation)
                        content.secondary else Color.Unspecified
                    paragraph(source.information, target.information, source.infoX, source.infoY, target.infoX, target.infoY, infoInk)
                    val art = pinchArtworkRect(source, target, p)
                    val number = target.number ?: source.number
                    if (number != null) {
                        val alpha = browseViewVisibility(source.number != null, target.number != null, p) * placed.alpha * content.opacity * content.textOpacity
                        drawText(number, color = content.secondary, alpha = alpha,
                            topLeft = Offset(left(art.left, art.width) + (art.width - number.size.width) / 2f,
                                art.top + (art.height - number.size.height) / 2f))
                    }
                    // The source overflow glyph is a bounded crop, not a scaled whole row.
                    val text = image.text
                    if (text != null && source.menuWidth > 0 && source.menuHeight > 0) {
                        val width = mix(source.menuWidth, target.menuWidth).roundToInt().coerceAtLeast(0)
                        val height = mix(source.menuHeight, target.menuHeight).roundToInt().coerceAtLeast(0)
                        if (width > 0 && height > 0) drawImage(text.image,
                            srcOffset = text.origin + IntOffset(if (motion.rightToLeft) text.size.width - source.menuX - source.menuWidth else source.menuX, source.menuY),
                            srcSize = IntSize(source.menuWidth, source.menuHeight),
                            dstOffset = IntOffset(left(mix(source.menuX, target.menuX), width.toFloat()).roundToInt(), mix(source.menuY, target.menuY).roundToInt()),
                            dstSize = IntSize(width, height), alpha = placed.alpha * browseViewVisibility(true, target.menuWidth > 0, p))
                    }
                }
            }
        }
        // Pass two: every artwork tile, live or virtualized, above all text.
        // Iterate only the two viewports, never the whole catalog on every frame.
        val paintOrder = (visible.map { it.index - 1 to it.key } + retained.map { it.value.index to it.key })
            .sortedBy { it.first }
        paintOrder.forEach { (_, key) ->
            val frozen = retained[key]
            if (frozen != null) {
                val content = frozen.item.content ?: return@forEach
                val art = frozen.item.artwork ?: return@forEach
                val rect = pinchArtworkRect(content.source, content.target, p)
                val cardWidth = frozen.item.size.width + (frozen.targetWidth - frozen.item.size.width) * p
                val x = if (motion.rightToLeft) cardWidth - rect.right else rect.left
                val side = rect.width.roundToInt().coerceAtLeast(0)
                if (side > 0) drawImage(art.image, srcOffset = art.origin, srcSize = art.size,
                    dstOffset = IntOffset((frozen.origin.x + x).roundToInt(), (frozen.origin.y + rect.top).roundToInt()),
                    dstSize = IntSize(side, side), alpha = frozen.alpha *
                        browseViewVisibility(content.source.artwork, content.target.artwork, p))
            } else if (key in liveKeys) {
                motion.artwork[key]?.let { drawLive(it, key) }
            }
        }
    }
}
