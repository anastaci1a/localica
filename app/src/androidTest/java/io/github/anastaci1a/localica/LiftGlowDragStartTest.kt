package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #553 regression: in a light-ink (white icon) Queue, a selected item's white lift glow must not
 * become a dark shadow on the frame a drag starts. The foreground drag overlay is composed under the
 * app's root ink, outside the Queue's theming, as ForegroundSongDragOverlay is in the app.
 */
class LiftGlowDragStartTest {
    @get:Rule val compose = localicaComposeRule()

    private val songs = (0..3).map { BrowserItem(MediaRef(MediaKind.Song, "$it"), "Song $it", null, emptyList()) }
    private var selected by mutableStateOf(false)

    @Test fun lightInkGlowContinuesThroughDragStartWithoutAShadowFrame() {
        lateinit var state: SongDragState
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            val selection = remember { SelectionScene() }
            state = remember { SongDragState(grid, scope).also { it.items = songs; it.ink = Color.White } }
            // As BrowserScreen does: cards start from the selection host's drawn lift.
            SideEffect { state.hoverLift = { key -> selection.hover[key] } }
            val source = rememberFrostedBackdrop(Unit, dark = true)
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = .6f),
                LocalReducedMotion provides false) {
                Box(Modifier.size(360.dp, 520.dp).testTag("glow-scene").onSizeChanged {
                    state.viewportWidth = it.width; state.viewportHeight = it.height
                }) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(.3f, .3f, .3f)) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        // The Queue: light ink, its own selection host.
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            ConnectedSelectionHost(Modifier.fillMaxSize(), scene = selection) {
                                LazyVerticalGrid(GridCells.Fixed(1), state = grid, contentPadding = PaddingValues(top = 40.dp)) {
                                    items(state.rendered(songs), key = { it?.key ?: DropGapKey }) { item ->
                                        Box(Modifier.fillMaxWidth().height(72.dp)
                                            .selectionLens(selected && item?.key == songs.first().key, false, itemKey = item?.key))
                                    }
                                }
                            }
                        }
                        // The foreground host: the app's dark root ink.
                        CompositionLocalProvider(LocalContentColor provides Color(0xFF202124)) { SongDragOverlay(state, false) }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val rest = capture()
        val d = rest.width / 360f
        val held = compose.runOnIdle { state.bounds().getValue(songs.first().key) }
        // A band just below the lifted bottom edge, across the item's centre.
        val x0 = (held.center.x - 40 * d).toInt(); val x1 = (held.center.x + 40 * d).toInt()
        val y0 = (held.bottom + 6 * d).toInt(); val y1 = (held.bottom + 10 * d).toInt()
        fun signed(image: ImageBitmap): Float {
            val now = image.toPixelMap(); val base = rest.toPixelMap()
            var sum = 0f
            for (y in y0 until y1) for (x in x0 until x1) sum += lum(now[x, y]) - lum(base[x, y])
            return sum / ((x1 - x0) * (y1 - y0))
        }
        compose.runOnIdle { selected = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val lifted = signed(capture().also { save(it, "lifted") })
        assertTrue("Light ink selects with a white glow below the item: $lifted", lifted > .006f)
        val pickup = mutableListOf(lifted)
        val later = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        try {
            // Press → lift → drag start: the pickup frames, sampled one frame at a time.
            compose.runOnIdle { assertTrue(state.begin(songs.first(), setOf(songs.first().key), held.center)) }
            for (step in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                val image = capture()
                if (step < 3) save(image, "drag-$step")
                pickup += signed(image)
            }
            // Drag a little and back, then cancel: the card returns and hands off to the selected row.
            for (dy in listOf(8f, 16f, 8f, 0f)) {
                compose.runOnIdle { state.move(held.center.copy(y = held.center.y + dy * d)) }
                compose.mainClock.advanceTimeByFrame()
                later += signed(capture())
            }
            compose.runOnIdle { state.finish(cancel = true, reducedMotion = false) { _, _, _ -> } }
            for (step in 0 until 40) {
                compose.mainClock.advanceTimeByFrame()
                val image = capture()
                if (step == 39) save(image, "cancelled")
                later += signed(image)
            }
        } finally { compose.mainClock.autoAdvance = true }
        (pickup.drop(1) + later).forEachIndexed { i, value ->
            assertTrue("Drag frame $i keeps the white glow, never a dark shadow: pickup=$pickup later=$later", value > -.002f)
        }
        val jump = pickup.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max()
        assertTrue("The lift glow is continuous across press → lift → drag start: jump=$jump pickup=$pickup",
            jump <= maxOf(.012f, lifted * .6f))
    }

    private fun lum(c: Color) = .2126f * c.red + .7152f * c.green + .0722f * c.blue
    private fun capture(): ImageBitmap = compose.onNodeWithTag("glow-scene").captureToImage()

    private fun save(image: ImageBitmap, name: String) {
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        val output = File(root, "lift-glow-553").apply { mkdirs() }
        File(output, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
