package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FlatNoneMaterialTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun sourcePresenceDoesNotChangePressDragAndReleaseGeometry() {
        val geometry = mutableMapOf<String, LensGeometry>()
        var normalValue by mutableFloatStateOf(.5f)
        var flatValue by mutableFloatStateOf(.5f)
        var normalChecked by mutableStateOf(false)
        var flatChecked by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider( LocalDensity provides Density(1f)) {
            LocalicaTheme {
                source = rememberFrostedBackdrop(Unit, dark = false)
                BackdropLease(source)
                Box(Modifier.size(300.dp, 240.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFFE0E0E0)) }
                    Column {
                        CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["normal-slider"] = it }) {
                                InterfaceSlider(normalValue, { normalValue = it }, Modifier.width(280.dp).testTag("normal-slider"))
                            }
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["normal-switch"] = it }) {
                                Box(Modifier.size(60.dp, 48.dp).testTag("normal-switch")) { InterfaceSwitch(normalChecked) { normalChecked = it } }
                            }
                        }
                        CompositionLocalProvider( LocalFrostedBackdrop provides null) {
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["flat-slider"] = it }) {
                                InterfaceSlider(flatValue, { flatValue = it }, Modifier.width(280.dp).testTag("flat-slider"))
                            }
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["flat-switch"] = it }) {
                                Box(Modifier.size(60.dp, 48.dp).testTag("flat-switch")) { InterfaceSwitch(flatChecked) { flatChecked = it } }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            for (kind in listOf("slider", "switch")) {
                fun trace(prefix: String): List<LensGeometry> {
                    compose.mainClock.advanceTimeBy(1200)
                    val frames = mutableListOf<LensGeometry>()
                    fun receipt() = compose.runOnIdle { frames.add(requireNotNull(geometry["$prefix-$kind"])) }
                    receipt()
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { down(center) }
                    repeat(12) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    compose.runOnIdle { assertTrue("$prefix $kind must expand on contact", frames.last().bounds.height > 28f) }
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { moveTo(Offset(width * .8f, centerY)) }
                    repeat(8) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { up() }
                    repeat(40) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    return frames
                }
                // Two simultaneous pointers trigger the controls' multi-touch cancellation.
                // Replay one identical single-pointer contact per control instead.
                val expected = trace("normal")
                val actual = trace("flat")
                expected.zip(actual).forEachIndexed { frame, (normalFrame, flatFrame) ->
                    assertEquals("$kind frame $frame bounds", normalFrame.bounds, flatFrame.bounds)
                    assertEquals("$kind frame $frame radius", normalFrame.corner, flatFrame.corner, 0f)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun redrawKeepsUnsavedContentMounted() {

        var mounts = 0
        var caption by mutableStateOf("Scene")
        compose.setContent {
                LocalicaTheme {
                    androidx.compose.material3.Text(caption)
                    remember { mounts++; Any() }
                    var draft by remember { mutableStateOf("") }
                    androidx.compose.material3.OutlinedTextField(draft, { draft = it }, Modifier.testTag("atlas-draft"))
                }
         }
        compose.onNodeWithTag("atlas-draft").performTextInput("Unsaved mode change")
        for (update in 0..1) {
            compose.runOnIdle { caption = "Scene $update" }
            compose.onNodeWithTag("atlas-draft").assertTextContains("Unsaved mode change")
            compose.runOnIdle { assertEquals("Mode changes cannot recreate window content", 1, mounts) }
        }
    }

    }
