package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun MarqueeTitle(title: String, reducedMotion: Boolean, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val textWidth = measurer.measure(AnnotatedString(title), style, maxLines = 1, softWrap = false).size.width
        if (reducedMotion || textWidth <= width || !constraints.hasBoundedWidth) {
            Text(title, maxLines = 1, style = style, overflow = TextOverflow.Ellipsis)
        } else {
            val offset = remember(title, width, style) { Animatable(0f) }
            val spacing = width / 3f
            val distance = textWidth + spacing
            val fadeWidth = minOf(width * .08f, with(density) { 16.dp.toPx() })
            LaunchedEffect(offset, distance, density.density) {
                while (true) {
                    delay(1800)
                    offset.animateTo(distance, tween((distance / with(density) { 30.dp.toPx() } * 1000).toInt(), easing = LinearEasing))
                    offset.snapTo(0f)
                }
            }
            Layout(content = { Text(title, maxLines = 1, softWrap = false, style = style) }, modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    clipRect {
                        translate(if (rtl) offset.value + size.width - textWidth else -offset.value) {
                            this@drawWithContent.drawContent()
                            translate(if (rtl) -distance else distance) { this@drawWithContent.drawContent() }
                        }
                    }
                    // Grow the leading fade only as glyphs actually leave the viewport.
                    // The incoming copy removes it again as it lands at the resting edge.
                    val leading = minOf(offset.value, distance - offset.value, fadeWidth).coerceAtLeast(0f)
                    val left = if (rtl) fadeWidth else leading
                    val right = if (rtl) leading else fadeWidth
                    if (left > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, left), blendMode = BlendMode.DstIn)
                    if (right > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - right, size.width), blendMode = BlendMode.DstIn)
                }) { items, limits ->
                val text = items.single().measure(Constraints(maxHeight = limits.maxHeight))
                layout(limits.maxWidth, text.height) { text.place(0, 0) }
            }
        }
    }
}
