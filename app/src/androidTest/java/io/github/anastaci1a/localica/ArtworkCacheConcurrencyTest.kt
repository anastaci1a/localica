package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkCacheStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ArtworkCacheConcurrencyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test fun warmBitmapAndKnownMissingCoverBypassHeldUnrelatedDecode() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val warm = bitmap()
        val loads = mutableListOf<String>()
        val store = ArtworkCacheStore { _, uri, _ ->
            loads += uri
            if (uri == "cold") { started.complete(Unit); release.await() }
            if (uri == "missing") null else warm
        }
        assertSame(warm, store.read(context, "warm", 128))
        assertNull(store.read(context, "missing", 128))
        val cold = async { store.read(context, "cold", 128) }
        try {
            withTimeout(5000) { started.await() }
            withTimeout(5000) {
                assertSame(warm, store.read(context, "warm", 100))
                assertNull(store.read(context, "missing", 100))
            }
            assertFalse(release.isCompleted)
            assertFalse(cold.isCompleted)
            assertEquals(listOf("warm", "missing", "cold"), loads)
        } finally { release.complete(Unit); cold.cancel(); withTimeout(5000) { cold.join() } }
    }

    @Test fun coldRequestsBeyondTheDecodeLimitQueueAndQueuedCancellationNeverLoads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = mutableListOf<String>()
        val store = ArtworkCacheStore(parallelism = 1) { _, uri, _ ->
            loads += uri
            if (uri == "first") { started.complete(Unit); release.await() }
            null
        }
        val first = async { store.read(context, "first", 128) }
        var queued: Deferred<Bitmap?>? = null
        try {
            withTimeout(5000) { started.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { store.read(context, "queued", 128) }
            queued = waiting
            assertEquals(listOf("first"), loads)
            waiting.cancel(); withTimeout(5000) { waiting.join() }
            release.complete(Unit)
            withTimeout(5000) { first.await() }
            assertEquals(listOf("first"), loads)
            assertNull(store.peek(listOf("queued")))
            assertNull(store.read(context, "next", 128))
            assertEquals(listOf("first", "next"), loads)
        } finally {
            release.complete(Unit); first.cancel(); queued?.cancel()
            withTimeout(5000) { first.join(); queued?.join() }
        }
    }

    @Test fun invalidatedInFlightDecodeCannotPopulateTheNewRevision() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = bitmap(); val fresh = bitmap()
        var calls = 0
        val store = ArtworkCacheStore { _, _, _ ->
            calls++
            if (calls == 1) { started.complete(Unit); release.await(); old } else fresh
        }
        val original = async { store.read(context, "cover", 128) }
        try {
            withTimeout(5000) { started.await() }
            val revision = store.revision.value
            store.invalidate()
            assertEquals(revision + 1, store.revision.value)
            release.complete(Unit)
            assertSame(old, withTimeout(5000) { original.await() }) // Existing caller contract is unchanged.
            assertNull(store.peek(listOf("cover")))
            assertSame(fresh, store.read(context, "cover", 128))
            assertSame(fresh, store.peek(listOf("cover"))?.bitmap)
            assertEquals(2, calls)
        } finally { release.complete(Unit); original.cancel(); withTimeout(5000) { original.join() } }
    }

    @Test fun waitingDuplicateRechecksTheCacheAndLargerBucketsRemainDistinct() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val picture = bitmap()
        val sizes = mutableListOf<Int>()
        val store = ArtworkCacheStore { _, _, edge ->
            sizes += edge
            if (sizes.size == 1) { started.complete(Unit); release.await() }
            picture
        }
        val first = async { store.read(context, "cover", 128) }
        var duplicate: Deferred<Bitmap?>? = null
        try {
            withTimeout(5000) { started.await() }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { store.read(context, "cover", 100) }
            duplicate = waiting
            assertEquals(listOf(128), sizes)
            release.complete(Unit)
            assertSame(picture, withTimeout(5000) { first.await() })
            assertSame(picture, withTimeout(5000) { waiting.await() })
            assertEquals(listOf(128), sizes)
            assertSame(picture, store.read(context, "cover", 768))
            assertEquals(listOf(128, 768), sizes)
        } finally {
            release.complete(Unit); first.cancel(); duplicate?.cancel()
            withTimeout(5000) { first.join(); duplicate?.join() }
        }
    }

    @Test fun canceledCallerCannotReturnAWarmHit() = runBlocking {
        val picture = bitmap()
        var calls = 0
        val store = ArtworkCacheStore { _, _, _ -> calls++; picture }
        assertSame(picture, store.read(context, "cover", 128))
        var rejected = false
        val canceled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { store.read(context, "cover", 128); fail("Cancelled read returned a warm hit") }
            catch (_: CancellationException) { rejected = true }
        }
        withTimeout(5000) { canceled.join() }
        assertTrue(rejected)
        assertEquals(1, calls)
    }
}
