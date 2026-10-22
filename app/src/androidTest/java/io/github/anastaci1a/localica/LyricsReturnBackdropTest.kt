package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LyricLine
import io.github.anastaci1a.localica.lyrics.LyricSource
import io.github.anastaci1a.localica.lyrics.LyricsResult
import io.github.anastaci1a.localica.lyrics.ParsedLyrics
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Real lyric text and the real return control, on both the legacy and native lens paths.
 * Chroma separates lyric ink from the neutral arrow, edge illumination and shadow. */
class LyricsReturnBackdropTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private lateinit var artwork: FrostedBackdrop
    private var stage by mutableIntStateOf(0)
    private var sampledScene by mutableStateOf<LyricsBackdropScene?>(null)
    private var sampleOrigin by mutableStateOf(Offset.Zero)
    private var lastSnapshot: ImageBitmap? = null
    private lateinit var fixtureState: Scene
    private val sourceFrameIds = mutableMapOf<Any, Int>()

    private data class ViewportPose(val surfaceTop: Float, val titleTop: Float) {
        val viewport get() = playerLyricsViewport(surfaceTop, titleTop, metadataTarget = 348f, topInset = 24f)
    }

    private class Scene {
        var document by mutableStateOf(document('M'))
        var incoming by mutableStateOf<ParsedLyrics?>(null)
        var position by mutableLongStateOf(30_000L)
        var depth by mutableFloatStateOf(.6f)
        var optics by mutableStateOf(true)
        var art by mutableStateOf(Color.Blue)
        var alpha by mutableFloatStateOf(1f)
        var shown by mutableStateOf(true)
        var song by mutableIntStateOf(0)
        var source by mutableIntStateOf(0)
        var pose by mutableStateOf<ViewportPose?>(null)
    }

    @Test fun movingAndReemphasizingGlyphsRefreshesTheActualReturnLens() {
        val state = Scene()
        mount(state)
        alignCurrentLine(state.document)

        // Establish that actual glyph pixels, not merely the Text layout bounds,
        // intersect the stable interior sampled below. The icon/rim remain present.
        compose.runOnIdle { state.optics = false }
        val direct = capture("direct-glyphs")
        val button = returnBounds()
        assertTrue("Fixture must put green lyric ink behind the button", green(direct, button) > .04f)
        compose.runOnIdle { state.optics = true }
        val shallow = capture("shallow")
        assertTrue("Return glass must contain lyric ink, not just artwork", green(shallow, button) > .025f)
        assertEquals("The sampled glyphs retain the actual bottom-viewport fade, not raw text alpha",
            green(direct, button), green(shallow, localBounds(compose.onNodeWithTag(ProbeTag))), .012f)
        assertTrue("Artwork-only negative control cannot contain green lyric ink",
            green(shallow, referenceBounds()) < .015f)

        compose.runOnIdle { state.depth = 2f }
        val deep = capture("deep")
        assertChromaMoves("Changing depth must refract the recorded glyphs, not expose direct underlay", shallow, deep, button, green = true)
        assertEquals("Depth cannot alter uncovered lyrics", uncoveredGreen(shallow, button), uncoveredGreen(deep, button), .003f)
        assertTrue("Depth/illumination alone cannot create lyric chroma", green(deep, referenceBounds()) < .015f)

        // A large enough displacement leaves only whitespace beneath the fixed
        // control. A cached pre-scroll source would still contain the green M.
        val distance = button.width * 1.4f
        scrollBy(distance)
        val moved = capture("scrolled-away", expectFresh = true)
        assertTrue("Settled legacy and modern sources must follow scrolling",
            green(moved, button) < green(deep, button) * .25f + .005f)
        scrollBy(-distance)
        val returned = capture("scrolled-back", expectFresh = true)
        assertEquals("The source remains registered after reversing the scroll",
            green(deep, button), green(returned, button), .025f)

        compose.runOnIdle { state.position = 31_000L }
        val reemphasized = capture("new-active-line", expectFresh = true)
        assertTrue("The old current line must refresh to red inactive ink", red(reemphasized, button) > .015f)
        assertTrue("No stale green emphasis may remain", green(reemphasized, button) < .015f)
        compose.runOnIdle { state.document = document(' ') }
        val blank = capture("blank-document", expectFresh = true)
        assertTrue("Blank-document negative control must remove sampled lyric detail",
            green(blank, button) < .015f && red(blank, button) < .015f)
        val blankProbe = localBounds(compose.onNodeWithTag(ProbeTag))
        for (y in (blankProbe.top + blankProbe.height * .3f).toInt() until (blankProbe.top + blankProbe.height * .7f).toInt())
            for (x in (blankProbe.left + blankProbe.width * .3f).toInt() until (blankProbe.left + blankProbe.width * .7f).toInt()) {
                val color = blank[x, y]
                assertTrue("The return arrow must never enter its own sampled source: $color",
                    color.blue > .98f && color.red < .015f && color.green < .015f)
            }
        val steady = capture("blank-still-stable")
        assertTrue("An idle control cannot accumulate its own arrow or rim in its source",
            interiorDifference(blank, steady, button) < .003f)
        if (Build.VERSION.SDK_INT < 33) assertLegacyReadbackBecomesIdle(requireNotNull(sampledScene))
    }

    @Test fun movingPlayerViewportKeepsTheActiveReturnLensRegisteredThroughReversal() {
        val poses = listOf(ViewportPose(0f, 348f), ViewportPose(48f, 316f))
        val state = Scene().apply { pose = poses.first() }
        mount(state)
        alignCurrentLine(state.document)
        val source = requireNotNull(sampledScene).source
        val selected = compose.onNodeWithText(state.document.lines[30].text)
        val list = compose.onNodeWithTag("lyric-lines")
        val initialRowOffset = (selected.getUnclippedBoundsInRoot().top - list.getUnclippedBoundsInRoot().top).value
        val frames = mutableMapOf<ViewportPose, PixelMap>()
        val bounds = mutableMapOf<ViewportPose, Rect>()
        // Keep the same document, scroll state and active return consumer while the
        // upper edge first stays window-anchored, then is clipped by the player.
        // The content drifts inside its stable 320 dp layout in both directions.
        for ((index, pose) in (poses + poses.dropLast(1).asReversed()).withIndex()) {
            compose.runOnIdle { state.pose = pose; state.depth = .6f }
            compose.onNodeWithContentDescription(ReturnLabel).assertIsDisplayed()
            updateSampleOrigin()
            // Capture with optics still enabled, before toggling the negative
            // control: reacquiring a lease must not conceal a stale motion frame.
            val shallow = capture("viewport-$index-shallow", expectFresh = index > 0)
            val button = returnBounds()
            val probe = localBounds(compose.onNodeWithTag(ProbeTag))
            assertSame("Viewport motion must retain the active sampling scene", source, requireNotNull(sampledScene).source)
            if (Build.VERSION.SDK_INT < 33)
                assertTrue("The moving return control must keep its legacy sampling lease", source.consumers > 0)
            val viewport = compose.onNodeWithTag("lyrics-viewport", true).getUnclippedBoundsInRoot()
            val root = compose.onNodeWithTag(SceneTag).getUnclippedBoundsInRoot()
            assertEquals("The viewport top must follow the player's clipping edge", max(24f, pose.surfaceTop),
                (viewport.top - root.top).value, 1f)
            assertEquals("The viewport bottom must follow the presented title", pose.surfaceTop + pose.titleTop - 4f,
                (viewport.bottom - root.top).value, 1f)
            assertEquals("Inner lyric layout must not resize with the viewport", 320f, list.getUnclippedBoundsInRoot().height.value, 1f)
            assertEquals("Viewport motion must not scroll or replace the lyric document", initialRowOffset,
                (selected.getUnclippedBoundsInRoot().top - list.getUnclippedBoundsInRoot().top).value, 1f)
            assertTrue("The moving lens must still sample actual lyric ink", green(shallow, button) > .025f)
            assertTrue("Artwork-only control cannot acquire lyric ink during motion", green(shallow, referenceBounds()) < .015f)

            compose.runOnIdle { state.optics = false }
            val direct = capture("viewport-$index-direct")
            assertTrue("Every held viewport must contain real glyph pixels behind its return control", green(direct, button) > .04f)
            assertEquals("Moving source must match the displayed clipped/faded viewport, not stale or raw glyphs",
                green(direct, button), green(shallow, probe), .012f)
            compose.runOnIdle { state.optics = true; state.depth = 2f }
            val deep = capture("viewport-$index-deep")
            assertChromaMoves("The actual return lens must refract glyphs at each held viewport", shallow, deep, button, green = true)
            assertEquals("Changing lens depth must not alter uncovered moving lyrics",
                uncoveredGreen(shallow, button), uncoveredGreen(deep, button), .003f)

            frames[pose]?.let { previous ->
                assertEquals("Reversal must restore the presented return geometry", bounds.getValue(pose), button)
                assertTrue("Reversal must restore the sampled glyph pattern, not retain the later viewport",
                    interiorDifference(previous, shallow, button) < .015f)
            }
            frames[pose] = shallow
            bounds[pose] = button
        }
        assertTrue("The fixture must move the return control, not only its contents",
            abs(bounds.getValue(poses.last()).center.y - bounds.getValue(poses.first()).center.y) >
                bounds.getValue(poses.first()).width * .2f)
        compose.onNodeWithContentDescription(ReturnLabel).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ReturnLabel).assertDoesNotExist()
        selected.assertIsDisplayed().assertIsSelected()
    }

    @Test fun overlappingDocumentsShareTheReturnSourceAndFadeAfterCompositing() {
        val state = Scene()
        mount(state)
        alignCurrentLine(state.document)
        compose.runOnIdle { state.incoming = document('|', dense = true); state.optics = false }
        val button = returnBounds()
        val direct = capture("overlap-direct")
        assertTrue("Outgoing green glyph pixels must intersect the sampled interior", green(direct, button) > .008f)
        assertTrue("Incoming red glyph pixels must intersect the sampled interior", red(direct, button) > .008f)
        compose.runOnIdle { state.optics = true }
        val shallow = capture("overlap-shallow", expectFresh = true)
        val probe = localBounds(compose.onNodeWithTag(ProbeTag))
        assertTrue("The actual shared source contains both documents", green(shallow, probe) > .008f && red(shallow, probe) > .008f)
        compose.runOnIdle { state.depth = 2f }
        val deep = capture("overlap-deep")
        assertChromaMoves("Outgoing document must remain in the shared return source", shallow, deep, button, green = true)
        assertChromaMoves("Incoming document must enter that same source", shallow, deep, button, green = false)
        assertEquals("Depth does not change direct outgoing glyphs", uncoveredChroma(shallow, button, true), uncoveredChroma(deep, button, true), .003f)
        assertEquals("Depth does not change direct incoming glyphs", uncoveredChroma(shallow, button, false), uncoveredChroma(deep, button, false), .003f)
        assertTrue("Art-only control cannot manufacture either document's ink",
            green(deep, referenceBounds()) < .015f && red(deep, referenceBounds()) < .015f)

        compose.runOnIdle { state.alpha = .5f }
        val half = capture("overlap-half-reveal", expectFresh = true)
        val overlap = interior(deep, probe).filter { point ->
            deep[point.x.toInt(), point.y.toInt()].let { it.green > .08f && it.red > .04f }
        }
        assertTrue("Fixture must include truly overlapping glyph coverage", overlap.size > 6)
        for (point in overlap) {
            val fullColor = deep[point.x.toInt(), point.y.toInt()]
            val halfColor = half[point.x.toInt(), point.y.toInt()]
            assertEquals("Fade the combined green contribution once", fullColor.green * .5f, halfColor.green, .02f)
            assertEquals("Fade the combined red contribution once", fullColor.red * .5f, halfColor.red, .02f)
            assertEquals("Transparent combined glyphs retain parent artwork", .5f + fullColor.blue * .5f, halfColor.blue, .02f)
        }
    }

    @Test fun interruptedPanelSourcesAndSongsReopenWithFreshSamplingAndWorkingReturn() {
        val sparse = document('|', dense = true)
        val state = Scene().apply { document = sparse }
        mount(state, panel = true)
        alignCurrentLine(state.document)
        // A clean mount is the expected optical result. Average chroma alone cannot
        // order W and | coverage inside this small refracted, arrow-excluded ring.
        val cleanSparse = capture("clean-sparse-panel")
        val cleanGeometry = panelGeometry(state.document)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.source++; state.document = document('M', dense = true) }
            compose.mainClock.advanceTimeBy(80)
            compose.onAllNodesWithTag("lyric-lines").assertCountEquals(2)
            // Interrupt a real panel Crossfade with a different song/source.
            // Controlled overlapping-plane optical alpha is covered above, not
            // actual animated alpha synchronization in these settled screenshots.
            compose.runOnIdle { state.song++; state.source++; state.document = document('W', dense = true) }
            compose.mainClock.advanceTimeBy(80)
        } finally { compose.mainClock.autoAdvance = true }
        // A fresh song resumes following, so there is deliberately no Return
        // until the next manual drag. Capturing this state must not require one.
        capture("interrupted-crossfade-settled", expectReturn = false)
        compose.onAllNodesWithTag("lyric-lines").assertCountEquals(1)
        alignCurrentLine(state.document)
        val replacement = capture("replacement-song")
        assertTrue("The replacement song has fresh sampled glyphs", green(replacement, returnBounds()) > .02f)
        assertTrue("The clean sparse baseline must distinguish a stale dense W source",
            interiorDifference(cleanSparse, replacement, returnBounds()) > .025f)
        compose.runOnIdle { state.shown = false }
        compose.onNodeWithContentDescription(ReturnLabel).assertDoesNotExist()
        compose.runOnIdle {
            state.song++; state.source++; state.document = sparse; state.shown = true
        }
        compose.waitForIdle()
        alignCurrentLine(state.document)
        val reopened = capture("reopened-song")
        assertTrue("The reopened scene samples the new document", green(reopened, returnBounds()) > .01f)
        assertEquals("The clean and reopened samples need identical viewport, row and lens placement",
            cleanGeometry, panelGeometry(state.document))
        assertSameInterior("Reopening must reproduce the clean new-document source", cleanSparse, reopened, returnBounds())
        assertTrue("Reopening must replace the sampled glyph pattern, not reuse a previous bitmap",
            interiorDifference(replacement, reopened, returnBounds()) > .025f)
        compose.onNodeWithContentDescription(ReturnLabel).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ReturnLabel).assertDoesNotExist()
        val selected = compose.onNodeWithText(state.document.lines[30].text).assertIsDisplayed().assertIsSelected()
        assertTrue("Return still navigates to the current line after replacement and re-entry",
            selected.fetchSemanticsNode().boundsInRoot.center.y <
                compose.onNodeWithTag("lyric-lines").fetchSemanticsNode().boundsInRoot.center.y)
    }

    @Test fun transparentArtworkAndLyricRevealHaveOnlyOneDisplayOwner() {
        val state = Scene().apply { art = Color.Blue.copy(alpha = .5f); optics = false }
        mount(state)
        alignCurrentLine(state.document)
        val full = capture("transparent-art-full-lyrics")
        val button = returnBounds()
        val viewport = localBounds(compose.onNodeWithTag("lyric-lines"))
        // This strip is inside the lyric viewport but before the text's 4 dp inset.
        // Replaying the parent artwork as part of visible lyric recording yields
        // .75 blue instead of .5 even where there are no glyphs.
        val x = (viewport.left + 1f).roundToInt()
        val y = (viewport.top + viewport.height * .45f).roundToInt()
        assertEquals("Transparent parent artwork must not be drawn twice", .5f, full[x, y].blue, .025f)
        assertEquals(0f, full[x, y].red, .015f)
        assertTrue("Reveal fixture needs uncovered glyph pixels", uncoveredGreen(full, button) > .002f)
        compose.runOnIdle { state.alpha = .5f }
        val half = capture("transparent-art-half-lyrics")
        assertEquals("Reveal must not fade or duplicate the parent artwork", full[x, y].blue, half[x, y].blue, .015f)
        assertEquals("The displayed lyric glyphs have one reveal-alpha owner",
            .5f, uncoveredGreen(half, button) / uncoveredGreen(full, button), .04f)

        // Also inspect the real source used by the actual return control, rather
        // than mistaking the correctly faded direct glyphs for correct sampling.
        compose.runOnIdle { state.art = Color.Blue; state.alpha = 1f; state.optics = true }
        val sampledFull = capture("source-full-reveal", expectFresh = true)
        val probe = localBounds(compose.onNodeWithTag(ProbeTag))
        assertTrue("The real sampled source must contain glyphs", green(sampledFull, probe) > .04f)
        compose.runOnIdle { state.alpha = .5f }
        val sampledHalf = capture("source-half-reveal", expectFresh = true)
        assertEquals("The sampled lyric composition has exactly one reveal-alpha owner",
            .5f, green(sampledHalf, probe) / green(sampledFull, probe), .04f)
        assertTrue("Sampling must preserve artwork beneath transparent glyphs",
            interior(sampledHalf, probe).any { point -> sampledHalf[point.x.toInt(), point.y.toInt()].let { it.blue > .95f && it.green < .02f } })
    }

    private fun mount(state: Scene, panel: Boolean = false) {
        fixtureState = state
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.Green, onSurfaceVariant = Color.Red),
                typography = MaterialTheme.typography.copy(headlineSmall = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 24.sp, lineHeight = 28.sp))) {
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(depth = state.depth,
                    blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f),
                    LocalReducedMotion provides true) {
                    artwork = rememberFrostedBackdrop(state.art, glassBlur = 0f, whiteTint = 0f)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(360.dp, 384.dp).background(Color.Black).testTag(SceneTag)) {
                            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(artwork)) { drawRect(state.art) }
                            CompositionLocalProvider(LocalFrostedBackdrop provides artwork, LocalGlassVisible provides state.optics) {
                                if (state.shown) {
                                    val pose = state.pose
                                    val viewport = pose?.viewport
                                    Box(Modifier.fillMaxSize().offset(y = (pose?.surfaceTop ?: 0f).dp).clipToBounds()) {
                                        val modifier = Modifier.offset(8.dp, (viewport?.top ?: 24f).dp)
                                            .width(280.dp).height((viewport?.height ?: 320f).dp)
                                            .graphicsLayer { alpha = state.alpha }
                                        if (panel) LyricsPanel(Track("lyrics-sampling:${state.song}", "Sampling fixture", fileName = "fixture.mp3", relativePath = "fixture.mp3"),
                                            state.position, false, {}, modifier,
                                            result = LyricsResult(listOf(LyricSource("source-${state.source}", "Fixture", state.document))),
                                            contentAlpha = { state.alpha })
                                        else LyricsBackdrop(state.document to state.incoming, modifier, contentAlpha = { state.alpha }) {
                                            val scene = requireNotNull(LocalLyricsBackdropScene.current)
                                            SideEffect { sampledScene = scene }
                                            val outgoingAlpha = if (state.incoming == null) 1f else .5f
                                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = outgoingAlpha }) {
                                                LyricsLines(state.document, state.position, true, {}, Modifier.fillMaxSize(),
                                                    sourceAlpha = { outgoingAlpha },
                                                    contentHeight = if (viewport != null) 320.dp else null,
                                                    contentOffset = (viewport?.contentOffset ?: 0f).dp)
                                            }
                                            state.incoming?.let { incoming ->
                                                Box(Modifier.fillMaxSize().graphicsLayer { alpha = .5f }) {
                                                    LyricsLines(incoming, state.position, true, {}, Modifier.fillMaxSize(), sourceAlpha = { .5f })
                                                }
                                            }
                                            SourceFrameMarker(scene)
                                        }
                                    }
                                }
                                if (!panel) {
                                    // A diagnostic view of the actual source footprint, outside
                                    // player reveal alpha. It supplies no source to the button,
                                    // acquires no extra lease and is never a registered lyric layer.
                                    Canvas(Modifier.offset(300.dp, 24.dp).size(CompactControlSize).clipToBounds().testTag(ProbeTag)) {
                                        sampledScene?.source?.takeIf { state.optics && it.recorded }?.let { source ->
                                            val offset = source.origin - sampleOrigin
                                            translate(offset.x, offset.y) {
                                                if (Build.VERSION.SDK_INT >= 33) drawLayer(source.layer)
                                                else source.snapshot?.let { drawImage(it, dstSize = source.size) }
                                            }
                                        }
                                    }
                                }
                                Box(Modifier.offset(300.dp, 285.dp)) {
                                    ReturnToPositionButton(false, ReferenceLabel) { }
                                }
                            }
                            PresentationMarker { stage }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10_000) { artwork.lensSnapshot != null }
    }

    private fun alignCurrentLine(document: ParsedLyrics) {
        val list = compose.onNodeWithTag("lyric-lines")
        // Only a real drag suspends following; semantic scrolling alone does not.
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, height * .2f), 300)
            moveBy(Offset.Zero, 200)
            up()
        }
        compose.onNodeWithContentDescription(ReturnLabel).assertIsDisplayed()
        list.performScrollToIndex(30)
        val text = compose.onNodeWithText(document.lines[30].text)
        val delta = text.fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithContentDescription(ReturnLabel).fetchSemanticsNode().boundsInRoot.center.y
        scrollBy(delta)
        assertEquals("The selected glyph row must intersect the actual return control",
            compose.onNodeWithContentDescription(ReturnLabel).fetchSemanticsNode().boundsInRoot.center.y,
            text.fetchSemanticsNode().boundsInRoot.center.y, 2f)
        updateSampleOrigin()
    }

    private fun updateSampleOrigin() {
        // Re-read window coordinates after every viewport/ancestor move. These
        // are pixels already; never mix their origin with the fixture's dp inputs.
        val bounds = compose.onNodeWithContentDescription(ReturnLabel).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            sampleOrigin = bounds.topLeft + Offset(origin[0].toFloat(), origin[1].toFloat())
        }
    }

    private fun scrollBy(amount: Float) {
        compose.onNodeWithTag("lyric-lines").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, amount) }
        compose.waitForIdle()
    }

    private data class SourceFrame(val id: Int, val marker: Color, val sampled: Color)

    private fun sourceFrame(scene: LyricsBackdropScene): SourceFrame {
        val key = listOf(fixtureState.art, fixtureState.alpha, scene.layers.filter { it.contentKey() !== SourceMarkerKey }.map { layer ->
            listOf(layer.source.recorded, layer.source.size, layer.source.location.placement,
                layer.alpha(), layer.contentKey())
        })
        val id = sourceFrameIds.getOrPut(key) { sourceFrameIds.size + 1 }
        check(id < 4096)
        val marker = Color((id and 15) * 17, (id shr 4 and 15) * 17, (id shr 8 and 15) * 17)
        return SourceFrame(id, marker, marker.copy(alpha = fixtureState.alpha).compositeOver(fixtureState.art))
    }

    @Composable private fun SourceFrameMarker(scene: LyricsBackdropScene) {
        val recording = rememberGraphicsLayer()
        val source = remember(recording) { FrostedBackdrop(recording, false).apply { size = IntSize(8, 8) } }
        val layer = remember(source) { LyricsBackdropLayer(source, { 1f }, { SourceMarkerKey }) }
        DisposableEffect(scene, layer) {
            scene.layers.add(layer)
            onDispose { scene.layers.remove(layer) }
        }
        // Record only, outside every tested return footprint. This marker has no
        // onPlaced/onSizeChanged observer: its key, alpha, size and placement counter
        // stay constant, so it cannot request refresh in place of real lyric changes.
        Canvas(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(8.dp)) {
            scene.source.location.placement
            source.location.coordinates = scene.source.location.coordinates
            source.location.windowOffset = scene.source.location.windowOffset
            source.layer.record(size = source.size) { drawRect(sourceFrame(scene).marker) }
            source.recorded = true
        }
    }

    private fun awaitLegacySample(scene: LyricsBackdropScene, previous: ImageBitmap?, requireFresh: Boolean) {
        var requested = 0
        var observed = Color.Transparent
        try {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                val frame = compose.runOnUiThread { sourceFrame(scene) }
                val source = scene.source
                val snapshot = source.snapshot
                requested = frame.id
                val marker = scene.layers.single { it.contentKey() === SourceMarkerKey }.source
                assertEquals("Diagnostic recording size is fixed", IntSize(8, 8), marker.size)
                assertEquals("Diagnostic placement must never request a source refresh", 0, marker.location.placement)
                val markerBounds = Rect(marker.origin, androidx.compose.ui.geometry.Size(8f, 8f))
                val button = returnBounds()
                assertFalse("Frame marker must remain beyond the return lens's sampling neighborhood",
                    markerBounds.overlaps(Rect(sampleOrigin, androidx.compose.ui.geometry.Size(button.width, button.height)).inflate(button.width)))
                val offset = marker.origin - source.origin + Offset(4f, 4f)
                val x = (offset.x * (snapshot?.width ?: 0) / source.size.width.coerceAtLeast(1)).toInt()
                val y = (offset.y * (snapshot?.height ?: 0) / source.size.height.coerceAtLeast(1)).toInt()
                // Zero blur/tint means preparation publishes the very same image.
                // Its marker identifies the real layer's requested content/placement,
                // not merely a different but still pre-alignment bitmap.
                val glyphsRedrawn = compose.runOnUiThread {
                    scene.layers.filter { it.contentKey() !== SourceMarkerKey }
                        .all { it.recordedReadbackRequest == source.readbackRequest }
                }
                if (!glyphsRedrawn || snapshot == null || source.lensSnapshot !== snapshot ||
                    requireFresh && snapshot === previous || x !in 0 until snapshot.width || y !in 0 until snapshot.height) false
                else {
                    observed = snapshot.toPixelMap(startX = x, startY = y, width = 1, height = 1)[0, 0]
                    maxChannelDifference(observed.toArgb(), frame.sampled.toArgb()) <= 1
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Legacy source-frame preparation: requested=$requested observed=$observed " +
                "sourceRevision=${scene.source.revision} samePreparedImage=${scene.source.lensSnapshot === scene.source.snapshot}", failure)
        }
    }

    private fun assertLegacyReadbackBecomesIdle(scene: LyricsBackdropScene) {
        var lastRequest = compose.runOnUiThread { scene.source.readbackRequest }
        var quietSince = SystemClock.uptimeMillis()
        try {
            // Longer than the 150ms coalescing + 500ms settled capture. This runs
            // after the optical assertions, never as a wait for their desired ink.
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                val request = compose.runOnUiThread { scene.source.readbackRequest }
                if (request != lastRequest) {
                    lastRequest = request
                    quietSince = SystemClock.uptimeMillis()
                }
                SystemClock.uptimeMillis() - quietSince >= 1_000
            }
        } catch (failure: Throwable) {
            throw AssertionError("Readback draw invalidation must not schedule another capture: request=$lastRequest " +
                "revision=${scene.source.revision}", failure)
        }
    }

    private fun capture(label: String, settle: Boolean = true, expectFresh: Boolean = false,
        expectReturn: Boolean = true): PixelMap {
        if (settle) {
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            // Wait for prepared-snapshot identity, never for the optical result.
            // This also fails if settled scrolling/emphasis stops requesting a refresh.
            sampledScene?.takeIf { Build.VERSION.SDK_INT < 33 && it.source.consumers > 0 }?.let { scene ->
                awaitLegacySample(scene, lastSnapshot, requireFresh = expectFresh)
            }
            if (Build.VERSION.SDK_INT < 33 && sampledScene == null) {
                // LyricsPanel owns its source privately. Give its debounce, settled
                // readback and off-main preparation bounded native turns; never poll
                // for the chroma being asserted. The final marker still proves which
                // frame was presented. Direct scene tests above verify snapshot IDs.
                val started = SystemClock.uptimeMillis()
                compose.waitUntil(2_000) {
                    compose.mainClock.advanceTimeBy(64)
                    compose.runOnUiThread { }
                    SystemClock.uptimeMillis() - started >= 1_000
                }
            }
        }
        compose.runOnIdle { stage = (stage + 1) % 32 }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag(SceneTag), stage) { view }
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() },
                0, pixels.width, 0, 0, pixels.width, pixels.height)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: requireNotNull(context.getExternalFilesDir(null))
            val directory = File(output, "player-expansion-motion/lyrics-return-backdrop")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "lyrics-return-${Build.VERSION.SDK_INT}-$label.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val button = if (expectReturn) returnBounds() else {
                compose.onNodeWithContentDescription(ReturnLabel).assertDoesNotExist()
                null
            }
            val source = sampledScene?.source
            File(directory, "lyrics-return-${Build.VERSION.SDK_INT}-$label.txt").writeText(buildString {
                appendLine("capture=${pixels.width}x${pixels.height} button=$button")
                if (button != null) appendLine("returnGreen=${green(pixels, button)} returnRed=${red(pixels, button)}")
                appendLine("artworkOnlyGreen=${green(pixels, referenceBounds())}")
                if (source != null) {
                    appendLine("sourceOrigin=${source.origin} sourceSize=${source.size} revision=${source.revision} consumers=${source.consumers}")
                    appendLine("snapshot=${source.snapshot?.let(System::identityHashCode)} lensSnapshot=${source.lensSnapshot?.let(System::identityHashCode)} readbackRequest=${source.readbackRequest}")
                    appendLine("sourceFrame=${compose.runOnUiThread { sourceFrame(requireNotNull(sampledScene)) }}")
                    appendLine("probeGreen=${green(pixels, localBounds(compose.onNodeWithTag(ProbeTag)))}")
                    compose.runOnUiThread {
                        sampledScene?.layers?.mapIndexed { index, layer ->
                            "layer=$index recorded=${layer.source.recorded} origin=${layer.source.origin} size=${layer.source.size} alpha=${layer.alpha()} recordedReadbackRequest=${layer.recordedReadbackRequest}"
                        }.orEmpty()
                    }.forEach { appendLine(it) }
                }
            })
        } finally { bitmap.recycle() }
        sampledScene?.source?.let { lastSnapshot = it.snapshot }
        return pixels
    }

    private fun localBounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot.translate(
        -compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInRoot.topLeft)
    private fun returnBounds() = localBounds(compose.onNodeWithContentDescription(ReturnLabel))
    private fun referenceBounds() = localBounds(compose.onNodeWithContentDescription(ReferenceLabel))

    private fun panelGeometry(document: ParsedLyrics) = listOf(returnBounds(),
        localBounds(compose.onNodeWithTag("lyrics-panel")),
        localBounds(compose.onNodeWithTag("lyric-lines")),
        localBounds(compose.onNodeWithText(document.lines[30].text)))

    private fun assertSameInterior(message: String, expected: PixelMap, actual: PixelMap, bounds: Rect) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var greatestChannelDelta = 0
        for (point in interior(expected, bounds)) {
            val before = expected[point.x.toInt(), point.y.toInt()].toArgb()
            val after = actual[point.x.toInt(), point.y.toInt()].toArgb()
            greatestChannelDelta = max(greatestChannelDelta, maxChannelDifference(before, after))
        }
        // Compare each encoded channel, not average ink coverage. At most one
        // 8-bit quantization level is allowed; no spatial/pattern error is hidden.
        assertTrue("$message: largest 8-bit channel difference=$greatestChannelDelta", greatestChannelDelta <= 1)
    }

    private fun maxChannelDifference(a: Int, b: Int) = listOf(0, 8, 16, 24).maxOf { shift ->
        abs((a ushr shift and 255) - (b ushr shift and 255))
    }

    private fun interior(pixels: PixelMap, bounds: Rect): List<Offset> {
        assertTrue("Capture must contain the entire button", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= pixels.width && bounds.bottom <= pixels.height)
        val radius = bounds.width / 2f
        val result = mutableListOf<Offset>()
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val dx = (x + .5f - bounds.center.x) / radius
            val dy = (y + .5f - bounds.center.y) / radius
            // Stay away from both the clipped rim and the centered 24 dp arrow.
            if (dx * dx + dy * dy < .78f * .78f && (abs(dx) > .52f || abs(dy) > .52f)) result += Offset(x.toFloat(), y.toFloat())
        }
        assertTrue("Non-vacuous interior sample", result.size > 25)
        return result
    }

    private fun green(pixels: PixelMap, bounds: Rect) = interior(pixels, bounds).map {
        pixels[it.x.toInt(), it.y.toInt()].let { c -> max(0f, c.green - c.red) }
    }.average().toFloat()
    private fun red(pixels: PixelMap, bounds: Rect) = interior(pixels, bounds).map {
        pixels[it.x.toInt(), it.y.toInt()].let { c -> max(0f, c.red - c.green) }
    }.average().toFloat()

    private fun assertChromaMoves(message: String, a: PixelMap, b: PixelMap, bounds: Rect, green: Boolean) {
        val points = interior(a, bounds)
        val changed = points.count {
            val x = it.x.toInt(); val y = it.y.toInt()
            val before = if (green) a[x, y].green - a[x, y].red else a[x, y].red - a[x, y].green
            val after = if (green) b[x, y].green - b[x, y].red else b[x, y].red - b[x, y].green
            max(before, after) > .04f && abs(before - after) > .035f
        }
        assertTrue("$message: changed=$changed / ${points.size}", changed > max(6, points.size / 100))
    }

    private fun uncoveredGreen(pixels: PixelMap, button: Rect) = uncoveredChroma(pixels, button, true)

    private fun uncoveredChroma(pixels: PixelMap, button: Rect, green: Boolean): Float {
        val viewport = localBounds(compose.onAllNodesWithTag("lyric-lines").onFirst())
        var total = 0f; var count = 0
        for (y in viewport.top.toInt() until viewport.bottom.toInt())
            for (x in viewport.left.toInt() until viewport.right.toInt()) {
                if (button.inflate(4f).contains(Offset(x.toFloat(), y.toFloat()))) continue
                val color = pixels[x, y]
                total += max(0f, if (green) color.green - color.red else color.red - color.green); count++
            }
        return total / count
    }

    private fun interiorDifference(a: PixelMap, b: PixelMap, bounds: Rect) = interior(a, bounds).map {
        val before = a[it.x.toInt(), it.y.toInt()]; val after = b[it.x.toInt(), it.y.toInt()]
        abs(before.red - after.red) + abs(before.green - after.green) + abs(before.blue - after.blue)
    }.average().toFloat()

    private companion object {
        val SourceMarkerKey = Any()
        const val ProbeTag = "actual-return-source-footprint"
        const val SceneTag = "lyrics-sampling-scene"
        const val ReturnLabel = "Return to current line"
        const val ReferenceLabel = "Artwork-only negative control"
        fun document(glyph: Char, dense: Boolean = false): ParsedLyrics {
            val lines = (0..45).map { index ->
                val words = (if (dense || index == 30) glyph else ' ').toString().repeat(14) + " " + index.toString().padStart(2, '0')
                LyricLine(words, index * 1000L)
            }
            return ParsedLyrics(lines, lines.map { LyricLine(it.text) })
        }
    }
}
