package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * One dragged card's live motion (#533), read while drawing. Positions are in the drag
 * overlay's coordinates; [geometry] is in the card's own coordinates.
 */
internal class HoverCardFrame(
    val position: () -> Offset,
    val geometry: () -> HoverGeometry,
    /** Card position minus the position whose backdrop region it shows (its carried cutout). */
    val sampleShift: () -> Offset,
    val restOutline: () -> Rect?,
    val destinationOutline: () -> Rect?,
    /** 0 at the item's rest position, 1 once it has joined the stack. */
    val flight: () -> Float,
    /** 0 until the drop, 1 once the card reaches its destination. */
    val landing: () -> Float,
    val alpha: () -> Float,
)

@Composable private fun rememberHoverGroupRenderer(): HoverGroupRenderer {
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember(graphics) { HoverGroupRenderer(graphics.createGraphicsLayer(), graphics.createGraphicsLayer(), scope) }
    DisposableEffect(renderer) { onDispose { graphics.releaseGraphicsLayer(renderer.output); graphics.releaseGraphicsLayer(renderer.flatOutput) } }
    return renderer
}

/** A dragged card reuses the hover outline and frost. The shared underlay owns the outside
 * shadow; a small additional shadow separates panes once they join the stack. */
@Composable
internal fun HoverDragCard(modifier: Modifier, frame: HoverCardFrame, backing: FrostedBackdrop?,
    stackShadow: () -> Float = { 0f }, contentAlpha: () -> Float = frame.alpha,
    content: @Composable BoxScope.() -> Unit) {
    val liftStyle = rememberLiftShadow()
    val averageProgress = glassAverageProgress()
    val group = rememberHoverGroupRenderer()
    val location = remember { BackdropLocation() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Box(modifier.backdropLocation(location)) {
        // Material and foreground have different handoff owners: frost/shadow fades
        // into the list, while its cover and text stay opaque until the row is ready.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = frame.alpha().coerceIn(0f, 1f) }.drawWithContent {
            val geometry = frame.geometry()
            with(group.shadow) {
                draw(listOf(geometry.lifted.contour to 1f), .22f * liftStyle.strength * stackShadow(),
                    liftStyle.spread, color = liftStyle.color)
            }
            drawHoverGroup(group, listOf(geometry), { _, _ -> 1f }, backing, location, Build.VERSION.SDK_INT >= 33,
                density, direction, MediaSelectionGap.toPx(), sampleOffset = frame.sampleShift(), shadow = false, lift = liftStyle,
                averageProgress = averageProgress.value)
        })
        Box(Modifier.graphicsLayer { alpha = contentAlpha().coerceIn(0f, 1f) }.drawWithContent {
            val geometry = frame.geometry()
            scale(geometry.scale, geometry.scale, geometry.pivot) { this@drawWithContent.drawContent() }
        }) { content() }
    }
}

/**
 * Beneath every card: one union shadow, and the bridges that keep neighbours joined. Items
 * adjacent at rest stay bridged while they leave for the stack (narrowing as they fly);
 * items adjacent at their destinations bridge again as they land.
 */
@Composable
internal fun HoverDragUnderlay(frames: () -> List<HoverCardFrame>, backing: FrostedBackdrop?, modifier: Modifier) {
    val liftStyle = rememberLiftShadow()
    val averageProgress = glassAverageProgress()
    val shadow = rememberHoverGroupRenderer()
    val bridges = remember { mutableListOf<HoverGroupRenderer>() }
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(graphics) { onDispose { bridges.forEach { graphics.releaseGraphicsLayer(it.output); graphics.releaseGraphicsLayer(it.flatOutput) }; bridges.clear() } }
    val location = remember { BackdropLocation() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Box(modifier.backdropLocation(location).drawWithContent {
        drawContent()
        val cards = frames()
        if (cards.isEmpty()) return@drawWithContent
        val gap = MediaSelectionGap.toPx()
        val placed = cards.map { it.geometry().translate(it.position()) }
        val alphas = cards.map { it.alpha().coerceIn(0f, 1f) }
        // Pairs that join: adjacent at rest (fading over the flight) or at the destination (growing on landing).
        data class Bridge(val a: Int, val b: Int, val strength: Float)
        fun adjacent(a: Rect?, b: Rect?): Boolean {
            if (a == null || b == null) return false
            val x = max(a.left - b.right, b.left - a.right)
            val y = max(a.top - b.bottom, b.top - a.bottom)
            return x <= gap && y <= gap && (x < 0f || y < 0f)
        }
        val joined = mutableListOf<Bridge>()
        for (i in cards.indices) for (j in i + 1 until cards.size) {
            val strength = hoverBridgeStrength(adjacent(cards[i].restOutline(), cards[j].restOutline()),
                adjacent(cards[i].destinationOutline(), cards[j].destinationOutline()),
                cards[i].flight() to cards[j].flight(), cards[i].landing() to cards[j].landing(),
                placed[i].progress to placed[j].progress)
            if (strength > .01f) joined += Bridge(i, j, strength)
        }
        // One shadow under everything; the bridges' own rectangles join it.
        val opacity = alphas.maxOrNull() ?: 0f
        val bodies = placed.map { it.lifted.contour to 1f }
        val bridgeBodies = joined.flatMap { bridge ->
            val path = HoverPathCache()
            val pair = listOf(placed[bridge.a], placed[bridge.b])
            path.path(pair.map { SelectionRegion(it.lifted.bounds, it.lifted.corner) },
                pair.map { SelectionRegion(it.rest.bounds, it.rest.corner) }, gap) { _, _ -> bridge.strength }
            path.bridges.map { androidx.compose.ui.geometry.RoundRect(it.first, androidx.compose.ui.geometry.CornerRadius.Zero) to 1f }
        }
        with(shadow.shadow) {
            draw(bodies + bridgeBodies, liftStyle.strength * (placed.maxOfOrNull { it.progress } ?: 0f) * opacity, liftStyle.spread, color = liftStyle.color)
        }
        while (bridges.size < joined.size) bridges += HoverGroupRenderer(graphics.createGraphicsLayer(), graphics.createGraphicsLayer(), scope)
        joined.forEachIndexed { index, bridge ->
            // The cards above cover their own areas; only the joining neck shows here.
            drawHoverGroup(bridges[index], listOf(placed[bridge.a], placed[bridge.b]), { _, _ -> bridge.strength }, backing, location,
                Build.VERSION.SDK_INT >= 33, density, direction, gap, sampleOffset = cards[bridge.a].sampleShift(), shadow = false,
                opacity = min(alphas[bridge.a], alphas[bridge.b]), lift = liftStyle, averageProgress = averageProgress.value)
        }
    })
}
