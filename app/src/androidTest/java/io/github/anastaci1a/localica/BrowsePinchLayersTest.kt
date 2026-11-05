package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.BrowseCellGeometry
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class BrowsePinchLayersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zeroSizeLiveCoordinatesSkipTheCoverAndRecoverAfterMeasurement() {
        val motion = BrowsePinchMotion()
        var empty: LayoutCoordinates? = null
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            Box(Modifier.size(180.dp, 250.dp).background(Color.Black).testTag("zero-scene")
                .pinchRetainedOverlay(motion, grid, mapOf("a" to 0, "b" to 1))) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "a") { Box(Modifier.height(100.dp).retainPinchArtwork("a", motion).background(Color.Green)) }
                    item(key = "b") { Box(Modifier.height(100.dp).retainPinchArtwork("b", motion).background(Color.Red)) }
                }
                Box(Modifier.size(0.dp).onGloballyPositioned { empty = it })
            }
        } }
        compose.waitForIdle()
        fun pixels() = compose.onNodeWithTag("zero-scene").captureToImage().toPixelMap()
        val before = pixels()
        assertTrue(before[before.width / 2, before.height / 5].green > .9f)
        lateinit var original: LayoutCoordinates
        compose.runOnIdle {
            val part = requireNotNull(motion.artwork["a"])
            assertTrue(part.layer.size.width > 0 && part.layer.size.height > 0)
            assertEquals(IntSize.Zero, requireNotNull(empty).size)
            original = requireNotNull(part.coordinates)
            part.coordinates = empty
            motion.itemAlpha["b"] = .99f // Invalidate the overlay while retaining the recording.
        }
        val skipped = pixels()
        assertTrue(skipped[skipped.width / 2, skipped.height / 5].green < .1f)
        assertTrue(skipped[skipped.width / 2, skipped.height * 3 / 5].red > .9f)
        compose.runOnIdle {
            requireNotNull(motion.artwork["a"]).coordinates = original
            motion.itemAlpha["b"] = 1f
        }
        val restored = pixels()
        assertTrue(restored[restored.width / 2, restored.height / 5].green > .9f)
    }

    @Test fun artworkPassStaysAboveTextFromALaterItemAtRestDuringPinchAndAfterHandoff() {
        val motion = BrowsePinchMotion()
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val shift = with(LocalDensity.current) { 100.dp.toPx() }
            Box(Modifier.size(180.dp, 250.dp).testTag("layer-scene")
                .pinchRetainedOverlay(motion, grid, mapOf("art" to 0, "text" to 1))) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "art") {
                        Box(Modifier.size(100.dp).retainPinchArtwork("art", motion).background(Color.Green))
                    }
                    item(key = "text") {
                        // Stand in for a later row's glyphs crossing the earlier cover.
                        Box(Modifier.height(100.dp).graphicsLayer { translationY = -shift }.background(Color.Red))
                    }
                }
            }
        } }
        fun verify() {
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("layer-scene").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, (pixels.height * .2f).toInt()]
            assertTrue("All artwork must be above every row's text, not only its own row: $color",
                color.green > .9f && color.red < .1f)
        }
        verify()
        compose.runOnIdle { motion.itemAlpha["art"] = .4f }
        val fading = compose.onNodeWithTag("layer-scene").captureToImage().toPixelMap()
        val color = fading[fading.width / 2, (fading.height * .2f).toInt()]
        assertEquals("Parent entry/drag opacity is applied exactly once", .4f, color.green, .03f)
        assertEquals(.6f, color.red, .03f)
        compose.runOnIdle { motion.itemAlpha["art"] = 1f; motion.source = BrowseStyle(); motion.target = BrowseStyle() }
        verify()
        compose.runOnIdle { motion.clearTransaction() }
        verify()
    }

    /** #522: within each pass the earlier item is on top; every cover stays above all text. */
    @Test fun earlierItemsPaintAboveLaterOnesWithinEachPass() {
        val motion = BrowsePinchMotion()
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val shift = with(LocalDensity.current) { 120.dp.toPx() }
            Box(Modifier.size(180.dp, 300.dp).testTag("order-scene")
                .pinchRetainedOverlay(motion, grid, mapOf("a" to 0, "b" to 1))) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    for ((key, art, text) in listOf(Triple("a", Color.Green, Color.Blue), Triple("b", Color.Red, Color.Yellow)))
                        item(key = key) {
                            // The later item is pulled up across the earlier one: its cover over the
                            // earlier cover and text, its text over the earlier text.
                            Column(Modifier.graphicsLayer { translationY = if (key == "b") -shift else 0f }) {
                                Box(Modifier.size(100.dp).retainPinchArtwork(key, motion).background(art))
                                Box(Modifier.size(100.dp, 40.dp).retainPinchText(key, motion).background(text))
                            }
                        }
                }
            }
        } }
        fun verify(phase: String) {
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("order-scene").captureToImage().toPixelMap()
            val scale = pixels.height / 300f
            fun at(y: Int) = pixels[(50 * scale).toInt(), (y * scale).toInt()]
            val covers = at(60); val texts = at(130); val crossing = at(110)
            assertTrue("$phase: the earlier cover is above the later cover: $covers", covers.green > .9f && covers.red < .1f)
            assertTrue("$phase: the earlier text is above the later text: $texts", texts.blue > .9f && texts.red < .1f)
            assertTrue("$phase: a later cover is still above earlier text: $crossing", crossing.red > .9f && crossing.blue < .1f)
        }
        verify("rest")
        compose.runOnIdle { motion.source = BrowseStyle(); motion.target = BrowseStyle() }
        verify("pinch")
    }

    @Test fun selectedRowKeepsCrossingArtworkInItsSharpForegroundAndBelowItsRim() {
        verifySelectedArtworkLayering()
    }

    @Test fun selectedArtworkLayeringUsesTheSamePixelOriginAtFractionalDensity() {
        verifySelectedArtworkLayering(Density(2.625f))
    }

    private fun verifySelectedArtworkLayering(density: Density? = null) {
        val motion = BrowsePinchMotion()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides (density ?: LocalDensity.current)) {
                Column {
                    for (global in listOf(false, true)) key(global) {
                        val grid = rememberLazyGridState()
                        val stripes: @Composable (Modifier) -> Unit = { modifier ->
                            Canvas(modifier) {
                                drawRect(Color.Green)
                                for (x in 0..18) if (x % 2 == 0) drawRect(Color.Black,
                                    topLeft = androidx.compose.ui.geometry.Offset(x * 10.dp.toPx(), 0f),
                                    size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
                            }
                        }
                        SelectionViewport(Modifier.size(180.dp, 120.dp).testTag(if (global) "selected-global" else "selected-reference"),
                            contentDrawing = if (global) Modifier.pinchRetainedOverlay(motion, grid, mapOf("art" to 0, "text" to 1)) else Modifier) {
                            LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                                item(key = "art") {
                                    if (global) stripes(Modifier.height(100.dp).testTag("global-art").retainPinchArtwork("art", motion))
                                    else Spacer(Modifier.height(100.dp))
                                }
                                item(key = "text") {
                                    Box(Modifier.height(100.dp).graphicsLayer {
                                        // Use laid-out pitch, not raw dp pixels: at 2.625 density
                                        // the 100 dp preceding row is 263 px, not 262.5 px.
                                        translationY = -(grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "art" }
                                            ?.size?.height ?: 0).toFloat()
                                    }
                                        .selectionLens(true, false)
                                        .then(if (global) Modifier.retainPinchText("text", motion) else Modifier)
                                        .background(Color.Red)) {
                                        // Reference paints the same foreground directly inside the
                                        // selected row. The global version crosses it from its neighbor.
                                        if (!global) stripes(Modifier.fillMaxSize().testTag("reference-art"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } }
        fun verify() {
            compose.waitForIdle()
            for (type in listOf("reference", "global")) {
                val viewport = compose.onNodeWithTag("selected-$type").fetchSemanticsNode().boundsInRoot
                val artwork = compose.onNodeWithTag("$type-art").fetchSemanticsNode().boundsInRoot
                assertEquals("$type artwork must start at the same pixel origin as its viewport", viewport.top, artwork.top, .01f)
            }
            val reference = compose.onNodeWithTag("selected-reference").captureToImage().toPixelMap()
            val actual = compose.onNodeWithTag("selected-global").captureToImage().toPixelMap()
            var different = 0; var green = 0
            for (y in 0 until (reference.height * 100 / 120)) for (x in 0 until reference.width) {
                val a = actual[x, y]; val b = reference[x, y]
                if (kotlin.math.abs(a.green - b.green) > .08f || kotlin.math.abs(a.red - b.red) > .08f) different++
                if (a.green > .8f && a.red < .2f) green++
            }
            assertTrue("Crossing artwork must remain visible and sharp", green > reference.width * reference.height / 4)
            assertTrue("Crossing art belongs inside the selection foreground, below its rim: $different", different < 8)
        }
        verify()
        compose.runOnIdle { motion.source = BrowseStyle(); motion.target = BrowseStyle() }
        verify()
        compose.runOnIdle { motion.clearTransaction() }
        verify()
    }

    @Test fun aVirtualizedCoverScalesByItsArtworkEndpointAboveLaterText() {
        val motion = BrowsePinchMotion()
        val image = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }.asImageBitmap()
        lateinit var grid: LazyGridState
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState()
            val empty = rememberTextMeasurer().measure(AnnotatedString(""))
            SideEffect {
                if (motion.source == null) {
                    val source = BrowsePinchEndpoint(50, 0, 0, empty, 0, 50, null, 0, 70, 0, 0, 0, 0, 100, 0f)
                    val target = source.copy(art = 100, titleY = 100)
                    motion.source = BrowseStyle(); motion.target = BrowseStyle()
                    motion.pixelDensity = 1f; motion.viewportWidth = 200f
                    motion.bounds = mapOf("frozen" to Rect(0f, 0f, 64f, 108f))
                    motion.frozen["frozen"] = FrozenBrowseItem(ImageBitmap(56, 100), IntOffset.Zero, IntSize(56, 100),
                        content = FrozenBrowseContent(source, target, Color.Black, Color.Black),
                        artwork = FrozenBrowsePart(image, IntOffset.Zero, IntSize(50, 50)))
                }
            }
            Box(Modifier.size(200.dp, 250.dp).background(Color.Red).testTag("frozen-scene")
                .pinchRetainedOverlay(motion, grid, mapOf("text" to 0, "frozen" to 1))) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "browser-notice") { Spacer(Modifier.height(0.dp)) }
                    item(key = "text") { Box(Modifier.height(100.dp).background(Color.Red)) }
                }
            }
        } }
        compose.waitForIdle()
        val rowHeight = grid.layoutInfo.visibleItemsInfo.first { it.key == "text" }.size.height
        val cutoff = browsePinchFadeWindow(rowHeight.toFloat(), 200f)
        for (p in listOf(.025f, .05f, .1f, .15f, .2f, .3f, .45f, .6f, .8f, 1f, .8f, .3f, .15f, .1f, .05f)) {
            compose.runOnIdle { runBlocking { motion.progress.snapTo(p) } }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("frozen-scene").captureToImage().toPixelMap()
            val ink = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                if (pixels[x, y].green > .025f) ink += x to y
            if (p >= 1f) {
                assertTrue("A singleton departure is invisible at the transition end: $p", ink.isEmpty())
            } else {
                assertTrue("Retained cover remains visible before its fade end: $p / $cutoff", ink.isNotEmpty())
                val width = ink.maxOf { it.first } - ink.minOf { it.first } + 1
                val top = ink.minOf { it.second }
                assertEquals("Retained cover follows its ordinary artwork scale at $p", 50f + 50f * minOf(p, cutoff), width.toFloat(), 2f)
                assertEquals("Retained cover follows the same progress in position at $p", 4f + rowHeight * minOf(p, cutoff), top.toFloat(), 2f)
                assertTrue("No departure grows past its fade-end size", width <= 50f + 50f * cutoff + 2f)
            }
        }
    }

    @Test fun retainedDrawingRebindsCurrentOrderAndOmitsRemovedKeys() {
        val fixture = retainedIndexFixture()
        assertRetainedPosition(fixture)
        val original = greenBounds()
        val bounds = fixture.motion.bounds
        val frozen = fixture.motion.frozen.getValue("frozen")
        val source = fixture.motion.source
        val transaction = fixture.motion.transaction

        compose.runOnIdle { fixture.keys.value = listOf("frozen", "spare", "anchor") }
        compose.waitForIdle()
        assertRetainedPosition(fixture)
        assertNotEquals("The current ordering must move the frozen destination", original, greenBounds())
        compose.runOnIdle { fixture.keys.value = listOf("spare", "anchor") }
        compose.waitForIdle()
        assertNull("A removed retained key must not borrow index zero", greenBounds())
        compose.runOnIdle {
            assertSame(bounds, fixture.motion.bounds)
            assertSame(frozen, fixture.motion.frozen["frozen"])
            assertSame(source, fixture.motion.source)
            assertEquals(transaction, fixture.motion.transaction)
        }
    }

    @Test fun liveRowsExcludeTheirFrozenCopyWithoutRetiringTransaction() {
        val fixture = retainedIndexFixture()
        assertNotNull(greenBounds())
        val frozen = fixture.motion.frozen.getValue("frozen")
        compose.runOnIdle { fixture.visible.value = listOf("anchor", "frozen") }
        compose.waitForIdle()
        drawAt(fixture, .11f)
        assertNull("The live blue row must exclude its retained green copy", greenBounds())
        assertDrawLookups(fixture, listOf("anchor"))
        compose.runOnIdle {
            assertSame(frozen, fixture.motion.frozen["frozen"])
            assertNotNull(fixture.motion.visualSource)
        }
    }

    @Test fun missingOneColumnAnchorKeepsMinusOneRowArithmetic() = missingAnchor(1)
    @Test fun missingMultiColumnAnchorKeepsMinusOneRowArithmetic() = missingAnchor(2)

    private fun missingAnchor(columns: Int) {
        val fixture = retainedIndexFixture(columns, listOf("missing-anchor"))
        assertRetainedPosition(fixture)
        assertDrawLookups(fixture, listOf("missing-anchor", "frozen"))
    }

    @Test fun retainedIndexLookupsDoNotScaleWithCatalogSize() {
        val fixture = retainedIndexFixture()
        for ((step, size) in listOf(100, 13_313).withIndex()) {
            compose.runOnIdle {
                fixture.keys.value = listOf("anchor", "spare", "frozen") + List(size - 3) { "unused-$it" }
            }
            compose.waitForIdle()
            drawAt(fixture, .11f + step * .01f)
            assertRetainedPosition(fixture)
            assertDrawLookups(fixture, listOf("anchor", "frozen"))
        }
    }

    @Test fun absentAnchorAndInactiveTransitionDoNotReadCatalogIndices() {
        val fixture = retainedIndexFixture()
        compose.runOnIdle { fixture.visible.value = emptyList() }
        compose.waitForIdle()
        drawAt(fixture, .11f)
        assertNull(greenBounds())
        assertDrawLookups(fixture, emptyList())
        compose.runOnIdle {
            fixture.visible.value = listOf("anchor")
            fixture.motion.source = null
        }
        compose.waitForIdle()
        drawAt(fixture, .12f)
        assertNull(greenBounds())
        assertDrawLookups(fixture, emptyList())
    }

    private class CountedIndices(private val delegate: Map<String, Int>) : Map<String, Int> by delegate {
        val reads = mutableListOf<String>()
        override fun get(key: String): Int? { reads += key; return delegate[key] }
        override val entries: Set<Map.Entry<String, Int>> get() = error("Drawing must not enumerate the catalog")
        override val keys: Set<String> get() = error("Drawing must not enumerate the catalog")
        override val values: Collection<Int> get() = error("Drawing must not enumerate the catalog")
    }

    private class RetainedIndexFixture(val columns: Int, visibleKeys: List<String>) {
        val motion = BrowsePinchMotion()
        val keys = mutableStateOf(listOf("anchor", "spare", "frozen"))
        val visible = mutableStateOf(visibleKeys)
        val draws = mutableListOf<List<String>>()
        lateinit var grid: LazyGridState
        var prepared = false
    }

    private fun retainedIndexFixture(columns: Int = 1, visible: List<String> = listOf("anchor")): RetainedIndexFixture {
        val fixture = RetainedIndexFixture(columns, visible)
        val motion = fixture.motion
        val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GREEN) }.asImageBitmap()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val grid = rememberLazyGridState()
            val keys = fixture.keys.value
            val indices = remember(keys) {
                val shared = keys.withIndex().associate { it.value to it.index }
                CountedIndices(retainedPinchItemIndices(keys, shared) { it })
            }
            val empty = rememberTextMeasurer().measure(AnnotatedString(""))
            SideEffect {
                fixture.grid = grid
                if (!fixture.prepared) {
                    fixture.prepared = true
                    val endpoint = BrowsePinchEndpoint(40, 0, 0, empty, 0, 40, null, 0, 60, 0, 0, 0, 0, 100, 0f)
                    motion.source = BrowseStyle(); motion.target = BrowseStyle()
                    motion.sourceColumns = columns; motion.targetColumns = columns
                    motion.pixelDensity = 1f; motion.viewportWidth = 200f
                    motion.bounds = linkedMapOf("anchor" to Rect.Zero,
                        "frozen" to Rect(20f, 60f, 84f, 168f), "no-snapshot" to Rect.Zero)
                    motion.frozen["frozen"] = FrozenBrowseItem(ImageBitmap(56, 100), IntOffset.Zero, IntSize(56, 100),
                        content = FrozenBrowseContent(endpoint, endpoint, Color.Black, Color.Black),
                        artwork = FrozenBrowsePart(image, IntOffset.Zero, IntSize(40, 40)))
                }
            }
            Box(Modifier.size(200.dp, 250.dp).testTag("index-scene").background(Color.Black)
                // Observe the actual overlay pass inside its recording boundary.
                .graphicsLayer()
                .drawWithContent {
                    val before = indices.reads.size
                    drawContent()
                    fixture.draws += indices.reads.subList(before, indices.reads.size).toList()
                }.pinchRetainedOverlay(motion, grid, indices)) {
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "browser-notice", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(0.dp)) }
                    items(fixture.visible.value, key = { it }) { key ->
                        // A fading green drawing must remain measurable across the live row edge.
                        Box(Modifier.height(100.dp).background(if (key == "frozen") Color.Blue else Color.Black))
                    }
                }
            }
        } } }
        compose.waitForIdle()
        drawAt(fixture, .1f)
        return fixture
    }

    private fun drawAt(fixture: RetainedIndexFixture, progress: Float) {
        compose.runOnIdle { fixture.draws.clear(); runBlocking { fixture.motion.progress.snapTo(progress) } }
        compose.waitForIdle()
    }

    private fun assertDrawLookups(fixture: RetainedIndexFixture, expected: List<String>) {
        compose.runOnIdle {
            assertTrue("A measured draw, not just an idle callback, is required", fixture.draws.isNotEmpty())
            fixture.draws.forEach { assertEquals("Each draw visits only its eligible viewport keys", expected, it) }
        }
    }

    private fun assertRetainedPosition(fixture: RetainedIndexFixture) {
        val actual = requireNotNull(greenBounds()) { "Retained artwork must remain visible" }
        compose.runOnIdle {
            val row = fixture.grid.layoutInfo.visibleItemsInfo.first { it.key != "browser-notice" }
            val keys = fixture.keys.value
            // Preserve the old list-search oracle, including a missing anchor's -1.
            val index = keys.indexOf("frozen")
            val rowIndex = keys.indexOf(row.key)
            val geometry = BrowseCellGeometry(fixture.columns)
            val inset = geometry.cardVerticalInset(BrowseStyle()).roundToInt()
            val y = row.offset.y + (index / fixture.columns - rowIndex / fixture.columns) * row.size.height
            val top = y + inset + pinchContentTranslation(60f, y.toFloat(), inset, inset, fixture.motion.progress.value)
            // Retained covers draw at the exact float rectangle (#505): a partly covered edge
            // pixel row/column can read as green, so the detected box is within one pixel.
            assertEquals("Current indices own the destination, source bounds stay frozen", top, actual.top, 1f)
            assertEquals(40f, actual.width, 1.01f)
            assertEquals(40f, actual.height, 1.01f)
        }
    }

    private fun greenBounds(): Rect? {
        val pixels = compose.onNodeWithTag("index-scene").captureToImage().toPixelMap()
        var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.green > color.red + .1f && color.green > color.blue + .1f) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    @Test fun sourceViewportKeepsDistinctCoversAcrossVirtualizationAndGlobalCacheEviction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        // Enough distinct 768 px covers to exceed the largest decoded-cache budget (48 MiB), whatever the device heap class.
        val files = (0 until 24).map { File.createTempFile("pinch-lease-$it-", ".png", directory) }
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)
        val uris = files.map { Uri.fromFile(it).toString() }
        val retention = BrowseArtworkScope().apply { activate(true) }
        var shown by mutableStateOf(true)
        val motion = BrowsePinchMotion()
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        var release: (() -> Unit)? = null
        try {
            files.forEachIndexed { index, file ->
                val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[index % 3]) }
                try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            runBlocking { uris.take(3).forEach { assertNotNull(ArtworkCache.read(context, it, 768)) } }
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalBrowseArtwork provides retention) {
                scope = rememberCoroutineScope()
                if (shown) Row {
                    uris.take(3).forEachIndexed { index, uri -> key(uri) {
                        MediaArtwork(listOf(uri), Modifier.size(48.dp).testTag("leased-$index"))
                    } }
                }
            } } }
            compose.waitForIdle()
            compose.runOnIdle { release = retention.retainForTransition(); shown = false }
            compose.waitForIdle()
            // The remaining distinct large decodes exceed the global LRU at any heap class. Do not
            // invalidate: an artwork revision must deliberately invalidate leases too.
            runBlocking { uris.drop(3).forEach { assertNotNull(ArtworkCache.read(context, it, 768)) } }
            assertNull("The test must genuinely evict the original source from the global cache", ArtworkCache.peek(listOf(uris.first())))
            assertNotNull(retention.peek(listOf(uris.first())))
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                motion.source = BrowseStyle(); motion.releaseArtwork = release
                motion.releaseArtworkAfterHandoff(scope)
                motion.clearTransaction() // The same ownership retirement as modal rollback.
                shown = true
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            for (index in 0..2) {
                val pixels = compose.onNodeWithTag("leased-$index").captureToImage().toPixelMap()
                val color = pixels[pixels.width / 2, pixels.height / 2]
                val channel = when (index) { 0 -> color.red; 1 -> color.green; else -> color.blue }
                val others = when (index) { 0 -> color.green + color.blue; 1 -> color.red + color.blue; else -> color.red + color.green }
                assertTrue("Remount must show retained pixels on its first frame, without a reload reveal: $index $color",
                    channel > .95f && others < .1f)
            }
            compose.onAllNodesWithTag("artwork-loading").assertCountEquals(0)
            compose.runOnIdle { release?.invoke(); shown = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNull("After handoff ordinary scrolling must release offscreen covers", retention.peek(listOf(uris.first())))
        } finally {
            release?.invoke()
            compose.mainClock.autoAdvance = true
            files.forEach { it.delete() }
        }
    }

    @Test fun invalidationAndANewerTransitionRetireOnlyTheirOwnBitmapLease() {
        val scope = BrowseArtworkScope().apply { activate(true) }
        val token = Any()
        val first = listOf("first")
        val cover = ArtworkCache.CachedArtwork(null, 128)
        scope.hold(token, first, cover)
        val old = scope.retainForTransition()
        val current = scope.retainForTransition()
        scope.release(token)
        old()
        assertSame(cover, scope.peek(first))
        ArtworkCache.invalidate()
        assertNull(scope.peek(first))
        current()
    }
}
