package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** Screens place a retained layer through the Engine; the layer's owner still controls its lifetime. */
internal fun DrawScope.drawRetainedLayer(layer: GraphicsLayer) = drawLayer(layer)

/** The opacity is read in the draw phase from the owning surface's phase, exactly as a graphicsLayer block would. */
internal fun Modifier.presentedAlpha(alpha: () -> Float): Modifier = presentedLayer(alpha)

/** A stacking slot inside a surface; screens supply its content only. */
@Composable internal fun SurfaceLayer(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment, content = content)

/** One engine layer binder; semantic component adapters supply the transform recipe. */
internal fun Modifier.presentedLayer(alpha: () -> Float, transform: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null): Modifier =
    graphicsLayer { this.alpha = alpha(); transform?.invoke(this) }

internal fun Modifier.artworkViewportLayer(scale: () -> Float, x: () -> Float, y: () -> Float, present: Boolean): Modifier =
    presentedLayer({ 1f }) {
        scaleX = if (present) scale() else 1f
        scaleY = scaleX
        translationX = if (present) x() else 0f
        translationY = if (present) y() else 0f
    }

internal fun queuePlayerAlpha(progress: Float) = ((progress - .4f) / .6f).coerceIn(0f, 1f)
internal fun navigationChromeOffset(expansion: Float, visibility: Float, travel: Float) =
    kotlin.math.round(travel * maxOf(expansion, 1f - visibility)).toInt()

internal fun Modifier.navigationChromeLayer(expansion: ExpansionView, visibility: () -> Float, travel: () -> Float): Modifier =
    offset {
        androidx.compose.ui.unit.IntOffset(0, navigationChromeOffset(expansion.phase.value(), visibility(), travel()))
    }.presentedAlpha(visibility)

internal fun playerCoverAlpha(swipe: Float, width: Float, active: Boolean, artworkAlpha: Float, neighbor: Boolean): Float {
    val remaining = if (neighbor) kotlin.math.abs(swipe) else width - kotlin.math.abs(swipe)
    return artworkAlpha * if (active) (remaining / (width * .08f)).coerceIn(0f, 1f) else 1f
}
internal fun playerCoverRotation(offset: Float, width: Float) = (offset / width).coerceIn(-1f, 1f) * -27f
internal fun playerCoverScale(offset: Float, width: Float): Float {
    val depth = kotlin.math.abs(offset / width).coerceIn(0f, 1f)
    return 1f - .035f * depth
}
internal fun Modifier.playerCoverLayer(offset: () -> Float, swipe: () -> Float, width: () -> Float,
    active: () -> Boolean, artworkAlpha: () -> Float, neighbor: Boolean): Modifier =
    presentedLayer({ playerCoverAlpha(swipe(), width(), active(), artworkAlpha(), neighbor) }) {
        val x = offset(); val w = width()
        translationX = x
        rotationY = playerCoverRotation(x, w)
        cameraDistance = 24f * density
        scaleX = playerCoverScale(x, w)
        scaleY = scaleX
    }
internal fun playerMetadataOrder(expansion: Float) = if (expansion > 0f && expansion < 1f) -.5f else 0f
internal fun playerArtworkOrder(expansion: Float, lyrics: Float): Float {
    val moving = expansion > 0f && expansion < 1f
    val layout = lyrics.coerceIn(0f, 1f) * expansion.coerceIn(0f, 1f)
    return if (moving || layout > 0f) 0f else -1f
}
internal fun Modifier.playerPartPlacement(expansion: ExpansionView, lyrics: ExpansionView, metadata: Boolean): Modifier =
    layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, child.height) {
            val p = expansion.phase.value()
            val order = if (metadata) playerMetadataOrder(p) else playerArtworkOrder(p, lyrics.phase.value())
            child.place(0, 0, zIndex = order)
        }
    }
internal fun playerSwipeFraction(offset: Float, width: Float) = (kotlin.math.abs(offset) / width).coerceIn(0f, 1f)

internal fun actionDragPoint(release: androidx.compose.ui.geometry.Offset, destination: androidx.compose.ui.geometry.Offset, fraction: Float) =
    androidx.compose.ui.geometry.lerp(release, destination, fraction)
internal fun Modifier.actionDragBubbleLayer(origin: () -> androidx.compose.ui.geometry.Offset, position: () -> androidx.compose.ui.geometry.Offset): Modifier =
    size(52.dp).presentedLayer({ 1f }) {
        val at = position()
        translationX = at.x - origin().x - size.width / 2
        translationY = at.y - origin().y - size.height / 2
        scaleX = 1.08f; scaleY = 1.08f
    }.then(Modifier.zIndex(10f))

internal data class PlayerParticipation(
    val collapsed: Boolean, val movingCover: Boolean, val recordLyricsBackdrop: Boolean,
    val recordPlayerBackdrop: Boolean, val scrollReady: Boolean, val backgroundGeometryReady: Boolean,
    val backgroundVisible: Boolean, val backgroundComplete: Boolean,
    val lyricsVisible: Boolean, val lyricsInteractive: Boolean, val retainLyrics: Boolean,
    val seekVisible: Boolean, val actionsVisible: Boolean, val handleVisible: Boolean,
    val recordCoverBackdrop: Boolean,
)
internal fun playerParticipation(expansion: Float, lyricsLayout: Float, lyricsAlpha: Float,
    backgroundReveal: Float, seekAlpha: Float, actionsAlpha: Float, handleReveal: Float,
    lyricsOpen: Boolean, swipeActive: Boolean, swipeOffset: Float): PlayerParticipation = PlayerParticipation(
    collapsed = expansion == 0f, movingCover = expansion > 0f && expansion < 1f,
    recordLyricsBackdrop = expansion > .7f, recordPlayerBackdrop = expansion >= .99f,
    scrollReady = expansion > .99f, backgroundGeometryReady = expansion >= .999f && !swipeActive && swipeOffset == 0f,
    backgroundVisible = backgroundReveal > 0f, backgroundComplete = backgroundReveal >= 1f,
    lyricsVisible = lyricsLayout > 0f, lyricsInteractive = lyricsAlpha > 0f,
    retainLyrics = lyricsOpen || lyricsLayout > 0f, seekVisible = seekAlpha > 0f,
    actionsVisible = actionsAlpha > 0f, handleVisible = handleReveal > 0f,
    recordCoverBackdrop = expansion > .7f && lyricsLayout <= 0f,
)
internal fun playerSceneBackground(progress: Float, lyricsLayout: Float) = progress * (1f - lyricsLayout)
internal fun playerSceneSoftness(coverBlend: Float, swipeFeather: Float) = maxOf(1f - coverBlend, swipeFeather / .9f)
internal data class PlayerInputBand(val top: Int, val bottom: Int) { val height get() = bottom - top }
internal fun playerTitleInputBand(statusAndControls: Int, origin: Int, height: Int, seek: Int, padding: Int): PlayerInputBand {
    val top = (statusAndControls - origin).coerceIn(0, height)
    val bottom = minOf(height, seek - padding - origin).coerceAtLeast(top)
    return PlayerInputBand(top, bottom)
}
