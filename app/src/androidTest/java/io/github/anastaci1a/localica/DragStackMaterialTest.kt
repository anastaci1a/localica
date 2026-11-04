package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real shared drag overlay: sampling follows motion, and each pane filters the panes below. */
class DragStackMaterialTest {
    @get:Rule val compose = localicaComposeRule()
    private lateinit var state: KeyedDragState<String, Unit>
    private lateinit var source: FrostedBackdrop
    private var changed by mutableStateOf(false)
    private val panes = mutableMapOf<Int, FrostedBackdrop>()
    private val entries = listOf("first", "second", "third", "fourth")

    private fun fixture(pickupContent: Boolean = false) {
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            state = remember { object : KeyedDragState<String, Unit>(grid, scope, { it }) {
                override fun capturePresentation() = Unit
            }.also { it.items = entries } }
            source = rememberFrostedBackdrop(changed, dark = false, settledRefresh = false, distinctPixels = true)
            if (pickupContent) BackdropLease(source)
            Box(Modifier.size(360.dp, 480.dp).testTag("stack-scene").onSizeChanged {
                state.viewportWidth = it.width; state.viewportHeight = it.height
            }) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val step = 20.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        val green = if ((x / step).toInt() % 2 == 0) .15f else .85f
                        drawRect(Color(.85f, green, .1f), Offset(x, 0f), Size(step, size.height / 2))
                        drawRect(if (changed) Color(.1f, .9f, .1f) else Color(.1f, green, .85f),
                            Offset(x, size.height / 2), Size(step, size.height / 2))
                        x += step
                    }
                }
                LazyVerticalGrid(GridCells.Fixed(1), state = grid) {
                    items(state.rendered(entries), key = { it ?: DropGapKey }) {
                        Box(Modifier.fillMaxWidth().height(72.dp))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source,
                    LocalDragPaneObserver provides { index, pane -> panes[index] = pane }) {
                    KeyedDragOverlay(state, !pickupContent, { "Dragging $it" }) { item, _ ->
                        if (pickupContent && item != entries.first()) Canvas(Modifier.fillMaxSize()) { drawRect(Color.Magenta) }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun begin(count: Int) {
        compose.runOnIdle {
            assertTrue(state.begin(entries.first(), entries.take(count).toSet(), state.bounds().getValue(entries.first()).center))
        }
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(15000) { source.lensSnapshot != null }
    }

    private fun capture(name: String): ImageBitmap {
        val image = compose.onNodeWithTag("stack-scene").captureToImage()
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val out = File(root, "drag-stack-557").apply { mkdirs() }
        File(out, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return image
    }

    @Test fun joinedPanesDiscardPickupContentFromTheirFrost() {
        fixture(pickupContent = true)
        if (android.os.Build.VERSION.SDK_INT < 33) {
            compose.waitUntil(15000) { source.lensSnapshot != null }
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle {
                    assertTrue(state.begin(entries.first(), entries.take(3).toSet(), state.bounds().getValue(entries.first()).center))
                }
                // Coroutine delays use the Compose clock too. Reach the 200 ms
                // paused-pointer refresh while pickup is still animating.
                repeat(14) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("stack-scene").captureToImage()
                }
                val bottom = requireNotNull(panes[2])
                // Service the paused-pointer capture before finishing the pickup animation.
                // Otherwise that later refresh could hide a missing pickup-phase dependency.
                compose.waitUntil(15000) { bottom.revision > 0 && bottom.readbackRequest >= 2 }
                repeat(2) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("stack-scene").captureToImage()
                }
                compose.waitUntil(15000) { bottom.snapshotReadbackRequest >= 2 }
                fun hasPickupContent(image: ImageBitmap): Boolean {
                    val pixels = image.toPixelMap()
                    val d = image.width / 360f
                    return (0 until (230 * d).toInt()).any { y ->
                        pixels[(180 * d).toInt(), y].let { it.red > .7f && it.blue > .7f && it.green < .3f }
                    }
                }
                assertTrue("Seed the native cache with the still-visible pickup content", hasPickupContent(requireNotNull(bottom.snapshot)))
                // Native readback needs rendered frames, not only virtual animation time.
                repeat(25) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("stack-scene").captureToImage()
                }
                compose.mainClock.autoAdvance = true
                var last: ImageBitmap? = null
                var stale = true
                compose.waitUntil(15000) {
                    bottom.snapshot?.let { current ->
                        if (current !== last) { stale = hasPickupContent(current); last = current }
                    }
                    !stale
                }
            } finally { compose.mainClock.autoAdvance = true }
        } else begin(3)
        compose.waitUntil(15000) {
            val image = compose.onNodeWithTag("stack-scene").captureToImage()
            val d = image.width / 360f
            image.toPixelMap()[(180 * d).toInt(), (36 * d).toInt()].blue < .2f
        }
        capture("joined-panes-without-pickup-content")
    }

    @Test fun unchangedLiveSceneDoesNotRepublishItsPixels() {
        fixture(); begin(3)
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        val previous = source.capture
        val request = source.snapshotReadbackRequest
        compose.runOnIdle { source.refresh() }
        compose.waitUntil(15000) { source.snapshotReadbackRequest > request }
        assertSame("Identical scene pixels must not restart recursive preparation", previous, source.capture)
    }

    @Test fun movingStackSamplesItsCurrentViewportAndLiveScene() {
        fixture(); begin(3)
        val initial = capture("pickup")
        val density = initial.width / 360f
        compose.runOnIdle { state.move(Offset(180f * density, 350f * density)) }
        compose.waitForIdle()
        val moved = capture("moved").toPixelMap()
        val pixel = moved[(180 * density).toInt(), (350 * density).toInt()]
        assertTrue("The moved pane samples blue below it, not red from pickup: $pixel", pixel.blue > pixel.red + .25f)
        compose.runOnIdle { changed = true }
        compose.waitUntil(15000) {
            val live = compose.onNodeWithTag("stack-scene").captureToImage().toPixelMap()
            val p = live[(180 * density).toInt(), (350 * density).toInt()]
            p.green > p.blue + .25f
        }
        capture("live-scene-change")
    }

    @Test fun threePanesRecursivelySoftenTheBackdropMoreThanOne() {
        fixture(); begin(1)
        val single = capture("single-pane")
        val density = single.width / 360f
        fun contrast(image: ImageBitmap): Float {
            val pixels = image.toPixelMap()
            val values = ((80 * density).toInt()..(220 * density).toInt()).map { pixels[it, (36 * density).toInt()].green }
            return values.max() - values.min()
        }
        val one = contrast(single)
        compose.runOnIdle { state.finish(cancel = true, reducedMotion = true) { _, _, _ -> fail("Cancel cannot save") } }
        compose.waitUntil(10000) { state.drag == null }
        begin(3)
        // Legacy prepares the recursive snapshots asynchronously. Pixel convergence, not a delay,
        // owns readiness; the endpoint must differ from independently sampled identical panes.
        try {
            compose.waitUntil(15000) {
                contrast(compose.onNodeWithTag("stack-scene").captureToImage()) < one - .015f
            }
        } finally { capture("recursive-endpoint") }
        val stack = capture("recursive-three-panes")
        assertTrue("Recursive frost reduces stripe contrast: one=$one stack=${contrast(stack)}", contrast(stack) < one - .015f)
        val top = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("drag-card-third").fetchSemanticsNode().boundsInRoot
        assertEquals("Stack panes retain equal widths", top.width, back.width, .5f)
        assertEquals("Stack panes retain equal heights", top.height, back.height, .5f)
        assertTrue("Trailing panes remain exposed", back.left > top.left && back.top > top.top)
    }
}
