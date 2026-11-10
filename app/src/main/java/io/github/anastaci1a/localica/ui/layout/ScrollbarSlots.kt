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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import io.github.anastaci1a.localica.ui.ScrollbarDrag
import io.github.anastaci1a.localica.ui.components.ScrollbarTapSharing
import io.github.anastaci1a.localica.ui.components.scrollbarInput
import io.github.anastaci1a.localica.ui.engine.rememberScrollbarMotion
import io.github.anastaci1a.localica.ui.engine.scrollbarPresentation
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.roundToInt

/** The measured rail owns thumb travel, its input geometry and the adjacent preview slot.
 * Its model supplies only normalized position and resting length for the measured track. The track
 * is the rail's height less standard padding at both ends; hosts bound the rail by their controls.
 * The held thumb's width, length and colour are one function of the grip progress (#704).
 */
@Composable
internal fun ScrollbarSlots(state: Any, key: Any, drag: ScrollbarDrag, movement: () -> Pair<Int, Int>,
    fraction: () -> Float, thumbSize: (Float, Float) -> Float, scrolling: () -> Boolean,
    dragging: () -> Boolean, onDragging: (Boolean) -> Unit, dragged: () -> Unit,
    request: (Float) -> Boolean, previewFor: () -> String?,
    speed: Float, reduced: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    // Pointer coroutines retain this reader across owner replacement during a held contact.
    val motion by rememberUpdatedState(rememberScrollbarMotion(state, movement, { activity }, scrolling,
        dragging() || pressed, speed, reduced))
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
        fun displayed() = if (dragging()) drag.fraction else (fraction() + motion.releaseOffset()).coerceIn(0f, 1f)
        fun position() = displayed().coerceIn(0f, 1f) * (track - thumb()).coerceAtLeast(0f)
        // The visible thumb, at least the minimum touch length about its centre. Hidden: no target.
        fun hit(y: Float): Boolean {
            if (motion.opacity() <= 0f) return false
            val length = thumb()
            val reach = maxOf(length, touch) / 2f
            val centre = position() + length / 2f
            return y >= centre - reach && y <= centre + reach
        }
        fun begin() {
            drag.begin(displayed())
            onDragging(true)
        }
        fun move(dy: Float) {
            activity++
            drag.move(dy / (track - thumb()).coerceAtLeast(1f))
            dragged()
        }
        fun endDrag() {
            if (!dragging()) return
            onDragging(false)
            motion.release(drag.fraction - fraction())
        }
        // The pointer coroutine outlives compositions; the track moves with the host bounds (#704), so it reads
        // the current geometry rather than the first composition's.
        val hitNow by rememberUpdatedState<(Float) -> Boolean>(::hit)
        val beginNow by rememberUpdatedState<() -> Unit>(::begin)
        val moveNow by rememberUpdatedState<(Float) -> Unit>(::move)
        val endNow by rememberUpdatedState<() -> Unit>(::endDrag)
        Box(Modifier.align(Alignment.CenterEnd).width(ScrollbarSpec.hitWidth)
            .fillMaxHeight().then(ScrollbarTapSharing).testTag("browse-scrollbar")
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request(it.coerceIn(0f, 1f)) }
            }
            .scrollbarInput(key, { hitNow(it) }, { pressed = it; activity++ }, { beginNow() }, { moveNow(it) }, { endNow() })) {
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
        // Only a drag reads the list position in composition; ordinary scrolling never recomposes the rail.
        val preview = if (dragging()) previewFor() else null
        if (preview != null) Surface(Modifier.align(Alignment.TopStart)
            .scrollbarPresentation(motion, ::position).testTag("scroll-preview"),
            shape = RoundedCornerShape(ScrollbarSpec.previewRadius),
            color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.defaultMinSize(ScrollbarSpec.previewMinimum,
                ScrollbarSpec.previewMinimum).padding(ScrollbarSpec.previewPadding),
                contentAlignment = Alignment.Center) {
                Text(preview, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The held colour at grip progress [t], interpolated per sRGB channel without allocating. */
internal fun scrollbarColor(rest: Color, held: Color, t: Float) = Color(rest.red + (held.red - rest.red) * t,
    rest.green + (held.green - rest.green) * t, rest.blue + (held.blue - rest.blue) * t,
    rest.alpha + (held.alpha - rest.alpha) * t)
