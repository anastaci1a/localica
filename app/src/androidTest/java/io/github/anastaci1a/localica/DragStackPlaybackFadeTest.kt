package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DragStackPlaybackFadeTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun queueMarkerFadesAtPickupWithoutFollowingTheStack() = checkFade(false)

    @Test fun queueMarkerPreservesItsLiftedPickupContourWhileFading() = checkFade(true)

    private fun checkFade(lifted: Boolean) {
        lateinit var state: SongDragState
        val songs = (0..3).map { BrowserItem(MediaRef(MediaKind.Song, "$it"), "Song $it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            state = remember { SongDragState(grid, scope).also {
                it.items = songs; it.currentSongKey = songs.first().key
            } }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val pickup = with(density) { hoverGeometry(
                MediaItemOutline(androidx.compose.ui.geometry.Rect(40.dp.toPx(), 20.dp.toPx(), 140.dp.toPx(), 120.dp.toPx()), 4.dp.toPx()),
                androidx.compose.ui.geometry.Rect(4.dp.toPx(), 4.dp.toPx(), 4.dp.toPx(), 4.dp.toPx()), 1f) }
            SideEffect {
                if (lifted) {
                    state.hoverLift = { key -> pickup.takeIf { key == songs.first().key } }
                    state.colors = androidx.compose.material3.lightColorScheme(primary = Color.Red)
                }
            }
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(360.dp, 480.dp).testTag("marker-scene").onSizeChanged {
                state.viewportWidth = it.width; state.viewportHeight = it.height
            }) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                LazyVerticalGrid(GridCells.Fixed(1), state = grid) {
                    items(state.rendered(songs), key = { it?.key ?: DropGapKey }) { item ->
                        Box(Modifier.fillMaxWidth().height(if (lifted) 144.dp else 72.dp)
                            .currentSongHighlight(item?.key == songs.first().key,
                                if (lifted) Color.Red else MaterialTheme.colorScheme.primary,
                                outline = { _, _ -> pickup.lifted.takeIf { lifted } }))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) { SongDragOverlay(state, false) }
            }
        } }
        compose.waitForIdle()
        val image = compose.onNodeWithTag("marker-scene").captureToImage()
        val d = image.width / 360f
        // Sample above the moving card's shadow, which crosses the row centre during pickup.
        val sampleX = ((if (lifted) 100 else 300) * d).toInt()
        val sampleY = ((if (lifted) 18 else 12) * d).toInt()
        val initial = image.toPixelMap()[sampleX, sampleY].green
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                assertTrue(state.begin(songs.first(), songs.take(3).map { it.key }.toSet(), state.bounds().getValue(songs.first().key).center))
                state.move(Offset(180 * d, 350 * d))
            }
            val samples = mutableListOf<Float>()
            for (step in listOf(16L, 64L, 80L, 160L)) {
                compose.mainClock.advanceTimeBy(step)
                val frame = compose.onNodeWithTag("marker-scene").captureToImage()
                val pixels = frame.toPixelMap()
                val markerPixel = pixels[sampleX, sampleY]
                samples += markerPixel.green
                if (lifted && samples.size == 1) assertTrue(
                    "Pickup retains the tinted lifted edge outside the rest outline: $markerPixel",
                    markerPixel.red - markerPixel.green > .07f)
                val args = InstrumentationRegistry.getArguments()
                val root = args.getString("additionalTestOutputDir")?.let(::File)
                    ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
                val output = File(root, "drag-stack-557").apply { mkdirs() }
                File(output, "marker-${if (lifted) "lifted-" else ""}${samples.size}-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                    frame.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val destination = pixels[(300 * d).toInt(), (350 * d).toInt()]
                assertTrue("The playback tint does not travel with the pane: $destination",
                    if (lifted) destination.red - destination.green < .03f else destination.green > .97f)
            }
            assertTrue("Pickup preserves the visible marker before fading: $initial -> $samples", samples.first() < .98f)
            assertTrue("An intermediate frame retains a partial marker: $samples", samples[1] > samples.first() + .005f && samples[1] < .99f)
            samples.zipWithNext().forEach { (a, b) -> assertTrue("Fade never reverses: $samples", b >= a - .005f) }
            assertTrue("The original marker finishes fading: $samples", samples.last() > .99f)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
