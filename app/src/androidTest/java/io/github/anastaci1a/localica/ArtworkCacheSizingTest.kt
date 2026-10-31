package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ArtworkCacheSizingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val mb = 1024 * 1024

    @Test fun tilesRoundToTheSmallestSufficientBucket() {
        val store = ArtworkCacheStore { _, _, _ -> null }
        val expected = mapOf(1 to 128, 128 to 128, 129 to 192, 180 to 192, 192 to 192, 193 to 256, 256 to 256,
            257 to 384, 384 to 384, 385 to 768, 4000 to 768)
        expected.forEach { (pixels, bucket) -> assertEquals("pixels=$pixels", bucket, store.bucket(pixels)) }
        assertEquals(expected.getValue(180), ArtworkCache.bucket(180))
    }

    @Test fun peekPrefersLargerThenNearestSmallerAcrossAllBuckets() = runBlocking {
        val small = bitmap(); val large = bitmap()
        val store = ArtworkCacheStore { _, _, edge -> if (edge == 128) small else large }
        store.read(context, "a", 128); store.read(context, "a", 384)
        assertSame(large, store.peek(listOf("a"), 192)?.bitmap)
        assertEquals(384, store.peek(listOf("a"), 192)?.edge)
        assertSame(large, store.peek(listOf("a"), 768)?.bitmap)
        assertEquals(128, store.peek(listOf("a"), 0)?.edge)
    }

    @Test fun cacheBudgetFollowsHeapClassWithinBounds() {
        assertEquals(12 * mb, artworkCacheBytes(0))
        assertEquals(12 * mb, artworkCacheBytes(64))
        assertEquals(24 * mb, artworkCacheBytes(192))
        assertEquals(32 * mb, artworkCacheBytes(256))
        assertEquals(48 * mb, artworkCacheBytes(384))
        assertEquals(48 * mb, artworkCacheBytes(Int.MAX_VALUE))
        var last = 0
        for (mc in 0..1024 step 16) {
            val bytes = artworkCacheBytes(mc)
            assertTrue(bytes in 12 * mb..48 * mb); assertTrue(bytes >= last); last = bytes
        }
        val store = ArtworkCacheStore { _, _, _ -> null }
        assertEquals(12 * mb, store.maxBytes)
        store.resize(artworkCacheBytes(256))
        assertEquals(32 * mb, store.maxBytes)
    }

    @Test fun lruEvictsToTheConfiguredBudget() = runBlocking {
        val store = ArtworkCacheStore(maxBytes = 100_000) { _, _, _ -> Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) } // 40 KB each
        repeat(5) { store.read(context, "u$it", 128) }
        assertNull(store.peek(listOf("u0")))
        assertNotNull(store.peek(listOf("u4")))
        assertNotNull(store.peek(listOf("u3")))
    }

    @Test fun parallelDecodesAreBoundedKeyedAndReturnTheirOwnCover() = runBlocking {
        val running = AtomicInteger(); val peak = AtomicInteger(); val starts = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val covers = (0 until 6).associate { "u$it" to bitmap() }
        val calls = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val store = ArtworkCacheStore(parallelism = 3) { _, uri, _ ->
            calls.getOrPut(uri) { AtomicInteger() }.incrementAndGet()
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }; starts.incrementAndGet()
            try { release.await(); covers.getValue(uri) } finally { running.decrementAndGet() }
        }
        val jobs = (0 until 6).flatMap { i -> listOf(async(Dispatchers.Default) { store.read(context, "u$i", 192) },
            async(Dispatchers.Default) { store.read(context, "u$i", 150) }) }
        try {
            withTimeout(5000) { while (starts.get() < 3) delay(5) }
            delay(100)
            assertEquals("Exactly the permitted decodes run while others queue", 3, starts.get())
            release.complete(Unit)
            val results = withTimeout(5000) { jobs.awaitAll() }
            results.forEachIndexed { index, bitmap -> assertSame(covers.getValue("u${index / 2}"), bitmap) }
            assertTrue(peak.get() in 2..3)
            assertEquals("Duplicates of one bucket decode once", List(6) { 1 }, (0 until 6).map { calls.getValue("u$it").get() })
        } finally { release.complete(Unit); jobs.forEach { it.cancel() } }
    }

    @Test fun invalidationDuringParallelDecodesKeepsOnlyNewRevisionAndCancelledWaitersNeverLoad() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val store = ArtworkCacheStore(parallelism = 2) { _, _, _ -> loads.incrementAndGet(); release.await(); bitmap() }
        val running = (0..1).map { async(Dispatchers.Default) { store.read(context, "r$it", 128) } }
        val waiting = async(Dispatchers.Default) { store.read(context, "w", 128) }
        try {
            withTimeout(5000) { while (loads.get() < 2) delay(5) }
            waiting.cancel(); withTimeout(5000) { waiting.join() }
            store.invalidate()
            release.complete(Unit)
            withTimeout(5000) { running.awaitAll() }
            assertEquals(2, loads.get())
            assertNull("Stale revisions must not repopulate", store.peek(listOf("r0")))
            assertNull(store.peek(listOf("w")))
        } finally { release.complete(Unit); running.forEach { it.cancel() }; waiting.cancel() }
    }
}
