package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerExpansionMotionTest {
    @get:Rule val compose = createComposeRule()
    private fun trace(phase: String) {
        Log.i("PlayerExpansionPhase", "ns=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name} phase=$phase " +
            "processReadbackActiveChildren=${ReadbackCompletion.activeProcessReadCount()}")
    }
    private val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000)
    private val visual = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }.asImageBitmap(), PlayerPalette(Color(0xFF303040), Color(0xFF303040), Color(0xFF303040)))

    @Test fun heldExpansionAlreadyShowsRealLyricsInBothArtworkLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("held-expansion-", ".opus", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
            exerciseHeldLyrics(Track(Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name), "lyric-lines")
        } finally { file.delete() }
    }

    @Test fun heldExpansionAlreadyShowsMissingLyricsInBothArtworkLayouts() {
        NoLyricsFixture("Missing").use { exerciseHeldLyrics(it.track, "empty-lyrics") }
    }

    private fun exerciseHeldLyrics(track: Track, contentTag: String) {
        val progress = Animatable(0f)
        var expanded by mutableStateOf(false)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        var reducedMotion by mutableStateOf(false)
        lateinit var scope: CoroutineScope
        var fingerDown = false
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            DisposableEffect(Unit) {
                trace("composition-enter:$contentTag")
                onDispose { trace("composition-dispose:$contentTag") }
            }
            scope = rememberCoroutineScope()
            PlayerSurface(state.copy(trackId = track.id, trackUri = track.uri), expanded, { expanded = it },
                InterfaceSettings(artworkLayout = layout, reducedMotion = reducedMotion), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = true, track = track, visual = visual) { Text("Album-only action") }
        } } }
        try {
            for (artworkLayout in ArtworkLayout.entries) {
                trace("layout-begin:$contentTag:$artworkLayout")
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { layout = artworkLayout; reducedMotion = false; expanded = false; scope.launch { progress.snapTo(0f) } }
                compose.waitForIdle()
                val mini = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
                compose.mainClock.autoAdvance = false
                val start = Offset(mini.left + mini.width / 2, mini.center.y)
                // Keep the same contact down through full expansion and its reversal.
                fingerDown = true
                compose.onRoot().performTouchInput {
                    down(start)
                    repeat(40) { index -> moveTo(Offset(start.x, start.y * (1f - (index + 1) / 40f)), delayMillis = 32) }
                }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertEquals("Held endpoint is fully expanded in $artworkLayout", 1f, progress.value, .0001f)
                    assertFalse("No release event has changed expanded", expanded)
                }
                compose.waitUntil(10000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag(contentTag, true).fetchSemanticsNodes().isNotEmpty()
                }
                // Let an asynchronously loaded document finish its own crossfade while still held.
                compose.mainClock.advanceTimeBy(400)
                trace("capture-content-begin:$contentTag:$artworkLayout")
                val content = compose.onNodeWithTag(contentTag, true).captureToImage().toPixelMap()
                trace("capture-content-end:$contentTag:$artworkLayout")
                var brightest = 0f
                for (y in 0 until content.height step 2) for (x in 0 until content.width step 2)
                    brightest = maxOf(brightest, content[x, y].red)
                assertTrue("Lyrics/empty ink is physically visible before release: $brightest", brightest > .28f)
                recordSemantics(artworkLayout, "held-$contentTag")
                // While held, the collapsed player remains a clickable semantics merger.
                // Inspect physical child geometry in the same unmerged tree before/after release.
                val player = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("player-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val seek = compose.onNodeWithTag("player-seek-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val transport = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
                val panel = compose.onNodeWithTag("lyrics-panel", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag(contentTag, true).assertIsDisplayed()
                compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                // Inspect actual player pixels, not a potentially transparent cover layer capture.
                trace("capture-player-begin:$contentTag:$artworkLayout")
                val pixels = compose.onNodeWithTag("player-surface").captureToImage().toPixelMap()
                trace("capture-player-end:$contentTag:$artworkLayout")
                val cover = compose.onNodeWithTag("player-artwork", true).fetchSemanticsNode().boundsInRoot
                for (fy in listOf(.3f, .5f, .7f)) for (fx in listOf(.3f, .5f, .7f)) {
                    val x = (cover.left - player.left + cover.width * fx).toInt().coerceIn(0, pixels.width - 1)
                    val y = (cover.top - player.top + cover.height * fy).toInt().coerceIn(0, pixels.height - 1)
                    val color = pixels[x, y]
                    assertTrue("Red cover is gone from the player before release: $color", color.red < color.blue + .1f)
                }

                val fullContent = compose.onNodeWithTag(contentTag, true).getUnclippedBoundsInRoot()
                // DpRect.height subtracts two independently converted Float coordinates.
                // Compare the actual integer-pixel measurement, which must stay identical.
                val fullContentSize = compose.onNodeWithTag(contentTag, true).fetchSemanticsNode().size
                compose.onRoot().performTouchInput { moveBy(Offset(0f, mini.top * .14f), delayMillis = 160) }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                val movingContent = compose.onNodeWithTag(contentTag, true).getUnclippedBoundsInRoot()
                assertEquals("Inner lyric geometry does not reflow during held collapse", fullContentSize,
                    compose.onNodeWithTag(contentTag, true).fetchSemanticsNode().size)
                val contentTravel = (movingContent.top - fullContent.top).value
                val playerTravel = (compose.onNodeWithTag("player-surface", true).getUnclippedBoundsInRoot().top -
                    compose.onRoot().getUnclippedBoundsInRoot().top).value
                assertTrue("Content must move slower than its enclosing player: $contentTravel vs $playerTravel",
                    contentTravel >= 0f && contentTravel < playerTravel * .5f)
                val heldBounds = listOf("lyrics-panel", "player-title", "player-seek-row").map {
                    compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot
                }
                compose.runOnIdle { reducedMotion = true }
                compose.mainClock.advanceTimeByFrame()
                assertEquals("Reduced motion cannot change held direct-manipulation geometry", heldBounds,
                    listOf("lyrics-panel", "player-title", "player-seek-row").map {
                        compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot
                    })
                compose.onRoot().performTouchInput { moveTo(Offset(start.x, 0f), delayMillis = 160) }
                compose.mainClock.advanceTimeByFrame()
                assertEquals("Held reversal restores the exact seek target", seek,
                    compose.onNodeWithTag("player-seek-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                compose.onRoot().performTouchInput { advanceEventTime(200); up() }
                fingerDown = false
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                assertTrue(expanded)
                recordSemantics(artworkLayout, "released-$contentTag")
                assertEquals("Release cannot change lyrics geometry", panel, compose.onNodeWithTag("lyrics-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                assertEquals(title, compose.onNodeWithTag("player-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                assertEquals(seek, compose.onNodeWithTag("player-seek-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                assertEquals(transport, compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot)
                assertEquals(player, compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot)

                trace("release-equalities-end:$contentTag:$artworkLayout")
                // Sample both directions, including the former low-opacity action ghost window.
                for (p in listOf(.99f, .96f, .92f, .86f, .8f, .78f, .5f, .78f, .8f, .86f, .92f, .96f, .99f, 1f)) {
                    trace("sweep-begin:$contentTag:$artworkLayout:$p")
                    compose.runOnIdle { scope.launch { progress.snapTo(p) } }
                    compose.onNodeWithTag("player-actions-row", true).assertDoesNotExist()
                    compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                    if (p <= .78f) compose.onNodeWithTag("lyrics-panel", useUnmergedTree = true).assertIsNotDisplayed()
                    else {
                        compose.onNodeWithTag(contentTag, true).assertExists()
                        val movingPanel = compose.onNodeWithTag("lyrics-panel", true).fetchSemanticsNode().boundsInRoot
                        val movingTitle = compose.onNodeWithTag("player-title", true).fetchSemanticsNode().boundsInRoot
                        val movingPlayer = compose.onNodeWithTag("player-surface", true).fetchSemanticsNode().boundsInRoot
                        assertEquals("The upper clip no longer rides down with the whole player",
                            maxOf(panel.top, movingPlayer.top), movingPanel.top, 1.5f)
                        assertEquals("The bottom clip follows the presented title with its normal gap",
                            title.top - panel.bottom, movingTitle.top - movingPanel.bottom, 1.5f)
                        assertEquals("Real and missing lyrics share the same visible clip", movingPanel,
                            compose.onNodeWithTag("lyrics-viewport", true).fetchSemanticsNode().boundsInRoot)
                    }
                    trace("sweep-end:$contentTag:$artworkLayout:$p")
                }
                trace("layout-end:$contentTag:$artworkLayout")
            }
        } finally {
            trace("finally-begin:$contentTag:fingerDown=$fingerDown")
            if (fingerDown) runCatching { compose.onRoot().performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
            trace("finally-end:$contentTag")
        }
    }

    @Test fun artworkImmediatelyEasesAlongItsCornerEnvelopeAndCollapseRetracesSizing() {
        val progress = Animatable(0f)
        var layout by mutableStateOf(ArtworkLayout.Floating)
        var selectedVisual by mutableStateOf(visual)
        var reduced by mutableStateOf(false)
        lateinit var scope: CoroutineScope
        var growth: PlayerArtworkGrowthPresentation? = null
        data class Frame(val progress: Float, val layout: ArtworkLayout, val aspect: Float, val reduced: Boolean)
        var drawnFrame: Frame? = null
        compose.setContent {
            val frame = Frame(progress.value, layout, selectedVisual.palette.aspect, reduced)
            LocalicaTheme { CompositionLocalProvider(LocalPlayerArtworkGrowthObserver provides { growth = it }) {
                BoxWithConstraints(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawnFrame = frame
                }) {
                    scope = rememberCoroutineScope()
                    PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = layout, reducedMotion = reduced),
                        progress, {}, {}, {}, {}, {}, {}, visual = selectedVisual)
                }
            } }
        }
        fun sampleProgress(p: Float) {
            compose.runOnIdle {
                val snap = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue("The direct progress sample must complete synchronously", snap.isCompleted)
                assertEquals("The fixture owns this exact progress", p, progress.value, 0f)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Bounds must belong to the requested drawn source frame",
                    Frame(p, layout, selectedVisual.palette.aspect, reduced), drawnFrame)
                assertEquals("Growth evidence belongs to this progress", p, requireNotNull(growth).expansion, 0f)
            }
        }
        fun presentedTitleBounds(): DpRect {
            val coordinates = compose.onNodeWithTag("player-title", true).fetchSemanticsNode().layoutInfo.coordinates
            return compose.runOnUiThread {
                // The layout modifier scales the child layer. Unclipped test bounds
                // retain that child's unscaled size; measure its transformed rectangle.
                val bounds = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
                with(compose.density) { DpRect(bounds.left.toDp(), bounds.top.toDp(), bounds.right.toDp(), bounds.bottom.toDp()) }
            }
        }
        val evidence = File(playerMotionOutputDirectory(), "artwork-size-frames.txt")
        evidence.writeText("")
        var admitted = 0
        for (reduce in listOf(false, true)) for (artworkLayout in ArtworkLayout.entries) for (aspect in listOf(1f / 3f, 1f, 3f)) {
            compose.runOnIdle {
                reduced = reduce
                layout = artworkLayout
                selectedVisual = visual.copy(artwork = Bitmap.createBitmap(if (aspect > 1f) 96 else 32,
                    if (aspect < 1f) 96 else 32, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.RED)
                }.asImageBitmap(), palette = visual.palette.copy(aspect = aspect))
            }
            compose.waitForIdle()
            sampleProgress(1f)
            val full = compose.onNodeWithTag("player-artwork", true).getUnclippedBoundsInRoot()
            val fullHost = compose.onNodeWithTag("player-background-space", true).getUnclippedBoundsInRoot()
            val backgroundHeight = fullHost.height.value
            sampleProgress(0f)
            val mini = compose.onNodeWithTag("player-artwork", true).getUnclippedBoundsInRoot()
            val miniTitle = presentedTitleBounds()
            val miniHost = compose.onNodeWithTag("player-background-space", true).getUnclippedBoundsInRoot()
            assertEquals("Measured mini title keeps its delivered reference", 7f, (miniTitle.top - miniHost.top).value, 1f)
            assertEquals("Native mini endpoint width", 32f, mini.width.value, .5f)
            assertEquals("Native mini endpoint height", 32f, mini.height.value, .5f)
            if (!requireNotNull(growth).curve.constrained) admitted++
            val forward = listOf(0f, .125f, .25f, .5f, .75f, 1f)
            val snapshots = mutableMapOf<Float, androidx.compose.ui.unit.DpRect>()
            for (p in forward + forward.dropLast(1).asReversed()) {
                sampleProgress(p)
                val observed = requireNotNull(growth)
                val source = observed.trajectory.single { it.expansion == p }
                val art = compose.onNodeWithTag("player-artwork", true).getUnclippedBoundsInRoot()
                val title = presentedTitleBounds()
                val host = compose.onNodeWithTag("player-background-space", true).getUnclippedBoundsInRoot()
                val context = "layout=$artworkLayout aspect=$aspect reduced=$reduce p=$p art=$art title=$title"
                evidence.appendText(context + " curve=" + observed.curve + " source=" + source + "\n")
                assertEquals("Actual artwork left supplies the curve: $context", source.artworkLeft, (art.left - host.left).value, 1f)
                assertEquals("Actual artwork top supplies the curve: $context", source.artworkTop, (art.top - host.top).value, 1f)
                assertEquals("Actual title top supplies the curve: $context", source.titleTop, (title.top - host.top).value, 1f)
                assertEquals("Measured title height supplies the band: $context", source.titleBottom, (title.bottom - host.top).value, 1f)
                assertEquals("Measured title right: $context", source.titleRight, (title.right - host.left).value, 1f)
                assertEquals("Measured title left supplies corner contact: $context", source.titleLeft,
                    (title.left - host.left).value, 1f)
                val q = playerArtworkSizeProgress(p, observed.curve)
                val expectedWidth = 32f + (observed.fullWidth - 32f) * q
                val expectedHeight = minOf(backgroundHeight, 32f + (observed.fullHeight - 32f) * q)
                assertEquals("Native immediate-ease width: $context", expectedWidth, art.width.value, 1f)
                assertEquals("Native immediate-ease height: $context", expectedHeight, art.height.value, 1f)
                if (p > 0f && p < 1f) {
                    assertTrue("No hold/grow gate", q > 0f)
                    if (expectedWidth > 32f + 1.1f / compose.density.density)
                        assertTrue("Observable growth begins before settling: $context", art.width > 32.dp)
                    if (expectedHeight > 32f + 1.1f / compose.density.density)
                        assertTrue("Observable height begins before settling: $context", art.height > 32.dp)
                }
                if (observed.curve.constrained) {
                    assertFalse("Unsupported geometry never claims corner exclusion", observed.curve.cornerAligned)
                    assertTrue("Best effort stays within the bounded immediate family", q >= p * p * p * p - .0001f && q <= p + .0001f)
                } else {
                    val rawBottom = source.artworkTop + 32f + (observed.fullHeight - 32f) * q
                    if (observed.curve.cornerAligned && rawBottom > source.titleTop + 1f &&
                        source.artworkTop < source.titleBottom) {
                        assertTrue("Aligned artwork never overlays the title: $context", art.right <= title.left + 1.dp)
                    }
                    if (rawBottom in source.titleTop..source.titleBottom) {
                        assertTrue("Corner remains inside title-right envelope: $context", art.right <= title.right + 1.dp)
                        assertTrue("Corner respects the fixed artwork-left reference: $context", (art.right - host.left).value >= 15f)
                    }
                }
                if (p == 1f) assertEquals("Expanded geometry remains unchanged", full, art)
                snapshots[p]?.let { assertEquals("Held reversal must retrace artwork bounds", it, art) }
                snapshots[p] = art
            }
        }
        assertTrue("Native matrix must exercise admitted geometry rather than only fallback", admitted > 0)
    }

    @Test fun tallArtworkActuallyMeetsTheTitleCornerAndRetracesDuringHeldReversal() =
        verifyCornerContactAndHeldReversal(960.dp)

    @Test fun compactArtworkActuallyMeetsTheTitleCornerAndRetracesDuringHeldReversal() =
        verifyCornerContactAndHeldReversal(600.dp)

    private fun verifyCornerContactAndHeldReversal(height: Dp) {
        val progress = Animatable(0f)
        var growth: PlayerArtworkGrowthPresentation? = null
        lateinit var scope: CoroutineScope
        compose.setContent {
            LocalicaTheme { CompositionLocalProvider(LocalPlayerArtworkGrowthObserver provides { growth = it }) {
                // Logical compact/tall fixtures must exercise admission on either emulator size.
                BoxWithConstraints(Modifier.requiredSize(412.dp, height)) {
                    scope = rememberCoroutineScope()
                    PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = ArtworkLayout.Floating),
                        progress, {}, {}, {}, {}, {}, {}, visual = visual)
                }
            } }
        }
        compose.waitForIdle()
        val initial = requireNotNull(growth)
        assertTrue("$height fixture must use corner-aligned curve", initial.curve.cornerAligned)
        compose.mainClock.autoAdvance = false
        fun gap(p: Float): Float {
            val points = initial.trajectory
            val index = (p * (points.size - 1)).toInt().coerceIn(0, points.size - 2)
            val a = points[index]; val b = points[index + 1]
            val t = (p - a.expansion) / (b.expansion - a.expansion)
            val top = a.artworkTop + (b.artworkTop - a.artworkTop) * t
            val title = a.titleTop + (b.titleTop - a.titleTop) * t
            return top + 32f + (initial.fullHeight - 32f) * playerArtworkSizeProgress(p, initial.curve) - title
        }
        var lo = 0f; var hi = .5f
        assertTrue(gap(lo) > 0f && gap(hi) < 0f)
        repeat(24) { val mid = (lo + hi) / 2f; if (gap(mid) > 0f) lo = mid else hi = mid }
        val contact = (lo + hi) / 2f
        val snapshots = mutableMapOf<Float, DpRect>()
        val samples = listOf(0f, .001f, .03f, contact - .003f, contact, contact + .003f, .2f, .4f, .8f, 1f).sorted()
        for (p in samples + samples.dropLast(1).asReversed()) {
            compose.runOnIdle {
                val snap = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue("Held progress snap completes synchronously", snap.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("Sample must not be displaced by a settling animation", p, requireNotNull(growth).expansion, 0f)
            assertEquals("Finger movement does not change its geometry-derived curve", initial.curve, requireNotNull(growth).curve)
            val artwork = compose.onNodeWithTag("player-artwork", true).getUnclippedBoundsInRoot()
            val coordinates = compose.onNodeWithTag("player-title", true).fetchSemanticsNode().layoutInfo.coordinates
            val title = compose.runOnUiThread {
                val bounds = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
                with(compose.density) { DpRect(bounds.left.toDp(), bounds.top.toDp(), bounds.right.toDp(), bounds.bottom.toDp()) }
            }
            if (p == contact) {
                assertEquals("Vertical corner contact", title.top.value, artwork.bottom.value, 1.5f)
                assertEquals("Horizontal corner contact", title.left.value, artwork.right.value, 1.5f)
            }
            if (artwork.bottom > title.top + 1.dp && artwork.top < title.bottom)
                assertTrue("Artwork must remain left of title before clearing it", artwork.right <= title.left + 1.dp)
            snapshots[p]?.let { assertEquals("Native reversal retraces the held artwork", it, artwork) }
            snapshots[p] = artwork
        }
    }

    @Test fun animatedArtworkLayoutRetainsBestEffortAcrossAdmissionChanges() {
        val progress = Animatable(0f)
        var growth: PlayerArtworkGrowthPresentation? = null
        var layout by mutableStateOf(ArtworkLayout.Floating)
        lateinit var scope: CoroutineScope
        compose.setContent {
            LocalicaTheme { CompositionLocalProvider(LocalPlayerArtworkGrowthObserver provides { growth = it }) {
                BoxWithConstraints(Modifier.requiredSize(412.dp, 600.dp)) {
                    scope = rememberCoroutineScope()
                    PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = layout),
                        progress, {}, {}, {}, {}, {}, {}, visual = visual)
                }
            } }
        }
        compose.waitForIdle()
        assertTrue("Compact fixture must start with admitted contact", requireNotNull(growth).curve.cornerAligned)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(.5f) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var previous = requireNotNull(growth).curve
        var admissionChanges = 0
        for ((target, frames) in listOf(ArtworkLayout.FullBackground to 8, ArtworkLayout.Floating to 32,
            ArtworkLayout.FullBackground to 32, ArtworkLayout.Floating to 32,
            ArtworkLayout.FullBackground to 32, ArtworkLayout.Floating to 32)) {
            compose.runOnIdle { layout = target }
            repeat(frames) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val observed = requireNotNull(growth)
                assertEquals("Layout changes do not steal held finger progress", .5f, observed.expansion, 0f)
                if (previous.cornerAligned != observed.curve.cornerAligned) admissionChanges++
                val q = playerArtworkSizeProgress(.5f, observed.curve)
                assertEquals("An admission change must not replace the fitted curve with p squared",
                    playerArtworkSizeProgress(.5f, previous), q, .08f)
                val artwork = compose.onNodeWithTag("player-artwork", true).getUnclippedBoundsInRoot()
                assertEquals("The native frame uses this geometry's full curve, without a stale curvature tween",
                    32f + (observed.fullWidth - 32f) * q, artwork.width.value, 1f)
                val coordinates = compose.onNodeWithTag("player-title", true).fetchSemanticsNode().layoutInfo.coordinates
                val title = compose.runOnUiThread {
                    val bounds = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
                    with(compose.density) { DpRect(bounds.left.toDp(), bounds.top.toDp(), bounds.right.toDp(), bounds.bottom.toDp()) }
                }
                if (observed.curve.cornerAligned && artwork.bottom > title.top + 1.dp && artwork.top < title.bottom)
                    assertTrue("Admitted intermediate layout must not cover the actual title", artwork.right <= title.left + 1.dp)
                previous = observed.curve
            }
        }
        assertTrue("Native animation must actually cross the admission boundary in both directions", admissionChanges >= 4)
        assertTrue("Repeated and interrupted layout changes restore the admitted floating endpoint", requireNotNull(growth).curve.cornerAligned)
    }

    @Test fun animatedArtworkAspectCrossesMiniHeightWithoutAFallbackJump() {
        val progress = Animatable(0f)
        var growth: PlayerArtworkGrowthPresentation? = null
        var selectedVisual by mutableStateOf(visual)
        lateinit var scope: CoroutineScope
        compose.setContent {
            LocalicaTheme { CompositionLocalProvider(LocalPlayerArtworkGrowthObserver provides { growth = it }) {
                BoxWithConstraints(Modifier.requiredSize(320.dp, 600.dp)) {
                    scope = rememberCoroutineScope()
                    PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = ArtworkLayout.Floating),
                        progress, {}, {}, {}, {}, {}, {}, visual = selectedVisual)
                }
            } }
        }
        compose.waitForIdle()
        // Derive the actual threshold rather than assuming emulator status/navigation insets.
        val aspectAtMiniHeight = requireNotNull(growth).fullWidth / 32f
        compose.runOnIdle { selectedVisual = visual.copy(palette = visual.palette.copy(aspect = aspectAtMiniHeight * .95f)) }
        compose.waitForIdle()
        assertTrue("Start above the mini height", requireNotNull(growth).fullHeight > 32f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(.5f) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var previous = playerArtworkSizeProgress(.5f, requireNotNull(growth).curve)
        var crossedBelow = false
        var sawBlendPair = false
        for (factor in listOf(1.05f, .95f, 1.05f, .95f)) {
            compose.runOnIdle { selectedVisual = visual.copy(palette = visual.palette.copy(aspect = aspectAtMiniHeight * factor)) }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val observed = requireNotNull(growth)
                val q = playerArtworkSizeProgress(.5f, observed.curve)
                assertEquals("Aspect animation cannot switch to quadratic at a 32 dp dimension", previous, q, .05f)
                assertEquals("Aspect animation preserves held finger progress", .5f, observed.expansion, 0f)
                // Aspect changes also crossfade the visual. Unmerged semantics retains
                // the cleared outgoing tree, so validate every real cover layer rather
                // than demanding a unique node or silently choosing one of the pair.
                val artworks = compose.onAllNodesWithTag("player-artwork", true)
                val artworkCount = artworks.fetchSemanticsNodes().size
                assertTrue("The actual cover has one settled layer or an outgoing/incoming pair", artworkCount in 1..2)
                sawBlendPair = sawBlendPair || artworkCount == 2
                repeat(artworkCount) { index ->
                    val artwork = artworks[index].getUnclippedBoundsInRoot()
                    assertEquals("Every native layer uses the continuous extreme-aspect width", 32f + (observed.fullWidth - 32f) * q, artwork.width.value, 1f)
                    assertEquals("Every native layer may shrink continuously below mini size", 32f + (observed.fullHeight - 32f) * q, artwork.height.value, 1f)
                }
                crossedBelow = crossedBelow || observed.fullHeight < 32f
                previous = q
            }
        }
        assertTrue("Native fixture observes the outgoing and incoming cover during the actual blend", sawBlendPair)
        assertTrue("Native fixture must actually cross the degeneracy", crossedBelow)
        assertTrue("Reversal restores the other side of mini height", requireNotNull(growth).fullHeight > 32f)
    }

    private fun playerMotionOutputDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val directory = File(root, "player-expansion-motion")
        check(directory.isDirectory || directory.mkdirs())
        return directory
    }

    private fun recordSemantics(layout: ArtworkLayout, stage: String) {
        trace("semantics-begin:$layout:$stage")
        File(playerMotionOutputDirectory(), "$layout-$stage.txt").writeText(
            "MERGED\n${compose.onRoot().printToString()}\nUNMERGED\n${compose.onRoot(useUnmergedTree = true).printToString()}")
        trace("semantics-end:$layout:$stage")
    }

    @Test fun artworkSeekAndActionsHaveSeparateScreenSpaceMotionBetweenTitleAndTransport() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = visual) {
                Text("Album-only action")
            }
        } } }
        val tags = listOf("player-title", "player-seek-row", "player-actions-row")
        fun tops() = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top } +
            compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { scope.launch { progress.snapTo(1f) } }
        val full = tops()
        val samples = mutableMapOf<Float, List<Float>>()
        for (p in listOf(.98f, .95f, .9f, .85f, .9f, .95f, .98f, 1f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            val current = tops()
            if (p < 1f) {
                val movement = current.zip(full) { now, target -> now - target }
                assertTrue("Title < seek < actions < transport at $p: $movement",
                    movement.zipWithNext().all { (slower, faster) -> slower < faster })
            } else assertEquals(full, current)
            samples[p]?.let { assertEquals("Reversal uses the same displayed trajectory at $p", it, current) }
            samples[p] = current
        }
    }

    @Test fun albumRowsRetainTheUnchangedTitleAndTransportReferences() = exerciseUnchangedReferences(false)

    @Test fun selectedLyricsRetainTheUnchangedTitleAndTransportReferences() = exerciseUnchangedReferences(true)

    private fun exerciseUnchangedReferences(lyrics: Boolean) {
        val progress = Animatable(0f)
        var lyricsMode by mutableStateOf(false)
        lateinit var scope: CoroutineScope
        var pixelsPerDp = 0f
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            pixelsPerDp = androidx.compose.ui.platform.LocalDensity.current.density
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = lyricsMode, visual = visual) { Text("Album-only action") }
        } } }
        fun snap(p: Float) {
            compose.runOnIdle {
                val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                assertTrue("Fixture progress is applied before measuring", update.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        fun surfaceTop() = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot.top
        fun references() = listOf(compose.onNodeWithTag("player-title", true).fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot.top).map { it - surfaceTop() }
        snap(1f)
        val normal = references()
        compose.runOnIdle { lyricsMode = lyrics }
        compose.waitForIdle()
        val selected = references()
        snap(0f)
        val mini = references()
        val snapshots = mutableMapOf<Float, List<Float>>()
        for (p in listOf(0f, .2f, .5f, .8f, .85f, .91f, .97f, 1f, .97f, .91f, .85f, .8f, .5f, .2f, 0f)) {
            snap(p)
            val actual = references()
            val metadata = normal[0] + (selected[0] - normal[0]) * p
            val transportTarget = normal[1] + (selected[1] - normal[1]) * p
            val smooth = p * p * (3f - 2f * p)
            val title = mini[0] + (metadata - mini[0]) * p
            val transport = mini[1] + (transportTarget - mini[1]) * smooth
            // The two independently rounded reference endpoints/placement can differ by one pixel each.
            assertEquals("Delivered title trajectory at p=$p lyrics=$lyrics", title, actual[0], 2f)
            assertEquals("Delivered transport trajectory at p=$p lyrics=$lyrics", transport, actual[1], 2f)
            if (p > .83f) {
                val seek = compose.onNodeWithTag("player-seek-row", true).fetchSemanticsNode().boundsInRoot.top - surfaceTop()
                val titleOffset = title - metadata
                val transportOffset = transport - transportTarget
                assertEquals("Seek mixes the unchanged references at p=$p", metadata + 64f * pixelsPerDp +
                    titleOffset + (transportOffset - titleOffset) / 3f, seek, 2f)
                if (!lyrics) {
                    val actions = compose.onNodeWithTag("player-actions-row", true).fetchSemanticsNode().boundsInRoot.top - surfaceTop()
                    assertEquals("Actions retain their distinct reference mixture at p=$p", normal[0] + 128f * pixelsPerDp +
                        titleOffset + (transportOffset - titleOffset) * (2f / 3f), actions, 2f)
                }
            }
            snapshots[p]?.let { assertEquals("Held reversal retraces both unchanged references", it, actual) }
            snapshots[p] = actual
        }
    }
}
