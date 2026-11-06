package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Rendered alpha, including the distinct live and retained compositing paths. */
class PinchFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smallAndNormalCoversRetraceTheirPixels() = partMirror(false, 1f)
    @Test fun smallAndNormalCoversRetraceTheirPixelsAtFractionalDensity() = partMirror(false, 1.75f)
    @Test fun tracklistAndGalleryCoversRetraceTheirPixels() = partMirror(true, 1f)
    @Test fun tracklistAndGalleryCoversRetraceTheirPixelsAtFractionalDensity() = partMirror(true, 1.75f)
    @Test fun liveArrivalsMirrorRetainedDeparturesAndFartherItemsFadeFirst() = journeyMirror(1f)
    @Test fun journeyFadesMirrorAtFractionalDensity() = journeyMirror(1.75f)
    @Test fun journeyFadesIncludeDifferentVerticalInsets() = journeyMirror(1f, true)
    @Test fun journeyFadesIncludeFractionalVerticalInsets() = journeyMirror(1.75f, true)

    private fun peak(tag: String, channel: (Color) -> Float): Float {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        var peak = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width)
            peak = maxOf(peak, channel(pixels[x, y]))
        return peak
    }

    private fun partMirror(tracklist: Boolean, scale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val cover = File.createTempFile("pinch-fade-", ".png", directory)
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            val track = Track(Uri.fromFile(cover).toString(), "Song", fileName = "song.mp3", relativePath = "song.mp3")
            val row = SongRow(track.id, track)
            val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
            val absent = BrowseStyle(listSize = if (tracklist) ListSize.Tracklist else ListSize.Small)
            val present = if (tracklist) BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5)
                else BrowseStyle(listSize = ListSize.Normal)
            val motion = BrowsePinchMotion()
            var style by mutableStateOf(present)
            fun columns(value: BrowseStyle) = if (value.presentation == Presentation.List) 1 else 5
            val viewport = (300 * scale).roundToInt()
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(scale)) { LocalicaTheme {
                val geometry = BrowseCellGeometry(columns(style))
                val width = geometry.cardWidth(geometry.cellWidth(viewport, scale), scale)
                LookaheadScope {
                    Box(Modifier.size(300.dp, 280.dp).background(Color.Black).testTag("part-fade")) {
                        Box(Modifier.width(with(LocalDensity.current) { width.toDp() })) {
                            BrowseItemLayout(item, style, geometry, this@LookaheadScope, false, menu = {},
                                trackNumber = "1", pinch = motion)
                        }
                    }
                }
            } } }
            fun alpha() = peak("part-fade") { it.red - maxOf(it.green, it.blue) }
            compose.waitUntil(5000) { alpha() > .95f }
            compose.mainClock.autoAdvance = false
            fun sweep(source: BrowseStyle, target: BrowseStyle, backwards: Boolean): List<Float> {
                compose.runOnIdle {
                    motion.source = source; motion.target = target
                    motion.pixelDensity = scale; motion.viewportWidth = viewport.toFloat()
                    motion.sourceColumns = columns(source); motion.targetColumns = columns(target)
                    motion.bounds = mapOf(item.key to Rect(0f, 0f,
                        BrowseCellGeometry(columns(source)).cellWidth(viewport, scale).toFloat(), 200 * scale))
                    style = target
                }
                return (0..20).map { step ->
                    val p = if (backwards) 1f - step / 20f else step / 20f
                    compose.runOnIdle { runBlocking { motion.progress.snapTo(p) } }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    alpha()
                }
            }
            val arriving = sweep(absent, present, false)
            val departing = sweep(present, absent, true)
            assertEquals(0f, arriving.first(), .01f)
            assertEquals(1f, arriving.last(), .02f)
            assertTrue("The sampled fade has intermediate painted frames", arriving.count { it in .05f.. .95f } >= 3)
            arriving.zip(departing).forEachIndexed { index, (a, b) ->
                assertEquals("Cover alpha retraces spatial position ${index / 20f} at density $scale", a, b, .035f)
            }
        } finally { compose.mainClock.autoAdvance = true; cover.delete() }
    }

    private fun journeyMirror(scale: Float, smallest: Boolean = false) {
        val motion = BrowsePinchMotion()
        var incoming by mutableStateOf(false)
        lateinit var empty: TextLayoutResult
        val pitch = (100 * scale).roundToInt()
        val viewport = (200 * scale).roundToInt()
        val art = (30 * scale).roundToInt()
        val list = BrowseStyle(listSize = if (smallest) ListSize.Smallest else ListSize.Normal)
        val gallery = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        // The nearer item travels farther: focus distance, not travel distance, orders fades.
        val indices = mapOf("anchor" to 0, "near" to 3, "far" to 1)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(scale)) { LocalicaTheme {
            empty = rememberTextMeasurer().measure(AnnotatedString(""))
            val grid = rememberLazyGridState()
            Box(Modifier.size(200.dp, 160.dp).background(Color.Black).testTag("journey-fade")
                .pinchRetainedOverlay(motion, grid, indices)) {
                LazyVerticalGrid(GridCells.Fixed(if (incoming) 2 else 1), state = grid,
                    modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    item(key = "browser-notice", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(0.dp)) }
                    if (!incoming) item(key = "anchor") { Spacer(Modifier.height(100.dp)) }
                    else for ((column, key) in listOf("near", "far").withIndex()) item(key = key) {
                        Box(Modifier.height(100.dp).padding(4.dp)) {
                            Box(Modifier.size(30.dp).graphicsLayer {
                                val delta = motion.contentTranslation(key, indices.getValue(key), IntOffset(column * pitch, 0),
                                    pitch - (8 * scale).roundToInt(), gallery, BrowseCellGeometry(2), scale)
                                translationX = delta.x; translationY = delta.y
                            }.retainPinchArtwork(key, motion).background(if (key == "near") Color.Green else Color.Blue))
                        }
                    }
                }
            }
        } } }
        compose.waitForIdle()
        fun configure(arriving: Boolean) = compose.runOnIdle {
            incoming = arriving
            motion.source = if (arriving) list else gallery
            motion.target = if (arriving) gallery else list
            motion.sourceColumns = if (arriving) 1 else 2
            motion.targetColumns = if (arriving) 2 else 1
            motion.pixelDensity = scale; motion.viewportWidth = viewport.toFloat()
            // Keep synthetic incoming endpoints on screen: this fixture measures alpha, not clipping.
            motion.anchorIndex = 0; motion.anchorTop = 0f; motion.rowHeight = scale
            motion.focusKey = "focus"; motion.focusTop = pitch / 2
            motion.bounds = if (arriving) emptyMap() else mapOf("near" to Rect(0f, 0f, pitch.toFloat(), pitch.toFloat()),
                "far" to Rect(pitch.toFloat(), 0f, viewport.toFloat(), pitch.toFloat()))
            motion.bounds = motion.bounds + ("focus" to Rect(0f, 0f, pitch.toFloat(), pitch.toFloat()))
            motion.frozen.clear()
            if (!arriving) for ((key, color) in listOf("near" to android.graphics.Color.GREEN, "far" to android.graphics.Color.BLUE)) {
                val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
                val endpoint = BrowsePinchEndpoint(art, 0, 0, empty, 0, art, null, 0, art, 0, 0, 0, 0, pitch, 0f)
                motion.frozen[key] = FrozenBrowseItem(ImageBitmap(pitch, pitch), IntOffset.Zero, IntSize(pitch, pitch),
                    content = FrozenBrowseContent(endpoint, endpoint, Color.Black, Color.Black),
                    artwork = FrozenBrowsePart(image, IntOffset.Zero, IntSize(32, 32)))
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            fun sweep(arriving: Boolean): List<Pair<Float, Float>> {
                configure(arriving)
                return (0..20).map { step ->
                    val p = step / 20f
                    compose.runOnIdle { runBlocking { motion.progress.snapTo(if (arriving) 1f - p else p) } }
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    peak("journey-fade") { it.green } to peak("journey-fade") { it.blue }
                }
            }
            val departures = sweep(false)
            val rollback = (20 downTo 0).map { step ->
                compose.runOnIdle { runBlocking { motion.progress.snapTo(step / 20f) } }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                peak("journey-fade") { it.green } to peak("journey-fade") { it.blue }
            }.reversed()
            departures.zip(rollback).forEachIndexed { index, (a, b) ->
                assertEquals("Near rollback retraces frame $index", a.first, b.first, .008f)
                assertEquals("Far rollback retraces frame $index", a.second, b.second, .008f)
            }
            val arrivals = sweep(true)
            departures.zip(arrivals).forEachIndexed { index, (a, b) ->
                assertEquals("Near journey retraces its alpha at ${index / 20f}", a.first, b.first, .008f)
                assertEquals("Far journey retraces its alpha at ${index / 20f}", a.second, b.second, .008f)
            }
            assertEquals(1f, departures.first().first, .02f)
            assertEquals(1f, departures.first().second, .02f)
            assertTrue("The farther departure disappears while the nearer cover still paints",
                departures.any { (near, far) -> near > .05f && far < .01f })
            assertTrue("Fades must still paint late in the eased transition", departures[15].first > .05f)
            assertTrue("Neighboring equal-width fades overlap", departures.any { (near, far) ->
                near in .05f.. .95f && far in .05f.. .95f
            })
            assertEquals(0f, departures.last().first, .01f)
            assertEquals(0f, departures.last().second, .01f)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
