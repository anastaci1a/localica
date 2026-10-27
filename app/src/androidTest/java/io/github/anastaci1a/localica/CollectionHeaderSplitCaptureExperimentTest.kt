package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** A test-only feasibility probe, not a production header or an optical regression.
 * Passing does not establish pinch placement, handoff or live sampling. The legacy
 * snapshot request and prepared lens identity are independent witnesses: the source
 * exposes no request-associated lens-preparation receipt. Nor can a coating/alpha
 * pixel alone prove which backdrop the nested GPU or mesh renderer consumed. */
class CollectionHeaderSplitCaptureExperimentTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        lateinit var context: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
        lateinit var header: GraphicsLayer
        lateinit var source: FrostedBackdrop
        var request by mutableIntStateOf(0)
        var exclude = false
        // Deliberately non-snapshot, synchronous draw-pass state. This tests whether
        // an already mounted nested surface actually honors a capture-only traversal.
        var inExcludedRecording = false
        @Volatile var recorded = -1
        @Volatile var sourceRecordedRequest = -1
        @Volatile var headerSourceRequest = -1
        var headerLens: ImageBitmap? = null
    }

    @Test fun recordingCanExcludeNestedGlassWithoutRemovingTheDisplayedControl() {
        val f = Fixture()
        compose.setContent { LocalicaTheme {
            val header = rememberGraphicsLayer()
            val source = rememberFrostedBackdrop(Unit)
            val context = LocalGraphicsContext.current
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val scope = rememberCoroutineScope()
            SideEffect { f.header = header; f.source = source; f.context = context
                f.density = density; f.direction = direction; f.scope = scope }
            Box(Modifier.size(192.dp, 128.dp).testTag("split-capture-fixture")) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source,
                    readbackRequest = { source.readbackRequest }, onRecorded = { f.sourceRecordedRequest = it })) {
                    val cell = 16.dp.toPx()
                    for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                        drawRect(if ((x * 3 + y * 2) % 5 < 2) Color(0xFF2A6275) else Color(0xFFE4B695),
                            Offset(x * cell, y * cell), Size(cell, cell))
                    }
                }
                Box(Modifier.matchParentSize().drawWithContent {
                    val request = f.request
                    if (f.recorded != request || header.size == IntSize.Zero) {
                        try {
                            f.inExcludedRecording = f.exclude
                            header.record { this@drawWithContent.drawContent() }
                        } finally { f.inExcludedRecording = false }
                        // Bind the test's header recording to the source state
                        // observed at that traversal, not a later readiness check.
                        f.headerSourceRequest = source.snapshotReadbackRequest
                        f.headerLens = source.lensSnapshot
                        f.recorded = request
                    }
                    // Do not display the capture: the ordinary live control must
                    // remain on screen while the retained source is read back.
                    drawContent()
                }) {
                    Box(Modifier.offset(x = 8.dp, y = 8.dp).size(24.dp).background(Color.Yellow))
                    Box(Modifier.offset(x = 64.dp, y = 48.dp).size(64.dp).drawWithContent {
                        if (!f.inExcludedRecording) drawContent()
                    }) {
                        LiquidGlassSurface(Modifier.fillMaxSize(), source, pressFeedback = false) {
                            Box(Modifier.align(androidx.compose.ui.Alignment.Center).size(12.dp).background(Color.Magenta))
                        }
                    }
                }
            }
        } }
        compose.waitUntil(10000) { f.recorded == 0 }
        compose.waitUntil(10000) { f.source.recorded }
        if (android.os.Build.VERSION.SDK_INT < 33) {
            compose.waitUntil(10000) { f.source.lensSnapshot != null }
            var previousRequest = -1
            var previousLens: ImageBitmap? = null
            compose.runOnIdle {
                previousRequest = f.source.snapshotReadbackRequest
                previousLens = f.source.lensSnapshot
                // A new scene revision schedules a fresh native source readback.
                f.source.refresh()
            }
            compose.waitUntil(10000) {
                var fresh = false
                compose.runOnUiThread {
                    val published = f.source.snapshotReadbackRequest
                    fresh = published > previousRequest && published == f.source.readbackRequest &&
                        published == f.sourceRecordedRequest && f.source.snapshot != null &&
                        f.source.lensSnapshot != null && f.source.lensSnapshot !== previousLens
                }
                fresh
            }
        }
        // The first header recording may predate the legacy source's prepared
        // lens. Force a new recording only after the above readiness witness.
        var readyRequest = -1
        var readyLens: ImageBitmap? = null
        compose.runOnIdle {
            readyRequest = f.source.snapshotReadbackRequest
            readyLens = f.source.lensSnapshot
            f.request++
        }
        compose.waitUntil(10000) { f.recorded == 1 }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.runOnIdle {
            assertEquals("The header recording observed the witnessed published request", readyRequest, f.headerSourceRequest)
            assertSame("The header recording observed the witnessed lens identity", readyLens, f.headerLens)
        }
        val baseline = captureAtlas(f)
        assertCapturePixels(f, baseline, controlPresent = true)
        assertDisplayedMarker(f)

        compose.runOnIdle { f.exclude = true; f.request++ }
        compose.waitUntil(10000) { f.recorded == 2 }
        val excluded = captureAtlas(f)
        assertCapturePixels(f, excluded, controlPresent = false)
        assertDisplayedMarker(f)
    }

    /** Copy the header through the actual production atlas owner and copy path. */
    private fun captureAtlas(f: Fixture): ImageBitmap {
        val done = CompletableDeferred<ImageBitmap>()
        compose.runOnIdle { f.scope.launch {
            try {
                done.complete(PinchReadback().capture(f.context, f.density, f.direction) { atlas ->
                    atlas.record(f.density, f.direction, f.header.size) { drawLayer(f.header) }
                })
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        } }
        compose.waitUntil(10000) { done.isCompleted }
        return runBlocking { done.await() }
    }

    private fun assertCapturePixels(f: Fixture, image: ImageBitmap, controlPresent: Boolean) {
        val pixels = image.toPixelMap()
        fun at(x: Int, y: Int) = pixels[(x * f.density.density).roundToInt(), (y * f.density.density).roundToInt()]
        val title = at(16, 16)
        assertTrue("Unrelated header content survives recording", title.alpha > .95f && title.red > .9f && title.green > .9f && title.blue < .1f)
        val marker = at(96, 80)
        if (controlPresent) {
            assertTrue("Control capture contains its distinctive foreground",
                marker.alpha > .95f && marker.red > .9f && marker.green < .1f && marker.blue > .9f)
            val d = f.density.density
            var glassPixels = 0
            for (y in (48 * d).roundToInt() until (112 * d).roundToInt())
                for (x in (64 * d).roundToInt() until (128 * d).roundToInt()) {
                    val inMarker = x in (89 * d).roundToInt()..(103 * d).roundToInt() &&
                        y in (73 * d).roundToInt()..(87 * d).roundToInt()
                    if (!inMarker && pixels[x, y].alpha > .02f) glassPixels++
                }
            // Positive alpha confirms non-marker drawing, not refraction, a
            // specific sampled source, or a positive shadow-rendering receipt.
            assertTrue("The control region has non-marker alpha pixels", glassPixels > 25)
        } else {
            assertTrue("Capture-only exclusion removes the control marker", marker.alpha < .02f)
            // The entire remaining header must be transparent, including any
            // surface shadow spill: the only unrelated drawing is the title.
            val left = (7 * f.density.density).roundToInt()
            val top = (7 * f.density.density).roundToInt()
            val right = (33 * f.density.density).roundToInt()
            val bottom = (33 * f.density.density).roundToInt()
            var unexpected = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if ((x !in left..right || y !in top..bottom) && pixels[x, y].alpha >= .02f) unexpected++
            }
            assertEquals("No glass, foreground or shadow remains in the retained header", 0, unexpected)
        }
    }

    private fun assertDisplayedMarker(f: Fixture) {
        val pixels = compose.onNodeWithTag("split-capture-fixture").captureToImage().toPixelMap()
        val marker = pixels[(96 * f.density.density).roundToInt(), (80 * f.density.density).roundToInt()]
        assertTrue("The visible header retains its live control during capture", marker.red > .9f && marker.green < .1f && marker.blue > .9f)
    }
}
