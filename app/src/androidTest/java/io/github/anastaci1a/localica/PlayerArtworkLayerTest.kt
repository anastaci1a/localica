package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.ArtworkLayout
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/** Compare actual player pixels with/without glyphs inside an opaque cover interior.
 * Geometry remains identical; the blank reference retains both text layout boxes. */
class PlayerArtworkLayerTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun movingCoverOccludesBothMetadataLinesAndKeepsSettledInk() {
        val progress = Animatable(0f)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        var reduced by mutableStateOf(false)
        var ink by mutableStateOf(true)
        lateinit var scope: CoroutineScope
        val visual = PlayerVisual(Bitmap.createBitmap(32, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }.asImageBitmap(), PlayerPalette(Color(0xFF303040), Color(0xFF303040), Color(0xFF303040), aspect = 1f / 3f))
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(PlaybackState(connected = true, title = if (ink) "MMMMMMMMMMMM" else "            ",
                artist = if (ink) "MMMMMMMMMMMM" else "            ", durationMs = 120000),
                progress.value == 1f, {}, InterfaceSettings(artworkLayout = layout, reducedMotion = reduced),
                progress, {}, {}, {}, {}, {}, {}, visual = visual)
        } } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "player-artwork-layer").apply { mkdirs() }
        val failures = mutableListOf<String>()
        val overlaps = mutableMapOf<String, Int>()
        fun bounds(tag: String): Rect {
            val coordinates = compose.onNodeWithTag(tag, true).fetchSemanticsNode().layoutInfo.coordinates
            return compose.runOnUiThread { coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false) }
        }
        fun capture(name: String): androidx.compose.ui.graphics.ImageBitmap {
            compose.waitForIdle()
            return compose.onRoot().captureToImage().also { image ->
                File(output, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        for (artworkLayout in ArtworkLayout.entries) for (reduce in listOf(false, true)) {
            compose.runOnIdle { layout = artworkLayout; reduced = reduce }
            compose.waitForIdle()
            // Includes expansion, contraction, a held reversal, and immediate endpoint completion.
            for ((index, p) in listOf(0f, .25f, .5f, .7f, .85f, .95f, 1f, .95f, .85f, .7f, .85f, .5f, .25f, 0f, 1f).withIndex()) {
                compose.runOnIdle {
                    ink = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                }
                compose.waitForIdle()
                val name = "$artworkLayout-reduced-$reduce-$index-p-$p"
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val cover = bounds("player-artwork")
                // Exclude rounded/feathered edges, where metadata legitimately shows through.
                val core = Rect(cover.left + cover.width * .28f, cover.top + cover.height * .28f,
                    cover.right - cover.width * .28f, cover.bottom - cover.height * .28f)
                val metadata = listOf("player-title", "player-artist").associateWith(::bounds)
                val shown = capture(name).toPixelMap()
                compose.runOnIdle { ink = false }
                val blank = capture("$name-blank").toPixelMap()
                assertEquals("Blank control must retain cover geometry", cover, bounds("player-artwork"))
                for ((tag, rect) in metadata) {
                    assertEquals("Blank control must retain $tag geometry", rect, bounds(tag))
                    val region = if (p > 0f && p < 1f) rect.intersect(core) else rect
                    var count = 0
                    var changed = 0
                    for (y in ceil(region.top - root.top).toInt().coerceAtLeast(0) until (region.bottom - root.top).toInt().coerceAtMost(shown.height))
                        for (x in ceil(region.left - root.left).toInt().coerceAtLeast(0) until (region.right - root.left).toInt().coerceAtMost(shown.width)) {
                            count++
                            val a = shown[x, y]; val b = blank[x, y]
                            if (maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) > .08f) changed++
                        }
                    if (p > 0f && p < 1f && count > 40) {
                        overlaps[tag] = (overlaps[tag] ?: 0) + 1
                        if (changed > 2) failures += "$name $tag: $changed/$count glyph pixels above cover"
                    } else if (p == 0f || p == 1f) {
                        assertTrue("Settled $name retains visible $tag ink ($changed/$count)", changed > 5)
                    }
                }
            }
        }
        File(output, "overlaps.txt").writeText("overlaps=$overlaps\n" + failures.joinToString("\n"))
        assertTrue("Fixture must exercise both text lines: $overlaps", listOf("player-title", "player-artist").all { (overlaps[it] ?: 0) >= 4 })
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
