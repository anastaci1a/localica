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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.R

internal val ListPillVerticalInset = 4.dp
@Composable internal fun listPillHeight() = maxOf(40.dp, with(LocalDensity.current) { 20.sp.toDp() } + 16.dp)
@Composable internal fun listPillTargetHeight() = maxOf(48.dp, listPillHeight())
@Composable internal fun listPillSlotHeight() = listPillTargetHeight() + ListPillVerticalInset * 2

/** Shared visible body and touch target with Library filter bubbles, including large text. */
@Composable
internal fun ListSearchPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, anchor: GlassAnchor? = null) {
    val interaction = remember { MutableInteractionSource() }
    val source = LocalFrostedBackdrop.current
    SideEffect { anchor?.source = source; anchor?.face = { SearchPillFace(label) } }
    Box(modifier.fillMaxWidth().height(listPillTargetHeight()).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .testTag("list-search-pill"), contentAlignment = Alignment.Center) {
        LiquidGlassSurface(Modifier.fillMaxWidth().height(listPillHeight()).then(if (anchor != null) Modifier.glassAnchor(anchor) else Modifier)) {
            anchor?.captureInk()
            SearchPillFace(label)
        }
    }
}

@Composable
private fun SearchPillFace(label: String) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = LocalContentColor.current.copy(alpha = .8f))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = .8f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GlassSearchField(value: String, onValue: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, fieldModifier: Modifier = Modifier, enabled: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default) {
    LiquidGlassSurface(modifier, cornerRadius = 28.dp, pressFeedback = false) {
        ClearableTextField(value, onValue, fieldModifier.fillMaxWidth().clearTextFocusOnBack().semantics { contentDescription = "$label input" }, enabled = enabled,
            singleLine = true, placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp)) },
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedTextColor = LocalContentColor.current, unfocusedTextColor = LocalContentColor.current))
    }
}

/** Fade the scrolling pixels, not a solid panel painted across the list. */
internal fun Modifier.listTopFade() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val height = 80.dp.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(*Array(17) { i -> val t = i / 16f; t to Color.Black.copy(alpha = t * t * (3f - 2f * t)) }, endY = height),
            size = Size(size.width, height), blendMode = BlendMode.DstIn)
    }

/** Reveal a scroll affordance only as content actually leaves that viewport edge. */
internal fun Modifier.scrollEdgeFade(scroll: androidx.compose.foundation.ScrollState) =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = 24.dp.toPx().coerceAtMost(size.height / 2)
        if (band > 0f) {
            val top = (scroll.value / band).coerceIn(0f, 1f)
            val bottom = ((scroll.maxValue - scroll.value) / band).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 1f - top), Color.Black), endY = band),
                size = Size(size.width, band), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - bottom)), startY = size.height - band, endY = size.height),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - band), size = Size(size.width, band), blendMode = BlendMode.DstIn)
        }
    }

@Composable
internal fun ListSearchWindow(visible: Boolean, anchor: GlassAnchor, hasQuery: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    GlassMorphPopup(visible, anchor, onDismiss, GlassPlacement.Search) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().then(if (hasQuery) Modifier.height(maxHeight * .85f) else Modifier.wrapContentHeight())
                .testTag("list-search-popup")) {
                content()
            }
        }
    }
}
