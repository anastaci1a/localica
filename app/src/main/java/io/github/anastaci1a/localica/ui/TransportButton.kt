package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.icons.SvgIconParser
import io.github.anastaci1a.localica.icons.builtinSvg
import io.github.anastaci1a.localica.icons.toImageVector

internal enum class TransportGlyph { Previous, PlayPause, Next }

@Composable
internal fun TransportButton(glyph: TransportGlyph, playing: Boolean, label: String, onClick: () -> Unit,
    modifier: Modifier, iconSize: Dp, tint: Color, enabled: Boolean, reducedMotion: Boolean, tintReader: androidx.compose.ui.graphics.ColorProducer? = null, iconSizeReader: (() -> Dp)? = null) {
    val reduced = rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current)
    val speed = LocalMotionSpeed.current
    val shapes = LocalTransportShapes.current
    val images = currentIconPackImages()
    val icon = when (glyph) {
        TransportGlyph.Previous -> AppIcon.TransportPrevious
        TransportGlyph.Next -> AppIcon.TransportNext
        TransportGlyph.PlayPause -> AppIcon.TransportPlay
    }
    fun replaced(id: AppIcon) = id.id in images.vectors || id.id in images.masks
    val custom = replaced(icon) || (glyph == TransportGlyph.PlayPause && replaced(AppIcon.TransportPause))
    val context = LocalContext.current
    // Planned once per published pack; the cache answers by identity on every later composition.
    val morph = if (custom && glyph == TransportGlyph.PlayPause) (PackPlayPauseMorphs.plan(images, builtin = { icon ->
        SvgIconParser.parse(builtinSvg(context, icon)).toImageVector()
    }) as? PackPlayPausePlan.Morph)?.morph else null
    val painter = appIconPainter(icon)
    val pausePainter = if (glyph == TransportGlyph.PlayPause) appIconPainter(AppIcon.TransportPause) else painter
    val pause by animateFloatAsState(if (playing) 1f else 0f,
        tween(if (reduced) 0 else 260, easing = FastOutSlowInEasing), label = "Play pause morph")
    val opacity by animateControlStateFloat(if (enabled) 1f else .38f, enabled, "Transport availability", reduced)
    val travel = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var destination by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) {
            animation?.cancel()
            destination = 0f
            travel.snapTo(0f)
        }
    }
    Box(modifier.ownButtonDrag(enabled).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null, onClick = controlActivation(enabled) {
            onClick()
            if (glyph != TransportGlyph.PlayPause && !reduced) {
                val velocity = travel.velocity
                animation?.cancel()
                destination += 1f
                val target = destination
                val duration = skipDuration(speed)
                val easing = skipRetargetEasing(velocity, target - travel.value, duration)
                animation = scope.launch {
                    travel.animateTo(target, tween(duration, easing = easing))
                    // An integral cycle has the same pixels. Rebase only after settling.
                    travel.snapTo(0f)
                    destination = 0f
                }
            }
        }), contentAlignment = Alignment.Center) {
        Canvas(if (iconSizeReader == null) Modifier.size(iconSize) else Modifier.liveControlSize(iconSizeReader)) {
            val progress = if (reduced) { if (playing) 1f else 0f } else pause
            val cycle = if (reduced) 0f else travel.value
            val shownTint = tintReader?.invoke() ?: tint
            val color = shownTint.copy(alpha = shownTint.alpha * opacity)
            if (morph != null) drawPackPlayPause(progress, color, morph, painter, pausePainter)
            else if (custom) drawTransportPainters(glyph, progress, cycle, color, painter, pausePainter)
            else drawTransportGlyph(glyph, progress, cycle, color, shapes)
        }
    }
}
