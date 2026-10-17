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
internal fun BrowseItemLayout(item: BrowserItem, style: BrowseStyle, geometry: BrowseCellGeometry,
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit, trackNumber: String? = null, pinch: BrowsePinchMotion? = null, artworkAnchor: GlassAnchor? = null, showInformation: Boolean = true, inheritedInformation: Map<io.github.anastaci1a.localica.navigation.MediaKind, BrowseStyle> = emptyMap()) {
    val dense = geometry.dense
    val gallery = style.presentation != Presentation.List
    val from = pinch?.source
    val progress = if (from != null) requireNotNull(pinch).progress.value else 1f
    val duration = if (reducedMotion || from != null) 0 else 350
    fun trailing(value: BrowseStyle) = value.presentation == Presentation.List && value.listSize.trailingInformation
    fun font(value: BrowseStyle, dense: Boolean) = if (dense) 12f else if (trailing(value)) 14f else if (value.presentation == Presentation.List && value.listSize == ListSize.Large) 18f else 16f
    fun line(value: BrowseStyle, dense: Boolean) = if (dense) 16f else if (trailing(value)) 20f else if (value.presentation == Presentation.List && value.listSize == ListSize.Large) 26f else 24f
    val titleSize by animateFloatAsState(if (from != null) {
        val old = font(from, from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3)
        old + (font(style, dense) - old) * progress
    } else font(style, dense), tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(if (from != null) {
        val old = line(from, from.presentation != Presentation.List && requireNotNull(pinch).sourceColumns >= 3)
        old + (line(style, dense) - old) * progress
    } else line(style, dense), tween(duration), label = "Browse title line")
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
                MediaArtwork(item.coverUris, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - numberAlpha }, shape = artworkShape(item.ref.kind))
        }
        Box(Modifier.morph().testTag("browse-text-${item.key}")) {
            Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp,
                fontWeight = if (trackNumber != null) androidx.compose.ui.text.font.FontWeight.Normal else MaterialTheme.typography.titleMedium.fontWeight),
                maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.morph().testTag("browse-info-${item.key}")) {
            if (showInformation) item.information(style, inheritedInformation)?.let { Text(it,
                color = if (trailing(style)) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                style = MaterialTheme.typography.bodySmall, maxLines = if (gallery) 2 else 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.testTag("browse-menu-${item.key}")) { menu(!gallery) }
    }) { children, constraints ->
        val targetHorizontalInset = geometry.contentInset.dp.roundToPx()
        val sourceHorizontalInset = if (from != null) BrowseCellGeometry(requireNotNull(pinch).sourceColumns).contentInset.dp.roundToPx() else targetHorizontalInset
        val width = (constraints.maxWidth - targetHorizontalInset * 2).coerceAtLeast(0)
        val oldGallery = from?.presentation?.let { it != Presentation.List } ?: gallery
        val oldWidth = if (from != null) requireNotNull(pinch).sourceContentWidth(item.key) else width
        fun mix(a: Int, b: Int) = (a + (b - a) * progress).toInt().coerceAtLeast(0)
        fun coverWidth(value: BrowseStyle, width: Int) = if (value.presentation != Presentation.List) width else
            if (numbered(value)) 28.dp.roundToPx().coerceAtMost(width)
            else when (value.listSize) {
                ListSize.Small, ListSize.Smallest -> 0
                ListSize.Compact -> 32.dp.roundToPx().coerceAtMost(width)
                else -> {
                    val list = BrowseCellGeometry(1)
                    val viewport = width + 2 * (list.cardInset.dp.roundToPx() + list.contentInset.dp.roundToPx())
                    BrowseCellGeometry(if (value.listSize == ListSize.Normal) 5 else 3).artworkWidth(viewport, density).coerceAtMost(width)
                }
            }
        val source = from ?: style
        val horizontalInset = mix(sourceHorizontalInset, targetHorizontalInset)
        val oldArt = coverWidth(source, oldWidth)
        val targetArt = coverWidth(style, width)
        val artWidth = mix(oldArt, targetArt)
        val oldMenu = if (oldGallery) 0 else 48.dp.roundToPx()
        val targetMenu = if (gallery) 0 else 48.dp.roundToPx()
        fun gap(isGallery: Boolean, art: Int) = if (isGallery || art == 0) 0 else 12.dp.roundToPx()
        val oldGap = gap(oldGallery, oldArt)
        val targetGap = gap(gallery, targetArt)
        val oldAvailable = (if (oldGallery) oldWidth else oldWidth - oldArt - oldGap - oldMenu).coerceAtLeast(0)
        val targetAvailable = (if (gallery) width else width - targetArt - targetGap - targetMenu).coerceAtLeast(0)
        val information = children[2].measure(Constraints(maxWidth = mix(
            if (trailing(source)) oldAvailable * 2 / 5 else oldAvailable,
            if (trailing(style)) targetAvailable * 2 / 5 else targetAvailable)))
        val infoGap = if (information.width > 0) 8.dp.roundToPx() else 0
        fun titleWidth(value: BrowseStyle, available: Int) =
            (available - if (trailing(value)) information.width + infoGap else 0).coerceAtLeast(0)
        val textWidth = mix(titleWidth(source, oldAvailable), titleWidth(style, targetAvailable))
        val art = children[0].measure(Constraints.fixed(artWidth, artWidth))
        val text = children[1].measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        fun textHeight(value: BrowseStyle) = if (trailing(value)) maxOf(text.height, information.height) else text.height + information.height
        fun rowHeight(value: BrowseStyle, art: Int): Int {
            if (value.presentation != Presentation.List) return art + textHeight(value)
            val minimum = when (value.listSize) {
                ListSize.Compact -> 56
                ListSize.Small -> 48
                ListSize.Smallest -> 40
                else -> 48
            }.dp.roundToPx()
            return maxOf(art, textHeight(value), minimum)
        }
        val oldHeight = rowHeight(source, oldArt)
        val targetHeight = rowHeight(style, targetArt)
        val actions = children[3].measure(Constraints.fixed(mix(oldMenu, targetMenu),
            mix(minOf(oldHeight, 48.dp.roundToPx()), minOf(targetHeight, 48.dp.roundToPx()))))
        val oldInset = if (trailing(source)) 0 else 8.dp.roundToPx()
        val targetInset = if (trailing(style)) 0 else 8.dp.roundToPx()
        val inset = mix(oldInset, targetInset)
        fun titleY(value: BrowseStyle, art: Int, height: Int) =
            if (value.presentation != Presentation.List) art else (height - textHeight(value)) / 2
        fun infoY(value: BrowseStyle, art: Int, height: Int) =
            if (trailing(value)) (height - information.height) / 2 else titleY(value, art, height) + text.height
        fun infoX(value: BrowseStyle, availableWidth: Int, art: Int, gap: Int, menu: Int) =
            if (trailing(value)) availableWidth - menu - information.width
            else if (value.presentation != Presentation.List) 0 else art + gap
        // Keep final lazy row heights during pinch; only the children interpolate.
        layout(constraints.maxWidth, targetHeight + targetInset * 2) {
            art.placeRelative(horizontalInset, inset + mix(if (oldGallery) 0 else (oldHeight - oldArt) / 2, if (gallery) 0 else (targetHeight - targetArt) / 2))
            text.placeRelative(horizontalInset + mix(if (oldGallery) 0 else oldArt + oldGap, if (gallery) 0 else targetArt + targetGap),
                inset + mix(titleY(source, oldArt, oldHeight), titleY(style, targetArt, targetHeight)))
            information.placeRelative(horizontalInset + mix(infoX(source, oldWidth, oldArt, oldGap, oldMenu), infoX(style, width, targetArt, targetGap, targetMenu)),
                inset + mix(infoY(source, oldArt, oldHeight), infoY(style, targetArt, targetHeight)))
            actions.placeRelative(horizontalInset + mix(oldWidth - oldMenu, width - targetMenu), inset + mix(if (oldGallery) oldArt else (oldHeight - actions.height) / 2,
                if (gallery) targetArt else (targetHeight - actions.height) / 2))
        }
    }
}
