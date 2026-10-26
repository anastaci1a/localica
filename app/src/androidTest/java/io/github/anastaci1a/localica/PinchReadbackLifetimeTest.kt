package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.PinchPublicationExpired
import io.github.anastaci1a.localica.ui.PinchReadback
import io.github.anastaci1a.localica.ui.BrowsePinchMotion
import io.github.anastaci1a.localica.ui.retainPinchItem
import io.github.anastaci1a.localica.ui.retainPinchText
import io.github.anastaci1a.localica.ui.retainPinchArtwork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** App-owned atlas/parent leases, never manual release of a composition-owned source. */
class PinchReadbackLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private class Fixture {
        lateinit var context: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
    }
    private fun fixture(): Fixture {
        val f = Fixture()
        compose.setContent {
            val context = LocalGraphicsContext.current
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val scope = rememberCoroutineScope()
            SideEffect { f.context = context; f.density = density; f.direction = direction; f.scope = scope }
            Box(Modifier.size(1.dp))
        }
        compose.waitForIdle()
        return f
    }

    @Test fun defaultCopyPreservesAllPremultipliedTranslucentPixelsAndReleasesItsOwnedAtlas() {
        val f = fixture()
        val reader = PinchReadback()
        var source: GraphicsLayer? = null
        val done = CompletableDeferred<ImageBitmap>()
        compose.runOnIdle { f.scope.launch {
            try {
                done.complete(reader.capture(f.context, f.density, f.direction) { atlas ->
                    source = atlas
                    atlas.record(f.density, f.direction, IntSize(64, 64)) { drawRect(Color.Red.copy(alpha = .5f)) }
                })
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        } }
        compose.waitUntil(5000) { done.isCompleted }
        val image = runBlocking { done.await() }
        val pixels = image.toPixelMap()
        assertEquals(64, pixels.width); assertEquals(64, pixels.height)
        for (y in 0 until 64) for (x in 0 until 64) {
            val pixel = pixels[x, y]
            assertEquals("Straight red survives premultiplied transport at $x,$y", 1f, pixel.red, 1f / 255f)
            assertEquals(0f, pixel.green, 1f / 255f); assertEquals(0f, pixel.blue, 1f / 255f)
            assertEquals(.5f, pixel.alpha, 1f / 255f)
        }
        compose.runOnIdle { assertTrue(requireNotNull(source).isReleased); assertFalse(reader.occupied) }
    }

    @Test fun cancelledCallerCannotReleasePendingAtlasOrPublishAndASecondReadIsRejected() = heldRead(true)
    @Test fun expiredPublicationKeepsPendingLeasesAndRemainsDistinctFromCancellation() = heldRead(false)

    @Test fun expiredTransactionCannotRerecordPendingSourcePasses() {
        val motion = BrowsePinchMotion()
        var color by mutableStateOf(Color.Green)
        val records = IntArray(3)
        val recordedColors = arrayOfNulls<Color>(3)
        var traversals = 0
        compose.setContent {
            Box(Modifier.size(64.dp).drawWithContent {
                color // Real content invalidation, without an extra draw request.
                drawContent()
                traversals++
            }) {
                for (pass in 0..2) {
                    val retained = when (pass) {
                        0 -> Modifier.retainPinchItem("pending-item", motion)
                        1 -> Modifier.retainPinchText("pending-text", motion)
                        else -> Modifier.retainPinchArtwork("pending-art", motion)
                    }
                    Box(retained.size(32.dp).drawWithContent {
                        records[pass]++; recordedColors[pass] = color
                        drawRect(color)
                    })
                }
            }
        }
        compose.waitUntil(5000) { records.all { it > 0 } }
        val before = records.copyOf()
        val beforeTraversal = traversals
        compose.runOnIdle {
            motion.capturing = true; motion.readbackPending = true
            motion.clearTransaction() // The actual publication-expiry retirement path.
            color = Color.Red
        }
        compose.waitUntil(5000) { traversals > beforeTraversal }
        compose.runOnIdle {
            assertArrayEquals("Retired publication cannot mutate leased source recordings", before, records)
            assertTrue(recordedColors.all { it == Color.Green })
            assertTrue(motion.readbackPending)
            motion.finishReadback()
        }
        compose.waitUntil(5000) { records.indices.all { records[it] > before[it] } }
        compose.runOnIdle { assertTrue(recordedColors.all { it == Color.Red }) }
    }

    @Test fun elapsedDeadlineRejectsACompletedCopyBeforeItsHandlerCanRun() {
        val f = fixture()
        var clock = 1000L
        var expired = 0
        var source: GraphicsLayer? = null
        var parent: GraphicsLayer? = null
        val reader = PinchReadback(now = { clock })
        val done = CompletableDeferred<Throwable?>()
        compose.runOnIdle { f.scope.launch {
            try {
                reader.capture(f.context, f.density, f.direction, onDeadline = { expired++ }, copy = {
                    parent = it
                    val image = it.toImageBitmap()
                    // No sleep or Main blocking: model elapsed native work in the same
                    // completion turn, before a delayed Handler callback can run.
                    clock += 4000
                    image
                }) {
                    source = it
                    it.record(f.density, f.direction, IntSize(64, 64)) { drawRect(Color.Blue) }
                }
                done.complete(null)
            } catch (failure: Throwable) { done.complete(failure) }
        } }
        compose.waitUntil(5000) { done.isCompleted }
        assertTrue(runBlocking { done.await() } is PinchPublicationExpired)
        compose.runOnIdle {
            assertEquals(1, expired)
            assertTrue(requireNotNull(source).isReleased); assertTrue(requireNotNull(parent).isReleased)
            assertFalse(reader.occupied)
        }
    }

    private fun heldRead(cancel: Boolean) {
        val f = fixture()
        val reader = PinchReadback()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expired = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Throwable?>()
        var atlas: GraphicsLayer? = null
        var parent: GraphicsLayer? = null
        var captured: ImageBitmap? = null
        var published = false
        lateinit var caller: Job
        compose.runOnIdle {
            caller = f.scope.launch(start = CoroutineStart.LAZY) {
                val originalCaller = currentCoroutineContext().job
                try {
                    reader.capture(f.context, f.density, f.direction,
                        timeoutMillis = if (cancel) 3000 else 1,
                        onDeadline = { expired.complete(Unit) }, copy = { retained ->
                            parent = retained
                            coroutineScope {
                                // This scope inherits the protected copy context, not the
                                // original caller Job that is cancelled below.
                                val native = async(start = CoroutineStart.UNDISPATCHED) { retained.toImageBitmap() }
                                if (Build.VERSION.SDK_INT < 28) assertFalse("API26 producer is actually submitted and pending", native.isCompleted)
                                else assertTrue("Pinned V28+ route completes synchronously before cancellation", native.isCompleted)
                                if (cancel) originalCaller.cancel()
                                entered.complete(Unit)
                                release.await()
                                native.await().also { captured = it }
                            }
                        }) { source ->
                        atlas = source
                        source.record(f.density, f.direction, IntSize(64, 64)) { drawRect(Color.Green) }
                    }
                    published = true
                    finished.complete(null)
                } catch (failure: Throwable) { finished.complete(failure) }
            }
            caller.start()
        }
        var primary: Throwable? = null
        try {
            compose.waitUntil(5000) { entered.isCompleted || finished.isCompleted }
            if (!entered.isCompleted) throw AssertionError("Native copy did not reach its held phase", runBlocking { finished.await() })
            if (!cancel) {
                compose.waitUntil(5000) { expired.isCompleted || finished.isCompleted }
                if (!expired.isCompleted) throw AssertionError("Native copy ended before deadline observation", runBlocking { finished.await() })
            }
            compose.runOnIdle {
                assertTrue(reader.occupied)
                assertFalse("Caller/deadline cannot retire pending source", requireNotNull(atlas).isReleased)
                assertFalse("Caller/deadline cannot retire pending parent", requireNotNull(parent).isReleased)
                assertFalse(published); assertFalse(finished.isCompleted)
            }
            val rejected = CompletableDeferred<Throwable?>()
            var recordedAnotherSource = false
            compose.runOnIdle { f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    reader.capture(f.context, f.density, f.direction) { recordedAnotherSource = true }
                    rejected.complete(null)
                } catch (failure: Throwable) { rejected.complete(failure) }
            } }
            compose.waitUntil(5000) { rejected.isCompleted }
            assertTrue(runBlocking { rejected.await() } is IllegalStateException)
            assertFalse("A rejected read must neither allocate/record nor queue an obsolete atlas", recordedAnotherSource)
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil(5000) { finished.isCompleted && caller.isCompleted }
            val failure = runBlocking { finished.await() }
            if (cancel) assertTrue("External cancellation propagates as cancellation", failure is CancellationException)
            else assertTrue("Publication expiry is not external cancellation", failure is PinchPublicationExpired)
            assertFalse(published)
            val pixels = requireNotNull(captured).toPixelMap()
            assertEquals(64, pixels.width); assertEquals(64, pixels.height)
            for (y in 0 until 64) for (x in 0 until 64) {
                assertEquals(Color.Green, pixels[x, y])
            }
            compose.runOnIdle {
                assertTrue(requireNotNull(atlas).isReleased)
                assertTrue(requireNotNull(parent).isReleased)
                assertFalse(reader.occupied)
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            compose.runOnIdle { release.complete(Unit); caller.cancel() }
            try { compose.waitUntil(5000) { caller.isCompleted } }
            catch (cleanup: Throwable) {
                // Never force-close a native resource to make a failed test terminate.
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }
}
