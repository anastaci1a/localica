package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.bindPinchClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Presented pixels at every explicitly advanced frame; no optical-result polling. */
class PinchRecognitionContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun listOutwardWithoutSelection() = inspect(false, true, false)
    @Test fun listInwardWithoutSelection() = inspect(false, false, false)
    @Test fun listOutwardWithSelections() = inspect(false, true, true)
    @Test fun listInwardWithSelections() = inspect(false, false, true)
    @Test fun galleryOutwardWithoutSelection() = inspect(true, true, false)
    @Test fun galleryInwardWithoutSelection() = inspect(true, false, false)
    @Test fun galleryOutwardWithSelections() = inspect(true, true, true)
    @Test fun galleryInwardWithSelections() = inspect(true, false, true)

    @Test fun artworkLocatorExcludesMatchingRedGlassOutsideTheCover() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            android.graphics.Canvas(this).apply {
                drawRect(20f, 20f, 80f, 80f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
                drawRect(30f, 30f, 70f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
            }
        }
        try {
            assertEquals(Rect(20f, 20f, 80f, 80f), paintedArt(bitmap.asImageBitmap().toPixelMap(), Offset(50f, 50f)))
        } finally { bitmap.recycle() }
    }

    @Test fun artworkLocatorTracksFractionalEdgesWithoutTwoPixelQuantization() {
        val heights = listOf(107.8f, 108f).map { height ->
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
                android.graphics.Canvas(this).apply {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.color = android.graphics.Color.BLUE
                    drawRect(20f, 80f - height / 2f, 128f, 80f + height / 2f, paint)
                    paint.color = android.graphics.Color.RED
                    drawRect(30f, 36f, 118f, 124f, paint)
                }
            }
            try {
                paintedArt(bitmap.asImageBitmap().toPixelMap(), Offset(74f, 80f)).height.also {
                    assertEquals("Resolve the fractional cover edge", height, it, .25f)
                }
            } finally { bitmap.recycle() }
        }
        assertEquals("Subpixel growth cannot become a two-pixel size jump", .2f, heights[1] - heights[0], .25f)
    }

    @Test fun firstTrackingFramePreservesThePresentedGeometry() = runBlocking {
        for (finger in listOf(.05f, .086f)) {
            val clock = BroadcastFrameClock()
            val owner = Job()
            val scope = CoroutineScope(owner + Dispatchers.Unconfined + clock)
            val motion = BrowsePinchMotion()
            scope.bindPinchClock(motion.progressOwner.motion)
            try {
                motion.progress.snapTo(0f)
                motion.held = true; motion.destinationReady = true; motion.fingerProgress = finger
                motion.followFingers(scope)
                clock.sendFrame(1_000_000_000L)
                assertEquals("Recognition starts from the presented source, without advancing a hidden frame",
                    0f, motion.progress.value, .000001f)
                clock.sendFrame(1_016_666_667L)
                assertTrue("Catch-up leaves the stationary source gently, rather than consuming most of the gap in one tick",
                    motion.progress.value in 0f..finger * .15f)
                repeat(7) { clock.sendFrame(1_000_000_000L + (it + 2) * 16_666_667L) }
                assertEquals("The complete threshold travel is still caught up", finger, motion.progress.value, .000001f)
            } finally { owner.cancel(); owner.join() }
        }
    }

    private data class Frame(val label: String, val time: Long, val mode: String?, val progress: Float?, val art: Rect, val laid: Rect)

    private fun inspect(gallery: Boolean, outward: Boolean, selected: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = List(40) { index -> Track("pinch-continuity:$index", "Frame ${index.toString().padStart(2, '0')}",
            artist = "Continuity", album = "Continuity", fileName = "$index.mp3", relativePath = "$index.mp3") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("continuity-root", "Music")), tracks = tracks)
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), "Continuity")
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val initial = if (gallery) BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3)
            else BrowseStyle(listSize = ListSize.Normal)
        val target = initial.zoomStep(outward, 360f, true)
        val selection = SessionSelection().apply {
            if (selected) select(route.collection, tracks.take(2).map { SongRow(it.id, it) })
        }
        val red = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        // The glass deliberately keeps its red palette. An opaque blue border separates
        // the cover's real outer edge from equally red selection material behind it.
        val marked = red.copy(Bitmap.Config.ARGB_8888, true).apply {
            android.graphics.Canvas(this).drawRect(0f, 0f, 768f, 768f, android.graphics.Paint().apply {
                color = android.graphics.Color.BLUE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 154f
            })
        }
        val green = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val artwork = BrowseArtworkScope().apply {
            tracks.forEachIndexed { index, track ->
                hold(track.id, listOf(track.uri), ArtworkCache.CachedArtwork(if (index == 0) marked else green, 768))
            }
            keepVisual(tracks.first().uri, PlayerVisual(red.asImageBitmap(), runBlocking { playerPalette(red) }))
        }
        val frames = mutableListOf<Frame>()
        val headerFrames = mutableListOf<CollectionHeaderFrame>()
        var stage by mutableIntStateOf(0)
        lateinit var view: View
        var held = false
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))
        val output = File(root, "pinch-recognition/$gallery-$outward-$selected").also { it.mkdirs() }
        prefs.write(route, initial, true)
        try {
            compose.setContent { LocalicaTheme {
                view = LocalView.current
                CompositionLocalProvider(LocalBrowseArtwork provides artwork, LocalReducedMotion provides false,
                    LocalSessionSelection provides selection,
                    LocalCollectionHeaderFrameObserver provides { headerFrames += it }) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("continuity-scene")) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                            { _, _, _ -> fail("Pinching must not start playback") }, null, null, true, false,
                            queryDispatcher = Dispatchers.Unconfined)
                        PresentationMarker(composeStage = true) { stage }
                    }
                }
            } }
            val list = compose.onNodeWithTag("browser-list")
            val scene = compose.onNodeWithTag("continuity-scene")
            val art = compose.onNodeWithTag("browse-art-${tracks.first().id}", true)
            compose.waitForIdle()
            // Keep a genuinely mounted strip of the header and leave the first cover clear of both clips.
            list.performScrollToIndex(1)
            list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
                it(0f, -96f * context.resources.displayMetrics.density)
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-art-${tracks.first().id}", true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            // Finish the setup's bounds/selection animations before a stationary source is captured.
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            fun sample(label: String): Frame {
                compose.runOnUiThread { stage = (stage + 1) % 24 }
                compose.mainClock.advanceTimeByFrame()
                val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
                val laid = art.fetchSemanticsNode().boundsInRoot.translate(-scene.fetchSemanticsNode().boundsInRoot.topLeft)
                val bounds = try { paintedArt(pixels, laid.center) } catch (failure: AssertionError) {
                    export(pixels, Rect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat()), File(output, "failed-$label.png"))
                    throw failure
                }
                val frame = Frame(label, compose.mainClock.currentTime,
                    list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
                    compose.runOnUiThread { headerFrames.lastOrNull { it.drawn }?.progress }, bounds, laid)
                frames += frame
                export(pixels, bounds.inflate(18f), File(output, "${frames.lastIndex.toString().padStart(3, '0')}-$label.png"))
                return frame
            }
            // Present the mounted selection and its nested render nodes before the stationary source.
            sample("prepared")
            val source = sample("source")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val centre = art.fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
            val x = viewport.width / 2f
            fun begin() {
                list.performTouchInput { down(0, Offset(x - 60f, centre.y)); down(1, Offset(x + 60f, centre.y)) }
                held = true
            }
            fun span(ratio: Float) = list.performTouchInput {
                updatePointerTo(0, Offset(x - 60f * ratio, centre.y))
                updatePointerTo(1, Offset(x + 60f * ratio, centre.y))
                move(delayMillis = 0)
            }
            fun sameSource(frame: Frame) {
                assertEquals("Source width is continuous: $frame versus $source", source.art.width, frame.art.width, 1.25f)
                assertEquals("Source height is continuous: $frame versus $source", source.art.height, frame.art.height, 1.25f)
            }
            begin(); sameSource(sample("down"))
            for ((index, change) in listOf(.01f, .02f, .03f, .039f).withIndex()) {
                span(if (outward) 1f + change else 1f / (1f + change))
                val frame = sample("slop-$index")
                sameSource(frame)
                assertEquals("No preview before direction recognition", source.mode, frame.mode)
            }
            span(if (outward) 1.0405f else 1f / 1.0405f)
            var preview = false
            repeat(18) { index ->
                val frame = sample("recognition-$index")
                if (frame.mode != source.mode) {
                    if (!preview) sameSource(frame)
                    preview = true
                    if (frame.progress == 0f) sameSource(frame)
                }
            }
            assertTrue("The threshold crossing must produce an actual preview", preview)
            span(1f)
            repeat(8) { sameSource(sample("reverse-$it")) }
            list.performTouchInput { cancel(0) }; held = false
            repeat(18) { sameSource(sample("rollback-$it")) }
            assertEquals(initial, prefs.read(route))
            begin(); sample("commit-down")
            repeat(4) { index ->
                val p = (index + 1) * .1f
                span(if (outward) 1f + .8f * p else 1f - .45f * p)
                sample("travel-$index")
            }
            list.performTouchInput { up(0); up(1) }; held = false
            repeat(26) { sample("settle-$it") }
            assertEquals("A genuine release commits one adjacent view", target, prefs.read(route))
            frames.zipWithNext().forEach { (a, b) ->
                assertEquals("No hidden test-clock advances between captured frames", 16L, b.time - a.time)
                if (a.progress != null && b.progress != null && a.mode == b.mode && a.label.startsWith("recognition") && b.label.startsWith("recognition")) {
                    val limit = abs(b.progress - a.progress) * source.art.width * 2f + 1.25f
                    assertTrue("Painted size changes on the shared geometry clock: $a -> $b, tolerance=$limit",
                        abs(b.art.width - a.art.width) <= limit && abs(b.art.height - a.art.height) <= limit)
                }
            }
        } finally {
            File(output, "frames.csv").writeText("label,time,mode,progress,left,top,right,bottom,laidLeft,laidTop,laidRight,laidBottom\n" +
                frames.joinToString("\n") { "${it.label},${it.time},${it.mode?.replace(',', ';')},${it.progress},${it.art.left},${it.art.top},${it.art.right},${it.art.bottom},${it.laid.left},${it.laid.top},${it.laid.right},${it.laid.bottom}" })
            try { if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel(0) } }
            finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, overridden) }
        }
    }

    private fun paintedArt(pixels: PixelMap, seed: Offset): Rect {
        fun red(x: Int, y: Int): Boolean = pixels[x, y].let { it.red > .9f && it.green < .1f && it.blue < .1f }
        fun blue(x: Int, y: Int): Float = pixels[x, y].let { it.blue - maxOf(it.red, it.green) }
        fun opaqueBlue(x: Int, y: Int): Float = pixels[x, y].let { minOf(it.blue - .9f, .1f - it.red, .1f - it.green) }
        val x = seed.x.toInt().coerceIn(0, pixels.width - 1)
        val y = seed.y.toInt().coerceIn(0, pixels.height - 1)
        assertTrue("Presented artwork remains visible at the actual part centre $seed", red(x, y))
        fun edge(dx: Int, dy: Int): Float {
            fun inside(step: Int) = x + step * dx in 0 until pixels.width && y + step * dy in 0 until pixels.height
            fun matches(step: Int, predicate: (Int, Int) -> Boolean) = inside(step) && predicate(x + step * dx, y + step * dy)
            var step = 0
            while (matches(step + 1, ::red)) step++
            val border = (step + 1..step + 3).firstOrNull { matches(it) { x, y -> opaqueBlue(x, y) > 0f } }
            assertNotNull("The opaque artwork border must remain visible toward ($dx,$dy) from $seed", border)
            step = requireNotNull(border)
            while (matches(step + 1) { x, y -> opaqueBlue(x, y) > 0f }) step++
            val background = if (inside(step + 3)) blue(x + (step + 3) * dx, y + (step + 3) * dy) else 1f
            // A sharp edge resolves at half coverage. During the later settle a cover
            // can cross the viewport fade; there, retain its visible opaque contour.
            val sharp = background < .5f
            // A blue border over an opaque red backdrop has zero background blue.
            // Its blue channel measures coverage without red-palette intensity bias.
            val backgroundColor = if (inside(step + 3)) pixels[x + (step + 3) * dx, y + (step + 3) * dy] else null
            val calibrated = sharp && backgroundColor != null && backgroundColor.red > 0f &&
                backgroundColor.green == 0f && backgroundColor.blue == 0f && backgroundColor.alpha == 1f
            val crossing = if (calibrated) .5f else if (sharp) (1f + background) / 2f else 0f
            fun score(at: Int) = if (calibrated) pixels[x + at * dx, y + at * dy].blue
                else if (sharp) blue(x + at * dx, y + at * dy) else opaqueBlue(x + at * dx, y + at * dy)
            while (inside(step + 1) && score(step + 1) > crossing) step++
            if (!inside(step + 1)) return when {
                dx < 0 || dy < 0 -> 0f
                dx > 0 -> pixels.width.toFloat()
                else -> pixels.height.toFloat()
            }
            // Interpolate between pixel centres at the colour crossing. Counting only
            // opaque pixels can turn symmetric subpixel growth into a two-pixel jump.
            val inner = score(step)
            val outer = score(step + 1)
            assertTrue("The pixels bracket the visible artwork edge", inner >= crossing && outer <= crossing)
            val fraction = (inner - crossing) / (inner - outer)
            return if (dx != 0) x + .5f + (step + fraction) * dx else y + .5f + (step + fraction) * dy
        }
        val left = edge(-1, 0); val right = edge(1, 0)
        val top = edge(0, -1); val bottom = edge(0, 1)
        return Rect(left, top, right, bottom)
    }

    private fun export(pixels: PixelMap, region: Rect, file: File) {
        val left = region.left.toInt().coerceIn(0, pixels.width - 1)
        val top = region.top.toInt().coerceIn(0, pixels.height - 1)
        val width = (region.right.toInt().coerceIn(left + 1, pixels.width) - left)
        val height = (region.bottom.toInt().coerceIn(top + 1, pixels.height) - top)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(width * height) { pixels[left + it % width, top + it / width].toArgb() },
                0, width, 0, 0, width, height)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
