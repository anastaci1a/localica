package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import io.github.anastaci1a.localica.ui.components.ScrollbarTapSharing
import io.github.anastaci1a.localica.ui.components.scrollbarInput
import io.github.anastaci1a.localica.ui.engine.rememberScrollbarMotion
import io.github.anastaci1a.localica.ui.engine.scrollbarPresentation
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.roundToInt

/** The measured rail owns thumb travel, its input geometry and the adjacent preview slot.
 * Its model supplies only normalized position and resting length for the measured track.
 */
@Composable
internal fun ScrollbarSlots(state: Any, key: Any, movement: () -> Pair<Int, Int>, fraction: () -> Float,
    thumbSize: (Float, Float) -> Float, dragging: () -> Boolean, onDragging: (Boolean) -> Unit,
    seekTo: (Float) -> Unit, request: (Float) -> Boolean, previewFor: () -> String?,
    speed: Float, reduced: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    val motion = rememberScrollbarMotion(state, movement, { activity }, dragging() || pressed, speed, reduced)
    // The preview extends 28 dp inward from the 48 dp input rail.
    BoxWithConstraints(modifier.fillMaxHeight()
        .width(ScrollbarSpec.railWidth)
        .then(ScrollbarTapSharing)
        .padding(top = ScrollbarSpec.topInset, bottom = ScrollbarSpec.bottomInset)) {
        val track = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val minThumb = with(density) { ScrollbarSpec.minimumThumbHeight.toPx() }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }
        var grab by remember { mutableFloatStateOf(0f) }
        fun restingThumb(): Float { fraction(); return thumbSize(track, minThumb) }
        fun thumb() = restingThumb() * motion.scale()
        fun displayed() = if (dragging()) dragFraction else (fraction() + motion.releaseOffset()).coerceIn(0f, 1f)
        fun position() = displayed().coerceIn(0f, 1f) * (track - thumb()).coerceAtLeast(0f)
        fun seek(y: Float) {
            activity++
            dragY = y
            dragFraction = ((y - grab - thumb() / 2) / (track - thumb()).coerceAtLeast(1f)).coerceIn(0f, 1f)
            seekTo(dragFraction)
        }
        fun endDrag() {
            if (!dragging()) return
            onDragging(false)
            motion.release(dragFraction - fraction())
        }
        Box(Modifier.align(Alignment.CenterEnd).width(ScrollbarSpec.hitWidth)
            .fillMaxHeight().then(ScrollbarTapSharing).testTag("browse-scrollbar")
            .semantics {
                contentDescription = "Scroll list"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                setProgress { request(it.coerceIn(0f, 1f)) }
            }
            .scrollbarInput(key, dragging, { pressed = it }, { activity++ }, ::position, ::thumb,
                { grab = it }, { onDragging(true) }, ::seek, { dragY }, ::endDrag)) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = -ScrollbarSpec.edgeInset)
                .scrollbarPresentation(motion, ::position, scaleThumb = true)
                .width(ScrollbarSpec.thumbWidth)
                .layout { measurable, constraints ->
                    val h = restingThumb().roundToInt()
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(p.width, p.height) { p.place(0, 0) }
                }.testTag("browse-scrollbar-thumb")
                .background(if (dragging()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(ScrollbarSpec.thumbRadius)))
        }
        val preview = previewFor()
        if (dragging() && preview != null) Surface(Modifier.align(Alignment.TopStart)
            .scrollbarPresentation(motion, ::position, scaleThumb = false).testTag("scroll-preview"),
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
