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
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer

internal val LocalGlassAppearance = compositionLocalOf { GlassAppearance() }

@Composable
internal fun GlassSettingsControls(value: GlassAppearance, onChange: (GlassAppearance) -> Unit) {
    Text("Tune floating glass and the navigation selector.")
    GlassRendererPreference(value.renderer) { onChange(value.copy(renderer = it)) }
    BooleanPreference("Live glass while scrolling", value.liveScrollGlass) { onChange(value.copy(liveScrollGlass = it)) }
    Text("Off holds glass controls on the last frame until scrolling stops, which scrolls more smoothly. On re-blurs the moving list every frame. Affects the Modern renderer.", style = MaterialTheme.typography.bodySmall)
    var previewLightInk by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalGlassAppearance provides value) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            val source = rememberFrostedBackdrop(Unit, dark = previewLightInk)
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
    GlassChoices(listOf("Dark icons", "White icons"), if (previewLightInk) 1 else 0, horizontal = true) { previewLightInk = it == 1 }
    GlassSlider("Blur", value.blur, "Clear", "Frosted") { onChange(value.copy(blur = it)) }
    GlassSlider("White tint", value.whiteTint, "None", "White") { onChange(value.copy(whiteTint = it)) }
    GlassSlider("Shadow strength", value.shadowStrength, "Off", "Strong") { onChange(value.copy(shadowStrength = it)) }
    GlassSlider("Shadow spread", value.shadowSpread, "Tight", "Broad") { onChange(value.copy(shadowSpread = it)) }
    SettingsHeading("Adaptive contrast")
    Text("Compress background brightness away from the page’s icon color.", style = MaterialTheme.typography.bodySmall)
    GlassSlider("Brightness trim", value.contrast, "Off", "50%", maximum = .5f) { onChange(value.copy(contrast = it)) }
    SliderPreference("Brightness weighting", value.contrastWeight, { onChange(value.copy(contrastWeight = it)) },
        Modifier.semantics { contentDescription = "Brightness weighting" }, valueRange = 1f..2f, steps = 19,
        low = "Even", high = "Highlights / shadows")
    SettingsHeading("Shape and motion")
    Text("Hold the preview to flatten it; release to let it rebound.", style = MaterialTheme.typography.bodySmall)
    SliderPreference("Shape damping · ${value.shapeDamping.roundToInt()}", value.shapeDamping, { onChange(value.copy(shapeDamping = it)) },
        Modifier.semantics { contentDescription = "Shape damping" }, valueRange = 2f..40f,
        description = "Lower is squishier; higher settles sooner.")
    SliderPreference("Resting depth · ${"%.2f".format(java.util.Locale.ROOT, value.depth)}", value.depth, { onChange(value.copy(depth = it)) },
        Modifier.semantics { contentDescription = "Resting depth" }, valueRange = .6f..2f)
    SettingsHeading("Navigation selector")
    ChoicePreference("Selector style", value.tabStyle, io.github.anastaci1a.localica.navigation.TabLensStyle.entries, { it.label }) {
        onChange(value.copy(tabStyle = it))
    }
    if (value.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Adjustable) {
        GlassSlider("Selector blur", value.tabBlur, "Clear", "Frosted") { onChange(value.copy(tabBlur = it)) }
        GlassSlider("Selector white tint", value.tabWhiteTint, "None", "White") { onChange(value.copy(tabWhiteTint = it)) }
    } else if (value.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Classic) {
        GlassSlider("Gray pill opacity", value.classicOpacity, "Clear", "Dark") { onChange(value.copy(classicOpacity = it)) }
    } else {
        GlassSlider("Overlay darkening", value.overlayDarkening, "None", "50%", maximum = .5f) {
            onChange(value.copy(overlayDarkening = it))
        }
        BooleanPreference("Refract icons and labels", value.overlayForeground) { onChange(value.copy(overlayForeground = it)) }
    }
    SettingsHeading("Sliders and switches")
    Text("White at rest; clear glass while held.", style = MaterialTheme.typography.bodySmall)
    SliderPreference("Control expansion · ${"%.2f".format(java.util.Locale.ROOT, value.controlExpansion)}×", value.controlExpansion,
        { onChange(value.copy(controlExpansion = it)) }, Modifier.semantics { contentDescription = "Control expansion" },
        valueRange = 1f..2f, steps = 19, low = "1×", high = "2×")
    ActionTextButton(onClick = { onChange(GlassAppearance()) }) { Text("Reset glass appearance") }
}

@Composable
internal fun GlassRendererPreference(value: GlassRendererMode, onChange: (GlassRendererMode) -> Unit) {
    val modernAvailable = android.os.Build.VERSION.SDK_INT >= 33
    val choices = if (modernAvailable) GlassRendererMode.entries else
        listOf(GlassRendererMode.Automatic, GlassRendererMode.Legacy)
    ChoicePreference("Glass renderer", value, choices, { it.label }, popup = true, onSelect = onChange)
    val explanation = when {
        value == GlassRendererMode.Modern && !modernAvailable ->
            "Modern is saved, but this Android version uses Legacy. Modern requires Android 13 or later."
        value == GlassRendererMode.Automatic ->
            "Automatic uses ${effectiveGlassBackend(value, android.os.Build.VERSION.SDK_INT).name} on this device."
        else -> "Using ${value.label} on this device."
    }
    Text(explanation, style = MaterialTheme.typography.bodySmall)
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
        ActionTextButton({ expanded = true }, Modifier.align(Alignment.BottomCenter)) { Text("Expand preview") }
        GlassMorphPopup(expanded, anchor, { expanded = false }, GlassPlacement.Center) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Glass preview", style = MaterialTheme.typography.titleLarge)
                Text("The same surface expands into this panel. Your glass and motion settings apply here too.")
                ActionTextButton({ expanded = false }, Modifier.align(Alignment.End)) { Text("Collapse preview") }
            }
        }
    }
}

@Composable
private fun GlassSlider(label: String, value: Float, low: String, high: String, maximum: Float = 1f, onChange: (Float) -> Unit) {
    SliderPreference("$label · ${(value * 100).roundToInt()}%", value, onChange,
        Modifier.semantics { contentDescription = label }, valueRange = 0f..maximum,
        steps = (maximum / .05f).roundToInt() - 1, low = low, high = high)
}
