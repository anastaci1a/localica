package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class BackdropInvalidationTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun rasterlessBackingRecordInvalidatesItsDependentRecording() {
        assumeTrue("CPU thumbnail scheduling is the pre-33 adapter", Build.VERSION.SDK_INT < 33)
        lateinit var backing: FrostedBackdrop
        lateinit var dependent: FrostedBackdrop
        var color by mutableStateOf(Color.Red)
        compose.setContent { LocalicaTheme {
            backing = rememberFrostedBackdrop(Unit, enabled = false, retainRaster = false)
            dependent = rememberFrostedBackdrop(Unit, enabled = false)
            Box(Modifier.size(96.dp)) {
                Canvas(Modifier.size(96.dp).recordFrostedBackdrop(backing)) { drawRect(color) }
                Canvas(Modifier.size(96.dp).recordFrostedBackdrop(dependent, backing)) {}
            }
        } }
        compose.waitForIdle()
        val beforeBacking = compose.runOnUiThread { backing.recordings }
        val beforeDependent = compose.runOnUiThread { dependent.recordings }
        compose.runOnUiThread { color = Color.Green }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertNull("Backing has no CPU snapshot to publish", backing.snapshot)
            assertTrue("The retained backing actually recorded its new pixels", backing.recordings > beforeBacking)
            assertTrue("A rasterless backing must notify its dependent CPU sampling boundary", dependent.recordings > beforeDependent)
        }
        val settled = compose.runOnUiThread { dependent.recordings }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnUiThread { assertEquals("Dependency observation must settle without redraw feedback", settled, dependent.recordings) }
    }

    @Test fun queuedFinalRecordingSurvivesASuspendedThumbnailCaptureAndThenSettles() {
        assumeTrue("CPU thumbnail worker is the pre-33 adapter", Build.VERSION.SDK_INT < 33)
        lateinit var source: FrostedBackdrop
        lateinit var graphics: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var layer: androidx.compose.ui.graphics.layer.GraphicsLayer
        lateinit var scope: CoroutineScope
        var enabled by mutableStateOf(false)
        var color by mutableStateOf(Color.Red)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var owner: Deferred<ImageBitmap?>? = null
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, enabled = enabled)
            BackdropLease(source)
            graphics = LocalGraphicsContext.current
            density = LocalDensity.current
            direction = LocalLayoutDirection.current
            layer = rememberGraphicsLayer()
            scope = rememberCoroutineScope()
            Canvas(Modifier.size(96.dp).recordFrostedBackdrop(source)) { drawRect(color) }
        } }
        compose.waitForIdle()
        try {
            compose.runOnUiThread {
                owner = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    source.captureCurrent(graphics, density, direction, { true }, layer,
                        record = { layer.record(density, direction, IntSize(1, 1)) { drawRect(Color.Red) } },
                        copy = { locked.complete(Unit); release.await(); ImageBitmap(1, 1) })
                }
                assertTrue("Test owns the real capture mutex before enabling the worker", locked.isCompleted)
                enabled = true
            }
            compose.waitUntil(5000) { source.readbackRequest > 0 }
            // The ordinary thumbnail worker has selected an epoch and suspended on the capture mutex.
            val firstRequest = compose.runOnUiThread { source.readbackRequest }
            val firstRecord = compose.runOnUiThread { source.recordings }
            compose.runOnUiThread { color = Color.Green }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("A final recording is queued while the worker is suspended", source.recordings > firstRecord)
                assertEquals("The worker cannot finish while the test owns native capture", firstRequest, source.readbackRequest)
                release.complete(Unit)
            }
            compose.waitUntil(5000) { source.snapshot != null }
            // On main the suspended capture stamps the newer record complete and discards this queue item.
            compose.waitUntil(5000) { source.readbackRequest > firstRequest }
            compose.waitUntil(5000) { source.snapshotReadbackRequest == source.readbackRequest }
            compose.runOnUiThread {
                val bitmap = requireNotNull(source.snapshot).asAndroidBitmap()
                val pixel = bitmap.getPixel(bitmap.width / 4, bitmap.height / 4)
                assertTrue("The queued final capture publishes the new green source", android.graphics.Color.green(pixel) > 240 && android.graphics.Color.red(pixel) < 15)
            }
            val requests = compose.runOnUiThread { source.readbackRequest }
            val records = compose.runOnUiThread { source.recordings }
            repeat(12) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnUiThread {
                assertEquals("No capture retry/redraw loop after the final queued frame", requests, source.readbackRequest)
                assertEquals("No producer redraw loop", records, source.recordings)
                assertTrue("Independent mutex owner completed", owner?.isCompleted == true)
            }
        } finally {
            compose.runOnUiThread { release.complete(Unit) }
        }
    }
}
