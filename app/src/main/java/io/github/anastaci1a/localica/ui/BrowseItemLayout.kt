package io.github.anastaci1a.localica.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.browse.*

/** Artwork, metadata and menu keep the same composition identities in every view. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BrowseItemLayout(item: BrowserItem, style: BrowseStyle, geometry: BrowseCellGeometry,
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null, showInformation: Boolean = true, inheritedInformation: Map<io.github.anastaci1a.localica.navigation.MediaKind, BrowseStyle> = emptyMap(), itemIndex: Int = 0) {
    val gallery = style.presentation != Presentation.List
    val from = pinch?.visualSource
    val progress = if (from != null) requireNotNull(pinch).progress.value else 1f
    val measureEndpoints = if (from != null) rememberBrowsePinchMeasure(item, from, style, requireNotNull(pinch), geometry,
        itemIndex, trackNumber, showInformation, inheritedInformation) else null
    val titleParagraph = remember { BrowsePinchParagraph() }
    val informationParagraph = remember { BrowsePinchParagraph() }
    // Read by the graphics layer: measuring an endpoint updates its
    // radius in the same layout pass without recomposing or changing artwork identity.
    val artCorner = remember { mutableFloatStateOf(0f) }
    val duration = if (reducedMotion || from != null) 0 else 350
    fun trailing(value: BrowseStyle) = value.presentation == Presentation.List && value.listSize.trailingInformation
    val titleSize by animateFloatAsState(style.browseTitleSize(geometry.columns).toFloat(), tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(style.browseTitleLine(geometry.columns).toFloat(), tween(duration), label = "Browse title line")
    fun numbered(value: BrowseStyle) = trackNumber != null && value.presentation == Presentation.List && value.listSize != ListSize.Large
    val numberAlpha = if (from == null) { if (numbered(style)) 1f else 0f } else
        (if (numbered(from)) 1f else 0f) + ((if (numbered(style)) 1f else 0f) - (if (numbered(from)) 1f else 0f)) * progress
    fun Modifier.morph() = if (from != null) this else animateBounds(lookahead, boundsTransform = { _, _ -> tween(duration) })
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box(Modifier.morph().then(if (gallery && artworkAnchor != null) Modifier.contextMenuAnchor(artworkAnchor,
            item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist, io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)) else Modifier).testTag("browse-art-${item.key}")) {
            if (numberAlpha > 0f) Text(requireNotNull(trackNumber), Modifier.fillMaxSize().graphicsLayer { alpha = numberAlpha }.wrapContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (numberAlpha < 1f)
                MediaArtwork(item.coverUris, Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - numberAlpha
                    shape = artworkShape(item.ref.kind, RoundedCornerShape(artCorner.floatValue))
                    clip = true
                }, shape = artworkShape(item.ref.kind, androidx.compose.ui.graphics.RectangleShape))
        }
        Box(Modifier.morph().testTag("browse-text-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(titleParagraph, item.title, progress)
            else Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (trackNumber != null) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.morph().testTag("browse-info-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(informationParagraph,
                if (showInformation) item.information(style, inheritedInformation) else null, progress,
                if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified)
            else if (showInformation) item.information(style, inheritedInformation)?.let { Text(it,
                color = if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                style = MaterialTheme.typography.bodySmall, maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.testTag("browse-menu-${item.key}")) { menu(!gallery) }
    }) { children, constraints ->
        if (measureEndpoints != null) {
            val width = (constraints.maxWidth - geometry.contentInset.dp.roundToPx() * 2).coerceAtLeast(0)
            val (source, target) = measureEndpoints(width)
            titleParagraph.layouts = source.title to target.title
            informationParagraph.layouts = source.information to target.information
            fun mix(a: Int, b: Int) = (a + (b - a) * progress.coerceIn(0f, 1f)).toInt()
            artCorner.floatValue = source.artCorner + (target.artCorner - source.artCorner) * progress.coerceIn(0f, 1f)
            val artSize = mix(source.art, target.art).coerceAtLeast(0)
            val art = children[0].measure(Constraints.fixed(artSize, artSize))
            val text = children[1].measure(Constraints.fixed(
                mix(source.title.size.width, target.title.size.width).coerceAtLeast(0),
                mix(source.title.size.height, target.title.size.height).coerceAtLeast(0)))
            val information = children[2].measure(Constraints.fixed(
                mix(source.information?.size?.width ?: 0, target.information?.size?.width ?: 0).coerceAtLeast(0),
                mix(source.information?.size?.height ?: 0, target.information?.size?.height ?: 0).coerceAtLeast(0)))
            val actions = children[3].measure(Constraints.fixed(mix(source.menuWidth, target.menuWidth).coerceAtLeast(0),
                mix(source.menuHeight, target.menuHeight).coerceAtLeast(0)))
            return@Layout layout(constraints.maxWidth, target.height) {
                art.placeRelative(mix(source.artX, target.artX), mix(source.artY, target.artY))
                text.placeRelative(mix(source.titleX, target.titleX), mix(source.titleY, target.titleY))
                information.placeRelative(mix(source.infoX, target.infoX), mix(source.infoY, target.infoY))
                actions.placeRelative(mix(source.menuX, target.menuX), mix(source.menuY, target.menuY))
            }
        }
        val width = (constraints.maxWidth - geometry.contentInset.dp.roundToPx() * 2).coerceAtLeast(0)
        val metrics = BrowseItemMetrics(style, geometry.columns, width, density, numbered(style))
        artCorner.floatValue = metrics.artCorner
        val information = children[2].measure(Constraints(maxWidth = metrics.informationLimit))
        val textWidth = metrics.titleWidth(information.width)
        val text = children[1].measure(Constraints.fixedWidth(textWidth))
        val placement = metrics.place(text.height, information.width, information.height)
        val art = children[0].measure(Constraints.fixed(metrics.art, metrics.art))
        val actions = children[3].measure(Constraints.fixed(metrics.menu, placement.menuHeight))
        layout(constraints.maxWidth, placement.height) {
            art.placeRelative(placement.artX, placement.artY)
            text.placeRelative(placement.titleX, placement.titleY)
            information.placeRelative(placement.infoX, placement.infoY)
            actions.placeRelative(placement.menuX, placement.menuY)
        }
    }
}
