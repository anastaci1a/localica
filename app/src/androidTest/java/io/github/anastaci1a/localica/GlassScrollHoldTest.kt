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
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Shared page recordings keep following scroll on every supported Android version. */
class GlassScrollHoldTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(val scene: BrowseBackdropScene)

    private fun mount(): Fixture {
        var scene: BrowseBackdropScene? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance()) {
                val scroll = rememberScrollState()
                val source = rememberFrostedBackdrop(Unit, dark = true, retainRaster = false)
                val page = rememberBrowseBackdropScene(source, null, Unit)
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
            // Measure after drag recognition.
            val begun = recordings(f)
            repeat(10) {
                compose.onNodeWithTag("scroll").performTouchInput { moveBy(Offset(0f, -30f)) }
                compose.mainClock.advanceTimeByFrame()
                body(begun)
            }
            compose.onNodeWithTag("scroll").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun liveGlassKeepsRecordingEveryScrollFrame() {
        val f = mount()
        var last = recordings(f)
        var advanced = 0
        scrollFrames(f) { _ ->
            val now = recordings(f)
            if (now > last) advanced++
            last = now
        }
        assertTrue("Live glass re-records while the page moves ($advanced frames)", advanced >= 5)
    }
}
