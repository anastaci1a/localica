package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun MarqueeTitle(title: String, reducedMotion: Boolean, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall, motionPaused: Boolean = false) {
    MarqueeTitle(AnnotatedString(title), reducedMotion, modifier, style, motionPaused)
}

@Composable
internal fun MarqueeTitle(title: AnnotatedString, reducedMotion: Boolean, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall, motionPaused: Boolean = false,
    visiblePrefixLength: Int = title.length, suffixVisibility: Float = 1f) {
    if (reducedMotion || LocalReducedMotion.current) {
        Text(title, modifier, maxLines = 1, style = style, overflow = TextOverflow.Ellipsis)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Color/annotation changes (the player's album reveal) and viewport changes
    // must not discard an in-flight return or restart the marquee delay.
    val motion = remember(title.text) { MarqueeMotion() }
    var frame by remember(motion) { mutableStateOf(motion.frame) }
    BoxWithConstraints(modifier) {
        val measured = measurer.measure(title, style, maxLines = 1, softWrap = false)
        val rtl = if (title.isNotEmpty()) measured.getParagraphDirection(0) == ResolvedTextDirection.Rtl else layoutRtl
        val fullTextWidth = measured.size.width.toFloat()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else fullTextWidth
        val prefixWidth = if (visiblePrefixLength >= title.length) fullTextWidth else
            measurer.measure(title.subSequence(0, visiblePrefixLength.coerceAtLeast(0)), style,
                maxLines = 1, softWrap = false).size.width.toFloat()
        val textWidth = prefixWidth + (fullTextWidth - prefixWidth) * suffixVisibility.coerceIn(0f, 1f)
        val allowed = !motionPaused && textWidth > width
        val currentTextWidth by rememberUpdatedState(textWidth)
        val currentWidth by rememberUpdatedState(width)
        val speed = with(density) { 30.dp.toPx() }
        LaunchedEffect(motion, allowed, speed) {
            do {
                val now = withFrameMillis { time ->
                    frame = motion.advance(time, currentTextWidth, currentWidth, speed, allowed)
                    time
                }
                // A stationary pause must release the frame clock (and Compose idleness).
                // Changing pause/overflow cancels this delay immediately.
                if (allowed && frame.phase == MarqueePhase.Rest) delay(motion.pauseRemaining(now))
            } while (allowed || frame.phase != MarqueePhase.Rest)
        }
        val fadeWidth = minOf(width * .12f, with(density) { 24.dp.toPx() })
        Layout(content = {
            Text(title, maxLines = 1, softWrap = false, style = style, overflow = TextOverflow.Clip)
        }, modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                clipRect {
                    translate(if (rtl) frame.offset + size.width - fullTextWidth else -frame.offset) {
                        this@drawWithContent.drawContent()
                        if (frame.offset > 0f) translate(if (rtl) -frame.repeatDistance else frame.repeatDistance) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
                val leading = frame.leadingFade
                val trailing = marqueeTrailingFade(textWidth, size.width, fadeWidth, frame)
                fun edge(start: Float, end: Float, amount: Float, reversed: Boolean) {
                    if (amount <= 0f) return
                    val stops = arrayOf(0f to 0f, .2f to .104f, .5f to .5f, .8f to .896f, 1f to 1f)
                    drawRect(Brush.horizontalGradient(*stops.map { (position, alpha) ->
                        position to Color.Black.copy(alpha = 1f - amount * (if (reversed) alpha else 1f - alpha))
                    }.toTypedArray(), startX = start, endX = end), blendMode = BlendMode.DstIn)
                }
                edge(0f, fadeWidth, if (rtl) trailing else leading, reversed = false)
                edge(size.width - fadeWidth, size.width, if (rtl) leading else trailing, reversed = true)
            }) { items, limits ->
            val text = items.single().measure(Constraints(maxHeight = limits.maxHeight))
            layout(if (limits.hasBoundedWidth) limits.maxWidth else text.width, text.height) { text.place(0, 0) }
        }
    }
}
