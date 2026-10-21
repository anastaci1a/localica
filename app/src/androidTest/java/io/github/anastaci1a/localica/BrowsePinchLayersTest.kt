package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowsePinchLayersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun artworkPassStaysAboveTextFromALaterItemAtRestDuringPinchAndAfterHandoff() {
        val motion = BrowsePinchMotion()
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val shift = with(LocalDensity.current) { 100.dp.toPx() }
            Box(Modifier.size(180.dp, 250.dp).testTag("layer-scene")
                .pinchRetainedOverlay(motion, grid, listOf("art", "text"))) {
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
        val motion = BrowsePinchMotion()
        compose.setContent { LocalicaTheme {
            Column {
                for (global in listOf(false, true)) key(global) {
                    val grid = rememberLazyGridState()
                    val shift = with(LocalDensity.current) { 100.dp.toPx() }
                    val stripes: @Composable (Modifier) -> Unit = { modifier ->
                        Canvas(modifier) {
                            drawRect(Color.Green)
                            for (x in 0..18) if (x % 2 == 0) drawRect(Color.Black,
                                topLeft = androidx.compose.ui.geometry.Offset(x * 10.dp.toPx(), 0f),
                                size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
                        }
                    }
                    SelectionViewport(Modifier.size(180.dp, 120.dp).testTag(if (global) "selected-global" else "selected-reference"),
                        contentDrawing = if (global) Modifier.pinchRetainedOverlay(motion, grid, listOf("art", "text")) else Modifier) {
                        LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize()) {
                            item(key = "art") {
                                if (global) stripes(Modifier.height(100.dp).retainPinchArtwork("art", motion))
                                else Spacer(Modifier.height(100.dp))
                            }
                            item(key = "text") {
                                Box(Modifier.height(100.dp).graphicsLayer { translationY = -shift }
                                    .selectionLens(true, false)
                                    .then(if (global) Modifier.retainPinchText("text", motion) else Modifier)
                                    .background(Color.Red)) {
                                    // Reference paints the same foreground directly inside the
                                    // selected row. The global version crosses it from its neighbor.
                                    if (!global) stripes(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        } }
        fun verify() {
            compose.waitForIdle()
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
                .pinchRetainedOverlay(motion, grid, listOf("text", "frozen"))) {
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
            val extended = pixels[57, y] // 4 px inset + 53 px; beyond the original 50 px cover.
            val outside = pixels[61, y]  // Exactly 55 px, not card aspect-fit, including after reversal.
            assertTrue("Retained art scales above later text, reversed=$reversed: $extended", extended.green > .8f && extended.red < .2f)
            assertTrue("Art uses its own endpoint, reversed=$reversed: $outside", outside.red > .9f && outside.green < .1f)
        }
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
