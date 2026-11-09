package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Private shared-controls fixture for agent80. Never a production route. */
@Composable
internal fun NoneMaterialControls(dark: Boolean) {
    val source = rememberFrostedBackdrop(Unit, dark = dark)
    var position by remember { mutableFloatStateOf(.2f) }
    var checked by remember { mutableStateOf(false) }
    GlassPanelTheme(dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(if (dark) Color(0xFF242528) else Color(0xFFF9F9FB))
                val cell = 32.dp.toPx()
                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                    val color = if ((x+y)%2 == 0) { if (dark) Color(0xFF071D3F) else Color(0xFFFF2D55) }
                        else { if (dark) Color(0xFF266B88) else Color(0xFFFFF2D8) }
                    drawRect(color.copy(alpha=.45f), Offset(x*cell,y*cell), Size(cell,cell))
                }
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("None material · shared controls", style=MaterialTheme.typography.titleLarge)
                    Text(if (dark) "Dark theme / contrasting source" else "Light theme / contrasting source")
                    Text("Slider · ${(position*100).toInt()}%")
                    InterfaceSlider(position, { position = it }, Modifier.fillMaxWidth())
                    BooleanPreference("Switch", checked) { checked = it }
                    Text("Disabled action / slider")
                    ActionTextButton({}, enabled=false) { Text("Unavailable") }
                    InterfaceSlider(.65f, {}, Modifier.fillMaxWidth(), enabled=false)
                    ActionTextButton({}) { Text("Enabled action") }
                    Text("Shared shapes, ink, shadow and rim. Synthetic source artwork.", style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
