package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
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
    val foreground: Color, val secondary: Color, val opacity: Float = 1f, val textOpacity: Float = 1f, val hover: HoverGeometry? = null)
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
        if (!motion.recordingFrozen || layer.size.width == 0 || layer.size.height == 0)
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
    // Keep the live coordinates reference; the overlay maps it when drawing. A
    // placement callback avoids a global-position dispatch for every scrolled frame.
    return onPlaced { recording.coordinates = it }.drawWithContent {
        if (!motion.recordingFrozen || layer.size.width == 0 || layer.size.height == 0)
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

/** One part's float rectangle between whole-pixel endpoint placements. */
internal fun browsePinchPartRect(x: Int, y: Int, width: Int, height: Int,
    targetX: Int, targetY: Int, targetWidth: Int, targetHeight: Int, progress: Float): Rect =
    pinchElementRect(Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()),
        Rect(targetX.toFloat(), targetY.toFloat(), (targetX + targetWidth).toFloat(), (targetY + targetHeight).toFloat()), progress)

/** Rank zero is farthest from the focus. Half-overlapping windows cover the whole
 * eased geometry progress; the singleton uses the complete transition. */
internal data class PinchFadeWindow(val start: Float, val width: Float) {
    fun alpha(progress: Float, arriving: Boolean = false): Float {
        val p = if (arriving) 1f - progress else progress
        val local = ((p.coerceIn(0f, 1f) - start) / width).coerceIn(0f, 1f)
        return 1f - local * local * (3f - 2f * local)
    }
}

internal fun pinchFadeWindow(rank: Int, count: Int): PinchFadeWindow {
    require(count > 0 && rank in 0 until count)
    return if (count == 1) PinchFadeWindow(0f, 1f)
        else PinchFadeWindow(rank.toFloat() / (count + 1f), 2f / (count + 1f))
}

/** Endpoint distances keep order independent of interpolated positions and direction.
 * Input order breaks equal-distance ties consistently for both drawing passes. */
internal fun <T> pinchFadeSchedule(distances: Map<T, Float>): Map<T, PinchFadeWindow> =
    distances.entries.sortedByDescending { it.value }.mapIndexed { rank, entry ->
        entry.key to pinchFadeWindow(rank, distances.size)
    }.toMap()

/** Drawing-only focus snapshot: re-grabbing a preview must not reorder its fades. */
internal class PinchFadeFocus {
    private var generation = -1L
    private var point = Offset.Zero
    fun resolve(transaction: Long, midpoint: Offset): Offset {
        if (generation != transaction) { generation = transaction; point = midpoint }
        return point
    }
}

private data class RetainedPinchPlacement(val key: Any, val item: FrozenBrowseItem, val origin: Offset,
    val targetWidth: Float, val targetHeight: Float, val alpha: Float, val index: Int,
    val geometryProgress: Float)

/** [indices] is the existing last-wins map for these same [items]. Reuse it for
 * unique keys; malformed duplicate input must retain indexOf's first match. */
internal fun <T> retainedPinchItemIndices(items: List<T>, indices: Map<String, Int>,
    keyOf: (T) -> String): Map<String, Int> {
    if (indices.size == items.size) return indices
    return buildMap {
        items.forEachIndexed { index, item ->
            val key = keyOf(item)
            if (!containsKey(key)) put(key, index)
        }
    }
}

/** Lazy layout can briefly expose a removed key or a non-item slot. */
internal fun Map<String, Int>.retainedPinchItemIndex(key: Any): Int =
    if (key is String) this[key] ?: -1 else -1

/** Live items in index order (each pass paints them reversed); lazy layout normally reports them sorted. */
internal fun <T> liveItemsInIndexOrder(items: List<T>, indexOf: (T) -> Int): List<T> {
    for (i in 1 until items.size) if (indexOf(items[i - 1]) > indexOf(items[i])) return items.sortedBy(indexOf)
    return items
}

@Composable
internal fun Modifier.pinchRetainedOverlay(motion: BrowsePinchMotion, grid: LazyGridState,
    indices: Map<String, Int>, scene: SelectionScene? = null): Modifier {
    val backing = LocalFrostedBackdrop.current
    val location = remember { BackdropLocation() }
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableListOf<HoverGroupRenderer>() }
    val liftStyle = rememberLiftShadow()
    val fadeFocus = remember(motion) { PinchFadeFocus() }
    val recordingDensity = androidx.compose.ui.platform.LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val renderer = currentGlassRendererGeneration()
    val blur = renderer.backend == GlassBackend.Modern && android.os.Build.VERSION.SDK_INT >= 31
    DisposableEffect(graphics) { onDispose { groups.forEach { it.release() } } }
    val geometryResolver = remember(motion) { { key: Any?, current: HoverGeometry -> motion.resolveHover(key, current) } }
    SideEffect {
        motion.hoverLift = { scene?.hover?.get(it) }
        scene?.geometryResolver = geometryResolver
    }
    BackdropLease(backing.takeIf { motion.visualSource != null && motion.frozen.values.any { it.content?.hover != null } })
    DisposableEffect(scene, geometryResolver) { onDispose {
        if (scene?.geometryResolver === geometryResolver) scene?.geometryResolver = null
        scene?.retainedHover = emptyList()
    } }
    // Keep geometry-clock reads in this producer, including inside an ancestor sampling recording.
    return graphicsLayer().backdropLocation(location).onGloballyPositioned { motion.viewportCoordinates = it }.drawWithContent {
        // Publish retained bodies before the host draws its one joined shadow/surface.
        val visible = grid.layoutInfo.visibleItemsInfo
        val p = motion.progress.value.coerceIn(0f, 1f)
        val liveKeys = if (motion.visualSource == null) emptySet() else visible.map { it.key }.toSet()
        val focus = if (motion.source == null) Offset.Zero else fadeFocus.resolve(motion.transaction,
            motion.gesture?.midpoint ?: Offset(motion.bounds[motion.focusKey]?.center?.x ?: motion.viewportWidth / 2f,
                motion.focusTop.toFloat()))
        val arrivals = if (motion.visualSource == null) emptyMap() else pinchFadeSchedule(
            visible.filter { it.key !in motion.bounds && (it.key in motion.artwork || it.key in motion.text) }
                .associate { entry -> entry.key to (Offset(entry.offset.x + entry.size.width / 2f,
                    entry.offset.y + entry.size.height / 2f) - focus).getDistance() })
        fun drawLive(part: BrowsePinchLayer, key: Any) {
            val viewport = motion.viewportCoordinates
            val coordinates = part.coordinates ?: return
            if (viewport?.isAttached != true || !coordinates.isAttached || part.layer.isReleased ||
                part.layer.size.width <= 0 || part.layer.size.height <= 0) return
            val bounds = viewport.localBoundingBoxOf(coordinates, clipBounds = false)
            val incoming = arrivals[key]?.alpha(p, arriving = true) ?: 1f
            part.layer.alpha = (motion.itemAlpha[key] ?: 1f) * incoming
            // A resized cover re-records its layer later in this frame (its own render node is
            // updated after this pass), so the layer still holds last frame's size. Scaling by it
            // painted the new drawing at old/new size, one to a few pixels off per frame (#505).
            val recorded = if (motion.recordingFrozen) part.layer.size else coordinates.size
            if (recorded.width <= 0 || recorded.height <= 0) return
            val lift = scene?.viewportHover?.get(key)
            scale(lift?.scale ?: 1f, lift?.scale ?: 1f, lift?.pivot ?: Offset.Zero) {
                translate(bounds.left, bounds.top) {
                    scale(bounds.width / recorded.width, bounds.height / recorded.height, Offset.Zero) { drawLayer(part.layer) }
                }
            }
        }
        if (motion.visualSource == null) {
            scene?.retainedHover = emptyList()
            drawContent()
            // Nothing is retained at rest. Keep the same stable two-pass topology
            // (every text layer, then every artwork layer in item order) without
            // per-frame key sets, placement maps or a sorted paint list while scrolling.
            clipRect {
                val order = liveItemsInIndexOrder(visible) { it.index }.asReversed()
                order.forEach { entry -> motion.text[entry.key]?.let { drawLive(it, entry.key) } }
                order.forEach { entry -> motion.artwork[entry.key]?.let { drawLive(it, entry.key) } }
            }
            return@drawWithContent
        }
        val retained = linkedMapOf<Any, RetainedPinchPlacement>()
        val row = visible.firstOrNull { it.key != "browser-notice" }
        if (row != null) {
            val rowIndex = indices.retainedPinchItemIndex(row.key)
            val pitch = max(row.size.height.toFloat(), 1f)
            val geometry = BrowseCellGeometry(motion.targetColumns)
            val sourceInset = (BrowseCellGeometry(motion.sourceColumns).cardInset * motion.pixelDensity).roundToInt()
            val targetInset = (geometry.cardInset * motion.pixelDensity).roundToInt()
            val sourceVerticalInset = (BrowseCellGeometry(motion.sourceColumns).cardVerticalInset(requireNotNull(motion.visualSource)) * motion.pixelDensity).roundToInt()
            val targetVerticalInset = (geometry.cardVerticalInset(requireNotNull(motion.target)) * motion.pixelDensity).roundToInt()
            motion.bounds.forEach { (key, old) ->
                if (key in liveKeys) return@forEach
                val image = motion.frozen[key] ?: return@forEach
                val index = indices.retainedPinchItemIndex(key)
                if (index < 0) return@forEach
                val column = index % motion.targetColumns
                val width = geometry.cellWidth(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
                val leading = geometry.cellLeft(motion.viewportWidth.toInt(), motion.pixelDensity, column).toFloat()
                val x = if (motion.rightToLeft) motion.viewportWidth - leading - width else leading
                val y = row.offset.y + (index / motion.targetColumns - rowIndex / motion.targetColumns) * pitch
                val sourceOrigin = Offset(old.left + sourceInset, old.top + sourceVerticalInset)
                val targetOrigin = Offset(x + targetInset, y + targetVerticalInset)
                val fadeWindow = browsePinchFadeWindow((targetOrigin - sourceOrigin).getDistance(), motion.viewportWidth)
                val exitProgress = browsePinchGeometryProgress(true, p, fadeWindow)
                // Position and every part's dimensions stop on the same spatial frame.
                val origin = sourceOrigin + (targetOrigin - sourceOrigin) * exitProgress
                retained[key] = RetainedPinchPlacement(key, image, origin, (width - 2 * targetInset).coerceAtLeast(0f),
                    image.content?.target?.height?.toFloat() ?: (pitch - 2 * targetVerticalInset).coerceAtLeast(0f),
                    1f, index, exitProgress)
            }
        }
        // Reuse the validated placements: scheduling must not add catalog lookups.
        val departures = pinchFadeSchedule(retained.keys.associateWith { key ->
            (motion.bounds.getValue(key).center - focus).getDistance()
        })
        retained.replaceAll { key, placed -> placed.copy(alpha = departures.getValue(key).alpha(p)) }
        fun retainedLift(placed: RetainedPinchPlacement): HoverGeometry? {
            val content = placed.item.content ?: return null
            val hover = content.hover ?: return null
            val size = placed.item.size
            val width = size.width + (placed.targetWidth - size.width) * placed.geometryProgress
            val outline = mediaItemOutlineLerp(content.source.outline(density), content.target.outline(density), placed.geometryProgress)
                .let { if (motion.rightToLeft) it.mirrored(width) else it }
            return motion.resolveHover(placed.key, hoverGeometry(outline, hover.room, hover.progress), placed.geometryProgress)
        }
        val lifted = retained.values.mapNotNull { placed -> retainedLift(placed)?.translate(placed.origin)?.let {
            it to (placed.alpha * (placed.item.content?.opacity ?: 1f))
        } }.filter { it.first.progress > 0f && it.second > 0f }
        scene?.retainedHover = lifted
        drawContent()
        clipRect {
            // Standalone compositors can render retained selections without a host.
            if (scene == null) {
                val components = hoverComponents(lifted.map { it.first.rest.bounds }, MediaSelectionGap.toPx())
                while (groups.size < components.size) groups += HoverGroupRenderer(graphics, scope)
                components.forEachIndexed { index, members ->
                    val parts = members.map { lifted[it].first }
                    drawHoverGroup(groups[index], parts, { a, b -> minOf(parts[a].progress, parts[b].progress) },
                        backing, location, blur, recordingDensity, direction, MediaSelectionGap.toPx(),
                        opacity = members.maxOf { lifted[it].second }, lift = liftStyle)
                }
            }
            fun drawRetainedText(placed: RetainedPinchPlacement) {
                val image = placed.item
                val content = image.content
                val geometryProgress = placed.geometryProgress
                translate(placed.origin.x, placed.origin.y) {
                    val lift = retainedLift(placed)
                    scale(lift?.scale ?: 1f, lift?.scale ?: 1f, lift?.pivot ?: Offset.Zero) {
                        image.highlight?.let { highlight ->
                            val original = androidx.compose.ui.geometry.Size(image.size.width.toFloat(), image.size.height.toFloat())
                            val size = androidx.compose.ui.geometry.Size(original.width + (placed.targetWidth - original.width) * geometryProgress,
                                original.height + (placed.targetHeight - original.height) * geometryProgress)
                            with(highlight.renderer) { retainedCurrentSong(size, original, content, geometryProgress, motion.rightToLeft, highlight.color, placed.alpha * (content?.opacity ?: 1f)) }
                        }
                        if (content == null) {
                            // Non-browser callers can still retain an arbitrary source drawing.
                            val fit = minOf(placed.targetWidth / image.size.width, placed.targetHeight / image.size.height)
                            val factor = 1f + (fit - 1f) * geometryProgress
                            scale(factor, factor, Offset.Zero) {
                                drawImage(image.image, srcOffset = image.origin, srcSize = image.size, dstSize = image.size, alpha = placed.alpha)
                            }
                        } else {
                            // Keep generic row decoration separate from endpoint glyphs.
                            // Hover and playback frost each have one explicit drawing owner.
                            val width = image.size.width + (placed.targetWidth - image.size.width) * geometryProgress
                            val height = image.size.height + (placed.targetHeight - image.size.height) * geometryProgress
                            scale(width / image.size.width, height / image.size.height, Offset.Zero) {
                                drawImage(image.image, srcOffset = image.origin, srcSize = image.size, dstSize = image.size, alpha = placed.alpha * content.opacity)
                            }
                            val source = content.source; val target = content.target
                            fun mix(a: Int, b: Int) = a + (b - a) * geometryProgress
                            val cardWidth = image.size.width + (placed.targetWidth - image.size.width) * geometryProgress
                            fun left(x: Float, width: Float) = if (motion.rightToLeft) cardWidth - x - width else x
                            fun paragraph(a: androidx.compose.ui.text.TextLayoutResult?, b: androidx.compose.ui.text.TextLayoutResult?,
                                ax: Int, ay: Int, bx: Int, by: Int, color: Color) {
                                val width = mix(a?.size?.width ?: 0, b?.size?.width ?: 0)
                                val height = mix(a?.size?.height ?: 0, b?.size?.height ?: 0)
                                translate(left(mix(ax, bx), width), mix(ay, by)) {
                                    clipRect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f)) {
                                        drawBrowsePinchParagraph(a, b, geometryProgress, width, color, content.foreground, placed.alpha * content.opacity * content.textOpacity)
                                    }
                                }
                            }
                            paragraph(source.title, target.title, source.titleX, source.titleY, target.titleX, target.titleY, Color.Unspecified)
                            val targetStyle = motion.target
                            val infoInk = if (targetStyle?.presentation == Presentation.List && targetStyle.listSize.trailingInformation)
                                content.secondary else Color.Unspecified
                            paragraph(source.information, target.information, source.infoX, source.infoY, target.infoX, target.infoY, infoInk)
                            val art = pinchArtworkRect(source, target, geometryProgress)
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
                                val width = mix(source.menuWidth, target.menuWidth).coerceAtLeast(0f)
                                val height = mix(source.menuHeight, target.menuHeight).coerceAtLeast(0f)
                                val x = left(mix(source.menuX, target.menuX), width)
                                val y = mix(source.menuY, target.menuY)
                                drawMotionImage(text.image,
                                    srcOffset = text.origin + IntOffset(if (motion.rightToLeft) text.size.width - source.menuX - source.menuWidth else source.menuX, source.menuY),
                                    srcSize = IntSize(source.menuWidth, source.menuHeight),
                                    rect = Rect(x, y, x + width, y + height), alpha = placed.alpha * browseViewVisibility(true, target.menuWidth > 0, p))
                            }
                        }
                    }
                }
            }
            val paintOrder = (visible.map { it.index - 1 to it.key } + retained.map { it.value.index to it.key })
                .sortedByDescending { it.first }
            paintOrder.forEach { (_, key) -> retained[key]?.let(::drawRetainedText) ?: motion.text[key]?.let { drawLive(it, key) } }
            // Pass two: every artwork tile, live or virtualized, above all text.
            // Iterate only the two viewports, never the whole catalog on every frame.
            paintOrder.forEach { (_, key) ->
                val frozen = retained[key]
                if (frozen != null) {
                    val content = frozen.item.content ?: return@forEach
                    val art = frozen.item.artwork ?: return@forEach
                    val rect = pinchArtworkRect(content.source, content.target, frozen.geometryProgress)
                    val cardWidth = frozen.item.size.width + (frozen.targetWidth - frozen.item.size.width) * frozen.geometryProgress
                    val x = if (motion.rightToLeft) cardWidth - rect.right else rect.left
                    // Independently rounded origin and side moved the far edge back and forth (#505).
                    val left = frozen.origin.x + x
                    val top = frozen.origin.y + rect.top
                    val lift = retainedLift(frozen)
                    scale(lift?.scale ?: 1f, lift?.scale ?: 1f, lift?.pivot?.plus(frozen.origin) ?: Offset.Zero) {
                        drawMotionImage(art.image, srcOffset = art.origin, srcSize = art.size,
                            rect = Rect(left, top, left + rect.width, top + rect.height), alpha = frozen.alpha *
                                browseViewVisibility(content.source.artwork, content.target.artwork, p))
                    }
                } else if (key in liveKeys) {
                    motion.artwork[key]?.let { drawLive(it, key) }
                }
            }
        }
    }
}
