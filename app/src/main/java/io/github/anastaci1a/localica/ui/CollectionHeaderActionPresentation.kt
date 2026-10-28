package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import kotlin.math.roundToInt
import kotlin.math.abs

internal data class CollectionHeaderActionSamplingFrame(val generation: Long, val progress: Float, val active: Boolean,
    val expectedRoot: Rect?, val draw: BackdropSamplingObservation)
internal val LocalCollectionHeaderActionSamplingObserver =
    staticCompositionLocalOf<((CollectionHeaderActionSamplingFrame) -> Unit)?> { null }

private class HeaderActionPlacement { var coordinates: LayoutCoordinates? = null }

internal data class CollectionHeaderActionSpec(val split: Boolean, val foreground: Color, val divider: Color, val outsetPx: Int)
internal data class CollectionHeaderActionCapture(val generation: Long, val bounds: Rect,
    val spec: CollectionHeaderActionSpec)

/** The retained image and the live material share exactly the same affine mapping.
 * Bounds are copied while the source header is publicly placed, never recovered
 * from a detached LazyGrid descendant after destination reflow. */
internal fun collectionHeaderActionRect(source: Rect, presented: Rect, local: Rect): Rect {
    require(source.width > 0f && source.height > 0f)
    // Match drawImage's integer destination first, then quantize the action
    // once. Placement, source registration and drawLayer use this same result.
    val left = presented.left.toInt().toFloat()
    val top = presented.top.toInt().toFloat()
    val x = presented.width.toInt().coerceAtLeast(1) / source.width
    val y = presented.height.toInt().coerceAtLeast(1) / source.height
    return Rect((left + local.left * x).roundToInt().toFloat(), (top + local.top * y).roundToInt().toFloat(),
        (left + local.right * x).roundToInt().toFloat(), (top + local.bottom * y).roundToInt().toFloat())
}

/** Native coordinate transforms may introduce float round-off around integer placement. */
internal fun collectionHeaderActionPlacementMatches(actual: Rect?, expected: Rect?): Boolean =
    actual != null && expected != null && abs(actual.left - expected.left) <= .01f &&
        abs(actual.top - expected.top) <= .01f && abs(actual.right - expected.right) <= .01f &&
        abs(actual.bottom - expected.bottom) <= .01f

/** A draw producer, not a second button tree. No click, touch, indication or
 * accessibility actions live here; the original LazyGrid owns all interaction.
 * Its live layer is not included in the immutable native atlas readback. */
@Composable
internal fun CollectionHeaderActionPresentation(owner: CollectionPinchHeader, source: FrostedBackdrop?) {
    val captured = owner.actionPresentation ?: return
    val layer = rememberGraphicsLayer()
    val placement = remember { HeaderActionPlacement() }
    SideEffect { owner.actionLayer = layer }
    DisposableEffect(owner, layer) { onDispose {
        if (owner.actionLayer === layer) {
            owner.actionLayer = null
            owner.actionRecordedGeneration = null
        }
    } }
    val upstreamObserver = LocalBackdropSamplingObserver.current
    val actionObserver = LocalCollectionHeaderActionSamplingObserver.current
    val inspect = owner.observer != null || actionObserver != null
    val drawObserver: ((BackdropSamplingObservation) -> Unit)? = if (inspect) { observation ->
        // Nested material display lists may be reused while geometry is unchanged.
        // Preserve the last actual recording, but never admit a retired producer.
        if (owner.actionPresentation?.generation == captured.generation) {
            owner.actionMaterialDraw = observation
            actionObserver?.invoke(CollectionHeaderActionSamplingFrame(captured.generation,
                owner.motion.progress.value, owner.motion.visualSource != null, owner.viewport?.takeIf { it.isAttached }?.let { viewport ->
                    owner.actionRect()?.translate(viewport.localToRoot(Offset.Zero))
                }, observation))
        }
        upstreamObserver?.invoke(observation)
    } else upstreamObserver
    CompositionLocalProvider(LocalFrostedBackdrop provides source,
        LocalSurfaceShadows provides null, LocalBottomShadows provides null,
        LocalBackdropSamplingObserver provides drawObserver) {
        Layout(content = {
            Box(Modifier.onPlaced { placement.coordinates = it }.drawWithContent {
                // Placement has already updated the material's BackdropLocation.
                // Retain only a normal display list, never a readback lease.
                owner.actionStageRect() // observe the pinch clock in DRAW too
                val actualRect = owner.actionPlacedRect(placement.coordinates)
                layer.record { this@drawWithContent.drawContent() }
                owner.actionRecordedRect = actualRect
                owner.actionRecordedGeneration = captured.generation
                // The parent's pinch overlay draws this AFTER the retained header.
            }.padding(with(androidx.compose.ui.platform.LocalDensity.current) { captured.spec.outsetPx.toDp() })) {
                LiquidGlassSurface(Modifier.fillMaxSize(), pressFeedback = false) {
                    CompositionLocalProvider(LocalContentColor provides captured.spec.foreground) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_play), null, Modifier.size(30.dp))
                            }
                            if (captured.spec.split) {
                                CompositionLocalProvider(LocalContentColor provides captured.spec.divider) { GlassPillDivider() }
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_shuffle), null, Modifier.size(30.dp))
                                }
                            }
                        }
                    }
                }
            }
        }) { measurables, constraints ->
            val rect = owner.actionStageRect()
            if (rect == null) {
                // The transaction may retire between composition and lookahead
                // measurement. The ordinary header already owns this endpoint;
                // leave the stale passive producer unplaced until recomposition.
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val child = measurables.single().measure(Constraints.fixed(
                    rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Absolute viewport coordinates, not RTL-relative offsets.
                    // Real placement moves the source sampling registration too.
                    child.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }
        }
    }
}
