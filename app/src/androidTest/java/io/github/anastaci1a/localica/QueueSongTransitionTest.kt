package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #578: with the Queue drawer open, a song change with different artwork eases the backdrop, the ink and every
 * material on ONE clock (the artwork blend's reveal): a common start and end, monotonic, no single-frame jump,
 * retargeting without jumps on rapid skips, and snapping under Reduced Motion. The host mirrors LocalicaScreen:
 * the player, and the Queue under a panel theme that reads the player's shared presented polarity.
 */
class QueueSongTransitionTest {
    @get:Rule val compose = createComposeRule<androidx.activity.ComponentActivity>()
    private val owner = PlayerBackgroundPolarityState()
    private val settings = InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground, glass = GlassAppearance(contrast = .3f))
    private var visual by mutableStateOf<PlayerVisual?>(null)
    private var reduced by mutableStateOf(false)
    @Volatile private var queueInk = Float.NaN
    @Volatile private var backdropMix = Float.NaN

    @Volatile private var surfaceTint = Float.NaN
    private lateinit var captureView: android.view.View
    private var captureStage by mutableIntStateOf(0)
    @Volatile private var drawnStage = -1
    // Adjacent stages must remain distinct even with the screenshot channel tolerance.
    private fun markerCode(stage: Int) = 20 + (stage % 24) * 8

    private var preparationBarrier: (suspend () -> Unit)? = null

    private class Frame(val mix: Float, val queue: Float, val backdrop: Float, val shot: Float, val clock: Float, val tint: Float, val incoming: Float)
    private val frames = ArrayList<Frame>()

    private fun cover(color: Int, key: String): PlayerVisual {
        val bitmap = Bitmap.createBitmap(150, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return PlayerVisual(bitmap.asImageBitmap(), runBlocking { playerPalette(bitmap) }, contentKey = key)
    }
    private val white = cover(android.graphics.Color.rgb(246, 244, 240), "white")
    private val black = cover(android.graphics.Color.rgb(12, 12, 16), "black")

    private fun mount(first: PlayerVisual, awaitReady: Boolean = true, queueShown: Boolean = true) {
        visual = first
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reduced,
                LocalPlayerBackgroundPreparationBarrier provides preparationBarrier) {
                BoxWithConstraints(Modifier.fillMaxSize().background(if (queueShown) Color.Transparent else Color.Magenta)) {
                    captureView = LocalView.current
                    val mix = owner.lightMix
                    val queue = remember { Animatable(if (queueShown) 1f else 0f) }
                    val backdrop = rememberFrostedBackdrop(Unit, enabled = false, dark = mix >= .5f, lightMix = mix, retainRaster = false)
                    SideEffect { backdropMix = backdrop.lightMix }
                    PlayerSurface(PlaybackState(connected = true, title = "Now and then", artist = "Artist", durationMs = 120000),
                        true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                        visual = visual, backgroundPolarity = owner, queueProgress = queue) { ink ->
                        val tint = requireNotNull(LocalPlayerDrawTheme.current).surface().convert(ColorSpaces.Oklab).red
                        SideEffect { surfaceTint = tint }
                        DrawInkText("Controls", ink)
                    }
                    if (queueShown) GlassPanelTheme(mix) {
                        QueueSurface(queue, settings, {}, playerProgress = { 1f }) {
                            val ink = LocalContentColor.current
                            SideEffect { queueInk = ink.red }
                            Text("Queue row")
                        }
                    }
                    // Independent compositor-frame receipt; never a predicate on the optics under test.
                    Canvas(Modifier.align(Alignment.TopStart).size(8.dp)) {
                        drawRect(Color(markerCode(captureStage) / 255f, 1f, 0f))
                        drawnStage = captureStage
                    }
                }
            }
        } }
        if (awaitReady) compose.waitUntil(10000) { owner.decision.confidence == PlayerBackgroundConfidence.Current && owner.frame != null }
        compose.waitForIdle()
    }

    private fun gutter(green: Boolean = false): Float {
        val enabled = HardwareRendererCompat.isDrawingEnabled()
        if (!enabled) HardwareRendererCompat.setDrawingEnabled(true)
        return try { captureGutter(green) } finally {
            if (!enabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    private fun captureGutter(green: Boolean): Float {
        // captureToImage forces a second native redraw and has a fixed two-second
        // acknowledgement timeout. With a paused test clock, that is not a receipt
        // for the frame just requested. Keep the clock fixed and observe our marker
        // in the window buffer instead; no retry can skip an artwork frame.
        val clock = compose.mainClock.currentTime
        val paused = !compose.mainClock.autoAdvance
        val stage = compose.runOnIdle {
            ++captureStage
            // A paused Compose clock does not promise another snapshot apply pass.
            // Flush the draw-only mutation and schedule Android's native traversal.
            Snapshot.sendApplyNotifications()
            captureView.invalidate()
            captureStage
        }
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        compose.runOnIdle { captureView.getLocationInWindow(origin) }
        val left = origin[0] + bounds.left.roundToInt()
        val top = origin[1] + bounds.top.roundToInt()
        val width = bounds.width.roundToInt(); val height = bounds.height.roundToInt()
        // Copy only the marker and original gutter samples, at native resolution.
        // Full-display UiAutomation captures repeatedly allocate multi-megabyte
        // SurfaceFlinger buffers; the API 26 guest rebooted during repeated captures.
        val cropWidth = maxOf(8, (width * .045f).toInt())
        val cropHeight = maxOf(8, (height * .8f).toInt())
        val crop = android.graphics.Rect(left, top, left + cropWidth, top + cropHeight)
        val window = compose.activity.window
        var result = Float.NaN
        var observed = 0
        try { compose.waitUntil(5000) {
            val screen = copyNativeWindowOnce(window,
                Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888),
                "Queue frame copy timed out at marker $stage", crop
            ) { code -> "Queue frame copy failed at marker $stage: $code" }
            try {
                val marker = screen.getPixel(2, 2)
                observed = marker
                val presented = abs(android.graphics.Color.red(marker) - markerCode(stage)) <= 1 &&
                    android.graphics.Color.green(marker) > 250 && android.graphics.Color.blue(marker) < 5
                if (presented) {
                    var sum = 0f; var n = 0
                    for (y in (height * .55f).toInt() until (height * .8f).toInt() step 6)
                        for (x in (width * .01f).toInt() until (width * .045f).toInt()) {
                            val pixel = screen.getPixel(x, y)
                            sum += (if (green) android.graphics.Color.green(pixel) else android.graphics.Color.red(pixel)) / 255f
                            n++
                        }
                    result = sum / n
                }
                presented
            } finally { screen.recycle() }
        } } catch (failure: Throwable) {
            android.util.Log.e("QueueFrameCapture", "stage=$stage drawn=$drawnStage observed=$observed origin=$left,$top size=$width,$height")
            runCatching { recordPresentationFailure(compose, "queue-marker-$stage", captureView, null, null) }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
        if (paused) assertEquals("Capturing a presented frame must not advance artwork time", clock, compose.mainClock.currentTime)
        return result
    }

    private fun record(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        // Let off-thread preparation of the new artwork's samples land between frames.
        Thread.sleep(12)
        val artworkFrame = owner.frame
        val incoming = artworkFrame?.roles?.firstOrNull { it.incoming && !it.soft }?.visual?.palette?.bottom
            ?.convert(ColorSpaces.Oklab)?.red ?: Float.NaN
        frames += Frame(owner.lightMix, (queueInk - .1255f) / .8745f, backdropMix, gutter(),
            artworkFrame?.clock ?: 1f, surfaceTint, incoming)
    }

    private fun assertOneClock(name: String, toLight: Boolean) {
        val mix = frames.map { it.mix }; val queue = frames.map { it.queue }; val shot = frames.map { it.shot }
        assertTrue("$name: polarity ends at its target: ${mix.takeLast(3)}", abs(mix.last() - if (toLight) 1f else 0f) < .02f)
        assertTrue("$name: eased through intermediates: $mix", mix.count { it > .05f && it < .95f } >= 3)
        mix.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name: polarity jump at $i: $a -> $b", abs(b - a) <= .3f)
            assertTrue("$name: polarity not monotonic at $i", if (toLight) b >= a - 1e-3f else b <= a + 1e-3f)
        }
        // The Queue's theme reads the same eased fraction (its capture trails the polarity read by up to a frame).
        frames.forEachIndexed { i, f ->
            val previous = frames.getOrNull(i - 1)?.mix ?: f.mix
            assertTrue("$name: queue ink $i ${f.queue} vs polarity ${f.mix} (previous $previous)", minOf(abs(f.queue - f.mix), abs(f.queue - previous)) <= .05f)
        }
        assertTrue("$name: materials follow ${frames.map { it.backdrop }}", frames.all { abs(it.backdrop - it.mix) <= .3f })
        // The backdrop crossfade shares that start and end.
        val from = shot.first(); val to = shot.last()
        assertTrue("$name: backdrop changed: $shot", abs(to - from) > .5f)
        val fraction = shot.map { ((it - from) / (to - from)).coerceIn(0f, 1f) }
        fraction.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name: backdrop jump at $i: $a -> $b", abs(b - a) <= .3f)
            assertTrue("$name: backdrop not monotonic at $i", b >= a - .02f)
        }
        val startTint = (if (toLight) white else black).palette.bottom.convert(ColorSpaces.Oklab).red
        val endTint = (if (toLight) black else white).palette.bottom.convert(ColorSpaces.Oklab).red
        val tintFraction = frames.map { (it.tint - startTint) / (endTint - startTint) }
        // Before admission, clock=1 still belongs to the outgoing artwork. Pair the
        // fraction with the target from the same immutable frame bundle.
        val clocks = frames.map { if (abs(it.incoming - endTint) < .001f) it.clock else 0f }
        frames.forEachIndexed { i, _ ->
            val previous = clocks.getOrNull(i - 1) ?: clocks[i]
            assertTrue("$name: surface tint left the artwork clock at $i: ${tintFraction[i]} vs ${clocks[i]}",
                minOf(abs(tintFraction[i] - clocks[i]), abs(tintFraction[i] - previous)) <= .05f)
        }
        tintFraction.zipWithNext().forEach { (a, b) ->
            assertTrue("$name: surface tint jump or reversal: $a -> $b", b >= a - .001f && b - a <= .3f)
        }
        val inkFraction = mix.map { if (toLight) it else 1f - it }
        fun first(values: List<Float>, level: Float) = values.indexOfFirst { it > level }
        assertTrue("$name: common start ${first(inkFraction, .05f)} vs ${first(fraction, .05f)}", abs(first(inkFraction, .05f) - first(fraction, .05f)) <= 6)
        assertTrue("$name: common end ${first(inkFraction, .95f)} vs ${first(fraction, .95f)}", abs(first(inkFraction, .95f) - first(fraction, .95f)) <= 6)
        frames.indices.forEach { assertTrue("$name: shared progress at $it: ${inkFraction[it]} vs ${fraction[it]}", abs(inkFraction[it] - fraction[it]) <= .35f) }
    }

    @Test fun songChangeWithTheDrawerOpenEasesInkMaterialsAndBackdropOnOneClockToDark() {
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(70)
        assertOneClock("light to dark artwork", toLight = true)
    }

    @Test fun songChangeWithTheDrawerOpenEasesBackToLightInk() {
        mount(black); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = white }
        record(70)
        assertOneClock("dark to light artwork", toLight = false)
    }

    @Test fun rapidSkipsRetargetWithoutJumps() {
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(22)
        compose.runOnIdle { visual = white }   // skip again before the first change has finished
        record(12)
        compose.runOnIdle { visual = black }
        record(70)
        frames.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("polarity jump at $i: ${a.mix} -> ${b.mix}", abs(b.mix - a.mix) <= .3f)
            assertTrue("surface tint jump at $i: ${a.tint} -> ${b.tint}", abs(b.tint - a.tint) <= .3f)
            assertTrue("queue ink jump at $i: ${a.queue} -> ${b.queue}", abs(b.queue - a.queue) <= .3f)
            assertTrue("backdrop jump at $i: ${a.shot} -> ${b.shot}", abs(b.shot - a.shot) <= .3f)
        }
        assertTrue("ends on the last song's ink: ${frames.last().mix}", frames.last().mix > .98f)
    }

    @Test fun reducedMotionSnapsTheWholeChange() {
        reduced = true
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(12)
        frames.forEach { f -> listOf(f.mix, f.backdrop).forEach { assertTrue("snap, not ease: $it", it == 0f || it == 1f) } }
        assertEquals(1f, frames.last().mix, 0f)
        val endpoints = listOf(white, black).map { it.palette.bottom.convert(ColorSpaces.Oklab).red }
        assertTrue("surface tint snaps with artwork", frames.all { f -> endpoints.any { abs(it - f.tint) < .02f } })
        assertEquals(endpoints.last(), frames.last().tint, .02f)
        val shots = frames.map { it.shot }
        assertTrue("backdrop snapped, never eased: $shots", shots.all { abs(it - shots.first()) < .05f || abs(it - shots.last()) < .05f })
    }

    /** #634 supersedes the empty first frames: a first open whose grade is still pending presents the same
     * artwork ungraded, never the empty window behind the player, then holds or eases into the grade. */
    @Test fun firstOpenPresentsTheArtworkNotAnEmptyWindowWhilePreparationIsPending() {
        val ready = CompletableDeferred<Unit>()
        preparationBarrier = { ready.await() }
        compose.mainClock.autoAdvance = false
        try {
            mount(white, awaitReady = false, queueShown = false)
            repeat(4) {
                compose.mainClock.advanceTimeByFrame()
                assertTrue("No grade may bypass the preparation barrier", owner.roleGrades.isEmpty())
                assertTrue("First frame $it must present the white artwork backdrop, not the empty window", gutter(green = true) > .75f)
            }
            ready.complete(Unit)
            for (i in 0 until 60) {
                compose.mainClock.advanceTimeByFrame()
                Thread.sleep(10)
                if (owner.roleGrades.isNotEmpty()) break
            }
            assertTrue("Preparation must release the backdrop", owner.roleGrades.isNotEmpty())
            compose.mainClock.advanceTimeByFrame()
            assertTrue("The graded backdrop is now drawn", gutter(green = true) > .75f)
        } finally { ready.complete(Unit); compose.mainClock.autoAdvance = true }
    }

}
