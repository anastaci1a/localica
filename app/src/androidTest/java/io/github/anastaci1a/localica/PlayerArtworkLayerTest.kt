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

/** Neutral metadata below a red cover preserves its red-minus-green chroma, including
 * feathered pixels. Neutral ink above it reduces that chroma. Blank text keeps geometry. */
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
        // #678 instrumentation (log only): where the method's time goes.
        val methodStart = System.nanoTime()
        var boundsNs = 0L; var captureNs = 0L; var pngNs = 0L; var loopNs = 0L; var settleNs = 0L
        fun bounds(tag: String): Rect {
            val t = System.nanoTime()
            val coordinates = compose.onNodeWithTag(tag, true).fetchSemanticsNode().layoutInfo.coordinates
            return compose.runOnUiThread { coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false) }
                .also { boundsNs += System.nanoTime() - t }
        }
        fun capture(name: String): androidx.compose.ui.graphics.ImageBitmap {
            val t = System.nanoTime()
            compose.waitForIdle()
            return compose.onRoot().captureToImage().also { image ->
                captureNs += System.nanoTime() - t
                val p = System.nanoTime()
                File(output, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                pngNs += System.nanoTime() - p
            }
        }
        for (artworkLayout in ArtworkLayout.entries) for (reduce in listOf(false, true)) {
            compose.runOnIdle { layout = artworkLayout; reduced = reduce }
            compose.waitForIdle()
            // Includes expansion, contraction, a held reversal, and immediate endpoint completion.
            for ((index, p) in listOf(0f, .25f, .5f, .7f, .85f, .95f, 1f, .95f, .85f, .7f, .85f, .5f, .25f, 0f, 1f).withIndex()) {
                val settleStart = System.nanoTime()
                compose.runOnIdle {
                    ink = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
                }
                compose.waitForIdle()
                settleNs += System.nanoTime() - settleStart
                val name = "$artworkLayout-reduced-$reduce-$index-p-$p"
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val cover = bounds("player-artwork")
                // Keep feathering in the assertion; omit only the rounded outside perimeter.
                val core = Rect(cover.left + cover.width * .03f, cover.top + cover.height * .03f,
                    cover.right - cover.width * .03f, cover.bottom - cover.height * .03f)
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
                    val loopStart = System.nanoTime()
                    for (y in ceil(region.top - root.top).toInt().coerceAtLeast(0) until (region.bottom - root.top).toInt().coerceAtMost(shown.height))
                        for (x in ceil(region.left - root.left).toInt().coerceAtLeast(0) until (region.right - root.left).toInt().coerceAtMost(shown.width)) {
                            val a = shown[x, y]; val b = blank[x, y]
                            if (p > 0f && p < 1f) {
                                if (b.red - b.green <= .15f) continue
                                count++
                                if (kotlin.math.abs((a.red - a.green) - (b.red - b.green)) > .04f) changed++
                            } else {
                                count++
                                if (maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) > .08f) changed++
                            }
                        }
                    loopNs += System.nanoTime() - loopStart
                    android.util.Log.i("Trim678", "$name $tag region=$region count=$count changed=$changed")
                    if (p > 0f && p < 1f && count > 40) {
                        overlaps[tag] = (overlaps[tag] ?: 0) + 1
                        if (changed > 2) failures += "$name $tag: $changed/$count glyph pixels above cover"
                    } else if (p == 0f || p == 1f) {
                        assertTrue("Settled $name retains visible $tag ink ($changed/$count)", changed > 5)
                    }
                }
            }
        }
        android.util.Log.i("Trim678", "PlayerArtworkLayerTest totalMs=${(System.nanoTime() - methodStart) / 1_000_000} settleMs=${settleNs / 1_000_000} " +
            "boundsMs=${boundsNs / 1_000_000} captureMs=${captureNs / 1_000_000} pngMs=${pngNs / 1_000_000} loopMs=${loopNs / 1_000_000}")
        File(output, "overlaps.txt").writeText("overlaps=$overlaps\n" + failures.joinToString("\n"))
        assertTrue("Fixture must exercise both text lines: $overlaps", listOf("player-title", "player-artist").all { (overlaps[it] ?: 0) >= 4 })
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
