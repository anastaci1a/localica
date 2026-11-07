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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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
    /** Visible height of the last measured highlight slab, in rows. */
    private var highlightExtent = 0

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
            else BrowseStyle(listSize = if (outward) ListSize.Tracklist else ListSize.Normal)
        val target = if (gallery) { if (outward) "Gallery, 4 columns" else "List, Large" }
            // Compact is now adjacent to both source views in the requested order.
            else "List, Compact"
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
        var drawObserver: android.view.ViewTreeObserver? = null
        var drawCount = 0
        var lastDrawNs = 0L
        val drawListener = android.view.ViewTreeObserver.OnDrawListener {
            drawCount++
            lastDrawNs = android.os.SystemClock.elapsedRealtimeNanos()
        }
        var pointersDown = false
        val destination = if (outward) initial.copy(itemsPerRow = 4) else initial.copy(presentation = Presentation.List)
        prefs.write(route, if (gallery) destination else initial, false)
        try {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                // The current-row highlight is a real rendered landmark in both
                // Tracklist (which has no cover) and Normal list presentations.
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
            compose.runOnUiThread {
                drawObserver = view.viewTreeObserver.also { it.addOnDrawListener(drawListener) }
            }
            // Probe endpoint geometry independently of the renderer's outline helper.
            // Blank title reservations are deliberately excluded from the measured shape.
            fun measuredPart(tag: String): Rect {
                val node = compose.onNodeWithTag("$tag-release:73", true).fetchSemanticsNode()
                return Rect(node.positionInRoot, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
            }
            fun measuredContour(row: Rect): Rect {
                val art = measuredPart("browse-art")
                val menu = measuredPart("browse-menu")
                // A menu contributes its horizontal slot; its tall touch target
                // contributes only the vertical centre of the drawn icon.
                val parts = listOf(art, measuredPart("browse-text"), measuredPart("browse-info")).filterNot { it.isEmpty } +
                    listOfNotNull(menu.takeIf { it.width > 0f }?.let { Rect(it.left, it.center.y, it.right, it.center.y) })
                val minimum = 2f * view.resources.displayMetrics.density
                val inset = maxOf(minimum, minOf(art.left - row.left, row.right - art.right,
                    art.top - row.top, row.bottom - art.bottom))
                return Rect(parts.minOf { it.left } - inset, parts.minOf { it.top } - inset,
                    parts.maxOf { it.right } + inset, parts.maxOf { it.bottom } + inset)
                    .intersect(row.inflate(minimum)).translate(-row.topLeft)
            }
            val targetRow = if (gallery) measuredPart("media") else Rect.Zero
            val targetContour = if (gallery) measuredContour(targetRow) else Rect.Zero
            if (gallery) {
                compose.runOnIdle { prefs.write(route, initial, false) }
                compose.waitUntil(5000) {
                    compose.onNodeWithTag("browser-list").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.StateDescription) == "Gallery, 5 columns"
                }
                compose.waitForIdle()
            }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(70)
            val anchor = compose.onNodeWithTag("media-release:73")
            // Explicit scrolling detaches playback following and exposes the
            // bottom-center Current song button. Establish the same unobstructed
            // physical swipe before pinching, then restore the source viewport.
            assertTouchScrolls(list, "The source list must accept an ordinary touch scroll")
            list.performScrollToIndex(70)
            compose.waitForIdle()
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val originalBounds = anchor.fetchSemanticsNode().boundsInRoot
            val artwork = compose.onNodeWithTag("browse-art-release:73", true)
            val originalArtwork = artwork.fetchSemanticsNode().boundsInRoot
            val originalContour = if (gallery) measuredContour(originalBounds) else Rect.Zero
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
            fun presentedHighlightWidth(pixels: PixelMap): Float {
                val columns = (0 until pixels.width).filter { px ->
                    (originalBounds.top.toInt().coerceAtLeast(0) until originalBounds.bottom.toInt().coerceAtMost(pixels.height)).count { py ->
                        val c = pixels[px, py]
                        c.red - c.green > .07f && c.blue - c.green > .07f
                    } >= 3
                }
                assertTrue("The source highlight remains visible", columns.isNotEmpty())
                return (columns.last() - columns.first() + 1).toFloat()
            }
            var initialHighlightWidth: Float? = null
            fun presentedCenter(): Float {
                fun phase(name: String) = compose.runOnUiThread {
                    android.util.Log.i("PinchCapturePhase",
                        "phase=$name ns=${android.os.SystemClock.elapsedRealtimeNanos()} stage=$stage " +
                            "clock=${compose.mainClock.currentTime} outward=$outward cancelled=$cancelled gallery=$gallery " +
                            "attached=${view.isAttachedToWindow} shown=${view.isShown} focus=${view.hasWindowFocus()} " +
                            "draws=$drawCount lastDrawNs=$lastDrawNs")
                }
                phase("begin")
                val pixels = try { capturePresentedPopupPixels(compose, scene, stage) { view } }
                    finally { phase("end") }
                if (gallery && stage == 0) initialHighlightWidth = presentedHighlightWidth(pixels)
                if (gallery && (stage in setOf(0, 1, 2, 6, 12, 22) ||
                    InstrumentationRegistry.getArguments().getString("pinchReviewAllFrames") == "true" &&
                    InstrumentationRegistry.getArguments().getString("pinchReview") == "true")) {
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
                val visibleCenter = highlightCenter(pixels, expectedFinalCenter, originalBounds.height * 2f, gallery)
                if (!gallery) return visibleCenter
                // Infer size from this same presented frame. Semantic artwork dimensions
                // can be one render frame ahead during release, even after the marker draws.
                // Horizontal size supplies the full contour; optical edges place it (#547).
                val row = anchor.fetchSemanticsNode().boundsInRoot
                val delta = targetContour.width - originalContour.width
                val progress = if (kotlin.math.abs(delta) < .5f) 0f else
                    ((presentedHighlightWidth(pixels) - originalContour.width) / delta).coerceIn(0f, 1f)
                fun mix(a: Float, b: Float) = a + (b - a) * progress
                val contour = Rect(mix(originalContour.left, targetContour.left), mix(originalContour.top, targetContour.top),
                    mix(originalContour.right, targetContour.right), mix(originalContour.bottom, targetContour.bottom)).translate(row.topLeft)
                val visualHeight = mix(originalBounds.height, targetRow.height)
                // A list reflow widens the slab beyond the crossing square covers:
                // its full-width scan retains both optical edges. A narrow middle
                // strip can instead stop on a cover edge while its semantic bounds
                // are one frame ahead. Density changes still need #547's full-height
                // placement because a crossing cover can hide an entire slab edge.
                val opticalCenter = if (!outward) visibleCenter else {
                    val covers = compose.onAllNodes(SemanticsMatcher("release artwork") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("browse-art-release:") == true
                    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                    galleryHighlightCenter(pixels, contour, covers)
                }
                val center = opticalCenter - (contour.center.y - row.top - visualHeight / 2f)
                if (!center.isFinite()) highlightExtent = 0
                android.util.Log.i("PinchReleaseContour",
                    "stage=$stage visible=$visibleCenter measured=$center extent=$highlightExtent contour=$contour")
                return center
            }
            val initialCenter = presentedCenter()
            assertEquals("Initial rendered landmark", expectedFinalCenter, initialCenter, 4f)
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
                // Compare presented outlines, which can extend beyond the layout box (#532).
                // Recognition keeps the travel past the 4% threshold (#520), so the highlight has
                // grown by at most that share (under 12%) toward the full-width list row.
                val initialWidth = requireNotNull(initialHighlightWidth)
                val width = presentedHighlightWidth(pixels)
                assertTrue("Recognition must retain the gallery highlight width: $width from $initialWidth",
                    width in initialWidth - 5f..initialWidth + (pixels.width - initialWidth) * .12f + 5f)
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
            val heldArtwork = artwork.fetchSemanticsNode().boundsInRoot
            list.performTouchInput {
                if (cancelled) cancel() else { up(0); up(1) }
            }
            pointersDown = false
            // The endpoint is the ORIGINAL pinch midpoint, never a possibly wrong
            // final sample. No frame may jump outside the held-to-endpoint path.
            val releaseRange = pinchReleaseAnchorRange(before, initialCenter, expectedFinalCenter)
            val samples = mutableListOf<Float>()
            val extents = mutableListOf<Int>()
            val artworkFrames = mutableListOf(heldArtwork)
            repeat(60) { frame ->
                // Marker waits only for this exact presented frame, with the test
                // clock paused; it cannot advance past a bad release frame.
                compose.runOnIdle { stage = (frame + 2) % 24 }
                compose.mainClock.advanceTimeByFrame()
                val center = presentedCenter()
                samples += center
                extents += highlightExtent
                artworkFrames += artwork.fetchSemanticsNode().boundsInRoot
                android.util.Log.i("PinchReleaseHandoff",
                    "outward=$outward cancelled=$cancelled frame=$frame t=${compose.mainClock.currentTime} center=$center expected=$expectedFinalCenter")
            }
            assertTrue("Every presented release frame must preserve the anchor: expected=$expectedFinalCenter held=$before samples=$samples extents=$extents",
                anchorPreserved(samples, extents, releaseRange))
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
            assertEquals(if (cancelled || gallery) initial.listSize else ListSize.Compact,
                prefs.read(route).listSize)
            if (gallery && !cancelled) {
                assertEquals(if (outward) Presentation.Standard else Presentation.List, prefs.read(route).presentation)
                if (outward) assertEquals(4, prefs.read(route).itemsPerRow)
            }
            // A completed/cancelled transaction must release its scroll mutation.
            // Ordinary touch scrolling must work immediately after the handoff.
            assertTouchScrolls(list, "Release must not leave the list scroll-locked", expectedFinalCenter)
        } finally {
            try {
                if (pointersDown) compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
            } finally {
                compose.runOnUiThread {
                    drawObserver?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
                }
                compose.mainClock.autoAdvance = true
                prefs.write(route, original, overridden)
            }
        }
    }

    private fun assertTouchScrolls(list: SemanticsNodeInteraction, message: String, expectedCenter: Float? = null) {
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("media-release:73").fetchSemanticsNode().boundsInRoot.center.y
        val returnBounds = compose.onNodeWithContentDescription("Current song")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The return control is a sibling of the list. Check the actual vertical
        // swipe path, not swipeUp()'s unused bottom-center default, so a control
        // drag cannot be mistaken for a leaked pinch scroll mutation.
        val start = Offset(viewport.width * .25f, viewport.height * .7f)
        val end = Offset(start.x, viewport.height * .3f)
        val rootStart = start + viewport.topLeft
        val rootEnd = end + viewport.topLeft
        for (rootPoint in listOf(rootStart, rootEnd)) {
            assertTrue("Scroll probe stays within the list", viewport.contains(rootPoint))
            assertFalse("Scroll probe must avoid the floating return control", returnBounds.contains(rootPoint))
        }
        assertTrue("Scroll probe path must avoid Current song: $rootStart->$rootEnd return=$returnBounds",
            rootStart.x < returnBounds.left || rootStart.x >= returnBounds.right ||
                maxOf(rootStart.y, rootEnd.y) < returnBounds.top ||
                minOf(rootStart.y, rootEnd.y) >= returnBounds.bottom)
        // Record the real layout hit path without moving the probe away from a
        // regression. Zero-width hidden menu slots can still own touch targets.
        val nearbyMenuSlots = compose.onAllNodes(SemanticsMatcher("release menu slot") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("browse-menu-release:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { (it.positionInRoot - rootStart).getDistance() }.take(3)
            .map { "${it.config.getOrNull(SemanticsProperties.TestTag)}:${it.positionInRoot}/${it.size}" }
        val rangeBefore = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
        list.performTouchInput { swipe(start, end, durationMillis = 200) }
        compose.mainClock.advanceTimeBy(500)
        val remaining = compose.onAllNodesWithTag("media-release:73").fetchSemanticsNodes()
        val after = if (remaining.isEmpty()) null else remaining.single().boundsInRoot.center.y
        val rangeAfter = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
        android.util.Log.i("PinchReleaseHandoff", "Touch scroll: before=$before after=$after range=$rangeBefore->$rangeAfter start=$rootStart slots=$nearbyMenuSlots")
        assertTrue("$message: before=$before after=$after start=$start return=$returnBounds range=$rangeBefore->$rangeAfter slots=$nearbyMenuSlots",
            after == null || after < (expectedCenter ?: before) - 8f)
    }

    /** Covers paint above every row decoration, so a crossing cover can hide part of the slab
     * for a frame. A real anchor jump moves the whole slab; occlusion only shortens it, and such
     * a frame cannot place the anchor. It stays rare (at most two frames) and never ends the release. */
    private fun anchorPreserved(samples: List<Float>, extents: List<Int>, range: ClosedFloatingPointRange<Float>): Boolean {
        val full = extents.sorted()[extents.size / 2]
        val occluded = extents.map { it < full * .7f }
        return occluded.count { it } <= 2 && !occluded.last() && samples.indices.all { occluded[it] || samples[it] in range }
    }


    @Test fun unplaceableHeldContourRetainsFiniteReleaseBoundsAndAllNegativeControls() {
        val range = pinchReleaseAnchorRange(Float.NaN, 400f, 400f)
        val samples = MutableList(12) { 400f }
        val extents = MutableList(12) { 280 }
        for (index in listOf(3, 4)) { samples[index] = Float.NaN; extents[index] = 0 }
        assertTrue("An occluded held contour cannot poison otherwise observed anchors",
            anchorPreserved(samples, extents, range))
        for (shift in listOf(-4.5f, 4.5f)) {
            val moved = samples.toMutableList().also { it[8] += shift }
            assertFalse("A whole visible contour moving beyond four pixels must fail",
                anchorPreserved(moved, extents, range))
        }
        val third = extents.toMutableList().also { it[5] = 0 }
        assertFalse("Three unplaceable release frames still fail", anchorPreserved(samples, third, range))
        val final = extents.toMutableList().also { it[11] = 0 }
        assertFalse("An unplaceable final frame still fails", anchorPreserved(samples, final, range))
        val observed = pinchReleaseAnchorRange(420f, 400f, 400f)
        assertTrue(420f in observed && 400f in observed)
        assertFalse(424.5f in observed || 395.5f in observed)
        assertThrows(IllegalArgumentException::class.java) { pinchReleaseAnchorRange(Float.NaN, Float.NaN, 400f) }
        assertThrows(IllegalArgumentException::class.java) { pinchReleaseAnchorRange(400f, 400f, Float.NaN) }
    }

    /** Negative control: the occlusion allowance never excuses a slab that moved whole. */
    @Test fun anchorCheckRejectsAWholeSlabJumpButNotAnOccludedFrame() {
        val steady = List(12) { 431.5f }; val extents = List(12) { 288 }
        val range = 427.5f..435.5f
        assertTrue(anchorPreserved(steady, extents, range))
        // One frame the whole slab shifts by 30 px: full extent, so it must place the anchor and fail.
        assertFalse(anchorPreserved(steady.toMutableList().also { it[4] = 461.5f }, extents, range))
        // One frame a crossing cover hides the upper slab: shortened, so it cannot place the anchor.
        assertTrue(anchorPreserved(steady.toMutableList().also { it[4] = 538f }, extents.toMutableList().also { it[4] = 77 }, range))
        // Three occluded frames, or occlusion at the end, are not excused.
        assertFalse(anchorPreserved(steady.toMutableList().also { for (i in 3..5) it[i] = 538f },
            extents.toMutableList().also { for (i in 3..5) it[i] = 77 }, range))
        assertFalse(anchorPreserved(steady.toMutableList().also { it[11] = 538f }, extents.toMutableList().also { it[11] = 77 }, range))
    }

    @Test fun galleryAnchorCheckPlacesSmallTopAndBottomClipsWhileTheCardResizes() {
        val centers = mutableListOf<Float>()
        val extents = mutableListOf<Int>()
        val legacy = mutableListOf<Float>()
        repeat(12) { frame ->
            val height = 282f - frame * 3f
            val contour = Rect(120f, 400f - height / 2f, 352f, 400f + height / 2f)
            val cover = if (frame % 2 == 0)
                Rect(100f, contour.top - 8f, 372f, contour.top + 15f)
            else Rect(100f, contour.bottom - 15f, 372f, contour.bottom + 8f)
            val pixels = galleryAnchorPixels(contour, covers = listOf(cover))
            legacy += highlightCenter(pixels, 400f, 400f, true)
            extents += highlightExtent
            centers += galleryHighlightCenter(pixels, contour, listOf(cover))
        }
        assertFalse("Small clips expose the old remaining-tint midpoint error",
            anchorPreserved(legacy, extents, 396f..404f))
        assertTrue("Every changing-size frame still places its full contour",
            anchorPreserved(centers, extents, 396f..404f))
        centers.forEach { assertEquals(400f, it, 1f) }
    }

    @Test fun galleryAnchorCheckRejectsTranslatedContoursEvenWithPartialOcclusion() {
        // Quarter-pixel edges keep the 4.5 px control distinct from raster rounding.
        val contour = Rect(120f, 260.25f, 352f, 539.75f)
        val masks = listOf(emptyList(),
            listOf(Rect(100f, contour.top - 20f, 372f, contour.top + 20f)),
            listOf(Rect(100f, contour.bottom - 20f, 372f, contour.bottom + 20f)))
        for (shift in listOf(-30f, -7.5f, -4.5f, 4.5f, 7.5f, 30f)) for (covers in masks) {
            val pixels = galleryAnchorPixels(contour, shift, covers)
            val measured = galleryHighlightCenter(pixels, contour, covers)
            assertTrue("A partly covered translated contour remains placeable: shift=$shift covers=$covers measured=$measured",
                measured.isFinite())
            val centers = MutableList(12) { 400f }.also { it[4] = measured }
            assertFalse("A real translation must fail the original 4 px bound: shift=$shift covers=$covers measured=$measured",
                anchorPreserved(centers, List(12) { 280 }, 396f..404f))
        }
    }

    @Test fun galleryAnchorCheckKeepsTheUnplaceableFrameCapAndFinalFrameRule() {
        val contour = Rect(120f, 260f, 352f, 540f)
        val covers = listOf(Rect(100f, 240f, 372f, 280f), Rect(100f, 520f, 372f, 560f))
        val hidden = galleryHighlightCenter(galleryAnchorPixels(contour, covers = covers), contour, covers)
        assertTrue("Neither optical edge can establish the full contour", hidden.isNaN())
        fun preserves(indices: List<Int>): Boolean {
            val centers = MutableList(12) { 400f }
            val extents = MutableList(12) { 280 }
            indices.forEach { centers[it] = hidden; extents[it] = 0 }
            return anchorPreserved(centers, extents, 396f..404f)
        }
        assertTrue(preserves(listOf(3, 4)))
        assertFalse(preserves(listOf(3, 4, 5)))
        assertFalse(preserves(listOf(11)))
    }

    @Test fun fullWidthGalleryAnchorRejectsWholeTranslationsWithCrossingMiddleCovers() {
        val contour = Rect(120f, 260.25f, 352f, 539.75f)
        // List reflows expose the outline on either side of the square covers.
        // These covers hide the central strip's top/bottom, but not the full edges.
        val covers = listOf(Rect(218f, 240f, 254f, 285f), Rect(218f, 515f, 254f, 560f))
        for (shift in listOf(0f, -7.5f, -4.5f, 4.5f, 7.5f)) {
            val measured = highlightCenter(galleryAnchorPixels(contour, shift, covers), 400f, 400f, true)
            assertEquals("Full-width pixels place the translated slab", 400f + shift, measured, .51f)
            val samples = MutableList(12) { 400f }.also { it[4] = measured }
            assertEquals("Crossing middle covers never excuse a whole-slab shift: $shift",
                shift == 0f, anchorPreserved(samples, List(12) { 280 }, 396f..404f))
        }
    }

    private fun galleryAnchorPixels(contour: Rect, shift: Float = 0f, covers: List<Rect> = emptyList()): PixelMap {
        val bitmap = android.graphics.Bitmap.createBitmap(480, 800, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.Magenta.copy(alpha = .13f).toArgb()
            canvas.drawRoundRect(contour.left, contour.top + shift, contour.right, contour.bottom + shift, 42f, 42f, paint)
            paint.color = android.graphics.Color.LTGRAY
            covers.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, paint) }
            return bitmap.asImageBitmap().toPixelMap()
        } finally { bitmap.recycle() }
    }

    private fun highlightCenter(pixels: PixelMap, expected: Float, radius: Float, fullWidth: Boolean = false): Float {
        // Sample away from the title, cover and action icon. Only the target row
        // has a magenta tint; neutral text and the green frame marker cannot match.
        // Keep unrelated queue toolbar accents out of the search, while retaining
        // two full source rows on either side to expose the coarse anchor jump.
        val top = (expected - radius).toInt().coerceAtLeast(0)
        val bottom = (expected + radius).toInt().coerceAtMost(pixels.height)
        val rows = (top until bottom).filter { y ->
            val columns = if (fullWidth) 0 until pixels.width else pixels.width * 2 / 3 until pixels.width * 3 / 4
            var matches = 0
            for (x in columns) {
                val color = pixels[x, y]
                if (color.red - color.green > .07f && color.blue - color.green > .07f) {
                    matches++
                    // Row membership only needs three matches; keep scanning every row.
                    if (matches == 3) return@filter true
                }
            }
            false
        }
        assertTrue("Presented target-row highlight must be visible", rows.isNotEmpty())
        highlightExtent = rows.last() + 1 - rows.first()
        return (rows.first() + rows.last() + 1) / 2f
    }
}
