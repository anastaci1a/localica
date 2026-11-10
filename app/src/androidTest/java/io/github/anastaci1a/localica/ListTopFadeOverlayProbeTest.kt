package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.listTopFade
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Test-only blend-order investigation. Method success means captures exist, not pixel acceptance.
 * Same coordinates and fixed clocks; no first-presented-frame or physical timing claim. */
@SdkSuppress(minSdkVersion = 33)
class ListTopFadeOverlayProbeTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun lightOpaqueBackdrop() = fixture(Color(0xFFF9F9FB), false)
    @Test fun darkOpaqueBackdrop() = fixture(Color(0xFF242528), false)
    @Test fun nonUniformBackdropNegativeControl() = fixture(Color(0xFFF9F9FB), true)

    private fun fixture(background: Color, nonUniform: Boolean) {
        var candidate by mutableStateOf(false)
        var strength by mutableFloatStateOf(1f)
        var shift by mutableFloatStateOf(0f)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val label = if (nonUniform) "nonuniform" else if (background.red > .5f) "light" else "dark"
        val out = File(base, "p1-overlay-probe-$label").apply { mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.1375f)) {
                Box(Modifier.requiredSize(173.dp, 227.dp).background(background).testTag("probe")) {
                    if (nonUniform) Canvas(Modifier.matchParentSize()) {
                        for (i in 0..32) drawRect(if (i % 2 == 0) Color.Blue else Color.Yellow,
                            Offset(i * 8.3f, 0f), Size(8.3f, size.height))
                    }
                    val fade = if (candidate) Modifier.overlayProbe(background) { strength }
                        else Modifier.listTopFade { strength }
                    Canvas(Modifier.matchParentSize().then(fade)) {
                        for (i in 0..24) {
                            val y = i * 11.7f + shift
                            drawRect(if (i % 2 == 0) Color.Red.copy(alpha = .61f) else Color.Cyan.copy(alpha = .37f),
                                Offset((i % 5) * 3.25f, y), Size(size.width * .83f, 17.3f))
                            drawRect(Color.Black.copy(alpha = .43f), Offset(size.width * .21f, y + 5.25f),
                                Size(size.width * .66f, 1.1f))
                        }
                    }
                }
            }
        }
        try {
            fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            fun save(name: String) {
                val image = compose.onNodeWithTag("probe").captureToImage()
                File(out, "$name.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(out, "$name.json").writeText(JSONObject().put("candidate", candidate)
                    .put("strength", strength).put("shift", shift).put("clockMs", compose.mainClock.currentTime)
                    .put("nonUniformBackdrop", nonUniform).put("scope", "settled native fixture; no tolerance or acceptance")
                    .toString(2))
            }
            for ((index, value) in listOf(1f, .5f, 0f, .25f, 1f).withIndex()) {
                compose.runOnIdle { strength = value; shift = if (index % 2 == 0) 0f else -3.25f }
                for ((arm, selected) in listOf("baseline" to false, "candidate" to true, "repeat" to false)) {
                    compose.runOnIdle { candidate = selected }
                    repeat(8) { frame() }
                    save("$index-$arm")
                }
            }
            assertEquals("Each of the five states has three independently saved captures", 15,
                out.listFiles().orEmpty().count { it.extension == "png" })
        } finally { compose.mainClock.autoAdvance = true }
    }

    /** Valid only with a separately proved uniform opaque backdrop; never a production path. */
    private fun Modifier.overlayProbe(background: Color, strength: () -> Float): Modifier = drawWithCache {
        val band = 80.dp.toPx().coerceAtMost(size.height)
        onDrawWithContent {
            drawContent()
            val fade = strength().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            if (fade == 0f || band <= 0f) return@onDrawWithContent
            val brush = Brush.verticalGradient(*Array(17) { i ->
                val t = i / 16f
                val smooth = t * t * (3f - 2f * t)
                val mask = if (fade == 1f) smooth else 1f + (smooth - 1f) * fade
                t to background.copy(alpha = 1f - mask)
            }, endY = band)
            drawRect(brush, size = Size(size.width, band))
        }
    }
}
