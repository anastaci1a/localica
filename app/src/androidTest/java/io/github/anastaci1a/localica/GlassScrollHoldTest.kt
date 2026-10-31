package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The page recording that Modern glass samples is held while scrolling unless live glass is enabled. */
class GlassScrollHoldTest {
    @get:Rule val compose = createComposeRule()
    private val holds = Build.VERSION.SDK_INT >= 33 // Legacy keeps its own coalesced refresh path.

    private class Fixture(val scene: BrowseBackdropScene)

    private fun mount(live: Boolean): Fixture {
        var scene: BrowseBackdropScene? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(liveScrollGlass = live)) {
                val scroll = rememberScrollState()
                val source = rememberFrostedBackdrop(Unit, dark = true, glassBlur = 0f, retainRaster = false, whiteTint = 0f)
                val page = rememberBrowseBackdropScene(source, null, Unit, scroll.isScrollInProgress)
                val viewport = rememberBrowseBackdropLayer(page, masked = true)
                SideEffect { scene = page }
                Box(Modifier.size(320.dp, 360.dp)) {
                    Column(Modifier.fillMaxSize().recordBrowseBackdropLayer(page, viewport).background(Color.Black)
                        .clipToBounds().testTag("scroll").verticalScroll(scroll)) {
                        repeat(80) { Text("Row $it", color = Color.White, modifier = Modifier.height(36.dp)) }
                    }
                    BrowseBackdropRecording(page, null)
                }
            }
        } }
        compose.waitUntil(10_000) { scene?.let { it.source.recorded && it.layers.all { l -> l.source.recorded } } == true }
        return Fixture(requireNotNull(scene))
    }

    private fun recordings(f: Fixture) = compose.runOnUiThread { f.scene.source.recordings }

    private fun scrollFrames(f: Fixture, body: (Int) -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("scroll").performTouchInput { down(Offset(width / 2f, height * .9f)) }
            repeat(6) {
                compose.onNodeWithTag("scroll").performTouchInput { moveBy(Offset(0f, -40f)) }
                compose.mainClock.advanceTimeByFrame()
            }
            // The hold is installed on the frame after scrolling begins; measure from here.
            val begun = recordings(f)
            repeat(10) {
                compose.onNodeWithTag("scroll").performTouchInput { moveBy(Offset(0f, -30f)) }
                compose.mainClock.advanceTimeByFrame()
                body(begun)
            }
            compose.onNodeWithTag("scroll").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun defaultOffDoesNotRerecordTheMovingPageAndRefreshesAfterScrolling() {
        val f = mount(false)
        scrollFrames(f) { begun ->
            assertTrue(compose.runOnUiThread { f.scene.frozen } == holds)
            if (holds) assertEquals("The held page is not re-recorded during scrolling", begun, recordings(f))
        }
        compose.waitUntil(10_000) { !compose.runOnUiThread { f.scene.frozen } }
        val held = recordings(f)
        compose.onNodeWithTag("scroll").performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { recordings(f) > held }
    }

    @Test fun liveGlassKeepsRecordingEveryScrollFrame() {
        val f = mount(true)
        var last = recordings(f)
        var advanced = 0
        scrollFrames(f) { _ ->
            assertFalse(compose.runOnUiThread { f.scene.frozen })
            val now = recordings(f)
            if (now > last) advanced++
            last = now
        }
        assertTrue("Live glass re-records while the page moves ($advanced frames)", advanced >= 5)
    }
}
