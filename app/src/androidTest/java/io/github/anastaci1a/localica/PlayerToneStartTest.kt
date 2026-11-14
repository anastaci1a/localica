package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Real external play requests, embedded cover decoding and LocalicaScreen expansion ownership. */
class PlayerToneStartTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Volatile private var ink: PlayerInkComposition? = null

    private fun covered(name: String, color: Int): Track {
        val file = File(context.cacheDir, "tone-969-$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        TagCodec.edit(file, TagPatch(artwork = ArtworkChange.Replace(CoverImage(bytes, "image/png", 64, 64))))
        return Track(file.toURI().toString(), "Tone $name", artist = "Localica", fileName = file.name, relativePath = file.name)
    }

    private fun run(external: Boolean) {
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val dark = covered("dark", android.graphics.Color.BLACK)
        val bright = covered("bright", android.graphics.Color.WHITE)
        val playback = compose.runOnUiThread { PlaybackConnection(context) }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Songs), expandOnPlay = true, respectSystemAnimationScale = false))
            compose.waitUntil(15_000) { playback.state.value.connected && !playback.queues.state.value.loading }
            val initial = if (external) dark else bright
            compose.runOnUiThread { playback.playFromList(listOf(initial), initial.id) }
            compose.waitUntil(15_000) { playback.state.value.trackId == initial.id && playback.state.value.isPlaying }
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalPlayerInkCompositionObserver provides { ink = it }) {
                    val state by playback.state.collectAsState()
                    val queues by playback.queues.state.collectAsState()
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(dark, bright)), loading = false),
                        state, {}, playback::toggle, playback::seekTo, {}, {}, {}, queues = queues,
                        queueController = playback.queues)
                }
            } }
            compose.waitUntil(15_000) { ink?.frame?.roles?.any { it.visual.artwork != null } == true }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val values = mutableListOf<Float>()
            try {
                if (external) compose.runOnUiThread { playback.playFromList(listOf(bright), bright.id) }
                else compose.onNodeWithTag("player-surface").performTouchInput { click(center) }
                repeat(120) {
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    Thread.sleep(10)
                    values += requireNotNull(ink).contentColor.red
                }
                compose.onAllNodesWithContentDescription("Collapse player").onFirst().assertIsDisplayed()
                assertEquals(bright.id, playback.state.value.trackId)
                assertEquals("Full player uses the new bright cover's dark ink: $values", 0f, values.last(), .01f)
                if (!external) assertTrue("First expansion never heads toward the cold white default: $values", values.all { it < .03f })
                values.zipWithNext().forEach { (a, b) -> assertTrue("Tone snaps: $values", abs(a - b) < .3f) }
                val output = File(context.getExternalFilesDir(null), "tone-969").apply { mkdirs() }
                File(output, if (external) "external.csv" else "cold.csv").writeText(values.joinToString("\n"))
                val image = compose.onNodeWithContentDescription("Pause").captureNativeFrame()
                File(output, if (external) "external.png" else "cold.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { compose.mainClock.autoAdvance = true }
        } finally {
            compose.runOnUiThread { playback.clear(); playback.close() }
            preferences.write(saved)
            listOf(dark, bright).forEach { File(java.net.URI(it.uri)).delete() }
        }
    }

    @Test fun firstExpansionAfterOpeningTheAppStartsWithTheDecodedArtworkTone() = run(false)
    @Test fun externalPlayWithAutoExpandUsesTheNewArtworkToneAndNeverSnaps() = run(true)
}
