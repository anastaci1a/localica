package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalGraphicsContext
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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class PlayerExpansionMotionTest {
    @get:Rule val compose = createComposeRule()
    private fun trace(phase: String) {
        Log.i("PlayerExpansionPhase", "ns=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name} phase=$phase")
    }
    @Composable
    private fun ObservePlayerGraphics(label: () -> String, content: @Composable () -> Unit) {
        val original = LocalGraphicsContext.current
        val currentLabel by rememberUpdatedState(label)
        val previousObserver by rememberUpdatedState(LocalLyricsBackdropObserver.current)
        val observation = remember(original) { PlayerGraphicsObservation(original, { currentLabel() }, ::trace) }
        val observer: (LyricsBackdropScene, Boolean) -> Unit = remember(observation) { { scene, mounted ->
            observation.scene(scene, mounted)
            previousObserver?.invoke(scene, mounted)
        } }
        CompositionLocalProvider(LocalGraphicsContext provides observation,
            LocalLayerReadbackObservation provides { event -> trace("readback:$event") },
            LocalLyricsBackdropObserver provides observer, content = content)
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
        compose.setContent { ObservePlayerGraphics({ "$contentTag layout=$layout progress=${progress.value} expanded=$expanded reduced=$reducedMotion" }) { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            DisposableEffect(Unit) {
                trace("composition-enter:$contentTag")
                onDispose { trace("composition-dispose:$contentTag") }
            }
            scope = rememberCoroutineScope()
            PlayerSurface(state.copy(trackId = track.id, trackUri = track.uri), expanded, { expanded = it },
                InterfaceSettings(artworkLayout = layout, reducedMotion = reducedMotion), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = true, track = track, visual = visual) { Text("Album-only action") }
        } } } }
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
            ObservePlayerGraphics({ "artwork-matrix layout=$layout progress=${progress.value} reduced=$reduced" }) {
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
                assertEquals("Use title RIGHT, never title-left: $context", source.titleRight, (title.right - host.left).value, 1f)
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
                    assertEquals("Explicit unsupported geometry uses ordinary immediate ease", p * p, q, .0001f)
                } else {
                    val rawBottom = source.artworkTop + 32f + (observed.fullHeight - 32f) * q
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
        compose.setContent { ObservePlayerGraphics({ "separate-motion progress=${progress.value}" }) { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = visual) {
                Text("Album-only action")
            }
        } } } }
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
        compose.setContent { ObservePlayerGraphics({ "unchanged-references lyrics=$lyrics progress=${progress.value}" }) { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            pixelsPerDp = androidx.compose.ui.platform.LocalDensity.current.density
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = lyricsMode, visual = visual) { Text("Album-only action") }
        } } } }
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

/** Public LocalGraphicsContext routing only, not a census of framework/native layers.
 * Weak scene references and immutable retired metadata do not retain readback leases.
 * No disposal action releases or delays a layer: the original owner remains responsible. */
private class PlayerGraphicsObservation(private val original: GraphicsContext,
    private val label: () -> String, private val emit: (String) -> Unit) : GraphicsContext by original {
    private val sequence = AtomicLong()
    @Volatile private var scenes = emptyList<WeakReference<LyricsBackdropScene>>()
    @Volatile private var retired = emptyList<String>()
    private val lastObserved = mutableMapOf<Int, List<String>>()

    fun scene(scene: LyricsBackdropScene, mounted: Boolean) {
        runCatching { Snapshot.withoutReadObservation {
            val id = System.identityHashCode(scene)
            val metadata = metadata(scene)
            val live = scenes.filter { it.get()?.let { current -> current !== scene || mounted } == true }
            scenes = if (mounted && live.none { it.get() === scene }) live + WeakReference(scene) else live
            if (!mounted) {
                retired = metadata
                lastObserved.remove(id)
            }
            if (!mounted || lastObserved[id] != metadata) {
                if (mounted) lastObserved[id] = metadata
                val event = sequence.incrementAndGet()
                emit("graphics:$event scene-observed mounted=$mounted ${label()}")
                metadata.forEach { emit("graphics:$event observed $it") }
            }
        } }
    }

    override fun createGraphicsLayer(): GraphicsLayer {
        val layer = original.createGraphicsLayer()
        record("create-return", layer)
        return layer
    }

    override fun releaseGraphicsLayer(layer: GraphicsLayer) {
        record("release-before", layer)
        original.releaseGraphicsLayer(layer)
        record("release-return", layer)
    }

    private fun record(operation: String, layer: GraphicsLayer) {
        // Logging failures must not replace an original create/release result or exception.
        runCatching { Snapshot.withoutReadObservation {
            val event = sequence.incrementAndGet()
            emit("graphics:$event $operation context=${System.identityHashCode(original)} layer=${System.identityHashCode(layer)} released=${layer.isReleased} ${label()}")
            scenes.mapNotNull { it.get() }.forEach { scene ->
                metadata(scene).forEach { emit("graphics:$event live $it") }
            }
            // These are explicitly the last unmounted observations, not current status.
            retired.forEach { emit("graphics:$event last-unmounted $it") }
        } }
    }

    private fun metadata(scene: LyricsBackdropScene): List<String> {
        val id = System.identityHashCode(scene)
        val result = (listOf("scene=$id composite ${source(scene.source)}") + scene.layers.map { producer ->
            "scene=$id producer=${System.identityHashCode(producer)} recordedRequest=${producer.recordedReadbackRequest} maskedRequest=${producer.maskedReadbackRequest} ${source(producer.source)}"
        }).toMutableList()
        // Public, non-atomic metadata only: recorded is sticky, not a fresh draw
        // acknowledgement or native retention proof. Keep no graph after this call.
        val seen = mutableListOf(scene.source)
        var child = scene.source
        for (depth in 1..8) {
            val parent = child.parent
            if (parent == null) {
                result += "scene=$id parentChainEnd depth=$depth parent=null"
                return result
            }
            if (seen.any { it === parent }) {
                result += "scene=$id parentChainCycle depth=$depth fromSource=${System.identityHashCode(child)} source=${System.identityHashCode(parent)}"
                return result
            }
            result += "scene=$id parentDepth=$depth fromSource=${System.identityHashCode(child)} nonAtomicMetadata ${source(parent)}"
            seen += parent
            child = parent
        }
        result += "scene=$id parentChainBound limit=8 remaining=${child.parent != null}"
        return result
    }

    private fun source(source: FrostedBackdrop): String =
        "source=${System.identityHashCode(source)} layer=${System.identityHashCode(source.layer)} request=${source.readbackRequest} snapshotRequest=${source.snapshotReadbackRequest} recorded=${source.recorded} released=${source.layer.isReleased} size=${source.size} snapshot=${source.snapshot?.let { System.identityHashCode(it) }}"
}
