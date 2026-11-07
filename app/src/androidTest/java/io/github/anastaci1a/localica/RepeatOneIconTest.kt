package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.queue.QueueRepeat
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RepeatOneIconTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() { IconPacks.reset(context) }
    private fun repeatOnlyPack() = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        entry("pack.json", """{"name":"Repeat-only fixture","version":"1"}""")
        entry("icons/action.repeat.svg", """<svg viewBox="0 0 24 24"><path d="M2 2 H22 V22 H2 Z"/></svg>""")
    } }.toByteArray()
    private fun pixels(tag: String): List<Color> {
        val p = compose.onNodeWithTag(tag, useUnmergedTree = true).captureNativeFrame().toPixelMap()
        return (0 until p.height).flatMap { y -> (0 until p.width).map { x -> p[x, y] } }
    }
    private fun same(expected: List<Color>, actual: List<Color>, message: String) {
        assertEquals(expected.size, actual.size)
        val error = expected.indices.sumOf { i ->
            kotlin.math.abs(expected[i].red - actual[i].red).toDouble() +
                kotlin.math.abs(expected[i].green - actual[i].green) + kotlin.math.abs(expected[i].blue - actual[i].blue)
        } / (expected.size * 3)
        assertTrue("$message; mean channel error=$error", error < .005)
    }
    @Test fun oneUsesDedicatedFallbackWithoutTextAndReversesContinuously() {
        IconPacks.import(context, repeatOnlyPack().inputStream())
        assertNull(IconPacks.images.value.vectors["action.repeat_one"])
        var repeat by mutableStateOf(QueueRepeat.All)
        compose.setContent { LocalicaTheme {
            Column {
                Box(Modifier.size(24.dp).background(Color.White).testTag("repeat-frame")) {
                    PlayerActionGlyph(PlayerAction.Repeat, "Repeat mode", repeat)
                }
                Box(Modifier.size(24.dp).background(Color.White).testTag("repeat-reference")) {
                    Icon(packIconPainter(AppIcon.ActionRepeatOne, null), null, Modifier.size(24.dp))
                }
            }
        } }
        compose.waitForIdle()
        val all = pixels("repeat-frame")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { repeat = QueueRepeat.One }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        val middle = pixels("repeat-frame")
        assertNotEquals("Transition cannot swap directly to either endpoint", all, middle)
        compose.runOnIdle { repeat = QueueRepeat.All }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val reversed = pixels("repeat-frame")
        assertNotEquals(all, reversed)
        compose.runOnIdle { repeat = QueueRepeat.One }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(600)
        val one = pixels("repeat-frame")
        assertNotEquals(all, one); assertNotEquals(middle, one)
        same(pixels("repeat-reference"), one, "Missing pack glyph uses the original dedicated vector")
        compose.onNodeWithText("1", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Repeat mode").assertCountEquals(1)
        compose.onNodeWithTag("repeat-one-glyph", useUnmergedTree = true).assertExists()
        compose.runOnIdle { repeat = QueueRepeat.Off }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(600)
        same(all, pixels("repeat-frame"), "Off and All share the repeat glyph")
        compose.mainClock.autoAdvance = true
    }
}
