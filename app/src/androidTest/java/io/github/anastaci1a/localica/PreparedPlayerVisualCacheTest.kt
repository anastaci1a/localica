package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.ui.PreparedPlayerVisualCache
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PreparedPlayerVisualCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test(timeout = 10000) fun concurrentRequestsShareFetchAndPreparedPixelsAndDifferentUrisDeduplicateCover() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = PreparedPlayerVisualCache(CoverProvider { _, _, _ ->
            calls.incrementAndGet(); entered.complete(Unit); release.await(); image(android.graphics.Color.RED)
        })
        val first = async(Dispatchers.Default) { cache.read(context, "one") }
        entered.await()
        val second = async(Dispatchers.Default) { cache.read(context, "one") }
        release.complete(Unit)
        val a = first.await()
        assertSame(a, second.await())
        assertEquals(1, calls.get())
        val b = cache.read(context, "another-track-same-cover")
        assertEquals(2, calls.get())
        assertSame("Bitmap identity is reusable without reusing track identity", a.artwork, b.artwork)
        assertSame(a.softArtwork, b.softArtwork)
    }

    @Test(timeout = 10000) fun cancellationReleasesPreparationAndNeverCachesAnObsoleteResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cache = PreparedPlayerVisualCache(CoverProvider { _, uris, _ ->
            if (uris.single() == "obsolete") { entered.complete(Unit); awaitCancellation() }
            image(android.graphics.Color.BLUE)
        })
        val obsolete = launch(Dispatchers.Default) { cache.read(context, "obsolete") }
        entered.await(); obsolete.cancelAndJoin()
        val current = cache.read(context, "current")
        assertNull(cache.peek("obsolete"))
        assertSame(current, cache.peek("current"))
        assertEquals(android.graphics.Color.BLUE, current.artwork!!.asAndroidBitmap().getPixel(0, 0))
    }

    @Test(timeout = 10000) fun missingAndFailedCoversAreReadyCachedResultsNotAnEndlessLoad() = runBlocking {
        val calls = AtomicInteger()
        val cache = PreparedPlayerVisualCache(CoverProvider { _, uris, _ ->
            calls.incrementAndGet()
            if (uris.single() == "error") throw java.io.IOException("Synthetic unavailable cover")
            null
        })
        for (key in listOf("missing", "error")) {
            val result = cache.read(context, key)
            assertNull(result.artwork); assertFalse(result.loading)
            assertSame(result, cache.read(context, key))
        }
        assertEquals(2, calls.get())
    }

    @Test(timeout = 10000) fun budgetEvictionDoesNotRecycleAFrameStillOwnedByThePlayer() = runBlocking {
        val cache = PreparedPlayerVisualCache(CoverProvider { _, uris, _ ->
            image(if (uris.single() == "red") android.graphics.Color.RED else android.graphics.Color.BLUE)
        }, budgetBytes = 4096)
        val red = cache.read(context, "red")
        cache.read(context, "blue")
        assertTrue(cache.retainedBytes <= 4096)
        val bitmap = requireNotNull(red.artwork).asAndroidBitmap()
        assertFalse(bitmap.isRecycled)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
    }

    @Test(timeout = 10000) fun readyCacheHitDoesNotWaitForAnUnrelatedColdPreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = PreparedPlayerVisualCache(CoverProvider { _, uris, _ ->
            if (uris.single() == "cold") { entered.complete(Unit); release.await() }
            image(android.graphics.Color.BLUE)
        })
        val ready = cache.read(context, "ready")
        val cold = async(Dispatchers.Default) { cache.read(context, "cold") }
        try {
            entered.await()
            assertSame("Synchronous lookup is ready while preparation is held", ready, cache.peek("ready"))
            assertSame("A complete cache hit must bypass the preparation mutex", ready,
                withTimeout(1000) { cache.read(context, "ready") })
        } finally { release.complete(Unit); cold.cancelAndJoin() }
    }

    @Test(timeout = 10000) fun revisionRetiresCacheOwnershipWithoutRecyclingSharedFrames() = runBlocking {
        val cache = PreparedPlayerVisualCache(CoverProvider { _, _, _ -> image(android.graphics.Color.RED) })
        val revision = ArtworkCache.revision.value
        val first = cache.read(context, "first")
        val alias = cache.read(context, "alias")
        assertSame(first.artwork, alias.artwork)
        assertSame(first.palette.wash, alias.palette.wash)
        val retained = listOfNotNull(first.artwork?.asAndroidBitmap(), first.softArtwork?.asAndroidBitmap(),
            first.palette.wash, first.palette.softWash, first.palette.collectionWash)
        assertTrue(cache.retainedBytes > 0)

        ArtworkCache.invalidate()
        assertNull("An old composition cannot read a new-revision entry", cache.peek("first", revision))
        assertNull(cache.peek("alias"))
        assertEquals("Retirement removes the old revision's ownership", 0, cache.retainedBytes)
        retained.forEach { assertFalse("Visible and outgoing references remain valid", it.isRecycled) }
        assertEquals(android.graphics.Color.RED, first.artwork!!.asAndroidBitmap().getPixel(0, 0))
        val replacement = cache.read(context, "first")
        assertNotSame(first.artwork, replacement.artwork)
        assertSame(replacement, cache.peek("first"))
        assertNull("An obsolete revision never aliases the new result", cache.peek("first", revision))
    }

    @Test(timeout = 10000) fun invalidationDuringPreparationCannotRepopulateTheRetiredRevision() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = PreparedPlayerVisualCache(CoverProvider { _, _, _ ->
            entered.complete(Unit); release.await(); image(android.graphics.Color.BLUE)
        })
        val pending = async(Dispatchers.Default) { cache.read(context, "obsolete") }
        try {
            entered.await()
            ArtworkCache.invalidate()
            release.complete(Unit)
            val outgoing = pending.await()
            assertNull(cache.peek("obsolete"))
            assertEquals(0, cache.retainedBytes)
            assertFalse(outgoing.artwork!!.asAndroidBitmap().isRecycled)
            assertSame(cache.read(context, "obsolete"), cache.peek("obsolete"))
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }
}
