package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    @Test fun longHeldCancelledInwardPinchRestoresOriginalAnchor() = release(outward = false, cancelled = true)

    @Test fun galleryFiveToLargeKeepsItsPresentedAnchorThroughRelease() = release(outward = false, gallery = true)
    @Test fun galleryFiveToFourKeepsItsPresentedAnchorThroughRelease() = release(outward = true, gallery = true)
    @Test fun cancelledGalleryToListRestoresItsArtworkWithoutReplay() = release(outward = false, cancelled = true, gallery = true)
    @Test fun cancelledGalleryDensityRestoresItsArtworkWithoutReplay() = release(outward = true, cancelled = true, gallery = true)

    private fun release(outward: Boolean, cancelled: Boolean = false, gallery: Boolean = false) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "release-queue"))
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = if (gallery) BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5, listSize = ListSize.Large)
            else BrowseStyle(listSize = if (outward) ListSize.Compact else ListSize.Normal)
        val target = if (gallery) { if (outward) "Gallery, 4 columns" else "List, Large" }
            else if (outward) "List, Normal" else "List, Compact"
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
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("media-release:73").assertIsDisplayed() }.isSuccess }
            compose.waitForIdle()
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(70)
            val anchor = compose.onNodeWithTag("media-release:73")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val originalBounds = anchor.fetchSemanticsNode().boundsInRoot
            val expectedFinalCenter = originalBounds.center.y
            val y = expectedFinalCenter - viewport.top
            val x = originalBounds.center.x - viewport.left
            val scene = compose.onNodeWithTag("release-scene")
            val reviewDirectory by lazy {
                val exported = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val root = exported?.let { java.io.File(it) } ?: requireNotNull(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
                java.io.File(root, "pinch-review").also {
                    check(it.isDirectory || it.mkdirs()) { "Pinch review directory must be writable" }
                }
            }
            fun presentedCenter(): Float {
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                if (gallery && stage in setOf(0, 1, 2, 6, 12, 22) &&
                    InstrumentationRegistry.getArguments().getString("pinchReview") == "true") {
                    val directory = reviewDirectory
                    val bitmap = android.graphics.Bitmap.createBitmap(pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.setPixels(IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() },
                            0, pixels.width, 0, 0, pixels.width, pixels.height)
                        java.io.File(directory, "$outward-$cancelled-${compose.mainClock.currentTime}-$stage.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
                return highlightCenter(pixels, expectedFinalCenter, originalBounds.height * 2f, gallery)
            }
            assertEquals("Initial rendered landmark", expectedFinalCenter, presentedCenter(), 4f)
            compose.mainClock.autoAdvance = false
            pointersDown = true
            list.performTouchInput {
                down(0, Offset(x - 60f, y)); down(1, Offset(x + 60f, y))
                val halfSpan = if (gallery) { if (outward) 62.45f else 57.65f }
                    else if (outward) 96f else 39f
                moveTo(0, Offset(x - halfSpan, y)); moveTo(1, Offset(x + halfSpan, y))
            }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == target
            }
            if (gallery) {
                compose.mainClock.advanceTimeBy(48)
                compose.runOnIdle { stage = 1 }
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                val columns = (0 until pixels.width).filter { px ->
                    (originalBounds.top.toInt().coerceAtLeast(0) until originalBounds.bottom.toInt().coerceAtMost(pixels.height)).count { py ->
                        val c = pixels[px, py]
                        c.red - c.green > .07f && c.blue - c.green > .07f
                    } >= 3
                }
                assertTrue("The source highlight remains visible at recognition", columns.isNotEmpty())
                // Playback frost shares the full selection footprint; allow edge antialiasing.
                assertEquals("Recognition must retain the gallery highlight width",
                    originalBounds.width, (columns.last() - columns.first() + 1).toFloat(), 5f)
                list.performTouchInput {
                    val halfSpan = if (outward) 96f else 39f
                    moveTo(0, Offset(x - halfSpan, y)); moveTo(1, Offset(x + halfSpan, y))
                }
            }
            // Keep both fingers down beyond the old 450 ms anchor lifetime, with a
            // partial committed preview so release has a real 220 ms settle phase.
            compose.mainClock.advanceTimeBy(1200)
            compose.runOnIdle { stage = 1 }
            val before = presentedCenter()
            val artwork = compose.onNodeWithTag("browse-art-release:73", true)
            val heldArtwork = artwork.fetchSemanticsNode().boundsInRoot
            list.performTouchInput {
                if (cancelled) cancel() else { up(0); up(1) }
            }
            pointersDown = false
            // The endpoint is the ORIGINAL pinch midpoint, never a possibly wrong
            // final sample. No frame may jump outside the held-to-endpoint path.
            val low = minOf(before, expectedFinalCenter) - 4f
            val high = maxOf(before, expectedFinalCenter) + 4f
            val samples = mutableListOf<Float>()
            val artworkFrames = mutableListOf(heldArtwork)
            repeat(60) { frame ->
                // Marker waits only for this exact presented frame, with the test
                // clock paused; it cannot advance past a bad release frame.
                compose.runOnIdle { stage = (frame + 2) % 24 }
                compose.mainClock.advanceTimeByFrame()
                val center = presentedCenter()
                samples += center
                artworkFrames += artwork.fetchSemanticsNode().boundsInRoot
                android.util.Log.i("PinchReleaseHandoff",
                    "outward=$outward cancelled=$cancelled frame=$frame t=${compose.mainClock.currentTime} center=$center expected=$expectedFinalCenter")
            }
            assertTrue("Every presented release frame must preserve the anchor: expected=$expectedFinalCenter held=$before samples=$samples",
                samples.all { it in low..high })
            assertEquals("Final rendered anchor must return to the original midpoint",
                expectedFinalCenter, samples.last(), 4f)
            assertEquals("Final layout anchor must return to the original midpoint",
                expectedFinalCenter, anchor.fetchSemanticsNode().boundsInRoot.center.y, 4f)
            // A correct decoration center alone can conceal artwork replay or a
            // one-frame layout handoff. Check every edge through 960ms of release.
            val edges: List<(Rect) -> Float> = listOf({ it.left }, { it.top }, { it.right }, { it.bottom })
            edges.forEachIndexed { edge, coordinate ->
                val values = artworkFrames.map(coordinate)
                val range = kotlin.math.abs(values.last() - values.first())
                val lowEdge = minOf(values.first(), values.last()) - 3f
                val highEdge = maxOf(values.first(), values.last()) + 3f
                assertTrue("Artwork edge $edge stays inside its endpoint path: $values", values.all { it in lowEdge..highEdge })
                assertTrue("Artwork edge $edge cannot jump at handoff: $values",
                    values.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) <= range * .25f + 3f })
            }
            assertEquals(if (cancelled || gallery) initial.listSize else if (outward) ListSize.Normal else ListSize.Compact,
                prefs.read(route).listSize)
            if (gallery && !cancelled) {
                assertEquals(if (outward) Presentation.Standard else Presentation.List, prefs.read(route).presentation)
                if (outward) assertEquals(4, prefs.read(route).itemsPerRow)
            }
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

    private fun highlightCenter(pixels: PixelMap, expected: Float, radius: Float, fullWidth: Boolean = false): Float {
        // Sample away from the title, cover and action icon. Only the target row
        // has a magenta tint; neutral text and the green frame marker cannot match.
        // Keep unrelated queue toolbar accents out of the search, while retaining
        // two full source rows on either side to expose the coarse anchor jump.
        val top = (expected - radius).toInt().coerceAtLeast(0)
        val bottom = (expected + radius).toInt().coerceAtMost(pixels.height)
        val rows = (top until bottom).filter { y ->
            (if (fullWidth) 0 until pixels.width else pixels.width * 2 / 3 until pixels.width * 3 / 4).count { x ->
                val color = pixels[x, y]
                color.red - color.green > .07f && color.blue - color.green > .07f
            } >= 3
        }
        assertTrue("Presented target-row highlight must be visible", rows.isNotEmpty())
        return (rows.first() + rows.last() + 1) / 2f
    }
}
