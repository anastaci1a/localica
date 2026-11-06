package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Exercise the real panel finish without lens sampling or child shadows. Compare each
 * wall with nearby coating: changing aspect must not stretch a corner glint into a strip. */
class PopupRimTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortPanelKeepsItsGlintsOnCurvedCorners() = verify(280, 120)
    @Test fun tallPanelUsesTheSameFinishWithoutStraightEdgeBands() = verify(120, 280)

    private fun verify(width: Int, height: Int) {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            LocalicaTheme { Box(Modifier.size(width.dp, height.dp).testTag("rim")) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF24262A))
                }
                LiquidGlassSurface(Modifier.fillMaxSize(), source = null, cornerRadius = 28.dp,
                    panelProgress = 1f, panelDark = true, inkOverride = Color.White,
                    refraction = 0f, shadow = false, pressFeedback = false)
                PresentationMarker { 1 }
            } }
        }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("rim"), 1) { view }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val file = File(root, "popup-rim-$width-$height-api${Build.VERSION.SDK_INT}.png")
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() },
                0, pixels.width, 0, 0, pixels.width, pixels.height)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val density = compose.density.density
        val radius = 28f * density
        val inset = (0.6f * density).roundToInt().coerceAtLeast(1)
        val start = (radius + 4f * density).roundToInt()
        val inner = (6f * density).roundToInt()
        fun brightness(x: Int, y: Int) = pixels[x, y].let { (it.red + it.green + it.blue) / 3f }
        val flat = buildList {
            for (x in start until pixels.width - start) {
                add(brightness(x, inset) - brightness(x, inner))
                add(brightness(x, pixels.height - 1 - inset) - brightness(x, pixels.height - 1 - inner))
            }
            for (y in start until pixels.height - start) {
                add(brightness(inset, y) - brightness(inner, y))
                add(brightness(pixels.width - 1 - inset, y) - brightness(pixels.width - 1 - inner, y))
            }
        }
        assertTrue("Flat walls must not carry a corner highlight: ${flat.max()}", flat.max() < .055f)
        val corner = (radius * (1f - 1f / sqrt(2f)) + inset / sqrt(2f)).roundToInt()
        assertTrue("Retain the intentional top-left glint", brightness(corner, corner) > brightness(corner + inner, corner + inner) + .12f)
        assertTrue("Retain the opposing bottom-right glint",
            brightness(pixels.width - 1 - corner, pixels.height - 1 - corner) >
                brightness(pixels.width - 1 - corner - inner, pixels.height - 1 - corner - inner) + .12f)
    }
}
