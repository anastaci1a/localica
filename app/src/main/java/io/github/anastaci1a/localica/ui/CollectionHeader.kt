package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.LibraryGroup

internal fun collectionIsLight(visual: PlayerVisual): Boolean = visual.palette.isLight()

@Composable
internal fun CollectionBackdrop(visual: PlayerVisual, artTop: Float, modifier: Modifier = Modifier,
    pinchHeader: CollectionPinchHeader? = null) {
    val light = collectionIsLight(visual)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = WindowInsets.statusBars.getTop(density)
    val parallax = if (LocalReducedMotion.current) 1f else .65f
    fun pictureTop() = inset + (pinchHeader?.heroTop() ?: artTop) * parallax
    SideEffect { pinchHeader?.heroInset = inset.toFloat(); pinchHeader?.parallax = parallax }
    BoxWithConstraints(modifier.then(Modifier.clipToBounds())) {
        val imageHeight = maxWidth
        // Browsing keeps its established dimmed continuation; the full player opts
        // into configurable contrast without changing album/artist pages.
        val palette = visual.palette
        PlayerBackdrop(palette.copy(top = lerp(palette.top, Color.Black, .4f),
            middle = lerp(palette.middle, Color.Black, .4f), bottom = lerp(palette.bottom, Color.Black, .4f),
            wash = palette.collectionWash ?: palette.wash), Modifier.fillMaxSize(), fullArtBlend = 1f,
            artHeight = with(density) { imageHeight.toPx() }, artTop = inset + artTop * parallax,
            softness = 1f, artTopReader = ::pictureTop)
        Box(Modifier.matchParentSize().background(if (light) Color.White.copy(alpha = .58f) else Color.Black.copy(alpha = .16f)))
        visual.artwork?.let { image ->
            Image(image, null, Modifier.fillMaxWidth().height(imageHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; translationY = pictureTop() }
                .onPlaced { pinchHeader?.observeArtwork(it) }.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .035f to Color.Black,
                        .58f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun CollectionHeader(title: String, subtitle: String?, visual: PlayerVisual, artist: Boolean,
    enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, playlist: Boolean = false,
    pinchHeader: CollectionPinchHeader? = null, onActionBottom: (Float) -> Unit = {}) {
    var headerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val imageHeight = maxWidth
        Box(Modifier.fillMaxWidth().height(imageHeight + 112.dp).onGloballyPositioned { headerCoordinates = it }) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                val split = artist || playlist
                val actionOutset = with(androidx.compose.ui.platform.LocalDensity.current) { glassShadowInset().roundToPx() }
                @Composable fun describeAction() {
                    val colors = IconButtonDefaults.iconButtonColors()
                    val foreground = controlStateColor(enabled, colors.contentColor, colors.disabledContentColor,
                        "Header action presentation availability")
                    val divider = LocalContentColor.current
                    SideEffect { pinchHeader?.actionSpec = CollectionHeaderActionSpec(split, foreground, divider, actionOutset) }
                }
                // The outer wrapper owns every action pixel, including overflow
                // shadow. It adds neither a pointer handler nor a semantic action.
                Box(Modifier.onGloballyPositioned { action ->
                    headerCoordinates?.takeIf { it.isAttached }?.let { header ->
                        onActionBottom(header.localPositionOf(action, Offset.Zero).y + action.size.height)
                    }
                }.onPlaced {
                    pinchHeader?.actionCoordinates = it
                    pinchHeader?.actionPlacedRequest = pinchHeader?.request ?: -1
                }.drawWithContent {
                    pinchHeader?.captureRequest
                    if (pinchHeader?.excludingActionFromCapture != true) drawContent()
                }) {
                if (split) LiquidGlassSurface(Modifier.height(64.dp)) {
                    describeAction()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIconButton(onPlay, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(appIconPainter(AppIcon.TransportPlay), if (playlist) "Play playlist" else "Play selected albums", Modifier.size(30.dp)) }
                        GlassPillDivider()
                        ActionIconButton(onShuffle, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(appIconPainter(AppIcon.ActionShuffle), if (playlist) "Shuffle playlist" else "Shuffle selected albums", Modifier.size(30.dp)) }
                    }
                } else LiquidGlassSurface(Modifier.size(64.dp)) {
                    describeAction()
                    ActionIconButton(onPlay, Modifier.fillMaxSize(), enabled = enabled) { Icon(appIconPainter(AppIcon.TransportPlay), "Play album", Modifier.size(30.dp)) }
                }
                }
            }
        }
    }
}

@Composable
internal fun ArtistAlbumChoices(albums: List<LibraryGroup>, selectedIds: List<String>, onSelection: (List<String>) -> Unit) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptics = rememberControlHaptics()
    val indication = LocalIndication.current
    // 112 dp covers one gallery gap apart (#573): touching tiles inset each cover by the gallery
    // card inset; the shared concentric outline (#532) keeps its 12 dp vertical padding.
    val cell = io.github.anastaci1a.localica.browse.BrowseCellGeometry.Carousel
    val inset = cell.cardInset.dp
    val albumOutline = rememberPaddedItemOutline(112.dp, inset, 12.dp, inset, 12.dp, centred = true)
    Column(Modifier.fillMaxWidth()) {
        Text("Albums", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        SelectionViewport(Modifier.fillMaxWidth(), observePlacement = { scroll.layoutInfo; Unit },
            visibleItemKeys = { scroll.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key } }, liftEdges = LiftEdges.Vertical) { overscroll ->
        LazyRow(Modifier.modalScroll(scroll).nestedScroll(HorizontalGestureOwner), state = scroll, overscrollEffect = overscroll,
            // Vertical room lets a selected album rise without its shadow meeting the clip (#533).
            contentPadding = PaddingValues(horizontal = cell.gridInset.dp, vertical = 8.dp)) {
            item(key = "all-albums") {
                Column(Modifier.width(112.dp + inset * 2).selectionLens(selectedIds.isEmpty(), false, itemKey = "all-albums", outline = albumOutline).toggleable(selectedIds.isEmpty(), role = Role.Checkbox, onValueChange = { onSelection(emptyList()) })
                    .semantics { selected = selectedIds.isEmpty() }.padding(horizontal = inset, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp)) {
                        Icon(appIconPainter(AppIcon.TabAlbums), null, Modifier.align(Alignment.Center).size(36.dp))
                    }
                    Text("All albums", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            items(albums, key = { it.id }) { album ->
                val selected = ArtistAlbumSelection.shownSelected(selectedIds, album.id)
                Column(Modifier.width(112.dp + inset * 2).selectionLens(selected, false, itemKey = album.id, outline = albumOutline).combinedClickable(
                    interactionSource = null, indication = indication, role = Role.Checkbox,
                    hapticFeedbackEnabled = false, onClickLabel = "Select only this album",
                    onClick = { onSelection(ArtistAlbumSelection.only(album.id)) },
                    onLongClickLabel = "Toggle album selection", onLongClick = {
                        haptics.emit(ControlHaptic.Strong)
                        onSelection(ArtistAlbumSelection.toggle(selectedIds, album.id, albums.map { it.id }))
                    }).semantics {
                        this.selected = selected
                        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    }.padding(horizontal = inset, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaArtwork(listOfNotNull(album.tracks.firstOrNull()?.uri), Modifier.size(112.dp))
                    Text(album.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = LocalContentColor.current.copy(alpha = .18f))
    }
}

/** Scroll-driven decoration owns its frame reads; the collection/query tree stays stable. */
@Composable
internal fun CollectionBackground(visual: PlayerVisual, grid: LazyGridState, source: FrostedBackdrop,
    reducedMotion: Boolean, modifier: Modifier, pinchHeader: CollectionPinchHeader? = null) {
    val scroll = remember(grid) { CollectionScroll() }
    val items = grid.layoutInfo.visibleItemsInfo.map {
        CollectionScroll.Item(it.key, it.index, it.offset.y, it.size.height)
    }
    val ordinary = scroll.update(items, grid.firstVisibleItemIndex == 0)
    pinchHeader?.ordinaryTop = ordinary
    pinchHeader?.presentedTop()?.let { scroll.presented(it, items) }
    val top = pinchHeader?.heroTop() ?: ordinary
    ArtworkBlend(visual, modifier.onPlaced { pinchHeader?.background = it }.drawWithContent {
        // Read outside the cached scene recording: header progress also owns
        // background invalidation when the source image itself is unchanged.
        val presentedTop = pinchHeader?.heroTop() ?: top
        val progress = pinchHeader?.motion?.progress?.value
        drawContent()
        if (pinchHeader != null && progress != null) pinchHeader.observeHero(presentedTop, progress)
    }.recordFrostedBackdrop(source),
        duration = if (reducedMotion) 120 else 240, opacityBlend = true) { frame ->
        CollectionBackdrop(frame, top, Modifier.fillMaxSize(), pinchHeader)
    }
}

@Composable
internal fun BoxScope.CollectionToolbar(grid: LazyGridState, width: Int, title: String, anchor: GlassAnchor,
    onBack: () -> Unit, onOptions: (Boolean) -> Unit, backdropScene: BrowseBackdropScene? = null,
    pinchHeader: CollectionPinchHeader? = null, onBackBottom: (Float) -> Unit = {}, menu: @Composable () -> Unit) {
    val fade = remember(grid, width, pinchHeader) { derivedStateOf {
        val header = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "browser-notice" }
        val presentedTop = pinchHeader?.presentedTop()
        val cover = width.toFloat().coerceAtLeast(1f)
        if (presentedTop == null && header == null && grid.firstVisibleItemIndex > 0) 1f
        else ((-(presentedTop ?: header?.offset?.y?.toFloat() ?: 0f) - cover * .65f) / (cover * .35f)).coerceIn(0f, 1f)
    } }
    val titled by remember(fade) { derivedStateOf { fade.value >= .1f } }
    val surface = MaterialTheme.colorScheme.surface
    val gradient = if (backdropScene != null) rememberBrowseBackdropLayer(backdropScene) else null
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(100.dp)
        .then(if (backdropScene != null && gradient != null) Modifier.recordBrowseBackdropLayer(backdropScene, gradient) else Modifier)
        .drawBehind {
            drawRect(Brush.verticalGradient(listOf(surface.copy(alpha = .7f * fade.value), Color.Transparent)))
        })
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.onGloballyPositioned { onBackBottom(it.positionInRoot().y + it.size.height) }) {
            ActionIcon(AppIcon.ActionChevronLeft, "Back", onClick = onBack)
        }
        Text(title, Modifier.weight(1f).graphicsLayer { alpha = fade.value }
            .then(if (!titled) Modifier.clearAndSetSemantics {} else Modifier), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        BrowseOptionsPill(anchor, onOptions)
        menu()
    }
}
