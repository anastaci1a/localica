package io.github.anastaci1a.localica.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.browse.*

/** Artwork, metadata and menu keep the same composition identities in every view. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BrowseItemLayout(item: BrowserItem, style: BrowseStyle, dense: Boolean,
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null) {
    val gallery = style.presentation != Presentation.List
    val from = pinch?.source
    val progress = if (from != null) requireNotNull(pinch).progress.value else 1f
    val duration = if (reducedMotion || from != null) 0 else 350
    val titleSize by animateFloatAsState(if (from != null) {
        val old = if (from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3) 12f else 16f
        old + ((if (dense) 12f else 16f) - old) * progress
    } else if (dense) 12f else 16f, tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(if (from != null) {
        val old = if (from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3) 16f else 24f
        old + ((if (dense) 16f else 24f) - old) * progress
    } else if (dense) 16f else 24f, tween(duration), label = "Browse title line")
    fun Modifier.morph() = if (from != null) this else animateBounds(lookahead, boundsTransform = { _, _ -> tween(duration) })
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box(Modifier.morph().then(if (gallery && artworkAnchor != null) Modifier.contextMenuAnchor(artworkAnchor,
            item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist, io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)) else Modifier).testTag("browse-art-${item.key}")) {
            if (trackNumber != null && !gallery) Text(trackNumber, Modifier.fillMaxSize().wrapContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            else MediaArtwork(item.coverUris, Modifier.fillMaxSize(), shape = artworkShape(item.ref.kind))
        }
        Column(Modifier.morph().testTag("browse-text-${item.key}")) {
            Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (trackNumber != null) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.information(style)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box { menu(!gallery) }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val oldGallery = from?.presentation?.let { it != Presentation.List } ?: gallery
        val oldWidth = if (from != null) (requireNotNull(pinch).viewportWidth / pinch.sourceColumns - (if (oldGallery && pinch.sourceColumns >= 3) 16.dp.toPx() else 32.dp.toPx())).toInt().coerceAtLeast(1) else width
        fun mix(a: Int, b: Int) = (a + (b - a) * progress).toInt().coerceAtLeast(0)
        fun coverWidth(gallery: Boolean, width: Int) = if (gallery) width else (if (trackNumber != null) 28.dp else 48.dp).roundToPx().coerceAtMost(width)
        val oldArt = coverWidth(oldGallery, oldWidth)
        val targetArt = coverWidth(gallery, width)
        val artWidth = mix(oldArt, targetArt)
        val oldMenu = if (oldGallery) 0 else 48.dp.roundToPx()
        val targetMenu = if (gallery) 0 else 48.dp.roundToPx()
        val menuWidth = mix(oldMenu, targetMenu)
        val oldGap = if (oldGallery) 0 else 12.dp.roundToPx()
        val targetGap = if (gallery) 0 else 12.dp.roundToPx()
        val textWidth = mix(if (oldGallery) oldWidth else oldWidth - oldArt - oldGap - oldMenu,
            if (gallery) width else width - targetArt - targetGap - targetMenu)
        val art = children[0].measure(Constraints.fixed(artWidth, artWidth))
        val text = children[1].measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        val actions = children[2].measure(Constraints.fixed(menuWidth, 48.dp.roundToPx()))
        val oldHeight = if (oldGallery) oldArt + text.height else maxOf(oldArt, text.height, actions.height)
        val targetHeight = if (gallery) targetArt + text.height else maxOf(targetArt, text.height, actions.height)
        // The grid must lay out its final rows once. Interpolating row height here
        // feeds the animation back into lazy virtualization and shifts every anchor.
        val height = if (from != null) targetHeight else mix(oldHeight, targetHeight)
        layout(width, height) {
            art.placeRelative(0, mix(if (oldGallery) 0 else (oldHeight - oldArt) / 2, if (gallery) 0 else (targetHeight - targetArt) / 2))
            text.placeRelative(mix(if (oldGallery) 0 else oldArt + oldGap, if (gallery) 0 else targetArt + targetGap),
                mix(if (oldGallery) oldArt else (oldHeight - text.height) / 2, if (gallery) targetArt else (targetHeight - text.height) / 2))
            actions.placeRelative(mix(oldWidth - oldMenu, width - targetMenu), mix(if (oldGallery) oldArt else (oldHeight - actions.height) / 2,
                if (gallery) targetArt else (targetHeight - actions.height) / 2))
        }
    }
}
