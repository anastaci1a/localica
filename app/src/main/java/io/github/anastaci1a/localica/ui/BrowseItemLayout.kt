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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
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
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null, showInformation: Boolean = true, inheritedInformation: Map<io.github.anastaci1a.localica.navigation.MediaKind, BrowseStyle> = emptyMap(), itemIndex: Int = 0, loadingReveal: BrowseLoadingCell? = null, artistOnlyLoading: Boolean = false) {
    val gallery = style.presentation != Presentation.List
    val from = pinch?.visualSource
    val progress = if (from != null) requireNotNull(pinch).progress.value else 1f
    val measureItem = rememberBrowseItemMeasure(item, trackNumber, showInformation, inheritedInformation, itemIndex)
    val foreground = androidx.compose.material3.LocalContentColor.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    if (pinch != null) {
        SideEffect { pinch.itemContent[item.key] = BrowsePinchContent(measureItem, foreground, secondary) { loadingReveal?.alpha ?: 1f } }
        DisposableEffect(pinch, item.key) { onDispose { pinch.itemContent.remove(item.key) } }
    }
    val measureEndpoints = if (from != null) rememberBrowsePinchMeasure(measureItem, item.key,
        from, style, requireNotNull(pinch), geometry, itemIndex) else null
    val titleParagraph = remember { BrowsePinchParagraph() }
    val informationParagraph = remember { BrowsePinchParagraph() }
    // Read by the graphics layer: measuring an endpoint updates its
    // radius in the same layout pass without recomposing or changing artwork identity.
    val artCorner = remember { mutableFloatStateOf(0f) }
    val duration = if (reducedMotion || from != null || loadingReveal != null) 0 else 350
    val artist = item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist,
        io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)
    val loadingInformation = showInformation && style.extraInfo != ExtraInfo.None
    val infoLine = MaterialTheme.typography.bodySmall.lineHeight
    val galleryLoadingText = if (gallery && loadingReveal != null) rememberGalleryLoadingTextMetrics(style, geometry.columns) else null
    fun Modifier.reveal() = if (loadingReveal == null) this else graphicsLayer { alpha = loadingReveal.alpha }
    fun Modifier.artShape() = graphicsLayer {
        shape = RoundedCornerShape(browseLoadingArtCorner(artCorner.floatValue, size.minDimension,
            artist, artistOnlyLoading, loadingReveal?.alpha ?: 1f))
        clip = true
    }
    fun trailing(value: BrowseStyle) = value.presentation == Presentation.List && value.listSize.trailingInformation
    val titleSize by animateFloatAsState(style.browseTitleSize(geometry.columns).toFloat(), tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(style.browseTitleLine(geometry.columns).toFloat(), tween(duration), label = "Browse title line")
    fun numbered(value: BrowseStyle) = value.trackNumberLabel(trackNumber, itemIndex) != null
    val numberLabel = style.trackNumberLabel(trackNumber, itemIndex) ?: from?.trackNumberLabel(trackNumber, itemIndex)
    var retainedNumberLabel by remember(item.key) { mutableStateOf(numberLabel) }
    SideEffect { if (numberLabel != null) retainedNumberLabel = numberLabel }
    val numberAlpha = browseViewAlpha(numbered(style), from?.let(::numbered), progress, reducedMotion)
    fun artwork(value: BrowseStyle) = !numbered(value) && (value.presentation != Presentation.List ||
        value.listSize !in setOf(ListSize.Small, ListSize.Smallest))
    val artworkAlpha = browseViewAlpha(artwork(style), from?.let(::artwork), progress, reducedMotion)
    val menuAlpha = browseViewAlpha(!gallery, from?.let { it.presentation == Presentation.List }, progress, reducedMotion)
    val incomingAlpha = if (from != null && item.key !in requireNotNull(pinch).bounds)
        browseViewVisibility(false, true, progress) else 1f
    fun Modifier.morph() = if (from != null) this else animateBounds(lookahead, boundsTransform = { _, _ -> tween(duration) })
    Layout(modifier = Modifier.fillMaxWidth().then(if (pinch != null) Modifier.retainPinchText(item.key, pinch) else Modifier)
        .graphicsLayer { alpha = incomingAlpha }, content = {
        Box(Modifier.morph().then(if (gallery && artworkAnchor != null) Modifier.contextMenuAnchor(artworkAnchor,
            item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist, io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)) else Modifier).testTag("browse-art-${item.key}")) {
            if (numberAlpha > 0f) Text(numberLabel ?: retainedNumberLabel.orEmpty(), Modifier.fillMaxSize().graphicsLayer { alpha = numberAlpha * (loadingReveal?.alpha ?: 1f) }.wrapContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (artworkAlpha > 0f || artwork(style) || from?.let(::artwork) == true)
                MediaArtwork(item.coverUris, Modifier.fillMaxSize()
                    .then(if (pinch != null) Modifier.retainPinchArtwork(item.key, pinch) else Modifier).graphicsLayer {
                    alpha = artworkAlpha * incomingAlpha * (loadingReveal?.alpha ?: 1f)
                    shape = RoundedCornerShape(browseLoadingArtCorner(artCorner.floatValue, size.minDimension,
                        artist, artistOnlyLoading, loadingReveal?.alpha ?: 1f))
                    clip = true
                }, shape = androidx.compose.ui.graphics.RectangleShape)
        }
        Box(Modifier.morph().reveal().testTag("browse-text-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(titleParagraph, item.title, progress)
            else Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (numbered(style)) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.morph().reveal().testTag("browse-info-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(informationParagraph,
                if (showInformation) item.information(style, inheritedInformation) else null, progress,
                if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified)
            else if (showInformation) item.information(style, inheritedInformation)?.let { Text(it,
                color = if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                style = MaterialTheme.typography.bodySmall, maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis) }
        }
        // Retain outgoing menu pixels, not an input shield. Even a zero-width
        // gallery slot can acquire a minimum touch target and swallow list drags.
        val menuTag = "browse-menu-${item.key}"
        Box(Modifier.morph().reveal().graphicsLayer { alpha = menuAlpha }.then(if (gallery)
            // Keep the layout witness inside the clear boundary; a later testTag
            // modifier would be cleared along with the outgoing action semantics.
            Modifier.clearAndSetSemantics { testTag = menuTag } else Modifier.testTag(menuTag))) { menu(!gallery || menuAlpha > 0f) }
        if (loadingReveal != null) {
        Box(Modifier.artShape().testTag("browse-loading-art-${item.key}")) {
            BrowseLoadingShape(Modifier.fillMaxSize(), { 0f }) { 1f - (loadingReveal?.alpha ?: 1f) }
        }
        BrowseLoadingShape(Modifier.testTag("browse-loading-title-${item.key}"), { artCorner.floatValue }) { 1f - (loadingReveal?.alpha ?: 1f) }
        BrowseLoadingShape(Modifier.testTag("browse-loading-info-${item.key}"), { artCorner.floatValue }) { if (loadingInformation) 1f - (loadingReveal?.alpha ?: 1f) else 0f }
        }
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
            children.drop(4).forEach { it.measure(Constraints.fixed(0, 0)) }
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
        val reserve = if (from == null) loadingReveal?.reservation ?: 0f else 0f
        val reservedTitle = maxOf(text.height, galleryLoadingText?.titleReservation ?: (metrics.titleLine.sp.roundToPx() * metrics.lines))
        val reservedInfo = if (loadingInformation) maxOf(information.height,
            galleryLoadingText?.informationReservation ?: (infoLine.roundToPx() * metrics.lines)) else information.height
        fun retained(real: Int, reserved: Int) = (real + (reserved - real) * reserve).toInt()
        val placement = metrics.place(retained(text.height, reservedTitle), information.width,
            retained(information.height, reservedInfo))
        val art = children[0].measure(Constraints.fixed(metrics.art, metrics.art))
        val actions = children[3].measure(Constraints.fixed(metrics.menu, placement.menuHeight))
        val blankArt = children.getOrNull(4)?.measure(Constraints.fixed(metrics.art, metrics.art))
        val blankInfoWidth = metrics.loadingInformationWidth(loadingInformation)
        val blankTitleHeight = if (gallery) galleryLoadingText?.titleLine ?: metrics.titleLine.sp.roundToPx() else reservedTitle
        val blankInfoHeight = if (loadingInformation) galleryLoadingText?.informationLine ?: infoLine.roundToPx() else 0
        val blankTitle = children.getOrNull(5)?.measure(Constraints.fixed(metrics.titleWidth(blankInfoWidth), blankTitleHeight))
        val blankInfo = children.getOrNull(6)?.measure(Constraints.fixed(blankInfoWidth, blankInfoHeight))
        val blankPlacement = if (gallery) metrics.place(blankTitleHeight, blankInfoWidth, blankInfoHeight) else
            metrics.place(retained(text.height, reservedTitle), blankInfoWidth, retained(information.height, reservedInfo))
        layout(constraints.maxWidth, placement.height) {
            blankArt?.placeRelative(placement.artX, placement.artY)
            blankTitle?.placeRelative(placement.titleX, placement.titleY)
            blankInfo?.placeRelative(blankPlacement.infoX, blankPlacement.infoY)
            art.placeRelative(placement.artX, placement.artY)
            text.placeRelative(placement.titleX, placement.titleY)
            information.placeRelative(placement.infoX, placement.infoY)
            actions.placeRelative(placement.menuX, placement.menuY)
        }
    }
}
