package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #624 evidence: Normal and None side by side for a floating pill, a popup, a selected row
 * and the Queue drawer edge, at the default Glass settings. Images go to the additional test
 * output; the selected-row lift is also compared outside its body. */
class NoneShadowScreenshotTest {
    @get:Rule val compose = localicaComposeRule()

    private val output: File by lazy {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(root, "none-shadow-compare-api${Build.VERSION.SDK_INT}").also { it.mkdirs() }
    }

    /** A page with artwork tiles and text rows, like a library list. */
    @Composable private fun Page(source: FrostedBackdrop) {
        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
            drawRect(Color(0xFFF4F1EC))
            val colors = listOf(Color(0xFFB0413E), Color(0xFF2E5E8C), Color(0xFFE0A030), Color(0xFF3F7F4F), Color(0xFF6B4B8A))
            var y = 24f; var i = 0
            while (y < size.height) {
                drawRect(colors[i % colors.size], Offset(32f, y), Size(112f, 112f))
                drawRect(Color(0xFF202124), Offset(176f, y + 28f), Size(size.width * .5f, 22f))
                drawRect(Color(0xFF80868B), Offset(176f, y + 66f), Size(size.width * .32f, 18f))
                y += 148f; i++
            }
        }
    }

    private fun screenshot(): Bitmap {
        compose.waitForIdle(); Thread.sleep(500); compose.waitForIdle()
        return InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    }

    private fun sideBySide(name: String, normal: Bitmap, none: Bitmap) {
        val gap = 24
        val combined = Bitmap.createBitmap(normal.width + gap + none.width, maxOf(normal.height, none.height), Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(combined).apply {
            drawColor(android.graphics.Color.MAGENTA)
            drawBitmap(normal, 0f, 0f, null); drawBitmap(none, (normal.width + gap).toFloat(), 0f, null)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.MAGENTA; textSize = 48f; isFakeBoldText = true }
            drawText("Normal", 24f, 64f, paint); drawText("None", normal.width + gap + 24f, 64f, paint)
        }
        for ((suffix, image) in listOf("normal" to normal, "none" to none, "side-by-side" to combined))
            File(output, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capture(name: String, runtime: GlassRendererRuntime, source: FrostedBackdrop): Pair<Bitmap, Bitmap> {
        // API 26-32 average materials draw nothing until the first scene readback lands; never compare that frame.
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.snapshot != null }
        compose.runOnIdle { runtime.request(GlassRendererMode.Normal) }
        val normal = screenshot()
        compose.runOnIdle { runtime.request(GlassRendererMode.FlatNone) }
        val none = screenshot()
        sideBySide(name, normal, none)
        return normal to none
    }

    @Test fun floatingPillSelectedRowAndPopup() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var popup by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                Page(source)
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(top = 220.dp)) {
                            Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp)
                                .selectionLens(true, false, itemKey = "selected").testTag("selected-row"))
                        }
                    }
                    LiquidGlassSurface(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).size(260.dp, 64.dp),
                        cornerRadius = 32.dp, pressFeedback = false) {
                        Box(contentAlignment = Alignment.Center) { Text("Floating pill") }
                    }
                    LiquidGlassSurface(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 24.dp).size(56.dp),
                        pressFeedback = false) { Box(contentAlignment = Alignment.Center) { Text("⋯") } }
                    if (popup) GlassAlertDialog(onDismissRequest = {}, title = { Text("Popup shadow") },
                        text = { Text("None replaces only the fill.") }, confirmButton = {})
                }
            }
        } } }
        val (normal, none) = capture("pill-row", runtime, source)
        // Selected-row lift: identical outside the lifted body in both renderers.
        val row = compose.onNodeWithTag("selected-row").fetchSemanticsNode().boundsInRoot // root-relative: boundsInWindow already includes the root offset
        val inWindow = IntArray(2).also { (compose.onRoot().fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view.getLocationOnScreen(it) }
        // The lifted body is magnified beyond the row's layout bounds; exclude a generous margin.
        val margin = 48f
        val body = android.graphics.RectF(row.left + inWindow[0] - margin, row.top + inWindow[1] - margin,
            row.right + inWindow[0] + margin, row.bottom + inWindow[1] + margin)
        var differing = 0; var largest = 0; val box = android.graphics.Rect()
        for (y in (body.top - 80).toInt().coerceAtLeast(0) until (body.bottom + 80).toInt().coerceAtMost(normal.height))
            for (x in 0 until normal.width) {
                if (body.contains(x + .5f, y + .5f)) continue
                val p = normal.getPixel(x, y); val q = none.getPixel(x, y)
                val delta = maxOf(kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)),
                    kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)), kotlin.math.abs((p and 255) - (q and 255)))
                // Two levels allow the retained-raster handoff rounding seen without any renderer change.
                if (delta > 2) { differing++; largest = maxOf(largest, delta); box.union(x, y) }
            }
        compose.runOnIdle { popup = true }
        capture("popup", runtime, source)
        assertEquals("Selected-row lift shadow differs outside the lifted body $body (largest $largest/255 within $box)", 0, differing)
    }

    @Test fun queueDrawerEdge() {
        val tracks = (1..14).map { Track("cmp:$it", "Queue song $it", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Queue")
        val state = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        lateinit var source: FrostedBackdrop
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, dark = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalSessionSelection provides remember { SessionSelection() }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Page(source)
                    QueueSurface(remember { Animatable(.45f) }, InterfaceSettings(), {}) {
                        Box(Modifier.weight(1f)) { QueueScreen(state, null, library, onMinimize = {}, playerOpen = false) }
                    }
                }
            }
        } } }
        capture("queue-edge", runtime, source)
    }
}
