package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * #532 pixel acceptance on the real queue browser: the current-occurrence frost (the shared
 * item outline) contains the artwork and its corner is concentric with the artwork corner,
 * in every list size with artwork and every gallery size, in both directions.
 */
class ItemOutlinePixelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentOutlineContainsConcentricArtworkLeftToRight() = outlines(false)
    @Test fun currentOutlineContainsConcentricArtworkRightToLeft() = outlines(true)

    private fun outlines(rtl: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = BrowsePreferences(context)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "outline-geometry"))
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val tracks = (0..11).map { Track("outline:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        val playing = "outline:0"
        val queue = ListeningQueue(id = "outline-geometry", name = "Outline geometry",
            entries = tracks.map { QueueEntry(id = it.id, track = it) }, currentEntryId = playing)
        val queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val styles = listOf(BrowseStyle(listSize = ListSize.Compact), BrowseStyle(listSize = ListSize.Normal),
            BrowseStyle(listSize = ListSize.Large)) + (1..5).map { BrowseStyle(presentation = Presentation.Standard, itemsPerRow = it) }
        val output = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null)))
            .let { File(it, "item-outlines-${if (rtl) "rtl" else "ltr"}") }.apply { mkdirs() }
        prefs.write(route, styles.first(), overridden)
        val failures = mutableListOf<String>()
        try {
            compose.setContent { EngineTheme(reduced = true) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Magenta)) {
                        Box(Modifier.size(360.dp, 640.dp).testTag("outline-scene")) {
                            BrowserScreen(library, PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                                queueState = queueState, playingEntryId = playing, reducedMotion = true,
                                queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                        }
                    }
                }
            } }
            for (style in styles) {
                compose.runOnIdle { prefs.write(route, style, overridden) }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("media-$playing").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("browser-list").performScrollToIndex(0)
                compose.waitForIdle()
                val label = "${style.presentation}/${style.listSize}/${style.itemsPerRow} rtl=$rtl"
                failures += measure(style, label, output)
            }
        } finally { prefs.write(route, original, overridden) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun measure(style: BrowseStyle, label: String, output: File): List<String> {
        val scene = compose.onNodeWithTag("outline-scene").fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithTag("media-outline:0").fetchSemanticsNode().boundsInRoot
        val artBounds = compose.onNodeWithTag("browse-art-outline:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pixels = compose.onNodeWithTag("outline-scene").captureToImage().also { image ->
            File(output, label.replace(Regex("[^A-Za-z0-9]+"), "-") + ".png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.toPixelMap()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val columns = if (style.presentation == Presentation.List) 1 else requireNotNull(style.itemsPerRow)
        val inset = (BrowseCellGeometry(columns).contentInset * density).roundToInt()
        val metrics = BrowseItemMetrics(style, columns, (media.width.roundToInt() - 2 * inset).coerceAtLeast(0), density, false)
        val radius = metrics.artCorner
        val art = artBounds.translate(-scene.topLeft)
        val rtl = label.endsWith("rtl=true")
        fun frost(x: Float, y: Float): Boolean {
            val px = x.roundToInt(); val py = y.roundToInt()
            if (px !in 0 until pixels.width || py !in 0 until pixels.height) return false
            val c = pixels[px, py]
            return c.red - c.green > .05f && c.blue - c.green > .05f
        }
        val problems = mutableListOf<String>()
        // List rows test both leading corners; gallery text sits below the artwork.
        val corners = if (style.presentation == Presentation.List) listOf(true, false) else listOf(true)
        for (top in corners) {
            val centre = Offset(if (rtl) art.right - radius else art.left + radius, if (top) art.top + radius else art.bottom - radius)
            val gaps = (0..9).map { step ->
                val angle = Math.PI / 2 * step / 9
                val direction = Offset((if (rtl) 1f else -1f) * kotlin.math.cos(angle).toFloat(),
                    (if (top) -1f else 1f) * kotlin.math.sin(angle).toFloat())
                // Start just outside the artwork's own antialiased edge and walk out of the frost.
                var distance = radius + 1.5f
                while (distance < radius + 40f * density && frost(centre.x + direction.x * distance, centre.y + direction.y * distance)) distance += .5f
                distance - (radius + 1.5f)
            }
            val corner = if (top) "top" else "bottom"
            if (gaps.min() < 1f) problems += "$label $corner: artwork reaches outside the outline (gaps px=$gaps)"
            if (gaps.max() - gaps.min() > 2.5f) problems += "$label $corner: corner is not concentric (gaps px=$gaps)"
        }
        return problems
    }
}
