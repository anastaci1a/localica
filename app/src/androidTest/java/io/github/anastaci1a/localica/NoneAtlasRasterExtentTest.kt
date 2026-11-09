package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.GlassColorShader
import io.github.anastaci1a.localica.ui.engine.NoneReductionAtlas
import io.github.anastaci1a.localica.ui.engine.NoneAtlasStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #691: the physical atlas shrinks without relocating live cells or changing retained output. */
@SdkSuppress(minSdkVersion = 33)
class NoneAtlasRasterExtentTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun shrinkRegrowReaddAndCapacityPreserveEveryRetainedOutputPixel() {
        var mode by mutableIntStateOf(0)
        var paintRevision by mutableIntStateOf(0)
        var owner: NoneReductionAtlas? = null
        var admitted = 0
        lateinit var host: android.view.View
        var frameCommits = 0
        val receipt = JSONArray()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            host = LocalView.current
            val graphics = LocalGraphicsContext.current
            val atlas = remember(graphics) { NoneReductionAtlas(graphics, GlassColorShader) }
            owner = atlas
            val entries = remember { List(33) { NoneReductionAtlas.Entry() } }
            val current = mode
            val active = when (current) { 0, 2 -> listOf(entries[0], entries[1]); 3 -> entries; else -> listOf(entries[0]) }
            DisposableEffect(atlas) { onDispose { atlas.release() } }
            DisposableEffect(atlas, current) {
                active.forEach(atlas::add)
                onDispose { active.filter { it !== entries[0] }.forEach(atlas::remove) }
            }
            Row {
                Canvas(Modifier.size(64.dp).testTag("owner")) {
                    admitted = 0
                    with(atlas) {
                        for (entry in active) {
                            val tall = entry === entries[1] && current != 3
                            val extent = if (tall) 2048f else 64f
                            if (place(entry, Unit, current to paintRevision, Unit, Rect(0f, 0f, extent, extent), NoneAtlasStyle()) {
                                drawRect(if (paintRevision == 0) Color(0xFF1964C8) else Color(0xFF0EC9AA), size = Size(extent, extent))
                                drawRect(if (paintRevision == 0) Color(0xFFC83264) else Color(0xFFEDB929), size = Size(extent / 2f, extent))
                            }) admitted++
                        }
                        drawMember(entries[0])
                    }
                }
                // The second consumer retains a reference to the same output. It neither places
                // the entry nor reads the mode, so atlas mutations must reach it through HWUI.
                Canvas(Modifier.size(64.dp).graphicsLayer().testTag("retained")) {
                    with(atlas) { drawMember(entries[0]) }
                }
            }
        } }
        fun capture(tag: String): IntArray {
            compose.waitForIdle()
            // Waiting for Compose idleness does not certify GPU completion. The large
            // cold atlas can exceed WindowCapture's fixed two-second fence on SwiftShader.
            // Commit an actual window frame first; never retry a failed pixel assertion.
            val committed = CountDownLatch(1)
            compose.runOnIdle {
                if (Build.VERSION.SDK_INT >= 33) {
                    host.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    host.invalidate()
                }
            }
            assertTrue("A complete GPU frame precedes $tag capture", committed.await(10, TimeUnit.SECONDS))
            frameCommits++
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() }
        }
        val reference = capture("owner")
        for ((step, next) in listOf(0, 1, 2, 1, 3, 1, 2, 1).withIndex()) {
            compose.runOnIdle { mode = next }
            assertArrayEquals("Owner step=$step mode=$next", reference, capture("owner"))
            assertArrayEquals("Retained consumer step=$step mode=$next", reference, capture("retained"))
            // A retained consumer must receive changed pixels, not merely keep a stale
            // image which happens to match the structural before/after reference.
            compose.runOnIdle { paintRevision = 1 }
            val changed = capture("owner")
            assertFalse("Live source pixels change after step=$step", reference.contentEquals(changed))
            assertArrayEquals("Retained consumer updates after step=$step", changed, capture("retained"))
            compose.runOnIdle { paintRevision = 0 }
            assertArrayEquals("Owner restores after step=$step", reference, capture("owner"))
            assertArrayEquals("Retained consumer restores after step=$step", reference, capture("retained"))
            compose.runOnIdle {
                val extent = requireNotNull(owner).rasterSize
                assertEquals("Atlas width", 2048, extent.width)
                if (next == 1) assertEquals("Trailing free cells do not retain raster rows", 256, extent.height)
                if (next == 0 || next == 2) assertEquals("Same-entry readdition regrows before sampling", 2112, extent.height)
                assertEquals("Capacity unchanged", if (next == 3) 32 else if (next == 1) 1 else 2, admitted)
                receipt.put(JSONObject().put("step", step).put("mode", next).put("width", extent.width)
                    .put("height", extent.height).put("admitted", admitted).put("rgba8Equal", true))
            }
        }
        val args = InstrumentationRegistry.getArguments()
        val output = args.getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        output.mkdirs()
        File(output, "none-atlas-physical-extent-api${Build.VERSION.SDK_INT}.json").writeText(
            JSONObject().put("cases", receipt).put("allRgba8Equal", true).put("gpuFrameCommits", frameCommits).toString(2))
    }
}
