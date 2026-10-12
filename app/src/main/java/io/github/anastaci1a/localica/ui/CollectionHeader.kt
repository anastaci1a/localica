package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.LibraryGroup

internal fun collectionIsLight(visual: PlayerVisual): Boolean = visual.palette.middle.let {
    Color((it.red / .6f).coerceAtMost(1f), (it.green / .6f).coerceAtMost(1f), (it.blue / .6f).coerceAtMost(1f)).luminance() > .45f
}

@Composable
internal fun CollectionBackdrop(visual: PlayerVisual, artTop: Float, modifier: Modifier = Modifier) {
    val light = collectionIsLight(visual)
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.then(Modifier.clipToBounds())) {
        val imageHeight = maxWidth
        PlayerBackdrop(visual.palette, Modifier.fillMaxSize(), fullArtBlend = 1f,
            artHeight = with(density) { imageHeight.toPx() }, artTop = artTop, softness = 1f)
        Box(Modifier.matchParentSize().background(if (light) Color.White.copy(alpha = .58f) else Color.Black.copy(alpha = .16f)))
        visual.artwork?.let { image ->
            Image(image, null, Modifier.offset(y = with(density) { artTop.toDp() }).fillMaxWidth().height(imageHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, .58f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
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
                        IconButton(onPlay, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(painterResource(R.drawable.ic_play), "Play selected albums", Modifier.size(30.dp)) }
                        Box(Modifier.height(24.dp).width(1.dp).background(LocalContentColor.current.copy(alpha = .2f)))
                        IconButton(onShuffle, Modifier.size(72.dp, 64.dp), enabled = enabled) { Icon(painterResource(R.drawable.ic_shuffle), "Shuffle selected albums", Modifier.size(30.dp)) }
                    }
                } else LiquidGlassSurface(Modifier.size(64.dp)) {
                    IconButton(onPlay, Modifier.fillMaxSize(), enabled = enabled) { Icon(painterResource(R.drawable.ic_play), "Play album", Modifier.size(30.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun ArtistAlbumChoices(albums: List<LibraryGroup>, selectedIds: List<String>, onSelection: (List<String>) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Albums", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "all-albums") {
                Column(Modifier.width(120.dp).selectionLens(selectedIds.isEmpty(), false).toggleable(selectedIds.isEmpty(), role = Role.Checkbox, onValueChange = { onSelection(emptyList()) })
                    .semantics { selected = selectedIds.isEmpty() }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp)) {
                        Icon(painterResource(R.drawable.ic_albums), null, Modifier.align(Alignment.Center).size(36.dp))
                    }
                    Text("All albums", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            items(albums, key = { it.id }) { album ->
                val selected = album.id in selectedIds
                Column(Modifier.width(120.dp).selectionLens(selected, false).toggleable(selected, role = Role.Checkbox, onValueChange = {
                    onSelection(if (selected) selectedIds - album.id else selectedIds + album.id)
                }).semantics { this.selected = selected }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaArtwork(album.tracks.map { it.uri }, Modifier.size(112.dp))
                    Text(album.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = LocalContentColor.current.copy(alpha = .18f))
    }
}
