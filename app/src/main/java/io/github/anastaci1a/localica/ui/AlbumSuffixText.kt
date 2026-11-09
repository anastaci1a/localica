package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Optional native evidence of the actual independently shaped paragraphs. */
internal val LocalAlbumSuffixLayoutObserver = staticCompositionLocalOf<((TextLayoutResult, TextLayoutResult) -> Unit)?> { null }

/** Both layouts retain the full paragraph's shaping, baseline and overflow. Only the second
 * paragraph has a shader span; its drawing is restricted to the suffix outside the prefix. */
@Composable
internal fun AlbumSuffixText(plain: AnnotatedString, shader: AnnotatedString, prefixLength: Int,
    ink: ColorProducer, modifier: Modifier = Modifier, style: TextStyle,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = false, maxLines: Int = 1) {
    var suffixClip by remember { mutableStateOf<Path?>(null) }
    val layouts = remember { arrayOfNulls<TextLayoutResult>(2) }
    val observer = LocalAlbumSuffixLayoutObserver.current
    fun publish(index: Int, layout: TextLayoutResult) {
        layouts[index] = layout
        val plainLayout = layouts[0]
        val shaderLayout = layouts[1]
        if (plainLayout != null && shaderLayout != null) observer?.invoke(plainLayout, shaderLayout)
    }
    Box(modifier, propagateMinConstraints = true) {
        BasicText(plain, style = style, overflow = overflow, softWrap = softWrap, maxLines = maxLines, color = ink,
            onTextLayout = { publish(0, it) })
        BasicText(shader, Modifier.clearAndSetSemantics {}.drawWithContent {
            suffixClip?.let { clipPath(it) { this@drawWithContent.drawContent() } }
        }, style = style, overflow = overflow, softWrap = softWrap, maxLines = maxLines, color = ink,
            onTextLayout = { layout: TextLayoutResult ->
                publish(1, layout)
                val prefix = prefixLength.coerceIn(0, shader.length)
                suffixClip = Path.combine(PathOperation.Difference,
                    layout.getPathForRange(prefix, shader.length), layout.getPathForRange(0, prefix))
            })
    }
}
