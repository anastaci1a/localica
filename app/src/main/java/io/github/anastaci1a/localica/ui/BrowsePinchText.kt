package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.MediaKind

/** Endpoint metrics never depend on finger progress or an intermediate paragraph wrap. */
internal data class BrowsePinchEndpoint(
    val art: Int, val artX: Int, val artY: Int,
    val title: TextLayoutResult, val titleX: Int, val titleY: Int,
    val information: TextLayoutResult?, val infoX: Int, val infoY: Int,
    val menuWidth: Int, val menuHeight: Int, val menuX: Int, val menuY: Int,
    val height: Int,
)

/** Measure the target from its actual row constraints, never reconstructed dp padding. */
@Composable
internal fun rememberBrowsePinchMeasure(item: BrowserItem, source: BrowseStyle, target: BrowseStyle,
    motion: BrowsePinchMotion, geometry: BrowseCellGeometry, index: Int, trackNumber: String?,
    showInformation: Boolean, inherited: Map<MediaKind, BrowseStyle>): (Int) -> Pair<BrowsePinchEndpoint, BrowsePinchEndpoint> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleMedium
    val infoStyle = MaterialTheme.typography.bodySmall
    fun endpoint(value: BrowseStyle, columns: Int, width: Int): BrowsePinchEndpoint {
        val numbered = trackNumber != null && value.presentation == Presentation.List && value.listSize != ListSize.Large
        val metrics = BrowseItemMetrics(value, columns, width, density.density, numbered)
        val info = if (showInformation) item.information(value, inherited)?.let {
            measurer.measure(AnnotatedString(it), infoStyle, overflow = TextOverflow.Ellipsis,
                maxLines = metrics.lines, constraints = Constraints(maxWidth = metrics.informationLimit))
        } else null
        val title = measurer.measure(AnnotatedString(item.title), titleStyle.copy(
            fontSize = metrics.titleSize.sp, lineHeight = metrics.titleLine.sp,
            fontWeight = if (trackNumber != null) FontWeight.Normal else titleStyle.fontWeight),
            overflow = TextOverflow.Ellipsis, maxLines = metrics.lines,
            constraints = Constraints.fixedWidth(metrics.titleWidth(info?.size?.width ?: 0)))
        val placement = metrics.place(title.size.height, info?.size?.width ?: 0, info?.size?.height ?: 0)
        return with(placement) {
            BrowsePinchEndpoint(metrics.art, artX, artY, title, titleX, titleY, info, infoX, infoY,
                metrics.menu, menuHeight, menuX, menuY, height)
        }
    }
    val sourceWidth = motion.sourceContentWidth(item.key, index)
    return remember(item, source, target, motion.sourceColumns, geometry.columns, sourceWidth,
        density, titleStyle, infoStyle, trackNumber, showInformation, inherited, measurer) {
        val from = endpoint(source, motion.sourceColumns, sourceWidth)
        var measuredWidth = -1
        var to: BrowsePinchEndpoint? = null
        val measure: (Int) -> Pair<BrowsePinchEndpoint, BrowsePinchEndpoint> = { width ->
            if (width != measuredWidth) { to = endpoint(target, geometry.columns, width); measuredWidth = width }
            from to requireNotNull(to)
        }
        measure
    }
}

@Stable
internal class BrowsePinchParagraph {
    // Written during layout and read only during drawing/semantics. Updating the
    // measured paragraphs cannot trigger a composition/measurement feedback loop.
    var layouts by mutableStateOf<Pair<TextLayoutResult?, TextLayoutResult?>?>(null)
}

/** The measured paragraphs themselves are painted, so endpoint rounding and the
 * visible glyphs share one layout. Artwork keeps its existing composition identity. */
@Composable
internal fun BrowsePinchText(paragraph: BrowsePinchParagraph, label: String?, progress: Float,
    color: Color = Color.Unspecified) {
    val foreground = LocalContentColor.current
    Canvas(Modifier.fillMaxSize().clipToBounds().semantics {
        if (label != null) text = AnnotatedString(label)
        getTextLayoutResult { result ->
            paragraph.layouts?.second?.let { result += it; true } ?: false
        }
    }) {
        val (source, target) = paragraph.layouts ?: return@Canvas
        val p = progress.coerceIn(0f, 1f)
        val alpha = p * p * (3f - 2f * p)
        val sourceFont = source?.layoutInput?.style?.fontSize?.value ?: target?.layoutInput?.style?.fontSize?.value ?: 1f
        val targetFont = target?.layoutInput?.style?.fontSize?.value ?: sourceFont
        val font = sourceFont + (targetFont - sourceFont) * p
        val baseline = (source?.firstBaseline ?: 0f) * (1f - p) + (target?.firstBaseline ?: 0f) * p
        fun paint(layout: TextLayoutResult?, opacity: Float, factor: Float) {
            if (layout == null || opacity <= 0f) return
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - layout.size.width * factor else 0f
            val ink = if (color != Color.Unspecified) color else
                layout.layoutInput.style.color.takeUnless { it == Color.Unspecified } ?: foreground
            translate(x, baseline - layout.firstBaseline * factor) {
                scale(factor, factor, Offset.Zero) { drawText(layout, color = ink, alpha = opacity) }
            }
        }
        paint(source, 1f - alpha, font / sourceFont)
        paint(target, alpha, font / targetFont)
    }
}
