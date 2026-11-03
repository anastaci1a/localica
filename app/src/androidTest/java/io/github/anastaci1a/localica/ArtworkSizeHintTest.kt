package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.ui.MediaArtwork
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ArtworkOffMainTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerLoadsRunOffTheMainThread() {
        val threads = CopyOnWriteArrayList<Boolean>()
        val provider = CoverProvider { _, _, _ ->
            threads += (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        compose.setContent { MediaArtwork(listOf("off-main"), Modifier.size(50.dp), provider) }
        compose.waitUntil(5000) { threads.isNotEmpty() }
        // The measured bucket can differ by display density, so more than
        // one load is valid here; every load must still run off the main thread.
        assertTrue(threads.isNotEmpty() && threads.none { it })
    }
}
