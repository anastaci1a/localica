package io.github.anastaci1a.localica

import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** #930 cost evidence: full and quick scan writes at 18,000 tracks, wall time, allocation, GC and heap. Runs unchanged on the base. */
@RunWith(AndroidJUnit4::class)
class DateCarryScale930Test {
    @Test fun fullAndQuickScanWritesAt18k() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "date-carry-scale-930.db"
        val count = 18_000
        val root = FolderSelection("tree:music", "Music")
        fun songs(from: Int, until: Int, title: String = "Song") =
            (from until until).map { Track("tree:music/document/$it", "$title $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "doc:$it", modifiedAt = 1L + it) }
        val library = songs(0, count)
        fun stat(key: String) = Debug.getRuntimeStat(key).toLong()
        val results = linkedMapOf<String, MutableList<String>>()
        suspend fun measure(label: String, setup: suspend (RoomLibraryStore) -> Unit, run: suspend (RoomLibraryStore) -> Unit) {
            repeat(6) { round ->
                context.deleteDatabase(name)
                val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
                try {
                    val store = RoomLibraryStore(database.libraryDao())
                    setup(store)
                    System.gc()
                    val allocated = stat("art.gc.bytes-allocated"); val gcs = stat("art.gc.gc-count"); val gcMs = stat("art.gc.gc-time")
                    val start = System.nanoTime()
                    run(store)
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                    if (round > 0) results.getOrPut(label) { mutableListOf() } += "%.1f ms, alloc %d KiB, gc %d (%d ms), heap %d KiB".format(ms,
                        (stat("art.gc.bytes-allocated") - allocated) / 1024, stat("art.gc.gc-count") - gcs, stat("art.gc.gc-time") - gcMs, heap / 1024)
                    assertEquals(count + if (label.contains("500")) 500 else 0, store.current().tracks.size)
                } finally { database.close(); context.deleteDatabase(name) }
            }
        }
        measure("full scan, 18000 new", {}) { it.replaceRoot(ScanResult(root, library, 0)) }
        measure("full rescan, 18000 saved", { it.replaceRoot(ScanResult(root, library, 0)) }) { it.replaceRoot(ScanResult(root, songs(0, count, "Retitled"), 0)) }
        measure("quick scan, 500 new over 18000 saved", { it.replaceRoot(ScanResult(root, library, 0)) }) { it.mergeRoot(ScanResult(root, songs(count, count + 500), 0)) }
        measure("quick scan, 18000 saved resent", { it.replaceRoot(ScanResult(root, library, 0)) }) { it.mergeRoot(ScanResult(root, library, 0)) }
        results.forEach { (label, rounds) -> rounds.forEachIndexed { i, line -> Log.i("Scale930", "$label | round ${i + 1} | $line") } }
        Log.i("Scale930", "maxMemory ${Runtime.getRuntime().maxMemory() / 1024} KiB")
        Unit
    }
}
