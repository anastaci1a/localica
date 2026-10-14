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
internal fun BrowseItemLayout(item: BrowserItem, style: BrowseStyle, dense: Boolean,
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null, showInformation: Boolean = true) {
    val gallery = style.presentation != Presentation.List
    val from = pinch?.source
    val progress = if (from != null) requireNotNull(pinch).progress.value else 1f
    val duration = if (reducedMotion || from != null) 0 else 350
    val compact = !gallery && style.listSize == ListSize.Compact
    fun font(value: BrowseStyle, dense: Boolean) = if (dense) 12f else if (value.presentation == Presentation.List && value.listSize == ListSize.Large) 18f else 16f
    fun line(value: BrowseStyle, dense: Boolean) = if (dense) 16f else if (value.presentation == Presentation.List && value.listSize == ListSize.Large) 26f else 24f
    val titleSize by animateFloatAsState(if (from != null) {
        val old = font(from, from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3)
        old + (font(style, dense) - old) * progress
    } else font(style, dense), tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(if (from != null) {
        val old = line(from, from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3)
        old + (line(style, dense) - old) * progress
    } else line(style, dense), tween(duration), label = "Browse title line")
    val fromCompact = from?.let { it.presentation == Presentation.List && it.listSize == ListSize.Compact } ?: compact
    val informationAlpha by animateFloatAsState(if (from != null)
        (if (fromCompact) 0f else 1f) + ((if (compact) 0f else 1f) - (if (fromCompact) 0f else 1f)) * progress
        else if (compact) 0f else 1f, tween(duration), label = "Browse detail visibility")
    fun numbered(value: BrowseStyle) = trackNumber != null && value.presentation == Presentation.List && value.listSize != ListSize.Large
    val numberAlpha = if (from == null) { if (numbered(style)) 1f else 0f } else
        (if (numbered(from)) 1f else 0f) + ((if (numbered(style)) 1f else 0f) - (if (numbered(from)) 1f else 0f)) * progress
    fun Modifier.morph() = if (from != null) this else animateBounds(lookahead, boundsTransform = { _, _ -> tween(duration) })
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box(Modifier.morph().then(if (gallery && artworkAnchor != null) Modifier.contextMenuAnchor(artworkAnchor,
            item.ref.kind in setOf(io.github.anastaci1a.localica.navigation.MediaKind.Artist, io.github.anastaci1a.localica.navigation.MediaKind.AlbumArtist)) else Modifier).testTag("browse-art-${item.key}")) {
            if (numberAlpha > 0f) Text(requireNotNull(trackNumber), Modifier.fillMaxSize().graphicsLayer { alpha = numberAlpha }.wrapContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (numberAlpha < 1f && (!compact || informationAlpha > 0f || from != null))
                MediaArtwork(item.coverUris, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - numberAlpha }, shape = artworkShape(item.ref.kind))
        }
        Column(Modifier.morph().testTag("browse-text-${item.key}")) {
            Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (trackNumber != null) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (showInformation && informationAlpha > 0f) item.information(style)?.let { Text(it, Modifier.graphicsLayer { alpha = informationAlpha },
                style = MaterialTheme.typography.bodySmall, maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis) }
        }
        Box { menu(!gallery) }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val oldGallery = from?.presentation?.let { it != Presentation.List } ?: gallery
        val oldWidth = if (from != null) (requireNotNull(pinch).viewportWidth / pinch.sourceColumns - 24.dp.toPx()).toInt().coerceAtLeast(1) else width
        fun mix(a: Int, b: Int) = (a + (b - a) * progress).toInt().coerceAtLeast(0)
        fun coverWidth(value: BrowseStyle, width: Int) = if (value.presentation != Presentation.List) width else
            if (numbered(value)) 28.dp.roundToPx().coerceAtMost(width)
            else when (value.listSize) {
                ListSize.Compact -> 0
                ListSize.Normal -> ((width + 24.dp.toPx()) / 5 - 24.dp.toPx()).toInt().coerceIn(0, width)
                ListSize.Large -> ((width + 24.dp.toPx()) / 4 - 24.dp.toPx()).toInt().coerceIn(0, width)
            }
        val oldArt = coverWidth(from ?: style, oldWidth)
        val targetArt = coverWidth(style, width)
        val artWidth = mix(oldArt, targetArt)
        val oldMenu = if (oldGallery) 0 else 48.dp.roundToPx()
        val targetMenu = if (gallery) 0 else 48.dp.roundToPx()
        val menuWidth = mix(oldMenu, targetMenu)
        val oldGap = if (oldGallery || fromCompact) 0 else 12.dp.roundToPx()
        val targetGap = if (gallery || compact) 0 else 12.dp.roundToPx()
        val textWidth = mix(if (oldGallery) oldWidth else oldWidth - oldArt - oldGap - oldMenu,
            if (gallery) width else width - targetArt - targetGap - targetMenu)
        val art = children[0].measure(Constraints.fixed(artWidth, artWidth))
        val text = children[1].measure(Constraints(minWidth = textWidth.coerceAtLeast(0), maxWidth = textWidth.coerceAtLeast(0)))
        val actions = children[2].measure(Constraints.fixed(menuWidth, 48.dp.roundToPx()))
        val oldHeight = if (oldGallery) oldArt + text.height else if (fromCompact) actions.height else maxOf(oldArt, text.height, actions.height)
        val targetHeight = if (gallery) targetArt + text.height else if (compact) actions.height else maxOf(targetArt, text.height, actions.height)
        val oldInset = if (fromCompact) 0 else 8.dp.roundToPx()
        val targetInset = if (compact) 0 else 8.dp.roundToPx()
        val inset = mix(oldInset, targetInset)
        // The grid must lay out its final rows once. Interpolating row height here
        // feeds the animation back into lazy virtualization and shifts every anchor.
        val height = if (from != null) targetHeight else mix(oldHeight, targetHeight)
        layout(width, height + targetInset * 2) {
            art.placeRelative(0, inset + mix(if (oldGallery) 0 else (oldHeight - oldArt) / 2, if (gallery) 0 else (targetHeight - targetArt) / 2))
            text.placeRelative(mix(if (oldGallery) 0 else oldArt + oldGap, if (gallery) 0 else targetArt + targetGap),
                inset + mix(if (oldGallery) oldArt else (oldHeight - text.height) / 2, if (gallery) targetArt else (targetHeight - text.height) / 2))
            actions.placeRelative(mix(oldWidth - oldMenu, width - targetMenu), inset + mix(if (oldGallery) oldArt else (oldHeight - actions.height) / 2,
                if (gallery) targetArt else (targetHeight - actions.height) / 2))
        }
    }
}
