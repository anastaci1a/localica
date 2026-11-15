package io.github.anastaci1a.localica

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** #957 slice B memory gate: browse results over a 1M-entry queue retain no queue-sized copy.
 * Arguments queueEntries (default 1,000,000) and libraryTracks (default 13,400). */
class BrowseQueueHeapTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val entries = arguments.getString("queueEntries")?.toInt() ?: 1_000_000
    private val tracks = arguments.getString("libraryTracks")?.toInt() ?: 13_400
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun liveBytes(): Long {
        repeat(3) { Runtime.getRuntime().gc(); SystemClock.sleep(150) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
    private fun gcCount() = Debug.getRuntimeStat("art.gc.gc-count").toLong()
    private fun gcTimeMs() = Debug.getRuntimeStat("art.gc.gc-time").toLong()

    @Test fun browseResultsOverAMillionEntryQueueRetainOnlyTheirActiveData() {
        val library = LibrarySearchIndexBuilder().prepare(LibrarySnapshot(tracks = List(tracks) { i ->
            Track("heap957:$i", "Song $i", artist = "Artist ${i % 900}", album = "Album ${i / 12}", durationMs = 180_000L + i,
                fileName = "$i.mp3", relativePath = "Album ${i / 12}/$i.mp3", year = 1960 + i % 60, genre = "Genre ${i % 24}",
                addedAt = 1_700_000_000_000L + i, playCount = (i % 7).toLong())
        }))
        var queue = ListeningQueue("heap957", "Heap", entries = List(entries) { QueueEntry("e$it", library.tracks[(it * 7919L % tracks).toInt()]) })
        val detail = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
        val peak = AtomicLong()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = thread(name = "heap957-sampler") {
            while (sampling.get()) { Runtime.getRuntime().let { peak.accumulateAndGet(it.totalMemory() - it.freeMemory()) { a, b -> maxOf(a, b) } }; SystemClock.sleep(100) }
        }
        val lines = mutableListOf<String>()
        val gcBefore = gcCount(); val gcTimeBefore = gcTimeMs()
        library.tracksById // The library's own lazy index, built by any first row projection, is not browse-owned.
        val closed = liveBytes()
        fun step(name: String, limit: Long, block: () -> Any?) {
            val started = SystemClock.elapsedRealtime()
            val held = block()
            val ms = SystemClock.elapsedRealtime() - started
            val retained = liveBytes() - closed
            lines += "$name retainedB=$retained ms=$ms"
            assertTrue("$name retained $retained B (limit $limit)", retained <= limit)
            assertNotNull(held)
        }
        val mb = 1L shl 20
        try {
            step("open", mb) { BrowseQueries.openingQueue(library, queue, detail, "e${entries / 2}")!!.also { it.indexOfKey("e${entries / 2}") } }
            step("detail", mb) { BrowseQueries.evaluate(library, emptyList(), detail, queues = listOf(queue)) }
            step("zeroMatch", mb) { BrowseQueries.evaluate(library, emptyList(), detail.copy(search = true, text = "nothing matches"), queues = listOf(queue)) }
            step("broad", mb + 8L * entries) { BrowseQueries.evaluate(library, emptyList(), detail.copy(search = true, text = "song 1"), queues = listOf(queue)) }
            step("matchAll", mb + 8L * entries) { BrowseQueries.evaluate(library, emptyList(), detail.copy(search = true, text = "mp3"), queues = listOf(queue))
                .also { assertEquals(entries, it.items.size) } }
            // Arrivals: each queue revision replaces the entries; only the newest stays reachable here.
            var latest: BrowseResult? = null
            step("arrivals20", 4L * mb) {
                repeat(20) { n ->
                    queue = queue.copy(entries = queue.entries + QueueEntry("arrival$n", library.tracks[n]))
                    latest = BrowseQueries.evaluate(library, emptyList(), detail, queues = listOf(queue))
                }
                latest
            }
        } finally {
            sampling.set(false)
            sampler.join()
        }
        val max = Runtime.getRuntime().maxMemory()
        lines += "peakB=${peak.get()} closedB=$closed maxB=$max gcCount=${gcCount() - gcBefore} gcTimeMs=${gcTimeMs() - gcTimeBefore}"
        val text = "entries=$entries tracks=$tracks\n" + lines.joinToString("\n") + "\n"
        android.util.Log.i("BrowseQueueHeap957", text)
        File(nativeEvidenceDirectory("heap-957b", context.getExternalFilesDir(null)), "browse-$entries-$tracks.txt").writeText(text)
        assertTrue("peak ${peak.get()} B under the ${max} B heap", peak.get() < max)
    }
}
