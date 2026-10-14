package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseArtworkTest {
    @get:Rule val compose = createComposeRule()
    private fun bitmap(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun automaticTransitDefersUncachedDecodeAndCancellationResumesIt() {
        val transit = ArtworkTransit()
        val journey = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var decoded = 0
        var job: kotlinx.coroutines.Job? = null
        var show by mutableStateOf(false)
        val provider = CoverProvider { _, _, _ -> decoded++; bitmap(android.graphics.Color.RED) }
        compose.setContent { LocalicaTheme {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                job = scope.launch { transit.during(true) { started.complete(Unit); journey.await() } }
            }
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                if (show) MediaArtwork(listOf("deferred"), Modifier.size(100.dp).testTag("deferred-cover"), provider)
            }
        } }
        compose.waitUntil(5000) { started.isCompleted }
        compose.runOnIdle { show = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, decoded); job!!.cancel() }
        compose.waitUntil(5000) { decoded == 1 }
        compose.waitForIdle()
        val image = compose.onNodeWithTag("deferred-cover").captureToImage().toPixelMap()
        assertTrue("Interrupted journeys resume the final viewport's artwork", image[image.width / 2, image.height / 2].red > .9f)
        compose.runOnIdle { assertFalse(transit.active) }
    }

    @Test fun visibleCoversSurviveTwoBackDestinationsButReleaseScrolledAndInvalidatedImages() {
        val store = BrowseArtworkStore()
        val cover = ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.RED), 128)
        val uri = listOf("retained")
        store.keep(setOf("home", "albums"), "albums")
        val albums = requireNotNull(store.scope("albums"))
        val offscreen = Any()
        albums.hold(offscreen, listOf("offscreen"), cover)
        albums.release(offscreen)
        assertNull(albums.peek(listOf("offscreen")))
        val token = Any()
        albums.hold(token, uri, cover)
        store.keep(setOf("home", "albums", "detail"), "detail")
        albums.release(token)
        store.keep(setOf("home", "albums", "detail", "nested"), "nested")
        assertSame(cover, store.scope("albums")?.peek(uri))
        store.keep(setOf("home", "albums", "detail"), "detail")
        assertNull(store.scope("nested"))
        assertSame(cover, store.scope("albums")?.peek(uri))
        store.seed("detail", cover)
        ArtworkCache.invalidate()
        assertNull(albums.peek(uri))
        assertNull(store.scope("detail")?.preview())
    }

    @Test fun detailShowsTappedThumbnailWhileLargerDecodeWaitsThenCrossfades() {
        val thumbnail = bitmap(android.graphics.Color.RED)
        val full = bitmap(android.graphics.Color.GREEN)
        val ready = CompletableDeferred<Bitmap?>()
        val retention = BrowseArtworkScope().apply { seed(ArtworkCache.CachedArtwork(thumbnail, 128)) }
        val provider = CoverProvider { _, _, _ -> ready.await() }
        var prepared = false
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalBrowseArtwork provides retention) {
            val visual = rememberCollectionVisual("test:delayed-detail", provider)
            SideEffect {
                prepared = visual.contentKey != null
                if (!prepared) assertTrue("Thumbnail also seeds the initial gradient", visual.palette.bottom.red > visual.palette.bottom.blue + .3f)
            }
            ArtworkBlend(visual, Modifier.size(120.dp).testTag("detail"), 240, opacityBlend = true) { frame ->
                ArtworkImage(frame.artwork, false, Modifier.fillMaxSize())
            }
        } } }
        fun color() = compose.onNodeWithTag("detail").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        assertTrue("Cached thumbnail must precede the large decode", color().red > .9f)
        compose.mainClock.autoAdvance = false
        ready.complete(full)
        compose.waitUntil(10000) { compose.mainClock.advanceTimeByFrame(); prepared }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(128)
        val middle = color()
        assertTrue("Replacement blends both images: $middle", middle.red > .05f && middle.green > .05f)
        compose.mainClock.advanceTimeBy(400)
        assertTrue("Larger image finishes the fade", color().green > .9f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun decodedResolutionUpgradeFadesInsteadOfSwitchingInOneFrame() {
        var image by mutableStateOf(bitmap(android.graphics.Color.RED).asImageBitmap())
        compose.setContent { LocalicaTheme { ArtworkImage(image, false, Modifier.size(120.dp).testTag("cover")) } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { image = bitmap(android.graphics.Color.GREEN).asImageBitmap() }
        compose.mainClock.advanceTimeBy(100)
        val pixels = compose.onNodeWithTag("cover").captureToImage().toPixelMap()
        val middle = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Both resolutions remain visible mid-fade: $middle", middle.red > .1f && middle.green > .1f)
        compose.mainClock.advanceTimeBy(300)
        val done = compose.onNodeWithTag("cover").captureToImage().toPixelMap()
        assertTrue(done[done.width / 2, done.height / 2].green > .9f)
        compose.mainClock.autoAdvance = true
    }
}
