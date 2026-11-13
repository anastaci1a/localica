package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import io.github.anastaci1a.localica.ui.components.ScrollbarTapSharing
import io.github.anastaci1a.localica.ui.components.scrollbarInput
import io.github.anastaci1a.localica.ui.engine.rememberScrollbarMotion
import io.github.anastaci1a.localica.ui.engine.scrollbarPresentation
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.roundToInt

/** The measured rail owns thumb travel, its input geometry and the adjacent preview slot.
 * Its model supplies only normalized position and resting length for the measured track. The track
 * is the rail's height less standard padding at both ends; hosts bound the rail by their controls.
 * The held thumb's width, length and colour are one function of the grip progress (#704). The thumb has two
 * states: [active] while it owns a contact, else inactive (#952). Active travel reports thumb-fraction deltas
 * ([dragBy]); the caller turns them into list movement. [claim] resolves a press over a shared button (#950).
 */
@Composable
internal fun ScrollbarSlots(state: Any, key: Any, movement: () -> Pair<Int, Int>,
    fraction: () -> Float, thumbSize: (Float, Float) -> Float, scrolling: () -> Boolean,
    active: () -> Boolean, onActive: (Boolean) -> Unit, claim: (Offset) -> ((Offset, Long) -> Boolean?)?,
    dragFraction: () -> Float, dragFrom: (Float) -> Unit, dragBy: (Float) -> Unit,
    request: (Float) -> Boolean, previewFor: () -> String?,
    speed: Float, reduced: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    var activity by remember { mutableIntStateOf(0) }
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Pointer coroutines retain this reader across owner replacement during a held contact.
    val motion by rememberUpdatedState(rememberScrollbarMotion(state, movement, { activity }, scrolling,
        active(), speed, reduced))
    val rest = MaterialTheme.colorScheme.outlineVariant
    val held = MaterialTheme.colorScheme.primary
    // The preview extends 28 dp inward from the 48 dp input rail.
    BoxWithConstraints(modifier.fillMaxHeight()
        .width(ScrollbarSpec.railWidth)
        .then(ScrollbarTapSharing)
        .padding(vertical = ScrollbarSpec.trackPadding)) {
        val track = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val minThumb = with(density) { ScrollbarSpec.minimumThumbHeight.toPx() }
        val lengthCap = with(density) { ScrollbarSpec.heldMaxExtraLength.toPx() }
        val restWidth = with(density) { ScrollbarSpec.thumbWidth.toPx() }
        val extraWidth = with(density) { ScrollbarSpec.heldExtraWidth.toPx() }
        val touch = with(density) { ScrollbarSpec.hitWidth.toPx() }
        val radius = with(density) { ScrollbarSpec.thumbRadius.toPx() }
        fun restingThumb(): Float { fraction(); return thumbSize(track, minThumb) }
        fun thumb(): Float { val r = restingThumb(); return r + ScrollbarSpec.heldExtraLength(r, lengthCap) * motion.grip() }
        fun displayed() = if (active()) dragFraction() else (fraction() + motion.releaseOffset()).coerceIn(0f, 1f)
        fun position() = displayed().coerceIn(0f, 1f) * (track - thumb()).coerceAtLeast(0f)
        // The visible thumb (at least the opacity floor), at least the minimum touch length about its centre.
        fun hit(y: Float): Boolean {
            if (motion.opacity() < ScrollbarSpec.hitOpacityFloor) return false
            val length = thumb()
            val reach = maxOf(length, touch) / 2f
            val centre = position() + length / 2f
            return y >= centre - reach && y <= centre + reach
        }
        // A modal cancellation may have deactivated it already; that release is not repeated.
        fun activate(on: Boolean) {
            if (on == active()) return
            activity++
            if (on) dragFrom(displayed())
            onActive(on)
            if (!on) motion.release(dragFraction() - fraction())
        }
        fun move(dy: Float) {
            activity++
            dragBy(dy / (track - thumb()).coerceAtLeast(1f))
        }
        fun claimAt(p: Offset) = placed[0]?.takeIf { it.isAttached }?.let { claim(it.localToRoot(p)) }
        // The pointer coroutine outlives compositions; the track moves with the host bounds (#704), so it reads
        // the current geometry rather than the first composition's.
        val hitNow by rememberUpdatedState<(Float) -> Boolean>(::hit)
        val claimNow by rememberUpdatedState<(Offset) -> ((Offset, Long) -> Boolean?)?>(::claimAt)
        val activateNow by rememberUpdatedState<(Boolean) -> Unit>(::activate)
        val moveNow by rememberUpdatedState<(Float) -> Unit>(::move)
        Box(Modifier.align(Alignment.CenterEnd).width(ScrollbarSpec.hitWidth)
            .fillMaxHeight().then(ScrollbarTapSharing).testTag("browse-scrollbar")
            .onPlaced { placed[0] = it }
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request(it.coerceIn(0f, 1f)) }
            }
            .scrollbarInput(key, { hitNow(it) }, { claimNow(it) }, { activateNow(it) }, { moveNow(it) })) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = -ScrollbarSpec.edgeInset)
                .scrollbarPresentation(motion, ::position)
                .layout { measurable, _ ->
                    val w = (restWidth + extraWidth * motion.grip()).roundToInt()
                    val h = thumb().roundToInt()
                    val p = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { p.place(0, 0) }
                }.testTag("browse-scrollbar-thumb")
                .drawBehind {
                    val corner = minOf(radius, size.width / 2f, size.height / 2f)
                    drawRoundRect(scrollbarColor(rest, held, motion.grip()), cornerRadius = CornerRadius(corner))
                })
        }
        // The preview text is derived state: a drag reads the list position, but the rail recomposes only when
        // the label itself changes, never on each movement frame.
        val labelFor by rememberUpdatedState(previewFor)
        val isActive by rememberUpdatedState(active)
        val preview by remember { derivedStateOf { if (isActive()) labelFor() else null } }
        val label = preview
        if (label != null) Surface(Modifier.align(Alignment.TopStart)
            .scrollbarPresentation(motion, ::position).testTag("scroll-preview"),
            shape = RoundedCornerShape(ScrollbarSpec.previewRadius),
            color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.defaultMinSize(ScrollbarSpec.previewMinimum,
                ScrollbarSpec.previewMinimum).padding(ScrollbarSpec.previewPadding),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The held colour at grip progress [t], interpolated per sRGB channel without allocating. */
internal fun scrollbarColor(rest: Color, held: Color, t: Float) = Color(rest.red + (held.red - rest.red) * t,
    rest.green + (held.green - rest.green) * t, rest.blue + (held.blue - rest.blue) * t,
    rest.alpha + (held.alpha - rest.alpha) * t)
