package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.ui.MediaArtwork
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ArtworkSizeHintTest {
    @get:Rule val compose = localicaComposeRule()

    /** The parent never measures its child, so onSizeChanged cannot fire: only a hint can start a load. */
    private fun unmeasured(hint: Int, edges: CopyOnWriteArrayList<Int>) {
        val provider = CoverProvider { _, _, edge -> edges += edge; Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
        compose.setContent {
            Layout(content = { MediaArtwork(listOf("hinted"), Modifier.size(100.dp), provider, sizeHint = hint) }) { _, constraints ->
                layout(constraints.minWidth, constraints.minHeight) {}
            }
        }
    }

    @Test fun hintStartsTheLoadAtTheBucketBeforeAnyMeasurement() {
        val edges = CopyOnWriteArrayList<Int>()
        unmeasured(180, edges)
        compose.waitUntil(5000) { edges.isNotEmpty() }
        assertEquals(listOf(192), edges.toList())
    }

    @Test fun withoutAHintNothingLoadsUntilMeasured() {
        val edges = CopyOnWriteArrayList<Int>()
        unmeasured(0, edges)
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertTrue(edges.isEmpty())
    }
}

class ArtworkOffMainTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun providerLoadsRunOffTheMainThread() {
        val threads = CopyOnWriteArrayList<Boolean>()
        val provider = CoverProvider { _, _, _ ->
            threads += (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        compose.setContent { MediaArtwork(listOf("off-main"), Modifier.size(50.dp), provider, sizeHint = 128) }
        compose.waitUntil(5000) { threads.isNotEmpty() }
        // The hint and the measured bucket can differ by display density, so more than
        // one load is valid here; every load must still run off the main thread.
        assertTrue(threads.isNotEmpty() && threads.none { it })
    }
}
