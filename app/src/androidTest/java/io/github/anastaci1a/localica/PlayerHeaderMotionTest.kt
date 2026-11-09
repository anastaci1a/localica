package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Identical reference/candidate fixture: paired artifacts decide drift and exact settle equality. */
@SdkSuppress(minSdkVersion = 33)
class PlayerHeaderMotionTest {
    @get:Rule val compose = localicaComposeRule()
    private data class Phase(val name: String, val progress: Float, val lyrics: Boolean = false, val exact: Boolean = false)

    @Test fun capturesFirstOpenReversalAndFirstSettledHeaderFrames() {
        val covers = BackdropPerfCovers.realistic() + BackdropPerfCovers.adversarial()
        val prepared = covers.map { cover ->
            cover to PlayerVisual(artwork = cover.bitmap.asImageBitmap(), palette = runBlocking { playerPalette(cover.bitmap) },
                contentKey = "perf-fixture-v1-${cover.name}")
        }
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val directory = File(output, "header-motion-api${Build.VERSION.SDK_INT}").also { it.mkdirs() }
        var selected by mutableIntStateOf(0)
        var expanded by mutableStateOf(false)
        var lyrics by mutableStateOf(false)
        lateinit var progress: Animatable<Float, *>
        lateinit var scope: CoroutineScope
        val observations = ArrayList<GlassSurfaceDrawObservation>()
        val receipt = JSONArray()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
            LocalGlassSurfaceDrawObserver provides { observations += it }) {
            key(selected) {
                progress = remember { Animatable(0f) }
                scope = rememberCoroutineScope()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    PlayerSurface(PlaybackState(connected = true, title = "Fixture ${covers[selected].name}",
                        artist = "Generated performance fixture", durationMs = 180000), expanded, { expanded = it },
                        InterfaceSettings(reducedMotion = true, glass = GlassAppearance(contrast = 0f)), progress,
                        {}, {}, {}, {}, {}, {}, lyricsOpen = lyrics, onLyrics = { lyrics = it }, visual = prepared[selected].second)
                }
            }
        } } }
        val phases = listOf(Phase("first-open-071", .71f), Phase("open-075", .75f), Phase("open-080", .8f),
            Phase("open-090", .9f), Phase("open-099", .99f), Phase("open-0995", .995f),
            Phase("first-settled", 1f, exact = true), Phase("reverse-0995", .995f), Phase("reverse-085", .85f),
            Phase("reverse-075", .75f), Phase("reopen-090", .9f), Phase("reopen-0995", .995f),
            Phase("reversed-settled", 1f, exact = true), Phase("lyrics-intent", .995f, lyrics = true, exact = true),
            Phase("lyrics-settled", 1f, lyrics = true, exact = true))
        try {
            for ((index, entry) in prepared.withIndex()) {
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { selected = index; expanded = false; lyrics = false }
                compose.waitForIdle()
                // A first drag opens from a fresh closed mount, before any settled scene
                // has existed. Snap positions after the initial closed animation ends.
                compose.mainClock.autoAdvance = false
                for (phase in phases) {
                    observations.clear()
                    compose.runOnIdle {
                        lyrics = phase.lyrics
                        if (phase.name == "first-settled") expanded = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.stop(); progress.snapTo(phase.progress) }
                    }
                    compose.mainClock.advanceTimeByFrame()
                    val player = compose.onNodeWithTag("player-surface")
                    val image = player.captureToImage()
                    val bounds = player.fetchSemanticsNode().boundsInRoot
                    val name = "${entry.first.name}-${phase.name}.png"
                    File(directory, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val left = compose.onNodeWithContentDescription("Collapse player").fetchSemanticsNode().boundsInRoot
                    val right = compose.onNodeWithTag("player-lyrics-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    fun rect(r: androidx.compose.ui.geometry.Rect) = JSONArray(listOf(r.left - bounds.left, r.top - bounds.top,
                        r.right - bounds.left, r.bottom - bounds.top))
                    val draws = JSONArray()
                    for (draw in observations) draw.source?.let { source ->
                        draws.put(JSONObject().put("surface", draw.surfaceIdentity).put("source", source.identity)
                            .put("layer", source.layerIdentity).put("recorded", source.recorded).put("released", source.released)
                            .put("consumers", source.consumers).put("retainedRaster", source.retainedRaster)
                            .put("sourceWidth", source.width).put("sourceHeight", source.height)
                            .put("layerWidth", source.layerWidth).put("layerHeight", source.layerHeight)
                            .put("rootCorners", JSONArray(draw.rootCorners ?: emptyList<Float>()))
                            .put("requestedReadback", source.requestedReadback).put("publishedReadback", source.publishedReadback))
                    }
                    assertTrue("Frame must include source draw receipts: ${entry.first.name}/${phase.name}", draws.length() > 0)
                    receipt.put(JSONObject().put("fixture", entry.first.name).put("classification", entry.first.classification)
                        .put("phase", phase.name).put("progress", phase.progress).put("lyricsOpen", phase.lyrics)
                        .put("requiresExact", phase.exact).put("file", name).put("width", image.width).put("height", image.height)
                        .put("playerBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                        .put("leftHeader", rect(left)).put("rightHeader", rect(right)).put("draws", draws))
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            File(directory, "frames.json").writeText(JSONObject().put("fixtureVersion", BackdropPerfCovers.VERSION)
                .put("api", Build.VERSION.SDK_INT).put("frames", receipt).put("pairedComparisonRequired", true).toString(2))
        }
    }
}
