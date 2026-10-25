package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MissingLyricsMotionTest {
    @get:Rule val compose = createComposeRule()

    private fun emptyCenter(): Offset {
        val background = compose.onNodeWithTag("player-background-space", true).fetchSemanticsNode().boundsInRoot
        return compose.onNodeWithTag("empty-lyrics", true).fetchSemanticsNode().boundsInRoot.center - background.topLeft
    }

    @Test fun emptyDecorationStaysMountedDuringOpeningClosingAndInterruptedTransitionsInBothLayouts() {
        var settings by mutableStateOf(InterfaceSettings())
        var open by mutableStateOf(true)
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(PlaybackState(connected = true, title = "No lyrics"), true, {}, settings,
                remember { Animatable(1f) }, {}, {}, {}, {}, {}, {}, lyricsOpen = open, onLyrics = { open = it })
        } } }
        try {
            for (layout in ArtworkLayout.entries) {
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { settings = settings.copy(artworkLayout = layout); open = true }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("empty-lyrics", true).fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                val expected = emptyCenter()
                val settledTitle = compose.onNodeWithTag("player-title", true).fetchSemanticsNode().boundsInRoot.top
                compose.mainClock.autoAdvance = false
                fun sample(frames: Int) {
                    repeat(frames) {
                        compose.mainClock.advanceTimeBy(32)
                        val center = emptyCenter()
                        assertEquals("Background-relative x in $layout", expected.x, center.x, 1f)
                        assertEquals("Background-relative y in $layout", expected.y, center.y, 1f)
                    }
                }
                compose.runOnIdle { open = false }
                sample(4)
                assertTrue("The test samples an actual metadata transition", kotlin.math.abs(settledTitle -
                    compose.onNodeWithTag("player-title", true).fetchSemanticsNode().boundsInRoot.top) > 2f)
                compose.runOnIdle { open = true }
                sample(6)
                compose.mainClock.advanceTimeBy(1000)
                compose.runOnIdle { open = false }
                sample(4)
                compose.mainClock.advanceTimeBy(1000)
                compose.onNodeWithTag("empty-lyrics", true).assertDoesNotExist()
                compose.runOnIdle { open = true }
                // The panel remounts after a complete exit; await its single source load while advancing frames.
                compose.waitUntil(5000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag("empty-lyrics", true).fetchSemanticsNodes().isNotEmpty()
                }
                sample(6)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun lyricLinesKeepTheirAnimatedViewportAndReducedMotionSongChangesKeepOneEmptyState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "missing-lyrics-motion.opus")
        instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
        val withLyrics = Track(android.net.Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name)
        val emptyFixture = NoLyricsFixture("Missing")
        val missing = emptyFixture.track
        var track by mutableStateOf(withLyrics)
        var open by mutableStateOf(true)
        var settings by mutableStateOf(InterfaceSettings())
        try {
            compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                    true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                    lyricsOpen = open, onLyrics = { open = it }, track = track)
            } } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("lyric-lines").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val panel = compose.onNodeWithTag("lyrics-panel").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(128)
            val movingPanel = compose.onNodeWithTag("lyrics-panel").fetchSemanticsNode().boundsInRoot
            val movingTitle = compose.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot
            assertEquals("The clip top stays fixed while metadata moves", panel.top, movingPanel.top, 1f)
            compose.onNodeWithTag("lyrics-panel").assertHeightIsAtLeast(1.dp)
            assertTrue("The clip bottom, not the content layout, follows metadata", movingPanel.height < panel.height)
            assertTrue("The viewport remains above the presented title", movingPanel.bottom < movingTitle.top)
            assertEquals("The actual lyric viewport shares the clip", movingPanel,
                compose.onNodeWithTag("lyrics-viewport").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { open = true; settings = settings.copy(reducedMotion = true); track = missing }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10000) { compose.onAllNodesWithTag("empty-lyrics", true).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("lyric-lines").assertDoesNotExist()
            val center = emptyCenter()
            compose.runOnIdle { track = missing.copy(id = "second-missing-song") }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("empty-lyrics", true).fetchSemanticsNodes().size == 1 }
            assertEquals(center, emptyCenter())
            compose.runOnIdle { open = false }
            compose.onNodeWithTag("lyrics-panel").assertDoesNotExist()
            compose.runOnIdle { open = true; track = withLyrics }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("lyric-lines").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("empty-lyrics", true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true; emptyFixture.close(); file.delete() }
    }
}
