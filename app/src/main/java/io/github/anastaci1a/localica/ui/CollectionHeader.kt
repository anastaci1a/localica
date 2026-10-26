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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
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
internal fun CollectionBackdrop(visual: PlayerVisual, artTop: Float, modifier: Modifier = Modifier) {
    val light = collectionIsLight(visual)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pictureTop = WindowInsets.statusBars.getTop(density) + artTop * if (LocalReducedMotion.current) 1f else .65f
    BoxWithConstraints(modifier.then(Modifier.clipToBounds())) {
        val imageHeight = maxWidth
        // Browsing keeps its established dimmed continuation; the full player opts
        // into configurable contrast without changing album/artist pages.
        val palette = visual.palette
        PlayerBackdrop(palette.copy(top = lerp(palette.top, Color.Black, .4f),
            middle = lerp(palette.middle, Color.Black, .4f), bottom = lerp(palette.bottom, Color.Black, .4f),
            wash = palette.collectionWash ?: palette.wash), Modifier.fillMaxSize(), fullArtBlend = 1f,
            artHeight = with(density) { imageHeight.toPx() }, artTop = pictureTop, softness = 1f)
        Box(Modifier.matchParentSize().background(if (light) Color.White.copy(alpha = .58f) else Color.Black.copy(alpha = .16f)))
        visual.artwork?.let { image ->
            Image(image, null, Modifier.offset(y = with(density) { pictureTop.toDp() }).fillMaxWidth().height(imageHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .035f to Color.Black,
                        .58f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun CollectionHeader(title: String, subtitle: String?, visual: PlayerVisual, artist: Boolean,
    enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val imageHeight = maxWidth
        Box(Modifier.fillMaxWidth().height(imageHeight + 112.dp)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                if (artist) LiquidGlassSurface(Modifier.height(64.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIconButton(onPlay, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(painterResource(R.drawable.ic_play), "Play selected albums", Modifier.size(30.dp)) }
                        GlassPillDivider()
                        ActionIconButton(onShuffle, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(painterResource(R.drawable.ic_shuffle), "Shuffle selected albums", Modifier.size(30.dp)) }
                    }
                } else LiquidGlassSurface(Modifier.size(64.dp)) {
                    ActionIconButton(onPlay, Modifier.fillMaxSize(), enabled = enabled) { Icon(painterResource(R.drawable.ic_play), "Play album", Modifier.size(30.dp)) }
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
    Column(Modifier.fillMaxWidth()) {
        Text("Albums", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        SelectionViewport(Modifier.fillMaxWidth(), observePlacement = { scroll.layoutInfo; Unit }) { overscroll ->
        LazyRow(Modifier.modalScroll(scroll).nestedScroll(HorizontalGestureOwner), state = scroll, overscrollEffect = overscroll,
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "all-albums") {
                Column(Modifier.width(136.dp).selectionLens(selectedIds.isEmpty(), false).toggleable(selectedIds.isEmpty(), role = Role.Checkbox, onValueChange = { onSelection(emptyList()) })
                    .semantics { selected = selectedIds.isEmpty() }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp)) {
                        Icon(painterResource(R.drawable.ic_albums), null, Modifier.align(Alignment.Center).size(36.dp))
                    }
                    Text("All albums", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            items(albums, key = { it.id }) { album ->
                val selected = ArtistAlbumSelection.shownSelected(selectedIds, album.id)
                Column(Modifier.width(136.dp).selectionLens(selected, false).combinedClickable(
                    interactionSource = null, indication = indication, role = Role.Checkbox,
                    hapticFeedbackEnabled = false, onClickLabel = "Select only this album",
                    onClick = { onSelection(ArtistAlbumSelection.only(album.id)) },
                    onLongClickLabel = "Toggle album selection", onLongClick = {
                        haptics.emit(ControlHaptic.Strong)
                        onSelection(ArtistAlbumSelection.toggle(selectedIds, album.id, albums.map { it.id }))
                    }).semantics {
                        this.selected = selected
                        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    reducedMotion: Boolean, modifier: Modifier) {
    val scroll = remember(grid) { CollectionScroll() }
    val top = scroll.update(grid.layoutInfo.visibleItemsInfo.map {
        CollectionScroll.Item(it.key, it.index, it.offset.y, it.size.height)
    }, grid.firstVisibleItemIndex == 0)
    ArtworkBlend(visual, modifier.recordFrostedBackdrop(source),
        duration = if (reducedMotion) 120 else 240, opacityBlend = true) { frame ->
        CollectionBackdrop(frame, top, Modifier.fillMaxSize())
    }
}

@Composable
internal fun BoxScope.CollectionToolbar(grid: LazyGridState, width: Int, title: String, anchor: GlassAnchor,
    onBack: () -> Unit, onOptions: (Boolean) -> Unit, menu: @Composable () -> Unit) {
    val fade = remember(grid, width) { derivedStateOf {
        val header = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "browser-notice" }
        val cover = width.toFloat().coerceAtLeast(1f)
        if (header == null && grid.firstVisibleItemIndex > 0) 1f
        else ((-(header?.offset?.y ?: 0) - cover * .65f) / (cover * .35f)).coerceIn(0f, 1f)
    } }
    val titled by remember(fade) { derivedStateOf { fade.value >= .1f } }
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(100.dp).drawBehind {
        drawRect(Brush.verticalGradient(listOf(surface.copy(alpha = .7f * fade.value), Color.Transparent)))
    })
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionIcon(R.drawable.ic_back, "Back", onClick = onBack)
        Text(title, Modifier.weight(1f).graphicsLayer { alpha = fade.value }
            .then(if (!titled) Modifier.clearAndSetSemantics {} else Modifier), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        BrowseOptionsPill(anchor, onOptions)
        menu()
    }
}
