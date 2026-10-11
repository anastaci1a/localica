package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.anastaci1a.localica.R

internal val ListPillVerticalInset = 4.dp
@Composable internal fun listPillHeight() = maxOf(40.dp, with(LocalDensity.current) { 20.sp.toDp() } + 16.dp)
@Composable internal fun listPillTargetHeight() = maxOf(48.dp, listPillHeight())
@Composable internal fun listPillSlotHeight() = listPillTargetHeight() + ListPillVerticalInset * 2

/** Shared visible body and touch target with Library filter bubbles, including large text. */
@Composable
internal fun ListSearchPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth().height(listPillTargetHeight()).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .testTag("list-search-pill"), contentAlignment = Alignment.Center) {
        LiquidGlassSurface(Modifier.fillMaxWidth().height(listPillHeight())) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Fade the scrolling pixels, not a solid panel painted across the list. */
internal fun Modifier.listTopFade() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val height = 20.dp.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = height),
            size = Size(size.width, height), blendMode = BlendMode.DstIn)
    }

@Composable
internal fun ListSearchWindow(hasQuery: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Surface(Modifier.fillMaxWidth().then(if (hasQuery) Modifier.height(maxHeight * .85f) else Modifier.wrapContentHeight())
                .testTag("list-search-popup"), shape = Corners.panel, tonalElevation = 6.dp, shadowElevation = 8.dp) {
                content()
            }
        }
    }
}
