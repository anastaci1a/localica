package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassOpticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingDepthBendsARecordedGridAndKeepsTheOutsideUntouched() {
        var depth by mutableFloatStateOf(.6f)
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalGlassAppearance provides
            GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f, depth = depth)) {
            Box(Modifier.size(320.dp, 420.dp).testTag("optics")) {
                source = rememberFrostedBackdrop(Unit)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val cell = 16.dp.toPx()
                    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt())
                        drawRect(if ((x + y) % 2 == 0) Color(0xFF2A6275) else Color(0xFFE4B695), Offset(x * cell, y * cell), Size(cell, cell))
                }
                Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LiquidGlassSurface(Modifier.size(260.dp, 64.dp), source, pressFeedback = false) { Text("Curved pill", Modifier.align(Alignment.Center)) }
                    LiquidGlassSurface(Modifier.size(96.dp), source, pressFeedback = false) { Text("Lens", Modifier.align(Alignment.Center)) }
                    LiquidGlassSurface(Modifier.size(260.dp, 160.dp), source, cornerRadius = 24.dp, pressFeedback = false) {
                        Text("Panel crown", Modifier.align(Alignment.Center))
                    }
                }
            }
        } } }
        val node = compose.onNodeWithTag("optics")
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.lensSnapshot != null }
        val before = node.captureToImage().toPixelMap()
        compose.runOnIdle { depth = 2f }
        val image = node.captureToImage()
        val after = image.toPixelMap()
        var changed = 0
        for (y in 0 until before.height step 2) for (x in 0 until before.width step 2) {
            if (kotlin.math.abs(before[x, y].red - after[x, y].red) > .08f) changed++
        }
        assertTrue("Depth must change spatial refraction", changed > 100)
        for (x in 0 until before.width step 4) assertEquals("No lens writes beyond its clipped outline", before[x, 4], after[x, 4])
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "glass-optics-review.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
