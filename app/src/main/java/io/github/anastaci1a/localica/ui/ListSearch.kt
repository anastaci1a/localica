package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
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
    Box(modifier.fillMaxWidth().height(listPillTargetHeight()).ownButtonDrag().clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
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
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    showSearchIcon: Boolean = true) {
    LiquidGlassSurface(modifier, cornerRadius = 28.dp, pressFeedback = false) {
        ClearableTextField(value, onValue, fieldModifier.fillMaxWidth().semantics { contentDescription = "$label input" }, enabled = enabled,
            singleLine = true, placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = if (showSearchIcon) {
                { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp).testTag("glass-search-icon")) }
            } else null,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedTextColor = LocalContentColor.current, unfocusedTextColor = LocalContentColor.current))
    }
}

/** Fade the scrolling pixels, not a solid panel painted across the list.
 * Read strength only while drawing; callers retain ownership of motion and geometry.
 * The 17-stop brush is built once per size and strength, not on every draw. From Android 13 only the
 * fade band is composited offscreen; earlier releases keep the full-viewport layer because CI showed
 * the band-limited layer differs by one color level at API 26 (exact-pixel contract). */
internal fun Modifier.listTopFade(strength: () -> Float = { 1f }) = composed {
    // Modal grids use the shared directional viewport mask instead of a
    // permanent top fade that would hide content even at the start of the list.
    if (LocalPopupScrollEdges.current) Modifier
    else if (android.os.Build.VERSION.SDK_INT < 33) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val band = 80.dp.toPx().coerceAtMost(size.height)
        val cache = FadeBrushCache(band)
        onDrawWithContent {
            drawContent()
            val fade = strength().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            // Identity means no mask operation, including native gradient dithering.
            if (fade == 0f) return@onDrawWithContent
            drawRect(cache.brush(fade), size = Size(size.width, band), blendMode = BlendMode.DstIn)
        }
    }
    else Modifier.drawWithCache {
        val band = 80.dp.toPx().coerceAtMost(size.height)
        // The mask is opaque on its last stop, so rows past a fractional band edge are unchanged;
        // splitting at a whole row keeps the direct and layered halves seam-free.
        val split = kotlin.math.ceil(band).coerceAtMost(size.height)
        val cache = FadeBrushCache(band)
        val layer = obtainGraphicsLayer().apply { compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen; clip = true }
        onDrawWithContent {
            val fade = strength().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            if (fade == 0f || band <= 0f) return@onDrawWithContent drawContent()
            val mask = cache.brush(fade)
            if (split < size.height) clipRect(top = split) { this@onDrawWithContent.drawContent() }
            // The same Offscreen layer mechanism as before, but only as tall as the fade band.
            layer.record(size = androidx.compose.ui.unit.IntSize(kotlin.math.ceil(size.width).toInt(), split.toInt())) {
                this@onDrawWithContent.drawContent()
                drawRect(mask, size = Size(size.width, band), blendMode = BlendMode.DstIn)
            }
            drawLayer(layer)
        }
    }
}

/** Single-entry memo of the 17-stop mask for one band height. */
private class FadeBrushCache(private val band: Float) {
    private var brush: Brush? = null
    private var strength = Float.NaN
    fun brush(fade: Float): Brush = brush?.takeIf { strength == fade } ?: Brush.verticalGradient(*Array(17) { i ->
        val t = i / 16f
        val smooth = t * t * (3f - 2f * t)
        val alpha = when (fade) { 1f -> smooth; 0f -> 1f; else -> 1f + (smooth - 1f) * fade }
        t to Color.Black.copy(alpha = alpha)
    }, endY = band).also { brush = it; strength = fade }
}

@Composable
internal fun ListSearchWindow(visible: Boolean, anchor: GlassAnchor, hasQuery: Boolean, onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    GlassMorphPopup(visible, anchor, onDismiss, GlassPlacement.Search, searchDismissOnImeBack = !hasQuery) {
        // Register before the editor so ending text input remains the first Back action.
        BackHandler(visible && onBack != null) { onBack?.invoke() }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().then(if (hasQuery) Modifier.heightIn(max = maxHeight * .85f) else Modifier.wrapContentHeight())
                .testTag("list-search-popup")) {
                content()
            }
        }
    }
}

