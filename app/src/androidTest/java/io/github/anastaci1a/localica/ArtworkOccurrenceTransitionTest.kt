package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.library.EmbeddedCoverProvider
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ArtworkOccurrenceTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun supersedingUndrawnVisitsPreserveTheLastDrawnCoverInsteadOfAnOlderRecording() {
        fun cover(key: String, color: Int): PlayerVisual {
            val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
            return PlayerVisual(image, softArtwork = runBlocking { blurGlassSnapshot(image, 6f) }, contentKey = key)
        }
        val red = cover("red", android.graphics.Color.RED)
        val blue = cover("blue", android.graphics.Color.BLUE)
        val green = cover("green", android.graphics.Color.GREEN)
        val yellow = cover("yellow", android.graphics.Color.YELLOW)
        var visual by mutableStateOf(red)
        var visit by mutableStateOf("red-visit")
        data class DrawRequest(val allowContent: Boolean, val observed: CountDownLatch = CountDownLatch(1))
        val drawRequest = mutableStateOf(DrawRequest(true))
        val allowedDraws = java.util.concurrent.atomic.AtomicInteger()
        // Observe public content lifetimes, including the legacy soft-image copies.
        val mountedFrames = mutableMapOf<Any, String>()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            Box(Modifier.size(144.dp).background(Color.White).testTag("record-provenance")) {
                ArtworkBlend(visual, Modifier.fillMaxSize().drawWithContent {
                    val request = drawRequest.value
                    if (request.allowContent) { drawContent(); allowedDraws.incrementAndGet() }
                    request.observed.countDown()
                }, 1000, occurrence = visit) { frame ->
                    val owner = remember { Any() }
                    val key = requireNotNull(frame.contentKey)
                    DisposableEffect(owner, key) {
                        mountedFrames[owner] = key
                        onDispose { mountedFrames.remove(owner) }
                    }
                    Image(requireNotNull(frame.artwork), null, Modifier.fillMaxSize())
                }
            }
        } } }
        fun setDrawing(allowContent: Boolean) {
            val request = DrawRequest(allowContent)
            compose.runOnUiThread { drawRequest.value = request }
            assertTrue("The public draw gate must acknowledge its requested state", request.observed.await(5, TimeUnit.SECONDS))
        }
        fun mounted() = compose.runOnUiThread { mountedFrames.values.toSet() }
        fun awaitIncoming(key: String) {
            compose.waitUntil(5000) {
                // Native readback can complete between frames; stop at the first
                // composition exposing this incoming image, not after an arbitrary delay.
                compose.mainClock.advanceTimeByFrame()
                key in mounted()
            }
        }
        fun pixels(): androidx.compose.ui.graphics.PixelMap {
            val heldTime = compose.mainClock.currentTime
            setDrawing(true)
            val captured = compose.onNodeWithTag("record-provenance").captureToImage().toPixelMap()
            assertEquals("Capturing a drawn frame must not advance its animation", heldTime, compose.mainClock.currentTime)
            return captured
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { visual = blue; visit = "blue-visit" }
            awaitIncoming("blue")
            compose.mainClock.advanceTimeBy(500)
            val recorded = pixels()
            val middle = recorded[recorded.width / 2, recorded.height / 2]
            assertTrue("A real mixed transition must have drawn before its recording can become stale: $middle",
                middle.red > .1f && middle.blue > .1f && middle.green < .05f)
            assertEquals(setOf("red", "blue"), mounted())

            // Skip the blend's entire draw path while animation/composition settle.
            // The old nonzero recording is therefore not refreshed by late transition frames.
            setDrawing(false)
            val recordedDrawCount = allowedDraws.get()
            compose.mainClock.advanceTimeBy(1200)
            compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); mounted() == setOf("blue") }
            assertEquals("Settling behind the gate cannot record another composite", recordedDrawCount, allowedDraws.get())
            val settled = pixels()
            val settledCenter = settled[settled.width / 2, settled.height / 2]
            assertEquals("The later directly drawn cover is actually blue", Color.Blue, settledCenter)
            assertNotEquals("The last presented cover differs from the earlier recorded mixture", middle, settledCenter)

            setDrawing(false)
            val lastPresentedDrawCount = allowedDraws.get()
            compose.runOnUiThread { visual = green; visit = "green-undrawn-visit" }
            awaitIncoming("green")
            assertTrue("Green must be admitted while blue is still its outgoing composition", "blue" in mounted())
            assertEquals("Green has never entered the blend's draw/record path", lastPresentedDrawCount, allowedDraws.get())
            compose.runOnUiThread { visual = yellow; visit = "yellow-superseding-visit" }
            awaitIncoming("yellow")
            assertEquals("Supersession is admitted before any replacement composite records", lastPresentedDrawCount, allowedDraws.get())

            // This is the first presented animation-clock frame after yellow's
            // admission. Repeated native draws during capture remain at that same time.
            val firstSuperseding = pixels()
            assertTrue("The new admission must really reach drawing", allowedDraws.get() > lastPresentedDrawCount)
            for (y in settled.height / 5 until settled.height * 4 / 5 step 5)
                for (x in settled.width / 5 until settled.width * 4 / 5 step 5)
                    assertEquals("First superseding pixels retain the last drawn cover, not an old recording at ($x,$y)",
                        settled[x, y], firstSuperseding[x, y])
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun coldBlendAndFrameUseOnlyTheSharedGradientThenRetainReadyPixelsDuringReplacement() {
        val shimmer = mutableStateOf(.2f)
        var visual by mutableStateOf(PlayerVisual(loading = true))
        var visit by mutableStateOf("cold")
        var emptyFramesAreLoading = true
        // Both subjects stay mounted. Each request is acknowledged only after the
        // selected subtree draws, including repeat captures of the same subject.
        data class DrawRequest(val reference: Boolean, val drawn: CountDownLatch = CountDownLatch(1))
        val drawRequest = mutableStateOf(DrawRequest(reference = false))
        val shape = RoundedCornerShape(16.dp)
        compose.setContent { LocalicaTheme {
            MaterialTheme(colorScheme = darkColorScheme(surfaceContainer = Color(0xFF223344), onSurface = Color.White)) {
                CompositionLocalProvider(LocalReducedMotion provides false, LocalLoadingShimmer provides shimmer,
                    LocalLoadingShimmerActivity provides null) {
                    Box(Modifier.size(144.dp).background(Color.White).testTag("cold-blend")) {
                        ArtworkBlend(visual, Modifier.matchParentSize().testTag("gradient-actual").drawWithContent {
                            val request = drawRequest.value
                            if (!request.reference) { drawContent(); request.drawn.countDown() }
                        }, 600, occurrence = visit) { frame ->
                            SideEffect { if (frame.artwork == null) emptyFramesAreLoading = emptyFramesAreLoading && frame.loading }
                            PlayerArtworkFrame(frame, Modifier.fillMaxSize(), shape, ContentScale.Crop)
                        }
                        Box(Modifier.matchParentSize().testTag("gradient-reference").clip(shape).drawWithContent {
                            val request = drawRequest.value
                            if (request.reference) { drawContent(); request.drawn.countDown() }
                        }) {
                            ArtworkPlaceholder(true, Modifier.fillMaxSize(), showIcon = false)
                        }
                    }
                }
            }
        } }
        fun selectDraw(reference: Boolean) {
            val request = DrawRequest(reference)
            compose.runOnUiThread { drawRequest.value = request }
            // Wait on the test thread for a real draw, without pumping the Compose clock.
            assertTrue("The selected gradient subject must draw before capture", request.drawn.await(5, TimeUnit.SECONDS))
        }
        fun pixels(reference: Boolean = false): androidx.compose.ui.graphics.PixelMap {
            selectDraw(reference)
            val host = compose.onNodeWithTag("cold-blend")
            val bounds = host.fetchSemanticsNode().boundsInRoot
            assertEquals("Actual pixels share the host's screen origin and size", bounds,
                compose.onNodeWithTag("gradient-actual").fetchSemanticsNode().boundsInRoot)
            assertEquals("Reference pixels share the host's screen origin and size", bounds,
                compose.onNodeWithTag("gradient-reference").fetchSemanticsNode().boundsInRoot)
            return host.captureToImage().toPixelMap()
        }
        fun assertGradient() {
            compose.onNodeWithContentDescription("No artwork").assertDoesNotExist()
            val actual = pixels()
            val expected = try { pixels(reference = true) } finally { selectDraw(reference = false) }
            for (y in actual.height / 5 until actual.height * 4 / 5 step 3)
                for (x in actual.width / 5 until actual.width * 4 / 5 step 3) {
                    assertEquals("Cold blend/frame pixels match the shared icon-free gradient", expected[x, y], actual[x, y])
                    assertEquals(1f, actual[x, y].alpha, .001f)
                }
        }
        assertGradient()
        val first = pixels()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { shimmer.value = .8f }
            compose.mainClock.advanceTimeByFrame()
            assertGradient()
            val swept = pixels()
            assertTrue("Cold loading keeps the shared sweep, not a static fill",
                (first.height / 5 until first.height * 4 / 5 step 3).any { y ->
                    (first.width / 5 until first.width * 4 / 5 step 3).any { x -> first[x, y] != swept[x, y] }
                })
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 70, 120)) }
            val ready = PlayerVisual(bitmap.asImageBitmap(), contentKey = "ready")
            compose.runOnIdle { visual = ready }
            for (delta in listOf(16L, 64L, 120L, 100L, 200L, 200L)) {
                compose.mainClock.advanceTimeBy(delta)
                compose.onNodeWithContentDescription("No artwork").assertDoesNotExist()
                compose.runOnIdle { assertTrue("Even a semantics-hidden outgoing frame stays gradient-only", emptyFramesAreLoading) }
                val frame = pixels()
                for (y in frame.height / 5 until frame.height * 4 / 5 step 5)
                    for (x in frame.width / 5 until frame.width * 4 / 5 step 5) {
                        val color = frame[x, y]
                        assertEquals("Cold-to-ready reveal stays opaque", 1f, color.alpha, .001f)
                        assertTrue("Cold-to-ready reveal cannot expose the white backing (delta=$delta, pixel=($x,$y), color=$color)", color.red < .8f && color.green < .8f && color.blue < .8f)
                    }
            }
            val settled = pixels()
            compose.runOnIdle { visit = "replacement"; visual = ready.copy(loading = true) }
            compose.mainClock.advanceTimeBy(900)
            val waiting = pixels()
            for (y in settled.height / 5 until settled.height * 4 / 5 step 5)
                for (x in settled.width / 5 until settled.width * 4 / 5 step 5)
                    assertEquals("Replacement loading retains the complete ready frame", settled[x, y], waiting[x, y])
            compose.onAllNodesWithTag("artwork-loading").assertCountEquals(1) // Only the separate reference.
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun defaultCacheIsReadyInTheFirstCompositionAndOnEachWarmRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = listOf(artworkFile(android.graphics.Color.RED), artworkFile(android.graphics.Color.BLUE))
        var mounted by mutableStateOf(true)
        var selected by mutableStateOf(0)
        val first = linkedMapOf<Int, PlayerVisual>()
        try {
            ArtworkCache.invalidate()
            val ready = files.map { file -> runBlocking { PlayerVisualCache.read(context, file.toURI().toString()) } }
            compose.setContent { LocalicaTheme { if (mounted) {
                val index = selected
                val visual = rememberPlayerVisual(files[index].toURI().toString())
                SideEffect { first.putIfAbsent(index, visual) }
                PlayerArtwork(visual, Modifier.size(96.dp), RoundedCornerShape(12.dp), ContentScale.Crop, 600)
            } } }
            compose.runOnIdle {
                assertSame(ready[0].artwork, first.getValue(0).artwork)
                assertSame(ready[0].palette, first.getValue(0).palette)
                assertFalse("A prepared first composition is not loading", first.getValue(0).loading)
                selected = 1
            }
            compose.runOnIdle {
                assertSame(ready[1].artwork, first.getValue(1).artwork)
                assertSame(ready[1].palette, first.getValue(1).palette)
                assertFalse("Changing to another prepared request is immediately ready", first.getValue(1).loading)
            }
        } finally {
            compose.runOnIdle { mounted = false }
            files.forEach { it.delete() }; ArtworkCache.invalidate()
        }
    }

    @Test fun customProvidersCannotBorrowTheDefaultCacheOrPublishACancelledGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = artworkFile(android.graphics.Color.GREEN)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val obsoleteJob = AtomicReference<Job>()
        val slow = CoverProvider { _, _, _ ->
            obsoleteJob.set(currentCoroutineContext().job)
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        }
        val replacement = CoverProvider { _, _, _ ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        }
        var mounted by mutableStateOf(true)
        var provider by mutableStateOf<CoverProvider>(EmbeddedCoverProvider)
        var generation by mutableStateOf("default")
        val first = linkedMapOf<String, PlayerVisual>()
        val latest = AtomicReference<PlayerVisual>()
        try {
            ArtworkCache.invalidate()
            val uri = file.toURI().toString()
            val cached = runBlocking { PlayerVisualCache.read(context, uri) }
            compose.setContent { LocalicaTheme { if (mounted) {
                val owner = generation
                val visual = rememberPlayerVisual(uri, provider)
                SideEffect { first.putIfAbsent(owner, visual); latest.set(visual) }
            } } }
            compose.runOnIdle {
                assertSame(cached.artwork, first.getValue("default").artwork)
                generation = "slow"; provider = slow
            }
            compose.waitUntil(5000) { entered.isCompleted }
            compose.runOnIdle {
                assertNull("A custom provider cannot inherit default-provider pixels", first.getValue("slow").artwork)
                assertTrue(first.getValue("slow").loading)
                generation = "replacement"; provider = replacement
            }
            compose.waitUntil(5000) { latest.get()?.let { !it.loading && it.artwork?.asAndroidBitmap()?.getPixel(0, 0) == android.graphics.Color.BLUE } == true }
            compose.runOnIdle {
                assertNull(first.getValue("replacement").artwork)
                assertTrue(first.getValue("replacement").loading)
                release.complete(Unit)
            }
            compose.waitUntil(5000) { obsoleteJob.get()?.isCompleted == true }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Cancelled provider completion cannot replace the current generation", android.graphics.Color.BLUE,
                    latest.get().artwork!!.asAndroidBitmap().getPixel(0, 0))
                assertSame("Custom requests do not overwrite the default cache", cached, PlayerVisualCache.peek(uri))
            }
        } finally {
            release.complete(Unit)
            compose.runOnIdle { mounted = false }
            file.delete(); ArtworkCache.invalidate()
        }
    }

    private fun artworkFile(color: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "playlist-covers").apply { mkdirs() }
        return File.createTempFile("artwork-readiness-", ".png", directory).also { file ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun sameDecodedCoverStillBlursForANewVisitAndLoadingDoesNotStartItEarly() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128)
            bitmap.setPixel(x, y, if ((x / 16 + y / 16) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val image = bitmap.asImageBitmap()
        val soft = runBlocking { blurGlassSnapshot(image, 6f) }
        val original = PlayerVisual(image, softArtwork = soft, contentKey = "shared-pixels")
        var visual by mutableStateOf(original)
        var visit by mutableStateOf("first")
        compose.setContent { LocalicaTheme {
            ArtworkBlend(visual, Modifier.size(192.dp).testTag("blend"), 600, occurrence = visit) { frame ->
                Image(requireNotNull(frame.artwork), null, Modifier.fillMaxSize())
            }
        } }
        fun pixels() = compose.onNodeWithTag("blend").captureToImage().toPixelMap()
        val before = pixels()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visit = "second"; visual = original.copy(loading = true) }
        compose.mainClock.advanceTimeBy(300)
        val waiting = pixels()
        for (y in 2 until before.height - 2 step 7) for (x in 2 until before.width - 2 step 7)
            assertEquals("Loading retains every sampled outgoing pixel", before[x, y], waiting[x, y])
        compose.runOnIdle { visual = original }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300)
        val middle = pixels()
        var changed = 0
        var sum = 0f
        for (y in 2 until middle.height - 2 step 3) for (x in 2 until middle.width - 2 step 3) {
            val value = middle[x, y]
            assertEquals("An opaque transition never exposes backing", 1f, value.alpha, .001f)
            if (kotlin.math.abs(value.red - before[x, y].red) > .08f) changed++
            sum += value.red
        }
        assertTrue("Identical bitmap visits must visibly blur, not snap", changed > 50)
        val samples = ((2 until middle.height - 2 step 3).count() * (2 until middle.width - 2 step 3).count())
        assertTrue("Transition cannot flash white", sum / samples < .65f)
        compose.mainClock.advanceTimeBy(700)
        val settled = pixels()
        for (y in 2 until before.height - 2 step 7) for (x in 2 until before.width - 2 step 7)
            assertEquals("The shared sharp pixels return", before[x, y], settled[x, y])
        compose.mainClock.autoAdvance = true
    }
}
