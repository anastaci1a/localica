package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ContainerTags
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import java.io.File
import kotlin.math.abs
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #717: one "current line" anchor. The lyrics screen opens with the current playback line already centred in the
 * lyric-lines container (no opening scroll) and following playback glides the new current line to that same
 * centre, including wrapped lines, the first and last lines and Reduced Motion. The real PlayerSurface runs on the
 * manual clock with a synced 40-line document; every frame compares the selected line's centre with the
 * container's centre from the semantics tree, and native frames of the opening are exported for review.
 */
class LyricsLineAnchorTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val owner = PlayerBackgroundPolarityState()
    private var lyrics by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var settings by mutableStateOf(InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground))
    private var file: File? = null
    @After fun cleanUp() { file?.delete() }

    /** Lines sit two seconds apart; line 26 (index 25) is long enough to wrap at the phone width. */
    private fun fixture(): Track {
        val target = File(instrumentation.targetContext.cacheDir, "line-anchor-717.mp3").also { file = it }
        copyTestAsset(instrumentation.context.assets, "tone.mp3", target)
        ContainerTags.configure()
        val document = (0 until 40).joinToString("\n") { index ->
            val seconds = index * 2
            val text = if (index == Wrapped) "Original line ${index + 1} carries many more words than its neighbours so that it wraps onto further rows"
                else "Original line ${index + 1}"
            "[%02d:%02d.00]%s".format(seconds / 60, seconds % 60, text)
        }
        AudioFileIO.read(target).apply { tag = ID3v24Tag().apply { setField(FieldKey.LYRICS, document) }; commit() }
        val track = Track(android.net.Uri.fromFile(target).toString(), "Line anchor", fileName = target.name, relativePath = target.name)
        val repository = (instrumentation.targetContext.applicationContext as LocalicaApplication).lyrics
        val parsed = kotlinx.coroutines.runBlocking { repository.load(track, ArtworkCache.revision.value) }
        assertTrue("The fixture is synced: $parsed", parsed.sources.first().document.synced)
        return track
    }

    private fun mount(track: Track) {
        val bitmap = android.graphics.Bitmap.createBitmap(150, 300, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        val visual = PlayerVisual(bitmap.asImageBitmap(), kotlinx.coroutines.runBlocking { playerPalette(bitmap) }, contentKey = "gray")
        compose.setContent { LocalicaTheme {
            val state = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title,
                positionMs = positionMs, durationMs = 125000)
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.width(411.dp).height(minOf(731.dp, maxHeight))) {
                        PlayerSurface(state, true, {}, settings,
                            remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                            visual = visual, backgroundPolarity = owner, lyricsOpen = lyrics, track = track)
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun linesNode() = compose.onNodeWithTag("lyric-lines", useUnmergedTree = true)
    private fun viewportComposed() =
        compose.onAllNodesWithTag("lyric-lines", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun selectedNodes() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
        hasAnyAncestor(hasTestTag("lyric-lines")), useUnmergedTree = true).fetchSemanticsNodes()

    /** Advance frames (polling real time between them) until the lyric-lines container is composed. */
    private fun awaitViewport(): Int {
        var frames = 0
        while (!viewportComposed()) {
            assertTrue("the lyric-lines container was not composed within $frames frames of opening", frames < 4)
            frame(); frames++
            val deadline = System.currentTimeMillis() + 1_000
            while (!viewportComposed() && System.currentTimeMillis() < deadline) { Thread.sleep(10); compose.waitForIdle() }
        }
        return frames
    }

    /** The selected line's centre relative to the container's centre, in pixels (positive = below); NaN without a current line. */
    private class Sample(val leg: String, val frame: Int, val container: Rect, val line: Rect?) {
        val delta get() = line?.let { it.center.y - container.center.y } ?: Float.NaN
    }
    private val samples = mutableListOf<Sample>()
    private val strip = mutableListOf<android.graphics.Bitmap>()
    private fun sample(leg: String, frame: Int, capture: Boolean = false): Sample {
        val container = linesNode().fetchSemanticsNode().boundsInRoot
        val line = selectedNodes().singleOrNull()?.boundsInRoot
        if (capture) strip += linesNode().captureNativeFrame().asAndroidBitmap()
        return Sample(leg, frame, container, line).also { samples += it }
    }
    private fun capture(leg: String, count: Int, native: Int = 0) = repeat(count) { frame(); sample(leg, it, capture = it < native) }

    private fun export(case: String) {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val rows = listOf("case,leg,frame,containerTop,containerBottom,lineTop,lineBottom,delta") + samples.map { s ->
            listOf(case, s.leg, s.frame, s.container.top, s.container.bottom, s.line?.top, s.line?.bottom, "%.2f".format(s.delta)).joinToString(",")
        }
        File(root, "lyrics-anchor-$case.csv").writeText(rows.joinToString("\n", postfix = "\n"))
        if (strip.isEmpty()) return
        // Each cell fits the largest frame (the panel grows while it opens) plus a 4 px gutter, so no frame covers another.
        val cellWidth = strip.maxOf { it.width } + 4
        val cellHeight = strip.maxOf { it.height } + 4
        val sheet = android.graphics.Bitmap.createBitmap(cellWidth * 8, cellHeight * ((strip.size + 7) / 8),
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        strip.forEachIndexed { index, image -> canvas.drawBitmap(image, (index % 8 * cellWidth).toFloat(), (index / 8 * cellHeight).toFloat(), null) }
        File(root, "lyrics-anchor-$case-strip.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertCentred(what: String, s: Sample) =
        assertTrue("$what: the current line (${s.line}) must be centred in the container (${s.container}); delta ${s.delta}",
            !s.delta.isNaN() && abs(s.delta) <= Tolerance)

    /** Opens the lyrics at [position] on the manual clock and returns the frames spent before the container existed. */
    private fun open(position: Long, reduced: Boolean = false): Int {
        positionMs = position
        if (reduced) settings = settings.copy(reducedMotion = true)
        val track = fixture()
        owner.pin(false); mount(track)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = true }
        return awaitViewport()
    }

    private fun openCentred(case: String, position: Long, frames: Int = 45) {
        open(position)
        sample("open", 0, capture = true)   // the first presented frame of the container
        capture("open", frames, native = 11)
        try {
            assertCentred("$case, first presented frame", samples.first())
            val spread = samples.maxOf { it.delta } - samples.minOf { it.delta }
            assertTrue("$case: no opening scroll, the line must not move after the first frame (spread $spread px): " +
                samples.joinToString { "%.1f".format(it.delta) }, spread <= 1f)
            samples.forEach { assertCentred("$case, frame ${it.frame}", it) }
        } finally { export(case) }
    }

    /** The current line is far down the document: the first frame already shows it centred and nothing scrolls. */
    @Test fun opensOnTheCurrentLineAlreadyCentredWithoutAnOpeningScroll() = openCentred("open", 40_000L)

    /** A wrapped current line is centred on its measured height, not on a single-line estimate. */
    @Test fun opensCentredOnAWrappedLine() {
        openCentred("wrapped", Wrapped * 2_000L, frames = 20)
        val line = requireNotNull(samples.first().line)
        val neighbour = compose.onNodeWithText("Original line $Wrapped", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The fixture line must wrap (${line.height} vs ${neighbour.height})", line.height > neighbour.height * 1.5f)
    }

    /** The list bounds coincide with the centred first and last lines. */
    @Test fun theFirstLineOpensCentred() = openCentred("first", 0L, frames = 20)
    @Test fun theLastLineOpensCentred() = openCentred("last", 78_000L, frames = 20)

    /** Following playback glides the new current line to the same centre, for a neighbour and for the far wrapped line. */
    @Test fun followingPlaybackGlidesTheNewCurrentLineToTheCentre() {
        open(40_000L)
        capture("settle", 60); samples.clear()
        compose.runOnIdle { positionMs = 42_000L }
        capture("neighbour", 40, native = 24)
        val deltas = samples.map { it.delta }
        try {
            assertCentred("after following to the next line", samples.last())
            // The recomposition that selects the new line lands one frame after the position change.
            val start = deltas.indexOfFirst { it > Tolerance * 2 }
            assertTrue("The next line starts below the centre within two frames: $deltas", start in 0..1)
            val glide = deltas.drop(start)
            val intermediates = glide.count { it > Tolerance && it < glide.first() - Tolerance }
            assertTrue("The follow eases through 3+ intermediate frames, saw $intermediates: $deltas", intermediates >= 3)
            glide.zipWithNext().forEach { (a, b) -> assertTrue("The follow moves monotonically: $deltas", b <= a + .5f) }
            samples.clear()
            compose.runOnIdle { positionMs = Wrapped * 2_000L }
            capture("wrapped", 45)
            assertCentred("after following to the wrapped line", samples.last())
            assertTrue("The wrapped line is current", selectedNodes().single().config[SemanticsProperties.Text].first().text.startsWith("Original line ${Wrapped + 1} "))
        } finally { export("follow") }
    }

    /** Under Reduced Motion the line opens centred and the follow snaps to the centre. */
    @Test fun reducedMotionOpensAndSnapsCentred() {
        open(40_000L, reduced = true)
        sample("open", 0)
        try {
            assertCentred("Reduced Motion, first presented frame", samples.first())
            capture("open", 5)
            compose.runOnIdle { positionMs = 42_000L }
            capture("snap", 3)
            assertCentred("Reduced Motion, after the follow", samples.last())
            val moved = samples.filter { it.leg == "snap" }.map { it.delta }
            assertTrue("The snap lands within two frames: $moved", moved.drop(1).all { abs(it) <= Tolerance })
        } finally { export("reduced") }
    }

    /** A manual drag detaches following; playback must not move the list, and Return re-centres the current line. */
    @Test fun manualScrollDetachesAndReturnRecentres() {
        positionMs = 40_000L
        val track = fixture()
        owner.pin(false); lyrics = true; mount(track)
        compose.waitUntil(10_000) { viewportComposed() }
        compose.waitForIdle()
        sample("rest", 0)
        assertCentred("at rest", samples.first())
        linesNode().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Return to current line").assertIsDisplayed()
        val detached = linesNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { positionMs = 44_000L }
        compose.waitForIdle()
        assertEquals("Playback must not move a manually detached list", detached,
            linesNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        compose.onNodeWithContentDescription("Return to current line").performClick()
        compose.waitForIdle()
        sample("returned", 0)
        try {
            assertCentred("after Return", samples.last())
            compose.onNodeWithContentDescription("Return to current line").assertDoesNotExist()
        } finally { export("return") }
    }

    private companion object {
        const val Wrapped = 25
        const val Tolerance = 1.5f
    }
}
