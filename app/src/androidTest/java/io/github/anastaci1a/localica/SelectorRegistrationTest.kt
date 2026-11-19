package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native sampling and contact invariants for the deformation shared with SelectionLens. */
class SelectorRegistrationTest {
    @get:Rule val compose = createComposeRule()

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun centerLandmarkStaysRegisteredThroughHorizontalAndVerticalDeformation() = registration(false)
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun centerLandmarkStaysRegisteredInsideOffsetModal() = registration(true)

    @Test fun deformationKeepsBothContactWallsPinnedOnEitherAxis() {
        var shape by mutableStateOf(GlassShape())
        var vertical by mutableStateOf(false)
        var wall by mutableFloatStateOf(-1f)
        var presentedStage by mutableIntStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(280.dp, 240.dp).testTag("contact-scene")) {
                Canvas(Modifier.align(Alignment.Center).size(220.dp, 120.dp)
                    .selectorDeformation({ shape }, vertical, { wall })) { drawRect(Color.Red) }
                PresentationMarker { presentedStage }
            }
        }
        for (axis in listOf(false, true)) for (edge in listOf(-1f, 1f)) {
            var restingEdge = 0
            for ((index, next) in listOf(GlassShape(), GlassShape(-.15f, -.2f), GlassShape(.04f, .08f)).withIndex()) {
                compose.runOnIdle { vertical = axis; wall = edge; shape = next; presentedStage++ }
                val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("contact-scene"), presentedStage) { view }
                val extent = if (axis) pixels.height else pixels.width
                val covered = (0 until extent).filter { position ->
                    val pixel = if (axis) pixels[pixels.width / 2, position] else pixels[position, pixels.height / 2]
                    pixel.red > .8f && pixel.green < .2f && pixel.blue < .2f
                }
                assertTrue("Visible body: vertical=$axis wall=$edge shape=$next", covered.isNotEmpty())
                val contact = if (edge < 0) covered.first() else covered.last()
                if (index == 0) restingEdge = contact
                else assertTrue("Pinned contact: vertical=$axis wall=$edge shape=$next expected=$restingEdge actual=$contact",
                    kotlin.math.abs(contact - restingEdge) <= 1)
            }
        }
    }

    private fun registration(modal: Boolean) {
        var shape by mutableStateOf(GlassShape())
        var vertical by mutableStateOf(false)
        var presentedStage by mutableIntStateOf(0)
        var readySource: FrostedBackdrop? = null
        var sourceShift by mutableStateOf(Offset.Zero)
        lateinit var sceneView: View
        compose.setContent { LocalicaTheme {
            @Composable fun Scene() {
                sceneView = LocalView.current
                val source = rememberFrostedBackdrop(Unit)
                readySource = source
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    // Offset both source and consumer inside their window. The same
                    // marker must survive a different Dialog screen origin as well.
                    Box(Modifier.padding(start = 17.dp, top = 29.dp, end = 11.dp, bottom = 13.dp)) {
                        Box(Modifier.size(280.dp, 240.dp).testTag("registration-scene")) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                                val cell = 10.dp.toPx()
                                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                                    drawRect(if ((x + y) % 2 == 0) Color.Blue else Color.Black,
                                        Offset(x * cell, y * cell), Size(cell, cell))
                                }
                                val marker = 12.dp.toPx()
                                drawRect(Color.Red, center + sourceShift - Offset(marker, marker), Size(marker * 2, marker * 2))
                            }
                            LiquidGlassSurface(Modifier.align(Alignment.Center).size(220.dp, 120.dp)
                                .selectorDeformation({ shape }, vertical).testTag("registration-lens"), pressFeedback = false, shadow = false, body = { shape })
                            PresentationMarker { presentedStage }
                        }
                    }
                }
            }
            if (modal) GlassMorphPopup(true, rememberGlassAnchor(), {}) { Scene() } else Scene()
        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { readySource?.snapshot != null }
        compose.waitForIdle()
        val momentum = GlassMomentum()
        repeat(180) { momentum.advance(0f, (1f / 60).toDouble(), pressure = -.15f, contact = 1f) }
        val stages = mutableListOf("rest" to GlassShape(), "held" to momentum.shape)
        repeat(32) { frame ->
            momentum.advance(0f, (1f / 60).toDouble())
            if (frame in listOf(0, 6, 12, 18, 27)) stages += "release-$frame" to momentum.shape
        }
        fun registrationError(pixels: androidx.compose.ui.graphics.PixelMap): Offset {
            val expected = Offset(pixels.width / 2f, pixels.height / 2f)
            val radius = with(compose.density) { 24.dp.toPx() }.toInt()
            fun centroid(horizontal: Boolean): Float {
                val middle = if (horizontal) expected.x else expected.y
                var weight = 0.0; var position = 0.0
                for (index in middle.toInt() - radius..middle.toInt() + radius) {
                    val p = if (horizontal) pixels[index, expected.y.toInt()] else pixels[expected.x.toInt(), index]
                    val amount = (p.red - p.green).coerceAtLeast(0f).toDouble()
                    weight += amount; position += (index + .5) * amount
                }
                assertTrue("The independent landmark centroid has red signal", weight > 0)
                return (position / weight - middle).toFloat()
            }
            return Offset(centroid(true), centroid(false))
        }
        val failures = mutableListOf<String>()
        for (axis in listOf(false, true)) for ((stage, next) in stages) {
            compose.runOnIdle { vertical = axis; shape = next; presentedStage++ }
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("registration-scene"), presentedStage) { sceneView }
            var red = 0f; var blue = 0f
            for (y in pixels.height / 2 - 1..pixels.height / 2 + 1)
                for (x in pixels.width / 2 - 1..pixels.width / 2 + 1) {
                    red += pixels[x, y].red; blue += pixels[x, y].blue
                }
            val error = registrationError(pixels)
            assertTrue("Independent source-center registration stays within one physical pixel: $error", kotlin.math.abs(error.x) <= 1f && kotlin.math.abs(error.y) <= 1f)
            if (stage == "rest" || stage == "held") {
                for (shift in listOf(Offset(2f, 0f), Offset(-2f, 0f), Offset(0f, 2f), Offset(0f, -2f))) {
                    compose.runOnIdle { sourceShift = shift; presentedStage++ }
                    val displaced = capturePresentedPopupPixels(compose, compose.onNodeWithTag("registration-scene"), presentedStage) { sceneView }
                    val wrong = registrationError(displaced)
                    assertTrue("A displaced source must fail the unchanged one-pixel coordinate budget: $shift -> $wrong",
                        kotlin.math.abs(wrong.x) > 1f || kotlin.math.abs(wrong.y) > 1f)
                }
                compose.runOnIdle { sourceShift = Offset.Zero; presentedStage++ }
                val zero = registrationError(capturePresentedPopupPixels(compose, compose.onNodeWithTag("registration-scene"), presentedStage) { sceneView })
                assertTrue("Restored source-center registration accepts zero: $zero", kotlin.math.abs(zero.x) <= 1f && kotlin.math.abs(zero.y) <= 1f)
            }
            val bounds = compose.onNodeWithTag("registration-lens").fetchSemanticsNode().boundsInRoot
            val result = "modal=$modal vertical=$axis stage=$stage strain=${next.strain} depth=${next.depth} " +
                "lensBounds=$bounds sourceOrigin=${readySource?.origin} sourceSize=${readySource?.size} " +
                "red=${red / 9} blue=${blue / 9}"
            android.util.Log.i("SelectorRegistration", result)
            if (!(red / 9 > .6f && blue / 9 < .3f)) failures += result
        }
        assertTrue("Symmetric lens center must retain its red landmark at every stage:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

}
