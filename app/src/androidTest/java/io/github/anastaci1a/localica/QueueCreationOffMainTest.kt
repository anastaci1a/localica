package io.github.anastaci1a.localica

import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
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
}
