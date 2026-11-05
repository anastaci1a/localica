package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueueShadowContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun queueSearchAtRestHasNoTruncatedShadowBand() = inspect(false)
    @Test fun queueSearchWithScrolledArtworkHasNoTruncatedShadowBand() = inspect(true)

    private fun inspect(scrolled: Boolean) {
        val tracks = (1..30).map { Track("shadow:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val artwork = BrowseArtworkScope()
        val cover = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        tracks.forEach { artwork.hold(it.uri, listOf(it.uri), ArtworkCache.CachedArtwork(cover, 256)) }
        val queue = QueueEdits.create(tracks, 0, "Shadow queue")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        var strength by mutableFloatStateOf(1f)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = strength, shadowSpread = 1f)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).testTag("queue-shadow-scene")) {
                    val backing = rememberFrostedBackdrop(Unit, dark = false)
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) { drawRect(Color.White) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides backing, LocalBrowseArtwork provides artwork) {
                    PlayerClippedOverlay({ 1f }, 80.dp) {
                    QueueSurface(remember { Animatable(1f) }, InterfaceSettings(), {}) {
                        Box(Modifier.weight(1f)) {
                            QueueScreen(state, null, library, onMinimize = {}, reducedMotion = true)
                        }
                    }
                    }
                    }
                }
            }
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("list-search-pill").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        if (scrolled) {
            compose.onNodeWithTag("browser-list").performScrollToIndex(5)
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
        }
        val scene = compose.onNodeWithTag("queue-shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val target = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val d = compose.density.density
        val bodyBottom = target.bottom - 4*d
        val bodyTop = target.top + 4*d
        val shadow = scene.captureToImage()
        compose.runOnIdle { strength = 0f }
        val baseline = scene.captureToImage()
        val output = nativeEvidenceDirectory("shadow-continuity",
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        listOf("queue-shadow" to shadow, "queue-baseline" to baseline).forEach { (name, image) ->
            java.io.File(output, "$name${if (scrolled) "-scrolled" else ""}.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val a = shadow.toPixelMap(); val b = baseline.toPixelMap()
        val x = target.center.x.toInt()
        val rows = (0..58).map { dp ->
            val y = (bodyBottom + dp*d).toInt().coerceIn(0, a.height-1)
            b[x,y].red - a[x,y].red
        }
        val topRows = (1..58).map { dp ->
            val y = (bodyTop - dp*d).toInt().coerceIn(0, a.height-1)
            b[x,y].red - a[x,y].red
        }
        val sideRows = (1 until target.left.toInt()).map { x ->
            val y = target.center.y.toInt()
            b[x,y].red - a[x,y].red
        }
        val rightRows = (target.right.toInt()+1 until a.width-1).map { x ->
            val y = target.center.y.toInt()
            b[x,y].red - a[x,y].red
        }
        assertTrue("Right shadow has no internal crop: $rightRows", rightRows.zipWithNext { p,q -> kotlin.math.abs(p-q) }.max() < .06f)
        java.io.File(output, "queue-profile${if (scrolled) "-scrolled" else ""}.txt").writeText("target=$target density=$d\nbottom=$rows\ntop=$topRows\nside=$sideRows\n")
        assertTrue("Top shadow extends above the viewport: $topRows", topRows.slice(5..9).max() > .015f)
        assertTrue("Top shadow has no internal crop: $topRows", topRows.zipWithNext { p,q -> kotlin.math.abs(p-q) }.drop(3).max() < .04f)
        assertTrue("Side shadow has no internal crop: $sideRows", sideRows.zipWithNext { p,q -> kotlin.math.abs(p-q) }.max() < .06f)
        assertTrue("Search casts below its layout slot: $rows", rows.slice(6..10).max() > .015f)
        val jump = rows.zipWithNext { p, q -> kotlin.math.abs(p-q) }.drop(4).max()
        assertTrue("Search shadow must taper instead of ending at a container edge: $rows", jump < .04f)
        ShadowFrameProbe.assertSpreadEdge(shadow,
            Rect(target.center.x-8*d, bodyBottom+30*d, target.center.x+8*d, bodyBottom+32*d),
            Rect(target.center.x-8*d, bodyBottom+32*d, target.center.x+8*d, bodyBottom+34*d), .02f, "Queue search")
    }
}
