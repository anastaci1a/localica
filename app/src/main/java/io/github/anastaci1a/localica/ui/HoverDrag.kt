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
    val renderer = remember(graphics) { HoverGroupRenderer(graphics, scope) }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    return renderer
}

/** A dragged card reuses the hover outline and frost, and its source's [liftStyle] (#553). The shared
 * underlay owns the outside shadow; a small additional shadow separates panes once they join the stack. */
@Composable
internal fun HoverDragCard(modifier: Modifier, frame: HoverCardFrame, backing: FrostedBackdrop?, liftStyle: LiftShadow,
    stackShadow: () -> Float = { 0f }, contentAlpha: () -> Float = frame.alpha,
    content: @Composable BoxScope.() -> Unit) {
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
            with(group.surface(listOf(0))) {
                drawShadow(listOf(geometry.lifted.contour to 1f), .22f * liftStyle.strength * stackShadow(),
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
internal fun HoverDragUnderlay(frames: () -> List<HoverCardFrame>, backing: FrostedBackdrop?, liftStyle: LiftShadow,
    modifier: Modifier) {
    val averageProgress = glassAverageProgress()
    val shadow = rememberHoverGroupRenderer()
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
        fun adjacent(a: Rect?, b: Rect?): Boolean {
            if (a == null || b == null) return false
            val x = max(a.left - b.right, b.left - a.right)
            val y = max(a.top - b.bottom, b.top - a.bottom)
            return x <= gap && y <= gap && (x < 0f || y < 0f)
        }
        val strengths = Array(cards.size) { FloatArray(cards.size) }
        for (i in cards.indices) for (j in i + 1 until cards.size) {
            strengths[i][j] = hoverBridgeStrength(adjacent(cards[i].restOutline(), cards[j].restOutline()),
                adjacent(cards[i].destinationOutline(), cards[j].destinationOutline()),
                cards[i].flight() to cards[j].flight(), cards[i].landing() to cards[j].landing(),
                placed[i].progress to placed[j].progress)
        }
        // Each opacity slice shares its analytic frost and shadow path. Cards above carry
        // their own backdrop. No strength cutoff removes a finite bridge.
        drawHoverGroup(shadow,placed,{ a,b -> strengths[min(a,b)][max(a,b)] },backing,location,
            Build.VERSION.SDK_INT >= 33,density,direction,gap,sampleOffset = cards.first().sampleShift(),
            opacity = alphas.maxOrNull() ?: 0f,itemOpacity = alphas,lift = liftStyle,averageProgress = averageProgress.value)
    })
}
