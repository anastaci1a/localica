package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Evidence harness for #528 (not for merge): repeat the scenario and classify each failure. */
class ArtworkCacheRaceStressTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val iterations = 400

    private class Outcome(val loads: Int, val uris: List<String>, val staleCached: Boolean)

    /** strict = start the cancelled reader only after both permits are held (what the product guarantees). */
    private suspend fun scenario(strict: Boolean): Outcome = coroutineScope {
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val uris = ConcurrentLinkedQueue<String>()
        val store = ArtworkCacheStore(parallelism = 2) { _, uri, _ -> uris += uri; loads.incrementAndGet(); release.await(); bitmap() }
        val running = (0..1).map { async(Dispatchers.Default) { store.read(context, "r$it", 128) } }
        var waiting: Deferred<Bitmap?>? = null
        if (!strict) waiting = async(Dispatchers.Default) { store.read(context, "w", 128) }
        try {
            withTimeout(5000) { while (loads.get() < 2) delay(1) }
            if (strict) { waiting = async(Dispatchers.Default) { store.read(context, "w", 128) }; delay(20) }
            waiting!!.cancel(); withTimeout(5000) { waiting.join() }
            store.invalidate()
            release.complete(Unit)
            withTimeout(5000) { running.awaitAll() }
            Outcome(loads.get(), uris.toList(), store.peek(listOf("r0")) != null || store.peek(listOf("w")) != null)
        } finally { release.complete(Unit); running.forEach { it.cancel() }; waiting?.cancel() }
    }

    private fun summarize(label: String, outcomes: List<Outcome>): String {
        val bad = outcomes.filter { it.loads != 2 }
        val waiterLoaded = bad.count { "w" in it.uris }
        val msg = "STRESS $label: n=${outcomes.size} loads!=2 in ${bad.size} (${100.0 * bad.size / outcomes.size}%); " +
            "of those the cancelled reader 'w' was loaded in $waiterLoaded; r-reader loaded twice in " +
            "${bad.count { it.uris.count { u -> u.startsWith("r") } > 2 }}; stale repopulated=${outcomes.count { it.staleCached }}"
        android.util.Log.i("STRESS528", msg); println(msg)
        return msg
    }

    @Test fun originalOrderingFailureRate() = runBlocking {
        val outcomes = (1..iterations).map { scenario(strict = false) }
        val msg = summarize("original(w launched concurrently)", outcomes)
        assertTrue(msg, outcomes.none { it.loads != 2 })
    }

    @Test fun deterministicOrderingNeverLoadsCancelledWaiterOrStaleRevision() = runBlocking {
        val outcomes = (1..iterations).map { scenario(strict = true) }
        val msg = summarize("strict(w queued behind two held permits)", outcomes)
        assertTrue(msg, outcomes.none { it.loads != 2 || it.staleCached })
    }
}
