package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A held card's content publishes its item outline here, so the card shares it (#532). */
internal val LocalHeldItemOutline = compositionLocalOf<MediaItemOutlineHolder?> { null }

/** Shared lifted material; callers own the gesture and landing position. With an [outline],
 * the glass occupies the item's shared outline inside the card instead of the whole card. */
@Composable
internal fun GlassDragBubble(modifier: Modifier = Modifier, landing: () -> Float = { 0f },
    shadow: Boolean = true, outline: (() -> MediaItemOutline)? = null, content: @Composable BoxScope.() -> Unit) {
    @Composable fun Modifier.landingFade() = glassShadowOpacity {
        val finish = landing().coerceIn(0f, 1f)
        1f - finish
    }
    if (outline == null) {
        LiquidGlassSurface(modifier.landingFade(), cornerRadius = 16.dp, pressFeedback = false, shadow = shadow, content = content)
        return
    }
    Box(modifier.landingFade()) {
        // Composition reads only the radius; placement reads the moving bounds.
        val corner = with(LocalDensity.current) { outline().corner.toDp() }
        LiquidGlassSurface(Modifier.heldOutlinePlacement(outline).testTag("held-outline"), cornerRadius = corner,
            pressFeedback = false, shadow = shadow)
        content()
    }
}

/** Place a child exactly at the outline rectangle of its parent card (it may extend slightly past it). */
internal fun Modifier.heldOutlinePlacement(outline: () -> MediaItemOutline): Modifier = layout { measurable, _ ->
    val bounds = outline().bounds
    val width = bounds.width.roundToInt().coerceAtLeast(0)
    val height = bounds.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt()) }
}
