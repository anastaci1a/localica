package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Lift growth in a gallery is measured against neighbouring content, with 1 dp clearance kept; the lifted frame is saved. */
class GalleryLiftFrameTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scene: SelectionScene
    private var selected by mutableStateOf(false)

    @Test fun aLiftedGalleryCoverGrowsAboutAQuarterOfTheContentGapAndKeepsClearance() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(360.dp, 200.dp).testTag("gallery-lift")) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(.93f, .91f, .96f)) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        scene = checkNotNull(LocalSelectionScene.current)
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { index ->
                                val outline = rememberPaddedItemOutline(104.dp, 0.dp, 0.dp, 0.dp, 0.dp)
                                Canvas(Modifier.size(104.dp, 150.dp).selectionLens(index == 1 && selected, false, itemKey = "cover$index", outline = outline)) {
                                    drawRect(Color(.45f, .35f, .7f), Offset.Zero, Size(size.width, 104.dp.toPx()))
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val restShot = compose.onNodeWithTag("gallery-lift").captureNativeFrame()
        compose.runOnIdle { selected = true }
        compose.waitUntil(5000) { scene.lastDrawnRegions.isNotEmpty() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val liftedShot = compose.onNodeWithTag("gallery-lift").captureNativeFrame()
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))).let { File(it, "gallery-lift") }.apply { mkdirs() }
        for ((name, image) in listOf("rest" to restShot, "lifted" to liftedShot))
            File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val lifted = scene.lastDrawnRegions.first().bounds
        val coverLeft = (16 + 104 + 8) * density          // Middle cover's left edge (content).
        val leftCover = (16 + 104) * density               // Left neighbour's right content edge.
        val growth = (coverLeft - 3.5f * density) - lifted.left // Outline rests 3.5 dp outside the cover.
        assertTrue("Lifted outline grows beyond rest by about a quarter of the content gap: ${growth / density} dp", growth > .9f * density)
        assertTrue("At least 1 dp clearance to the neighbour's content: ${(lifted.left - leftCover) / density} dp", lifted.left - leftCover >= 1f * density - 1f)
    }
}
