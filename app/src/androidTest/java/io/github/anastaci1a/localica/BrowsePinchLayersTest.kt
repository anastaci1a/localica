package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.test.junit4.createComposeRule
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
            Box(Modifier.size(200.dp, 250.dp).testTag("frozen-scene")
                .pinchRetainedOverlay(motion, grid, mapOf("text" to 0, "frozen" to 1))) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "browser-notice") { Spacer(Modifier.height(0.dp)) }
                    item(key = "text") { Box(Modifier.height(100.dp).background(Color.Red)) }
                }
            }
        } }
        for (reversed in listOf(false, true)) {
            if (reversed) {
                compose.runOnIdle { runBlocking { motion.progress.snapTo(.3f) } }
                compose.waitForIdle()
            }
            compose.runOnIdle { runBlocking { motion.progress.snapTo(.1f) } }
            compose.waitForIdle()
            val rowHeight = grid.layoutInfo.visibleItemsInfo.first { it.key == "text" }.size.height
            val pixels = compose.onNodeWithTag("frozen-scene").captureToImage().toPixelMap()
            val y = (4 + rowHeight * .1f + 25).toInt()
            // At .1 progress the departing 50→100 px cover has reached 61 px,
            // ahead of the live 55 px scale. Position still follows the ordinary anchor.
            val extended = pixels[63, y] // Beyond both the source and ordinary live width.
            val outside = pixels[67, y]  // Outside the accelerated artwork endpoint, not card aspect-fit.
            assertTrue("Retained art scales above later text, reversed=$reversed: $extended", extended.green > .8f && extended.red < .2f)
            assertTrue("Art uses its own endpoint, reversed=$reversed: $outside", outside.red > .9f && outside.green < .1f)
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
                .drawWithContent {
                    val before = indices.reads.size
                    drawContent()
                    fixture.draws += indices.reads.subList(before, indices.reads.size).toList()
                }.pinchRetainedOverlay(motion, grid, indices)) {
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize()) {
                    item(key = "browser-notice", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(0.dp)) }
                    items(fixture.visible.value, key = { it }) { key ->
                        Box(Modifier.height(100.dp).background(if (key == "frozen") Color.Blue else Color.Red))
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
            assertEquals("Current indices own the destination, source bounds stay frozen", top.roundToInt(), actual.top.toInt())
            assertEquals(40f, actual.width, .01f)
            assertEquals(40f, actual.height, .01f)
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
        val files = (0..9).map { File.createTempFile("pinch-lease-$it-", ".png", directory) }
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
            // Seven distinct large decodes exceed the global 12 MiB LRU. Do not
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
