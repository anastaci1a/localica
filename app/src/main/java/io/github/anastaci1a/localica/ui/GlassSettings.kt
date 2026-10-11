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

internal val LocalGlassAppearance = compositionLocalOf { GlassAppearance() }

@Composable
internal fun GlassSettingsControls(value: GlassAppearance, onChange: (GlassAppearance) -> Unit) {
    Text("Tune floating glass throughout the app. Text and icons stay sharp.")
    CompositionLocalProvider(LocalGlassAppearance provides value) {
        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(Color(0xFFBCDCE7))
                val stripe = 12.dp.toPx()
                for (i in 0..(size.width / stripe).toInt()) {
                    drawRect(if (i % 2 == 0) Color(0xFF729EB6) else Color(0xFFE1AB94),
                        Offset(i * stripe, 0f), Size(stripe, size.height))
                }
            }
            LiquidGlassSurface(Modifier.size(220.dp, 64.dp), source) {
                Text("Glass preview", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    GlassSlider("Blur", value.blur, "Clear", "Frosted") { onChange(value.copy(blur = it)) }
    GlassSlider("White tint", value.whiteTint, "None", "White") { onChange(value.copy(whiteTint = it)) }
    GlassSlider("Shadow strength", value.shadowStrength, "Off", "Strong") { onChange(value.copy(shadowStrength = it)) }
    GlassSlider("Shadow spread", value.shadowSpread, "Tight", "Broad") { onChange(value.copy(shadowSpread = it)) }
    TextButton(onClick = { onChange(GlassAppearance()) }) { Text("Reset glass appearance") }
}

@Composable
private fun GlassSlider(label: String, value: Float, low: String, high: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        InterfaceSlider(value, onChange, Modifier.semantics { contentDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.bodySmall)
            Text(high, style = MaterialTheme.typography.bodySmall)
        }
    }
}
