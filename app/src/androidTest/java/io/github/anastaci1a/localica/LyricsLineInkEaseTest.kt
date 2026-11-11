package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
 * #564 (reopened 2026-11-08): the presented ink of every lyric line follows the shared lyrics theme ease
 * together. The real PlayerSurface runs on the manual clock with a synced 40-line document; the pinned
 * decision flips to the dark lyrics theme part-way through the opening animation. Every frame is a native
 * PixelCopy of the real lyric-lines viewport, and each visible line's glyph luminance against the backdrop
 * is compared across lines (uniform in the same frame, including lines the autoscroll composes mid-ease),
 * against the sampled shared foreground (no line keeps the old polarity once the theme has crossed) and
 * against its own previous frame (no single-frame step). Strips and CSVs are exported for review.
 */
class LyricsLineInkEaseTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val owner = PlayerBackgroundPolarityState()
    private var lyrics by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var settings by mutableStateOf(InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground))
    @Volatile private var inkComposition: PlayerInkComposition? = null
    private var file: File? = null
    @After fun cleanUp() { file?.delete() }

    /** A readable MP3 whose embedded lyrics are an original synced 40-line document, two seconds per line. */
    private fun fixture(): Track {
        val target = File(instrumentation.targetContext.cacheDir, "line-ink-564.mp3").also { file = it }
        copyTestAsset(instrumentation.context.assets, "tone.mp3", target)
        ContainerTags.configure()
        val document = (0 until 40).joinToString("\n") { index ->
            val seconds = index * 2
            "[%02d:%02d.00]Original line %d".format(seconds / 60, seconds % 60, index + 1)
        }
        AudioFileIO.read(target).apply { tag = ID3v24Tag().apply { setField(FieldKey.LYRICS, document) }; commit() }
        val track = Track(android.net.Uri.fromFile(target).toString(), "Line ink", fileName = target.name, relativePath = target.name)
        // Parse through the application's repository (the one rememberLyrics uses) so the opening composition's
        // produceState is a cache hit and the lyric lines exist from the first frames of the ease.
        val repository = (instrumentation.targetContext.applicationContext as LocalicaApplication).lyrics
        val parsed = kotlinx.coroutines.runBlocking { repository.load(track, ArtworkCache.revision.value) }
        val lines = parsed.sources.first().document
        assertTrue("The fixture is synced: $parsed", lines.synced)
        assertEquals(40, lines.lines.size)
        return track
    }

    private fun mount(track: Track) {
        val bitmap = android.graphics.Bitmap.createBitmap(150, 300, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        val visual = PlayerVisual(bitmap.asImageBitmap(), kotlinx.coroutines.runBlocking { playerPalette(bitmap) }, contentKey = "gray")
        compose.setContent { LocalicaTheme {
            val state = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title,
                positionMs = positionMs, durationMs = 125000)
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalPlayerInkCompositionObserver provides { inkComposition = it }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // A tall phone column in density-1 pixels (the full emulator height), so the solid 12-88 % band of the
                    // lyrics container holds several lines of each emphasis in every frame.
                    BoxWithConstraints(Modifier.width(411.dp).fillMaxHeight()) {
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
    private fun viewportComposed() =
        compose.onAllNodesWithTag("lyric-lines", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The lyrics document arrives through produceState on the IO dispatcher (a cache hit): advance frames until the
     *  lyric-lines viewport is composed, polling real time between frames, and return the frames spent. */
    private fun awaitViewport(): Int {
        var frames = 0
        while (!viewportComposed()) {
            assertTrue("the lyric-lines viewport was not composed within $frames frames of opening", frames < 4)
            frame(); frames++
            val deadline = System.currentTimeMillis() + 1_000
            while (!viewportComposed() && System.currentTimeMillis() < deadline) { Thread.sleep(10); compose.waitForIdle() }
        }
        return frames
    }
    private fun lum(c: Color) = .3f * c.red + .59f * c.green + .11f * c.blue

    /** One visible line: its index, emphasis and glyph ink relative to the backdrop (positive = lighter). */
    private class Line(val index: Int, val selected: Boolean, val ink: Float)
    private class Sample(val leg: String, val frame: Int, val foreground: Float, val progress: Float, val backdrop: Float,
        val lines: List<Line>, val mix: Float)

    /** Native crop of the lyric-lines viewport; each fully visible, unfaded line's extreme glyph luminance.
     *  Null once the viewport has left the composition (after a completed close). */
    private fun sample(leg: String, index: Int, strip: MutableList<android.graphics.Bitmap>): Sample? {
        if (!viewportComposed()) return null
        val viewport = compose.onNodeWithTag("lyric-lines", useUnmergedTree = true)
        val image = viewport.captureNativeFrame()
        strip += image.asAndroidBitmap()
        val pixels = image.toPixelMap()
        val origin = viewport.fetchSemanticsNode().boundsInRoot
        // The backdrop is read in the text-free right margin at mid height (lines are short and start-aligned).
        var backdrop = 0f
        for (dy in -2..2) backdrop += lum(pixels[pixels.width - 6, pixels.height / 2 + dy])
        backdrop /= 5f
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag("lyric-lines")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val lines = nodes.mapNotNull { node ->
            val bounds = node.boundsInRoot
            val top = (bounds.top - origin.top).toInt(); val bottom = (bounds.bottom - origin.top).toInt()
            val left = (bounds.left - origin.left).toInt(); val right = (bounds.right - origin.left).toInt()
            // The viewport mask fades the top and bottom 12 %: only measure lines fully inside the solid band.
            if (top < pixels.height * .12f || bottom > pixels.height * .88f) return@mapNotNull null
            var extreme = 0f
            for (y in top until bottom) for (x in left until minOf(right, pixels.width)) {
                val delta = lum(pixels[x, y]) - backdrop
                if (abs(delta) > abs(extreme)) extreme = delta
            }
            val selected = node.config.getOrNull(SemanticsProperties.Selected) == true
            Line(node.id, selected, extreme)
        }
        val ink = requireNotNull(inkComposition)
        // The shared light mix the player published for this frame, read as an immutable value on Main.
        val mix = compose.runOnIdle { owner.lightMix }
        return Sample(leg, index, lum(ink.contentColor), ink.lyricsProgress, backdrop, lines, mix)
    }

    private val strip = mutableListOf<android.graphics.Bitmap>()
    private val samples = mutableListOf<Sample>()
    private fun capture(leg: String, count: Int) = repeat(count) { frame(); sample(leg, it, strip)?.let { s -> samples += s } }

    /** The theme flips to dark part-way through the opening animation; every visible line must follow it together.
     *  With [composeMidEase] the list is shifted (semantics, following stays on) by four rows and back after the flip, so
     *  lines are composed mid-ease exactly as the autoscroll of a moving song composes them. */
    private fun run(case: String, position: Long, composeMidEase: Boolean = false) {
        positionMs = position
        val track = fixture()
        owner.pin(true); mount(track)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = true }
        val lead = awaitViewport()        // frames before the lines exist (expected 1-2); the flip lands after them
        capture("open-light", 6 - lead)
        owner.pin(false)      // the lyrics theme differs: it arrives part-way through the animation
        if (composeMidEase) {
            capture("open-dark", 4)
            val list = compose.onNodeWithTag("lyric-lines", useUnmergedTree = true)
            // A partial shift: about half the visible rows stay composed while fresh rows enter, both ways.
            list.performScrollToIndex(LrcParserCurrentLine + 4); capture("open-dark-far", 10)
            list.performScrollToIndex(LrcParserCurrentLine); capture("open-dark-back", 26)
        } else capture("open-dark", 40)
        val last = samples.last()
        val failures = mutableListOf<String>()
        val first = samples.first()
        if (first.leg != "open-light" || first.lines.size < 3) failures += "too few lines before the theme flip (lead $lead frames): ${first.lines.size}"
        if (first.progress >= .5f) failures += "the lines were composed too late for the flip to land mid-ease: progress ${first.progress}"
        if (last.progress != 1f) failures += "opening did not reach the lyrics endpoint (${last.progress})"
        if (last.lines.none { it.selected } || last.lines.size < 3) failures += "too few measured lines at the end: ${last.lines.size}"
        analyse(case, failures)
    }

    /** Checks (a) uniform ink across lines of equal emphasis, (b) no line keeps the old polarity once the shared theme
     *  has crossed, (c) no line steps on its own relative to the shared theme, (d) the shared theme is exactly the approved
     *  U08.02a PhaseBinding of the logged phase in every frame (a theme snap fails here); then exports the CSV and the
     *  contact sheet before asserting. */
    private fun analyse(case: String, failures: MutableList<String>, polarity: Boolean = true, exclude: Set<Int> = emptySet()) {
        val rows = mutableListOf("case,leg,frame,foreground,progress,backdrop,mix,lines")
        var previous: Map<Int, Float> = emptyMap()
        var previousTheme: Float? = null
        // The theme is pinned light until the flip, then dark; the opening legs travel to 1 and the close leg to 0.
        val binding = io.github.anastaci1a.localica.ui.engine.PhaseBinding(1f)
        samples.forEach { s ->
            rows += listOf(case, s.leg, s.frame, s.foreground, s.progress, s.backdrop, s.mix,
                s.lines.joinToString(" ") { "${it.index}${if (it.selected) "*" else ""}=${"%.3f".format(it.ink)}" }).joinToString(",")
            // (a) Every line of the same emphasis is the same colour in the same frame (a line whose emphasis is still
            // fading is excluded by the emphasis case).
            val inactive = s.lines.filter { !it.selected && it.index !in exclude }
            if (inactive.size >= 2) {
                val spread = inactive.maxOf { it.ink } - inactive.minOf { it.ink }
                if (spread > .08f) failures += "${s.leg} ${s.frame}: inactive lines differ by $spread: ${rows.last()}"
            }
            // (b) Once the shared theme has crossed the backdrop, no visible line keeps the old polarity.
            val theme = s.foreground - s.backdrop
            if (polarity && abs(theme) >= .15f) s.lines.forEach { line ->
                if (abs(line.ink) >= .05f && line.ink * theme < 0f)
                    failures += "${s.leg} ${s.frame}: line ${line.index} keeps the old polarity (${line.ink}) while the theme is at $theme"
            }
            // (c) No line steps in a single frame on its own: its change beyond the shared theme's change stays within .25.
            val shared = previousTheme?.let { theme - it } ?: 0f
            val current = s.lines.associate { it.index to it.ink }
            current.forEach { (id, ink) -> previous[id]?.let { before ->
                if (abs(ink - before - shared) > .25f)
                    failures += "${s.leg} ${s.frame}: line $id steps $before -> $ink beyond the shared theme step $shared"
            } }
            previous = current
            previousTheme = theme
            // (d) The shared theme itself follows the approved phase binding exactly, so its own steps are those of the ease.
            if (polarity) {
                val step = binding.resolve(if (s.leg == "open-light") 1f else 0f, s.progress, if (s.leg == "close") 0f else 1f)
                binding.present(step)
                if (s.mix != step.value) failures += "${s.leg} ${s.frame}: shared light mix ${s.mix} is not the phase binding's ${step.value} at phase ${s.progress}"
            }
        }
        export(case, rows)
        assertTrue("Every lyric line follows the shared theme ease ($case):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun export(case: String, rows: List<String> = samples.map { s -> listOf(case, s.leg, s.frame, s.foreground, s.progress, s.backdrop,
        s.lines.joinToString(" ") { "${it.index}${if (it.selected) "*" else ""}=${"%.3f".format(it.ink)}" }).joinToString(",") }) {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(root, "lyrics-line-ink-$case.csv").writeText(rows.joinToString("\n", postfix = "\n"))
        // Each cell fits the largest frame (the panel grows while it opens) plus a 4 px gutter, so no frame covers another.
        val cellWidth = strip.maxOf { it.width } + 4
        val cellHeight = strip.maxOf { it.height } + 4
        val sheet = android.graphics.Bitmap.createBitmap(cellWidth * 8, cellHeight * ((strip.size + 7) / 8),
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        strip.forEachIndexed { index, image -> canvas.drawBitmap(image, (index % 8 * cellWidth).toFloat(), (index / 8 * cellHeight).toFloat(), null) }
        File(root, "lyrics-line-ink-$case-strip.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Lines composed while the theme eases (a scroll to a far region and back) are the colour of their neighbours. */
    @Test fun linesComposedDuringAScrollMidEaseShareTheEasedInkOfTheirNeighbours() = run("composed", LrcParserCurrentLine * 2_000L, composeMidEase = true)

    /** The current line is the first: nothing scrolls, and the lines must still ease with the theme. */
    @Test fun linesEaseWithTheThemeWithoutAnyAutoscroll() = run("no-scroll", 0L)

    /** With the theme fixed, a line change crossfades the emphasis of exactly the two lines over its own 350 ms clock. */
    @Test fun emphasisCrossfadesOverItsOwnClockWithTheThemeFixed() {
        positionMs = 0L
        val track = fixture()
        owner.pin(false); lyrics = true; mount(track)
        compose.mainClock.autoAdvance = false
        capture("settle", 40); samples.clear(); strip.clear()
        val before = requireNotNull(sample("before", 0, strip))
        val selectedBefore = requireNotNull(before.lines.singleOrNull { it.selected }) { "one current line: ${before.lines.size}" }
        compose.runOnIdle { positionMs = 2_000L }   // the second line becomes current
        capture("emphasis", 30)
        val failures = mutableListOf<String>()
        val rising = samples.mapNotNull { s -> s.lines.firstOrNull { it.selected && it.index != selectedBefore.index }?.ink }
        val falling = samples.mapNotNull { s -> s.lines.firstOrNull { it.index == selectedBefore.index }?.ink }
        fun eased(name: String, values: List<Float>) {
            if (values.size < 8) { failures += "$name was measured in only ${values.size} frames"; return }
            val low = values.minOf { abs(it) }; val high = values.maxOf { abs(it) }
            if (high - low < .1f) failures += "$name never changed emphasis: $values"
            val intermediates = values.count { abs(it) > low + .02f && abs(it) < high - .02f }
            if (intermediates < 4) failures += "$name should pass through 4+ intermediate frames, saw $intermediates: $values"
            values.zipWithNext().forEachIndexed { i, (a, b) -> if (abs(b - a) > .25f) failures += "$name steps at $i: $a -> $b" }
        }
        eased("rising line", rising); eased("falling line", falling)
        // Lines outside the transition keep their ink exactly while the theme is fixed.
        val others = samples.map { s -> s.lines.filter { !it.selected && it.index != selectedBefore.index }.map { it.ink } }
        others.forEach { inks -> if (inks.size >= 2 && inks.max() - inks.min() > .08f) failures += "untouched lines differ: $inks" }
        analyse("emphasis", failures, polarity = false, exclude = setOf(selectedBefore.index))
    }

    /** Closing part-way through the theme ease: no line steps and none returns to the old polarity on its own. */
    @Test fun closingMidEaseTurnsEveryLineAroundWithoutAStep() {
        positionMs = 0L
        val track = fixture()
        owner.pin(true); mount(track)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = true }
        val lead = awaitViewport()
        capture("open-light", 6 - lead)
        owner.pin(false); capture("open-dark", 6)
        compose.runOnIdle { lyrics = false }     // reversed part-way through the ease; sampling stops when the viewport leaves
        capture("close", 40)
        analyse("reverse", mutableListOf())
    }

    /** Under Reduced Motion the lines snap with the layout: no line shows an eased intermediate value. */
    @Test fun reducedMotionSnapsEveryLineWithTheLayout() {
        positionMs = 0L
        settings = settings.copy(reducedMotion = true)
        val track = fixture()
        owner.pin(true); mount(track)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = true }
        val lead = awaitViewport()
        capture("open-light", 3 - lead)
        owner.pin(false); capture("open-dark", 10)
        val failures = mutableListOf<String>()
        val inks = samples.flatMap { s -> s.lines.filter { !it.selected }.map { it.ink } }
        val low = inks.min(); val high = inks.max()
        if (high - low < .3f) failures += "the lines never changed polarity: $inks"
        inks.forEach { if (it > low + .05f && it < high - .05f) failures += "eased line ink under Reduced Motion: $it in $low..$high" }
        // (c) does not apply: the snap is the expected single-frame step.
        samples.forEach { s -> val inactive = s.lines.filter { !it.selected }
            if (inactive.size >= 2 && inactive.maxOf { it.ink } - inactive.minOf { it.ink } > .08f) failures += "${s.leg} ${s.frame}: lines differ under Reduced Motion" }
        export("reduced")
        assertTrue("Every lyric line snaps with the layout under Reduced Motion:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
    private companion object {
        /** The current line of the composed case: line 11 at 20 s, so the shift composes fresh rows both ways. */
        const val LrcParserCurrentLine = 10
    }
}
