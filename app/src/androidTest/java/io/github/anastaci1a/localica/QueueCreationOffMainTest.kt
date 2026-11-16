package io.github.anastaci1a.localica

import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import kotlinx.coroutines.flow.first
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A library-sized song tap builds its queue (one random occurrence ID per song) off the main thread. */
class QueueCreationOffMainTest {
    @Test fun libraryTapReadsItsSongsOffMainAndKeepsTheSelectedOccurrence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val source = List(13_000) { index ->
            Track("content://queue-creation/$index", "Track $index", fileName = "$index.mp3", relativePath = "$index.mp3")
        }
        val mainReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val tracks = object : AbstractList<Track>() {
            override val size get() = source.size
            override fun get(index: Int): Track {
                (if (Looper.myLooper() == Looper.getMainLooper()) mainReads else workerReads).incrementAndGet()
                return source[index]
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            preferences.write(QueueSettings())
            lateinit var queues: QueueController
            instrumentation.runOnMainSync {
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (queues.state.value.loading) { assertTrue(SystemClock.elapsedRealtime() < deadline); Thread.sleep(20) }
            instrumentation.runOnMainSync { queues.tap(tracks, 6500) }
            while (queues.state.value.library.active?.entries?.size != 13_000) {
                assertTrue("The tapped queue was not created", SystemClock.elapsedRealtime() < deadline); Thread.sleep(20)
            }
            val active = queues.state.value.library.active!!
            assertEquals(active.entries[6500].id, active.currentEntryId)
            assertEquals(source[6500], active.entries[6500].track)
            assertTrue("Queue construction must iterate the songs on a worker", workerReads.get() >= source.size)
            // Only the selected song is resolved on Main before construction moves to the worker.
            assertTrue("Main read ${mainReads.get()} songs", mainReads.get() < 16)
        } finally {
            scope.cancel()
            db.close()
            preferences.write(original)
        }
    }

    @Test fun browseTapPreparesItsNameOriginsAndPendingSnapshotWithoutMainReadsAtScale() = pendingAtScale(0)
    @Test fun pinnedIdTapFindsTheFirstMatchAndSnapshotsWithoutMainReadsAtScale() = pendingAtScale(1)
    @Test fun collectionShuffleKeepsEveryOriginPairedWithoutMainReadsAtScale() = pendingAtScale(2)

    private fun pendingAtScale(admission: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val library = (context.applicationContext as LocalicaApplication).library
        val songs = List(50_000) { index -> Track("content://intake/$index", "Track $index", artist = "Artist",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        try { for (count in listOf(50_000, 1_000_000)) {
            val mainReads = AtomicInteger()
            val workerReads = AtomicInteger()
            val rows = object : AbstractList<SongRow>() {
                override val size get() = count
                override fun get(index: Int): SongRow {
                    (if (Looper.myLooper() == Looper.getMainLooper()) mainReads else workerReads).incrementAndGet()
                    return SongRow(index.toString(), songs[index % songs.size], index.toString())
                }
            }
            val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val seed = QueueEdits.create(listOf(songs[0]), 0, "Existing")
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(seed), seed.id))
            preferences.write(QueueSettings(maximum = 1))
            lateinit var queues: QueueController
            try {
                withContext(Dispatchers.Main) { queues = QueueController(db.queues(), preferences, library, scope) }
                withTimeout(10_000) { queues.state.first { !it.loading } }
                val runtime = Runtime.getRuntime()
                runtime.gc()
                val before = runtime.totalMemory() - runtime.freeMemory()
                val peak = java.util.concurrent.atomic.AtomicLong(before)
                val gcBefore = android.os.Debug.getRuntimeStats()
                val sampler = scope.launch(Dispatchers.Default) {
                    while (isActive) {
                        peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                        delay(10)
                    }
                }
                val pending = try {
                    withContext(Dispatchers.Main) {
                        when (admission) {
                            0 -> queues.tap(rows, count - 1, SongListView.Songs, BrowseRoute(MainTab.Songs))
                            1 -> queues.tap(rows.mapView(SongRow::track), songs.last().id, sourceName = "Pinned songs")
                            else -> queues.randomizedQueue(rows.mapView(SongRow::track), "Collection", rows.mapView(SongRow::sourcePlaylistId))
                        }
                    }
                    withTimeout(120_000) { queues.state.first { it.pending != null }.pending!! }
                } finally { sampler.cancelAndJoin() }
                val gcAfter = android.os.Debug.getRuntimeStats()
                val line = "admission=$admission entries=$count tracks=${songs.size} mainReads=${mainReads.get()} workerReads=${workerReads.get()} beforeBytes=$before peakBytes=${peak.get()} settledBytes=${runtime.totalMemory() - runtime.freeMemory()} maxBytes=${runtime.maxMemory()} gcBefore=$gcBefore gcAfter=$gcAfter"
                android.util.Log.i("CommandIntake1022", line)
                java.io.File(nativeEvidenceDirectory("command-intake-1022", context.getExternalFilesDir(null)), "scale.txt").appendText(line + "\n")
                assertEquals("No source element may be read on Main", 0, mainReads.get())
                assertTrue("The worker must own the complete pending snapshot", workerReads.get() >= count)
                assertEquals(count, pending.tracks.size)
                assertEquals(if (admission == 1) songs.lastIndex else if (admission == 0) count - 1 else 0, pending.selectedIndex)
                assertEquals(listOf("All Songs ◀ Artist", "Pinned songs", "Collection")[admission], pending.sourceName)
                assertFalse(queues.state.value.settings.shuffle)
                if (admission == 1) {
                    assertEquals(songs.last().id, pending.tracks[pending.selectedIndex].id)
                    assertTrue(pending.playlistOrigins.isEmpty())
                } else {
                    val seen = BooleanArray(count)
                    for (index in 0 until count) {
                        val origin = pending.playlistOrigins[index]!!.toInt()
                        assertFalse("Each source occurrence must appear exactly once", seen[origin])
                        seen[origin] = true
                        assertEquals(songs[origin % songs.size].id, pending.tracks[index].id)
                        if (admission == 0) assertEquals(index, origin)
                    }
                    assertTrue(seen.all { it })
                }
                withContext(Dispatchers.Main) { queues.cancelCreation() }
                withTimeout(10_000) { queues.state.first { it.pending == null } }
                assertEquals(seed.id, queues.state.value.library.activeId)
                assertEquals(0, mainReads.get())
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
        } } finally { preferences.write(original) }
    }
}
