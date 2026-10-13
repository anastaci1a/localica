package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer

internal val LocalGlassAppearance = compositionLocalOf { GlassAppearance() }

@Composable
internal fun GlassSettingsControls(value: GlassAppearance, onChange: (GlassAppearance) -> Unit) {
    Text("Tune floating glass and the navigation selector.")
    CompositionLocalProvider(LocalGlassAppearance provides value) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                val light = listOf(Color(0xFFF1D8BC), Color(0xFFBCD9CE), Color(0xFFCDC8E5), Color(0xFFE9B9AC))
                val dark = listOf(Color(0xFF294D60), Color(0xFF68566F), Color(0xFF3C6962), Color(0xFF8B514D))
                val cell = 24.dp.toPx()
                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                    val palette = if ((x + y) % 2 == 0) light else dark
                    drawRect(palette[(x / 2 + y) % palette.size], Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
            GlassPreview(source)
        }
    }
    GlassSlider("Blur", value.blur, "Clear", "Frosted") { onChange(value.copy(blur = it)) }
    GlassSlider("White tint", value.whiteTint, "None", "White") { onChange(value.copy(whiteTint = it)) }
    GlassSlider("Shadow strength", value.shadowStrength, "Off", "Strong") { onChange(value.copy(shadowStrength = it)) }
    GlassSlider("Shadow spread", value.shadowSpread, "Tight", "Broad") { onChange(value.copy(shadowSpread = it)) }
    GlassSlider("Adaptive contrast", value.contrast, "Off", "Strong") { onChange(value.copy(contrast = it)) }
    SettingsHeading("Shape and motion")
    Text("Hold the preview to flatten it; release to let it rebound.", style = MaterialTheme.typography.bodySmall)
    Text("Shape damping · ${value.shapeDamping.roundToInt()}", style = MaterialTheme.typography.titleMedium)
    InterfaceSlider(value.shapeDamping, { onChange(value.copy(shapeDamping = it)) },
        Modifier.semantics { contentDescription = "Shape damping" }, valueRange = 2f..40f)
    Text("Lower is squishier; higher settles sooner.", style = MaterialTheme.typography.bodySmall)
    Text("Resting depth · ${"%.2f".format(java.util.Locale.ROOT, value.depth)}", style = MaterialTheme.typography.titleMedium)
    InterfaceSlider(value.depth, { onChange(value.copy(depth = it)) },
        Modifier.semantics { contentDescription = "Resting depth" }, valueRange = .6f..2f)
    SettingsHeading("Navigation selector")
    ChoicePreference("Selector style", value.tabStyle, io.github.anastaci1a.localica.navigation.TabLensStyle.entries, { it.label }) {
        onChange(value.copy(tabStyle = it))
    }
    if (value.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Adjustable) {
        GlassSlider("Selector blur", value.tabBlur, "Clear", "Frosted") { onChange(value.copy(tabBlur = it)) }
        GlassSlider("Selector white tint", value.tabWhiteTint, "None", "White") { onChange(value.copy(tabWhiteTint = it)) }
    } else Text(if (value.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Overlay)
        "The clear lens bends the bar beneath sharp labels. Selector blur and tint are saved but inactive."
        else "A simple gray pill, without glass deformation.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { onChange(GlassAppearance()) }) { Text("Reset glass appearance") }
}

@Composable
private fun GlassPreview(source: FrostedBackdrop) {
    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var raw by remember { mutableFloatStateOf(0f) }
    val anchor = rememberGlassAnchor()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val travel = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val limit = with(density) { ((maxWidth - 180.dp) / 2).toPx().coerceAtLeast(1f) }
        val body = rememberGlassBody({ travel.value / limit },
            pressure = { glassEdgePressure((raw - raw.coerceIn(-limit, limit)) / limit) },
            contact = { if (pressed) 1f else 0f })
        SideEffect { anchor.source = source; anchor.face = { Text("Drag preview") } }
        LiquidGlassSurface(Modifier.offset { androidx.compose.ui.unit.IntOffset(travel.value.toInt(), 0) }
            .size(180.dp, 56.dp).glassAnchor(anchor).observeGlassContact { pressed = it }
            .elasticControlDrag(true,
                onStart = { settling?.cancel(); raw = travel.value }, onDelta = { delta ->
                    raw += delta
                    scope.launch { travel.snapTo(raw.coerceIn(-limit, limit)) }
                }, onEnd = { _ -> raw = raw.coerceIn(-limit, limit)
                    settling = scope.launch { if (reduced) travel.snapTo(0f) else travel.animateTo(0f, glassMorphSpring(1f)); raw = 0f }
                }).graphicsLayer {
                    scaleX = body.value.scaleX(); scaleY = body.value.scaleY()
                    translationX = (1f - scaleX) * size.width / 2f * (travel.value / limit).coerceIn(-1f, 1f)
                }, source, pressFeedback = false, body = { body.value }) {
            anchor.captureInk()
            Text("Drag preview", Modifier.align(Alignment.Center))
        }
        TextButton({ expanded = true }, Modifier.align(Alignment.BottomCenter)) { Text("Expand preview") }
        GlassMorphPopup(expanded, anchor, { expanded = false }, GlassPlacement.Center) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Glass preview", style = MaterialTheme.typography.titleLarge)
                Text("The same surface expands into this panel. Your glass and motion settings apply here too.")
                TextButton({ expanded = false }, Modifier.align(Alignment.End)) { Text("Collapse preview") }
            }
        }
    }
}

@Composable
private fun GlassSlider(label: String, value: Float, low: String, high: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        InterfaceSlider(value, onChange, Modifier.semantics { contentDescription = label })
        SliderEndpoints(low, high)
    }
}
