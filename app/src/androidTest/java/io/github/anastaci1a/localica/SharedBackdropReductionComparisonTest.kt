package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.rememberMaterialAreaReduction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/** Diagnostic experiment for #691, not a replacement for either production None path.
 * Both sides retain crop/padding/halving. The right side first shares a half-size source
 * grid. This asks whether moving even one reduction before the crop preserves bytes. */
class SharedBackdropReductionComparisonTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun sourceFirstGridCannotReplaceCropFirstAtArbitraryBoundaries() {
        val phase = mutableIntStateOf(0)
        val region = mutableStateOf(Rect(3.25f, 5.75f, 20.25f, 18.75f))
        val parentRecords = intArrayOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val child = rememberGraphicsLayer()
                val parent = rememberGraphicsLayer()
                val shared = rememberMaterialAreaReduction()
                val original = rememberMaterialAreaReduction()
                val experiment = rememberMaterialAreaReduction()
                Canvas(Modifier.size(144.dp, 72.dp).testTag("comparison")) {
                    val current = phase.intValue
                    val crop = region.value
                    child.record(size = IntSize(63, 47)) {
                        drawRect(if (current == 0) Color(0x805B193D) else Color(0xA0197D35))
                        for (x in 0 until 63) if ((x + current) % 3 == 0)
                            drawRect(Color(0xC0F3D107), Offset(x.toFloat(), 0f), Size(1f, 47f))
                        drawRect(Color(0x407030E0), Offset(9.5f, 11.25f), Size(8.75f, 19.5f))
                    }
                    if (parentRecords[0] == 0) {
                        parent.record(size = IntSize(63, 47)) { drawLayer(child) }
                        parentRecords[0]++
                    }
                    val sharedGrid = with(shared) {
                        record(IntSize(63, 47), minimum = 32) { drawLayer(parent) }
                    }
                    val extent = IntSize(ceil(crop.width).toInt(), ceil(crop.height).toInt())
                    val old = with(original) { record(extent) {
                        clipRect(right = crop.width, bottom = crop.height) {
                            translate(-crop.left, -crop.top) { drawLayer(parent) }
                        }
                    } }
                    val next = with(experiment) { record(extent) {
                        clipRect(right = crop.width, bottom = crop.height) {
                            translate(-crop.left, -crop.top) {
                                // Restore the padded source basis after its shared 2x step.
                                scale(2f, 2f, Offset.Zero) { drawLayer(sharedGrid) }
                            }
                        }
                    } }
                    // Equal coverage and identical display mapping; compare raw premultiplied
                    // reductions before either backend's material style or foreground enters.
                    drawRect(Color.Black)
                    translate(4f, 4f) { scale(64f, 64f, Offset.Zero) { drawLayer(old) } }
                    translate(76f, 4f) { scale(64f, 64f, Offset.Zero) { drawLayer(next) } }
                }
            }
        }
        val directory = outputDirectory()
        val receipts = mutableListOf<String>()
        var first: Bitmap? = null
        for ((index, bounds) in listOf(
            Rect(3.25f, 5.75f, 20.25f, 18.75f),
            Rect(2f, 4f, 19f, 17f),
            Rect(42.5f, 34.25f, 62.75f, 46.75f)
        ).withIndex()) {
            compose.runOnIdle { region.value = bounds }
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("comparison").captureToImage().asAndroidBitmap()
            val old = bitmap.getPixel(32, 32)
            val next = bitmap.getPixel(104, 32)
            receipts += "case=$index bounds=$bounds cropFirst=$old sourceFirst=$next equal=${old == next}"
            File(directory, "case-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (index == 0) first = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        // Mutate only the child RenderNode. No parent display-list recording occurs.
        compose.runOnIdle {
            region.value = Rect(3.25f, 5.75f, 20.25f, 18.75f)
            phase.intValue = 1
        }
        compose.waitForIdle()
        val changed = compose.onNodeWithTag("comparison").captureToImage().asAndroidBitmap()
        receipts += "childOnlyUpdate parentRecordings=${parentRecords[0]} before=${first!!.getPixel(32, 32)} after=${changed.getPixel(32, 32)}"
        File(directory, "child-only-update.png").outputStream().use { changed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "comparison.txt").writeText(receipts.joinToString("\n", postfix = "\n"))
        assertEquals("The parent counter must remain unchanged", 1, parentRecords[0])
        assertNotEquals("Child content updates despite an unchanged parent counter", first!!.getPixel(32, 32), changed.getPixel(32, 32))
        assertTrue("A source-first grid is not byte-identical for every regional crop; preserve production",
            receipts.take(3).any { it.endsWith("equal=false") })
    }

    private fun outputDirectory(): File {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        return File(root, "shared-reduction-experiment-api${Build.VERSION.SDK_INT}").also { it.mkdirs() }
    }
}
