package io.github.anastaci1a.localica

import android.os.Debug
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.library.mapView
import io.github.anastaci1a.localica.library.ownerRevision
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseEffectKeyTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun millionEntryEffectKeyComparesOwnersWithoutReadingElementsOnMain() {
        var reads = 0
        fun source() = object : AbstractList<Int>() {
            override val size = 1_000_000
            override fun get(index: Int): Int {
                check(Looper.myLooper() == Looper.getMainLooper())
                reads++
                return index
            }
        }
        val original = source()
        val project: (Int) -> Int = { it }
        val changed: (Int) -> Int = { it + 1 }
        var input by mutableStateOf(original to project, neverEqualPolicy())
        var ownerKey by mutableStateOf(false)
        var started = 0
        var cancelled = 0
        compose.setContent {
            val (owner, projection) = input
            val items = owner.mapView(projection).mapView(project)
            // Control uses the old content key; candidate uses BrowserScreen's owner key.
            LaunchedEffect(if (ownerKey) items.ownerRevision else items) {
                started++
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        compose.runOnIdle {
            assertEquals(1, started)
            assertEquals(0, reads)
            input = original to project
        }
        compose.runOnIdle {
            assertEquals("content equality visits both million-entry views", 2_000_000, reads)
            assertEquals(1, started)
            assertEquals(0, cancelled)
            ownerKey = true
        }
        compose.runOnIdle {
            assertEquals(2, started)
            assertEquals(1, cancelled)
            reads = 0
            input = original to project
        }
        compose.runOnIdle {
            assertEquals("same published owner", 0, reads)
            assertEquals(2, started)
            assertEquals(1, cancelled)
            input = source() to project
        }
        compose.runOnIdle {
            assertEquals("equal content with a different owner", 0, reads)
            assertEquals(3, started)
            assertEquals(2, cancelled)
            input = input.first to changed
        }
        compose.runOnIdle {
            assertEquals("changed projection", 0, reads)
            assertEquals(4, started)
            assertEquals(3, cancelled)
            val runtime = Runtime.getRuntime()
            Log.i("BrowseEffectKey", "entries=1000000 controlReads=2000000 ownerReads=$reads " +
                "started=$started cancelled=$cancelled heapBytes=${runtime.totalMemory() - runtime.freeMemory()} " +
                "gc=${Debug.getRuntimeStats()}")
        }
    }
}
