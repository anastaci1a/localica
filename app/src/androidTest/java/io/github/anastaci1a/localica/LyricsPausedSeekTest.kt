package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.anastaci1a.localica.navigation.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LyricsRepository
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tapping a lyric while paused moves the highlight to it promptly, every time (#518). */
class LyricsPausedSeekTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pausedLyricTapsMoveTheHighlightWithinHalfASecond() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "lyric-paused-seek.opus")
        instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(android.net.Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name)
        try {
            val document = kotlinx.coroutines.runBlocking { LyricsRepository(instrumentation.targetContext).load(track, 0) }
                .sources.first().document
            assertTrue("The fixture must be synced", document.synced)
            var state by mutableStateOf(PlaybackState(connected = true, trackId = track.id, trackUri = track.uri,
                title = track.title, positionMs = 0, durationMs = 125000, isPlaying = false))
            compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(state, true, {}, InterfaceSettings(reducedMotion = true), remember { Animatable(1f) }, {},
                    { state = state.copy(positionMs = it) }, {}, {}, {}, {}, lyricsOpen = true, track = track)
            } } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("lyric-lines").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            fun current() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                hasAnyAncestor(hasTestTag("lyric-lines")), useUnmergedTree = true).fetchSemanticsNodes().size
            val unique = document.lines.groupBy { it.text }.filter { it.key.isNotEmpty() && it.value.size == 1 }.keys
            val inLines = hasAnyAncestor(hasTestTag("lyric-lines"))
            for (trial in 0 until 8) {
                // Tap a different visible line each time, alternating forwards and backwards.
                val current = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
                val candidates = document.lines.map { it.text }.filter { it in unique }.filter {
                    compose.onAllNodes(hasText(it) and inLines, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasText(it) and inLines and current, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                assertTrue("Trial $trial: a visible line other than the current one: $candidates", candidates.isNotEmpty())
                val text = if (trial % 2 == 0) candidates.last() else candidates.first()
                val line = document.lines.first { it.text == text }
                compose.onNode(hasText(text) and inLines, useUnmergedTree = true).performClick()
                compose.mainClock.autoAdvance = true
                val deadline = System.currentTimeMillis() + 500
                var selected = false
                while (System.currentTimeMillis() < deadline && !selected) {
                    compose.waitForIdle()
                    selected = runCatching { compose.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                        hasAnyAncestor(hasTestTag("lyric-lines")), useUnmergedTree = true).assertExists() }.isSuccess
                }
                assertTrue("Trial $trial: the tapped line '${line.text}' at ${line.timeMs} is highlighted within 500 ms", selected)
                assertEquals("Trial $trial: exactly one line is current", 1, current())
            }
        } finally { file.delete() }
    }
}
