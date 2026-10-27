package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ActionStripDrawingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun adjacentStripsAdmitEveryButtonQuadrantInBothLayoutDirections() {
        var randomizations = 0
        var reversals = 0
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalReducedMotion provides true) {
                Column(Modifier.width(240.dp).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassActionRow {
                        GlassActionButton(R.drawable.ic_shuffle, "Randomize", onClick = { randomizations++ })
                    }
                    GlassActionRow {
                        GlassActionButton(R.drawable.ic_reverse, "Reverse", onClick = { reversals++ })
                    }
                }
            }
        } }
        var expected = 0
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection }
            for ((x, y) in listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f)) {
                nativeContact("Randomize", x, y)
                expected++
                compose.runOnIdle {
                    assertEquals("A neighboring decorative viewport must not steal DOWN", expected, randomizations)
                    assertEquals(0, reversals)
                }
            }
        }
    }

    @Test fun replayPreservesOriginalPaddedPixelsGeometryAndPresenceThroughExitReversalAndScroll() {
        var replay by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var drawnReplay: Boolean? = null
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalReducedMotion provides reduced) {
                val state = rememberScrollState()
                SideEffect { scroll = state }
                val inset = glassShadowInset()
                val drawing = rememberActionStripDrawing()
                Box(Modifier.size(280.dp, 180.dp).background(Color.White).testTag("comparison")
                    .drawWithContent { drawContent(); drawnReplay = replay }) {
                    // Keep exactly the same composed controls/Presence across the
                    // representation switch, including their prepared shadow caches.
                    Box(Modifier.align(Alignment.Center).width(216.dp)
                        .then(drawing.replay(inset) { replay }).graphicsLayer { clip = replay }) {
                        Row(Modifier.fillMaxWidth().shadowViewportOutset(inset, horizontal = true)
                            .then(drawing.record { replay })
                            .controlHorizontalScroll(state).padding(inset),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassActionButton(R.drawable.ic_shuffle, "Randomize", onClick = {})
                            GlassPresence(visible) {
                                GlassActionButton(R.drawable.ic_revert, "Revert", onClick = {})
                            }
                        }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1600)
        fun placedBounds(): androidx.compose.ui.geometry.Rect {
            val coordinates = compose.onNodeWithText("Randomize").fetchSemanticsNode().layoutInfo.coordinates
            return compose.runOnUiThread {
                assertTrue(coordinates.isAttached)
                androidx.compose.ui.geometry.Rect(coordinates.localToRoot(Offset.Zero),
                    coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
            }
        }
        fun parity(stage: String) {
            compose.runOnIdle { replay = false }
            // The logical hit clip deliberately changes clipped semantics at
            // overflow edges. Actual placement and all presented pixels must agree.
            val geometry = placedBounds()
            val metrics = compose.runOnIdle { scroll.maxValue to scroll.viewportSize }
            val expected = compose.onNodeWithTag("comparison").captureToImage().toPixelMap()
            assertEquals("$stage: reference mode actually drew at the frozen clock", false, drawnReplay)
            compose.runOnIdle { replay = true }
            val actual = compose.onNodeWithTag("comparison").captureToImage().toPixelMap()
            assertEquals("$stage: replay mode actually drew at the same frozen clock", true, drawnReplay)
            assertEquals("$stage: native control geometry", geometry, placedBounds())
            compose.runOnIdle { assertEquals("$stage: identical scroll extent/footer inputs", metrics, scroll.maxValue to scroll.viewportSize) }
            assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
            var maximum = 0f
            var sum = 0f
            var ink = 0
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                val a = expected[x, y]; val b = actual[x, y]
                if (a.red < .8f || a.green < .8f || a.blue < .8f) ink++
                val delta = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))
                maximum = maxOf(maximum, delta); sum += delta
            }
            assertTrue("$stage: comparison contains actual material/glyphs", ink > 50)
            // Existing same-host shadow representation criterion, across ALL channels.
            assertTrue("$stage: padded body/shadow parity max=$maximum mean=${sum / (expected.width * expected.height)}",
                maximum < .04f && sum / (expected.width * expected.height) < .003f)
        }
        parity("rest")
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(80)
        parity("retained exit")
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(80)
        parity("interrupted reverse")
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        parity("overflow end")
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        compose.mainClock.advanceTimeByFrame()
        parity("RTL overflow")
        compose.runOnIdle { reduced = true; visible = false }
        compose.mainClock.advanceTimeByFrame()
        parity("reduced hidden")
    }

    private fun nativeContact(label: String, x: Float, y: Float) {
        val node = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().assertHasClickAction().fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
        val point = compose.runOnUiThread {
            assertTrue(coordinates.isAttached && view.isAttachedToWindow && view.hasWindowFocus())
            val local = Offset(coordinates.size.width * x, coordinates.size.height * y)
            assertTrue(node.boundsInRoot.contains(coordinates.localToRoot(local)))
            val screen = IntArray(2).also(view::getLocationOnScreen)
            val window = IntArray(2).also(view::getLocationInWindow)
            coordinates.localToWindow(local) + Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        var down = false
        try { send(MotionEvent.ACTION_DOWN); down = true; send(MotionEvent.ACTION_UP); down = false }
        finally { if (down) send(MotionEvent.ACTION_CANCEL) }
    }
}
