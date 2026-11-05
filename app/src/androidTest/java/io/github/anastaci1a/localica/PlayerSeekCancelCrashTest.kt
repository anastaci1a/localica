package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #583: a seek drag in v0.32.0 overflowed the API 33+ RenderThread. The seek-cancel X sampled the
 * player Surface's recording while being composed inside that Surface, so the X drew itself. */
class PlayerSeekCancelCrashTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("file:///missing.mp3", "Morning light", artist = "Localica", fileName = "morning.mp3", relativePath = "morning.mp3")
    private var seeks = 0

    @Test fun seekDragOverTheCancelTargetAndReleaseInFullBackgroundLayout() = drag(ArtworkLayout.FullBackground, release = Release.OverCancel)
    @Test fun seekDragOverTheCancelTargetAndReleaseInFloatingLayout() = drag(ArtworkLayout.Floating, release = Release.OverCancel)
    @Test fun seekDragHoverTheCancelTargetThenReleaseOnTheBar() = drag(ArtworkLayout.FullBackground, release = Release.BackOnBar)

    private enum class Release { OverCancel, BackOnBar }

    private fun drag(layout: ArtworkLayout, release: Release) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(artworkLayout = layout))
        val queue = QueueEdits.create(listOf(track), 0, "Morning")
        compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
            PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title, artist = track.artist,
                durationMs = 125000, positionMs = 30000), {}, {}, { seeks++ }, {}, {}, {},
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
            playlist = PlaylistState(loading = false)) } }
        compose.onNodeWithTag("player-surface").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
        // Let the expansion settle so the X samples the complete, recorded player scene (#562).
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val seek = compose.onNodeWithContentDescription("Seek").fetchSemanticsNode().boundsInRoot
        val start = Offset(seek.left + seek.width * .3f, seek.center.y) - root.topLeft
        compose.onRoot().performTouchInput {
            down(start)
            repeat(8) { moveBy(Offset(seek.width * .04f, 0f), delayMillis = 32) }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Release to cancel seek").fetchSemanticsNodes().isNotEmpty() }
        // Several real frames with the X composed over the recorded player.
        repeat(6) { Thread.sleep(50); compose.waitForIdle() }
        val cancel = compose.onNodeWithContentDescription("Release to cancel seek").fetchSemanticsNode().boundsInRoot
        val over = cancel.center - root.topLeft
        val here = start + Offset(seek.width * .32f, 0f)
        compose.onRoot().performTouchInput {
            repeat(10) { i -> moveTo(here + (over - here) * ((i + 1) / 10f), delayMillis = 32) }
        }
        repeat(6) { Thread.sleep(50); compose.waitForIdle() }
        compose.onNodeWithContentDescription("Release to cancel seek")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Cancel seek"))
        compose.onRoot().performTouchInput {
            if (release == Release.BackOnBar) repeat(10) { i -> moveTo(over + (here - over) * ((i + 1) / 10f), delayMillis = 32) }
            up()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Release to cancel seek").fetchSemanticsNodes().isEmpty() }
        repeat(6) { Thread.sleep(50); compose.waitForIdle() }
        compose.onNodeWithContentDescription("Collapse player").assertExists()
        if (release == Release.OverCancel) compose.runOnIdle { assertEquals("Releasing over the X cancels the seek", 0, seeks) }
    }
}
