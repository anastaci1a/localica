package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PinchReleaseHandoffTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longHeldOutwardPinchDoesNotRecenterForOneReleaseFrame() = release(outward = true)
    @Test fun longHeldInwardPinchDoesNotRecenterForOneReleaseFrame() = release(outward = false)

    @Test fun longHeldCancelledPinchRestoresOriginalAnchor() = release(outward = true, cancelled = true)

    private fun release(outward: Boolean, cancelled: Boolean = false) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "release-queue"))
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = BrowseStyle(listSize = if (outward) ListSize.Compact else ListSize.Normal)
        val target = if (outward) "List, Normal" else "List, Compact"
        val tracks = (0..159).map { index -> Track("release:$index", "Song ${index.toString().padStart(3, '0')}",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        // Keep query identity stable when the presentation marker invalidates drawing.
        val library = LibraryUiState(LibrarySnapshot(
            folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        val playlists = PlaylistState()
        val queue = ListeningQueue(id = "release-queue", name = "Release regression",
            entries = tracks.map { QueueEntry(id = it.id, track = it) }, currentEntryId = "release:73")
        val queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        var pointersDown = false
        prefs.write(route, initial, false)
        try {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                // The current-row highlight is a real rendered landmark in both
                // Compact (which has no cover) and Normal list presentations.
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Magenta)) {
                    Box(Modifier.fillMaxSize().testTag("release-scene")) {
                        BrowserScreen(library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                            queueState = queueState, playingEntryId = "release:73")
                        PresentationMarker { stage }
                    }
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-release:0").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(70)
            val anchor = compose.onNodeWithTag("media-release:73")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val originalBounds = anchor.fetchSemanticsNode().boundsInRoot
            val expectedFinalCenter = originalBounds.center.y
            val y = expectedFinalCenter - viewport.top
            val scene = compose.onNodeWithTag("release-scene")
            fun presentedCenter(): Float = highlightCenter(
                capturePresentedPopupPixels(compose, scene, stage) { view },
                expectedFinalCenter, originalBounds.height * 2f)
            assertEquals("Initial rendered landmark", expectedFinalCenter, presentedCenter(), 4f)
            compose.mainClock.autoAdvance = false
            pointersDown = true
            list.performTouchInput {
                down(0, Offset(centerX - 60f, y)); down(1, Offset(centerX + 60f, y))
                val halfSpan = if (outward) 96f else 39f
                moveTo(0, Offset(centerX - halfSpan, y)); moveTo(1, Offset(centerX + halfSpan, y))
            }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == target
            }
            // Keep both fingers down beyond the old 450 ms anchor lifetime, with a
            // partial committed preview so release has a real 220 ms settle phase.
            compose.mainClock.advanceTimeBy(1200)
            compose.runOnIdle { stage = 1 }
            val before = presentedCenter()
            list.performTouchInput {
                if (cancelled) cancel() else { up(0); up(1) }
            }
            pointersDown = false
            // The endpoint is the ORIGINAL pinch midpoint, never a possibly wrong
            // final sample. No frame may jump outside the held-to-endpoint path.
            val low = minOf(before, expectedFinalCenter) - 4f
            val high = maxOf(before, expectedFinalCenter) + 4f
            val samples = mutableListOf<Float>()
            repeat(60) { frame ->
                // Marker waits only for this exact presented frame, with the test
                // clock paused; it cannot advance past a bad release frame.
                compose.runOnIdle { stage = (frame + 2) % 24 }
                compose.mainClock.advanceTimeByFrame()
                val center = presentedCenter()
                samples += center
                android.util.Log.i("PinchReleaseHandoff",
                    "outward=$outward cancelled=$cancelled frame=$frame t=${compose.mainClock.currentTime} center=$center expected=$expectedFinalCenter")
            }
            assertTrue("Every presented release frame must preserve the anchor: expected=$expectedFinalCenter held=$before samples=$samples",
                samples.all { it in low..high })
            assertEquals("Final rendered anchor must return to the original midpoint",
                expectedFinalCenter, samples.last(), 4f)
            assertEquals("Final layout anchor must return to the original midpoint",
                expectedFinalCenter, anchor.fetchSemanticsNode().boundsInRoot.center.y, 4f)
            assertEquals(if (cancelled) initial.listSize else if (outward) ListSize.Normal else ListSize.Compact,
                prefs.read(route).listSize)
            // A completed/cancelled transaction must release its scroll mutation.
            // Ordinary touch scrolling must work immediately after the handoff.
            list.performTouchInput { swipeUp(durationMillis = 200) }
            compose.mainClock.advanceTimeBy(500)
            assertTrue("Release must not leave the list scroll-locked",
                compose.onAllNodesWithTag("media-release:73").fetchSemanticsNodes().isEmpty() ||
                    anchor.fetchSemanticsNode().boundsInRoot.center.y < expectedFinalCenter - 8f)
        } finally {
            try {
                if (pointersDown) compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
            } finally {
                compose.mainClock.autoAdvance = true
                prefs.write(route, original, overridden)
            }
        }
    }

    private fun highlightCenter(pixels: PixelMap, expected: Float, radius: Float): Float {
        // Sample away from the title, cover and action icon. Only the target row
        // has a magenta tint; neutral text and the green frame marker cannot match.
        // Keep unrelated queue toolbar accents out of the search, while retaining
        // two full source rows on either side to expose the coarse anchor jump.
        val top = (expected - radius).toInt().coerceAtLeast(0)
        val bottom = (expected + radius).toInt().coerceAtMost(pixels.height)
        val rows = (top until bottom).filter { y ->
            (pixels.width * 2 / 3 until pixels.width * 3 / 4).count { x ->
                val color = pixels[x, y]
                color.red - color.green > .07f && color.blue - color.green > .07f
            } >= 3
        }
        assertTrue("Presented target-row highlight must be visible", rows.isNotEmpty())
        return (rows.first() + rows.last() + 1) / 2f
    }
}
