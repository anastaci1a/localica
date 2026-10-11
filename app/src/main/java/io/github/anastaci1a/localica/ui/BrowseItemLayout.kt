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
    lookahead: LookaheadScope, reducedMotion: Boolean, menu: @Composable (Boolean) -> Unit) {
    val gallery = style.presentation != Presentation.List
    val duration = if (reducedMotion) 0 else 350
    val titleSize by animateFloatAsState(if (dense) 12f else 16f, tween(duration), label = "Browse title size")
    val titleLine by animateFloatAsState(if (dense) 16f else 24f, tween(duration), label = "Browse title line")
    fun Modifier.morph() = animateBounds(lookahead, boundsTransform = { _, _ -> tween(duration) })
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box(Modifier.morph().testTag("browse-art-${item.key}")) {
            MediaArtwork(item.coverUris, Modifier.fillMaxSize(), shape = artworkShape(item.ref.kind))
        }
        Column(Modifier.morph().testTag("browse-text-${item.key}")) {
            Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize.sp, lineHeight = titleLine.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.information(style)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box { menu(!gallery) }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val artWidth = if (gallery) width else 48.dp.roundToPx().coerceAtMost(width)
        val gap = if (gallery) 0 else 12.dp.roundToPx()
        val menuWidth = if (gallery) 0 else 48.dp.roundToPx().coerceAtMost((width - artWidth - gap).coerceAtLeast(0))
        val textWidth = if (gallery) width else (width - artWidth - gap - menuWidth).coerceAtLeast(0)
        val art = children[0].measure(Constraints.fixed(artWidth, artWidth))
        val text = children[1].measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        val actions = children[2].measure(if (gallery) Constraints.fixed(0, 0) else Constraints.fixed(menuWidth, 48.dp.roundToPx()))
        val height = if (gallery) art.height + text.height else maxOf(art.height, text.height, actions.height)
        layout(width, height) {
            art.placeRelative(0, if (gallery) 0 else (height - art.height) / 2)
            text.placeRelative(if (gallery) 0 else artWidth + gap, if (gallery) art.height else (height - text.height) / 2)
            actions.placeRelative(width - menuWidth, if (gallery) art.height else (height - actions.height) / 2)
        }
    }
}
