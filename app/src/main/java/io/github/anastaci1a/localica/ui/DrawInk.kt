package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** A retained ink owner. Reading its color subscribes the calling phase only. */
@Stable
internal class DrawInk(initial: Color) {
    var color by mutableStateOf(initial)
}

/** Player labels have no links or inline content. Keep Material's resolved metrics,
 * annotation precedence and BasicText semantics while supplying ink at draw time. */
@Composable
internal fun DrawInkText(text: AnnotatedString, ink: ColorProducer, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current, overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = style, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, color = ink)
}

@Composable
internal fun DrawInkText(text: String, ink: ColorProducer, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current, overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = style, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, color = ink)
}

/** Material still owns the icon's sizing and semantics; the delegated painter owns
 * the same SrcIn tint at draw time, including animated tint alpha. */
@Composable
internal fun DrawInkIcon(painter: Painter, description: String?, ink: ColorProducer,
    modifier: Modifier = Modifier) {
    val currentInk = rememberUpdatedState(ink)
    val tinted = remember(painter) { object : Painter() {
        override val intrinsicSize: Size get() = painter.intrinsicSize
        override fun DrawScope.onDraw() {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(currentInk.value())) }
        }
    } }
    Icon(tinted, description, modifier, tint = Color.Unspecified)
}

/** Android's ShaderBrushSpan observes createShader through derivedStateOf in its
 * draw-state callback. SpanStyle must use alpha=1f: the provider owns final alpha. */
internal class DrawInkSpanBrush(private val ink: ColorProducer) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: Size): androidx.compose.ui.graphics.Shader {
        val color = ink()
        return androidx.compose.ui.graphics.LinearGradientShader(
            androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(1f, 0f),
            listOf(color, color))
    }
}
