package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Identical fixture on both comparison arms; exports exact geometry, alpha and opaque-content pixels. */
class SettingsSearchEntryAdoptionTest {
    @get:Rule val compose = localicaComposeRule()
    private lateinit var focus: SettingsSearchFocus
    private var mounted by mutableStateOf(true)
    private var replacement by mutableIntStateOf(0)
    private fun output(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(nativeEvidenceDirectory("settings-search-entry-A", context.getExternalFilesDir(null)),
            "api${Build.VERSION.SDK_INT}-$name")
    }
    private fun show(reduced: Boolean, speed: Float, rtl: Boolean = false, font: Float = 1f) {
        compose.setContent { LocalicaTheme {
            val device = LocalDensity.current
            val scroll = rememberScrollState()
            focus = remember(replacement) { SettingsSearchFocus("Glass", scroll) }
            CompositionLocalProvider(LocalSettingsSearchFocus provides focus, LocalReducedMotion provides reduced, LocalMotionSpeed provides speed,
                LocalDensity provides Density(device.density, font),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Magenta)) {
                    Box(Modifier.width(300.dp)) {
                        if (mounted) SettingsEntry("Glass renderer") {
                            Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) {
                                Text("Opaque settings row", color = Color.Black)
                                SettingsEntry("Glass appearance") { Text("Nested content", color = Color.Black) }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun row() = compose.onNodeWithTag("setting:Glass renderer", useUnmergedTree = true)
    private fun alpha() = row().fetchSemanticsNode().config[SettingsHighlightAlpha]
    private fun sample(samples: JSONArray, start: Long) {
        val node = row().fetchSemanticsNode()
        val bounds = node.boundsInRoot
        samples.put(JSONObject().put("elapsedMs", compose.mainClock.currentTime - start)
            .put("alpha", node.config[SettingsHighlightAlpha].toDouble())
            .put("left", bounds.left.toDouble()).put("top", bounds.top.toDouble())
            .put("right", bounds.right.toDouble()).put("bottom", bounds.bottom.toDouble()))
    }
    private fun pulse(name: String, reduced: Boolean, speed: Float, rtl: Boolean = false, font: Float = 1f) {
        show(reduced, speed, rtl, font)
        assertEquals(0f, alpha(), 0f)
        compose.onNodeWithTag("setting:Glass appearance", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(setOf("Glass renderer"), focus.anchors.keys.toSet())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focus.highlighted = "Glass renderer" }
        val start = compose.mainClock.currentTime
        val samples = JSONArray()
        for (target in listOf(32L, 80L, 160L, 320L, 640L, 800L, 1040L, 1440L)) {
            compose.mainClock.advanceTimeBy(start + target - compose.mainClock.currentTime)
            compose.waitForIdle()
            sample(samples, start)
            if (target == 320L) {
                assertEquals(.18f, alpha(), .001f)
                row().assertIsFocused()
                row().captureToImage().asAndroidBitmap().let { bitmap ->
                    output("$name-peak.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        assertEquals(0f, alpha(), 0f)
        output("$name.json").writeText(JSONObject().put("samples", samples).toString(2))
        // Cancelling a new pulse must clear it immediately, without retaining the old delay/fade.
        compose.runOnIdle { focus.highlighted = null }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { focus.highlighted = "Glass renderer" }
        compose.mainClock.advanceTimeBy(80)
        assertTrue(alpha() > 0f)
        compose.runOnIdle { focus.highlighted = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0f, alpha(), 0f)
        val old = focus
        compose.runOnIdle { replacement++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("Replacing the registration retires the old owner", old.anchors.isEmpty())
        assertTrue("The current callback registers the replacement owner", focus.anchors.containsKey("Glass renderer"))
        compose.runOnIdle { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("Disposal removes the current anchor", focus.anchors.isEmpty())
        compose.mainClock.autoAdvance = true
    }
    @Test fun normalPulseKeepsExactTimingGeometryAndCancellation() = pulse("normal", false, 4f / 3f)
    @Test fun fasterRetainsTheIncomingHighlightTiming() = pulse("faster", false, 2f)
    @Test fun reducedKeepsItsLongerHoldAndImmediateReset() = pulse("reduced", true, 4f / 3f)
    @Test fun rtlAndLargeFontsKeepTheSameEntryAndOpaqueOverlay() = pulse("rtl-font2", true, 4f / 3f, true, 2f)
}
