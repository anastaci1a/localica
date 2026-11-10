package io.github.anastaci1a.localica.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.browse.*

/** Artwork, metadata and menu keep the same composition identities in every view. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BrowseItemLayout(item: BrowserItem, style: BrowseStyle, geometry: BrowseCellGeometry,
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null, showInformation: Boolean = true, inheritedInformation: Map<io.github.anastaci1a.localica.navigation.MediaKind, BrowseStyle> = emptyMap(), itemIndex: Int = 0, loadingReveal: BrowseLoadingCell? = null, artistOnlyLoading: Boolean = false, mutablePosition: Int? = null,
    outline: MediaItemOutlineHolder? = null) {
    val gallery = style.presentation != Presentation.List
    val from = pinch?.visualSource
    val geometryClock = pinch?.progress.takeIf { from != null }
    val geometryProgress = remember(geometryClock) { { geometryClock?.value ?: 1f } }
    val progress = geometryProgress()
    val measureItem = rememberBrowseItemMeasure(item, trackNumber, showInformation, inheritedInformation, itemIndex, mutablePosition)
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
    fun numbered(value: BrowseStyle) = value.trackNumberLabel(trackNumber, itemIndex, mutablePosition) != null
    val numberLabel = style.trackNumberLabel(trackNumber, itemIndex, mutablePosition) ?: from?.trackNumberLabel(trackNumber, itemIndex, mutablePosition)
    var retainedNumberLabel by remember(item.key) { mutableStateOf(numberLabel) }
    SideEffect { if (numberLabel != null) retainedNumberLabel = numberLabel }
    val numberAlpha = browseViewAlpha(numbered(style), from?.let(::numbered), progress, reducedMotion)
    fun artwork(value: BrowseStyle) = !numbered(value) && (value.presentation != Presentation.List ||
        value.listSize !in setOf(ListSize.Small, ListSize.Smallest))
    val artworkAlpha = browseViewAlpha(artwork(style), from?.let(::artwork), progress, reducedMotion)
    val menuAlpha = browseViewAlpha(!gallery, from?.let { it.presentation == Presentation.List }, progress, reducedMotion)
    // The viewport applies distance-aware row visibility to both global passes.
    val incomingAlpha = if (from != null && pinch?.viewportCoordinates?.isAttached != true && item.key !in requireNotNull(pinch).bounds)
        browseViewVisibility(false, true, progress) else 1f
    fun Modifier.morph() = if (from != null) this else slotBounds(lookahead) { if (duration == 0) null else tween(duration) }
    // One artwork reveal drives the text (#526); pinches keep their own text handling.
    val textReveal = remember(item.key) { ArtworkTextReveal() }
    val gatedText = textReveal.takeIf { from == null && artwork(style) }
    // Only the zero crossing changes composition. Opacity remains a layer-phase read.
    val menuRevealed by remember(textReveal, artworkAlpha, loadingReveal) {
        derivedStateOf { textReveal.menuAlpha(artworkAlpha) * (loadingReveal?.alpha ?: 1f) > 0f }
    }
    var titleLayout by remember(item.key) { mutableStateOf<TextLayoutResult?>(null) }
    var informationLayout by remember(item.key) { mutableStateOf<TextLayoutResult?>(null) }
    Layout(modifier = Modifier.fillMaxWidth().then(if (pinch != null) Modifier.retainPinchText(item.key, pinch) else Modifier)
        .graphicsLayer { alpha = incomingAlpha }, content = {
        Box(Modifier.morph().then(if (gallery && artworkAnchor != null) Modifier.contextMenuAnchor(artworkAnchor,
            item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist, io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)) else Modifier).testTag("browse-art-${item.key}")) {
            if (numberAlpha > 0f) Text(numberLabel ?: retainedNumberLabel.orEmpty(), Modifier.fillMaxSize().graphicsLayer { alpha = numberAlpha * (loadingReveal?.alpha ?: 1f) }.wrapContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (artworkAlpha > 0f || artwork(style) || from?.let(::artwork) == true)
                ArtistMediaArtwork(item, textReveal = textReveal, modifier = Modifier.fillMaxSize()
                    .then(if (pinch != null) Modifier.retainPinchArtwork(item.key, pinch) else Modifier).graphicsLayer {
                    alpha = artworkAlpha * incomingAlpha * (loadingReveal?.alpha ?: 1f)
                    shape = RoundedCornerShape(browseLoadingArtCorner(artCorner.floatValue, size.minDimension,
                        artist, artistOnlyLoading, loadingReveal?.alpha ?: 1f))
                    clip = true
                }, shape = androidx.compose.ui.graphics.RectangleShape)
        }
        // A bounds tween would reflow a paragraph across successive widths. Measure ordinary
        // text at its final width; explicit pinch text owns its own motion.
        Box(Modifier.reveal().artworkTextReveal(gatedText, { titleLayout }, { artCorner.floatValue }).testTag("browse-text-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(titleParagraph, item.title, geometryProgress)
            else Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (numbered(style)) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = style.browseTitleLines(), overflow = TextOverflow.Ellipsis, onTextLayout = { titleLayout = it })
        }
        Box(Modifier.reveal().artworkTextReveal(gatedText, { informationLayout }, { artCorner.floatValue }, loadingInformation).testTag("browse-info-${item.key}")) {
            if (measureEndpoints != null) BrowsePinchText(informationParagraph,
                if (showInformation) item.information(style, inheritedInformation) else null, geometryProgress,
                if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified)
            else if (showInformation) item.information(style, inheritedInformation)?.let { Text(it,
                color = if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                style = MaterialTheme.typography.bodySmall, maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis,
                onTextLayout = { informationLayout = it }) }
        }
        // Retain outgoing menu pixels, not an input shield. Even a zero-width
        // gallery slot can acquire a minimum touch target and swallow list drags.
        val menuTag = "browse-menu-${item.key}"
        // A settled gallery slot draws nothing (menus and dialogs use their own
        // windows), so it needs no fade layer. The layer and the outgoing button
        // enter and leave together; no drawn child ever changes its parent layer.
        val menuDrawn = !gallery || menuAlpha > 0f
        Box(Modifier.morph().reveal().then(if (menuDrawn) Modifier.graphicsLayer { alpha = menuAlpha * textReveal.menuAlpha(artworkAlpha) } else Modifier).then(if (gallery)
            // Keep the layout witness inside the clear boundary; a later testTag
            // modifier would be cleared along with the outgoing action semantics.
            Modifier.clearAndSetSemantics { testTag = menuTag } else Modifier.testTag(menuTag))) { menu(menuDrawn && menuAlpha > 0f && menuRevealed) }
        if (loadingReveal != null) {
        Box(Modifier.artShape().testTag("browse-loading-art-${item.key}")) {
            BrowseLoadingShape(Modifier.fillMaxSize(), { 0f }) { 1f - (loadingReveal?.alpha ?: 1f) }
        }
        BrowseLoadingShape(Modifier.testTag("browse-loading-title-${item.key}"), { artCorner.floatValue }) { 1f - (loadingReveal?.alpha ?: 1f) }
        BrowseLoadingShape(Modifier.testTag("browse-loading-info-${item.key}"), { artCorner.floatValue }) { if (loadingInformation) 1f - (loadingReveal?.alpha ?: 1f) else 0f }
        }
    }) { children, constraints ->
        if (measureEndpoints != null) {
            val p = geometryProgress()
            val width = (constraints.maxWidth - geometry.contentInset.dp.roundToPx() * 2).coerceAtLeast(0)
            val (source, target) = measureEndpoints(width)
            titleParagraph.layouts = source.title to target.title
            informationParagraph.layouts = source.information to target.information
            artCorner.floatValue = source.artCorner + (target.artCorner - source.artCorner) * p.coerceIn(0f, 1f)
            // One float rectangle per part; truncating size and position separately made
            // cover edges wobble by a pixel against the fractional row translation (#505).
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            fun rect(x: Int, y: Int, width: Int, height: Int, x2: Int, y2: Int, width2: Int, height2: Int) =
                motionPixelRect(browsePinchPartRect(x, y, width, height, x2, y2, width2, height2, p)
                    .let { if (rtl) it.mirrored(constraints.maxWidth.toFloat()) else it })
            val artRect = rect(source.artX, source.artY, source.art, source.art, target.artX, target.artY, target.art, target.art)
            val textRect = rect(source.titleX, source.titleY, source.title.size.width, source.title.size.height,
                target.titleX, target.titleY, target.title.size.width, target.title.size.height)
            val infoRect = rect(source.infoX, source.infoY, source.information?.size?.width ?: 0, source.information?.size?.height ?: 0,
                target.infoX, target.infoY, target.information?.size?.width ?: 0, target.information?.size?.height ?: 0)
            val menuRect = rect(source.menuX, source.menuY, source.menuWidth, source.menuHeight,
                target.menuX, target.menuY, target.menuWidth, target.menuHeight)
            val art = children[0].measure(artRect.constraints())
            val text = children[1].measure(textRect.constraints())
            val information = children[2].measure(infoRect.constraints())
            val actions = children[3].measure(menuRect.constraints())
            children.drop(4).forEach { it.measure(Constraints.fixed(0, 0)) }
            // The outline follows its parts: both endpoint outlines, interpolated like their rectangles.
            if (outline != null) mediaItemOutlineLerp(source.outline(density), target.outline(density), p).let {
                outline.value = if (rtl) it.mirrored(constraints.maxWidth.toFloat()) else it
            }
            return@Layout layout(constraints.maxWidth, target.height) {
                placeMotion(art, artRect)
                placeMotion(text, textRect)
                placeMotion(information, infoRect)
                placeMotion(actions, menuRect)
            }
        }
        val width = (constraints.maxWidth - geometry.contentInset.dp.roundToPx() * 2).coerceAtLeast(0)
        val metrics = BrowseItemMetrics(style, geometry.columns, width, density, numbered(style))
        artCorner.floatValue = metrics.artCorner
        val information = children[2].measure(Constraints(maxWidth = metrics.informationLimit))
        val textWidth = metrics.titleWidth(information.width)
        val text = children[1].measure(Constraints.fixedWidth(textWidth))
        val reserve = if (from == null) loadingReveal?.reservation ?: 0f else 0f
        val reservedTitle = maxOf(text.height, galleryLoadingText?.titleReservation ?: (metrics.titleLine.sp.roundToPx() * metrics.titleLines))
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
        if (outline != null) outline.value = metrics.outline(placement, constraints.maxWidth, text.width, text.height,
            information.width, information.height, artwork(style), layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl, density)
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
