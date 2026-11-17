package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

class PlayerArtworkPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realPlaybackKeepsArtworkSettledThroughTwelveCursorSaves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val file = File.createTempFile("playing-artwork-", ".mp3", context.cacheDir)
        copyTestAsset(instrumentation.context.assets, "queue-tone.mp3", file)
        val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(128 * 128) { pixel ->
            android.graphics.Color.rgb(pixel % 128 * 2, pixel / 128 * 2, 160)
        }, 0, 128, 0, 0, 128, 128)
        val bytes = java.io.ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)) }
        finally { bitmap.recycle() }
        io.github.anastaci1a.localica.editing.TagCodec.edit(file,
            io.github.anastaci1a.localica.editing.TagPatch(artwork = io.github.anastaci1a.localica.editing.ArtworkChange.Replace(
                io.github.anastaci1a.localica.editing.CoverImage(bytes.toByteArray(), "image/png", 128, 128))))
        val song = Track(file.toURI().toString(), "playing-artwork", fileName = file.name, relativePath = file.name, id = "playing-artwork", durationMs = 20000)
        lateinit var connection: io.github.anastaci1a.localica.playback.PlaybackConnection
        val future = runBlocking(Dispatchers.Main) {
            connection = io.github.anastaci1a.localica.playback.PlaybackConnection(context)
            androidx.media3.session.MediaController.Builder(context, androidx.media3.session.SessionToken(context,
                android.content.ComponentName(context, io.github.anastaci1a.localica.playback.PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        var oldSpeed = 1f
        var oldVolume = 1f
        val ownedQueues = mutableSetOf<String>()
        val positions = linkedSetOf<Long>()
        val blends = linkedSetOf<Any>()
        var latest: ArtworkBackgroundFrame? = null
        var progress = 0f
        var observing = false
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home), reducedMotion = false))
            assertNotNull(runBlocking { PlayerVisualCache.read(context, song.uri) }.artwork)
            compose.setContent { EngineTheme {
                val playback = rememberPlaybackObservation(connection.state.collectAsState())
                val queues by connection.queues.presentation.collectAsState()
                SideEffect {
                    queues.library.active?.takeIf { it.entries.any { entry -> entry.track.id == song.id } }?.let {
                        ownedQueues += it.id
                        if (observing) positions += it.positionMs
                    }
                }
                CompositionLocalProvider(LocalPlayerInkCompositionObserver provides { sample ->
                    latest = sample.frame
                    if (observing && sample.frame?.transitioning == true)
                        sample.frame.roles.filter { it.incoming && !it.soft }.forEach { blends += it.identity }
                }, LocalPlayerWorkObserver provides { kind, value -> if (kind == "root" && value != null) progress = value }) {
                    LocalicaScreen(LibraryUiState(loading = false), playback.screen.value, connection::play, connection::toggle,
                        connection::seekTo, {}, {}, {}, queues = queues, queueController = connection.queues,
                        onPrevious = connection::previous, onNext = connection::next, playbackSample = playback.sample)
                }
            } }
            compose.runOnIdle {
                oldSpeed = controller.playbackParameters.speed
                oldVolume = controller.volume
                controller.volume = 0f
                // The existing twenty-second fixture lasts one hundred seconds at this native playback rate.
                controller.setPlaybackSpeed(.2f)
                connection.playFromList(listOf(song, song), song.id)
            }
            compose.waitUntil(20000) { connection.state.value.isPlaying && connection.state.value.trackId == song.id &&
                latest?.roles?.any { it.visual.artwork != null } == true && latest?.transitioning == false }
            val initial = connection.state.value
            assertEquals(.2f, initial.playbackSpeed, 0f)
            val visit = initial.playbackVisit
            assertNotNull(visit)
            compose.runOnIdle { blends.clear(); positions.clear(); observing = true }
            val started = SystemClock.elapsedRealtime()
            for (expanded in listOf(false, true)) {
                if (expanded) {
                    compose.onNodeWithTag("player-surface").performClick()
                    compose.waitUntil(10000) { progress == 1f }
                }
                val until = SystemClock.elapsedRealtime() + 33000
                compose.waitUntil(40000) {
                    compose.waitForIdle()
                    assertTrue("Playback stays active throughout the observation", connection.state.value.isPlaying)
                    assertEquals(visit, connection.state.value.playbackVisit)
                    assertEquals("Cursor saves must not blur the current cover (expanded=$expanded)", 0, blends.size)
                    SystemClock.elapsedRealtime() >= until
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue(elapsed >= 66000)
            assertTrue("At least twelve real timer publications were observed: $positions", positions.size >= 12)
            assertTrue(connection.state.value.positionMs > initial.positionMs + 12000)
            assertEquals(1f, latest!!.clock, 0f)
            val receipt = JSONObject().put("elapsedMs", elapsed).put("playbackSpeed", initial.playbackSpeed)
                .put("cursorPositions", org.json.JSONArray(positions.toList())).put("blendAdmissions", blends.size)
                .put("visit", visit).put("api", android.os.Build.VERSION.SDK_INT)
            File(nativeEvidenceDirectory("artwork-reload-1056", context.getExternalFilesDir(null)), "playback.json").writeText(receipt.toString(2))
            println("ArtworkReload1056 $receipt")
            compose.runOnIdle { connection.next() }
            compose.waitUntil(15000) { connection.state.value.playbackVisit != visit && latest?.transitioning == false && blends.isNotEmpty() }
            assertEquals("A real next to a duplicate cover admits exactly one blend", 1, blends.size)
            compose.runOnIdle { observing = false }
        } finally {
            compose.runOnIdle {
                observing = false
                controller.pause()
                controller.setPlaybackSpeed(oldSpeed)
                controller.volume = oldVolume
                connection.clear()
                connection.queues.removeQueues(ownedQueues)
                connection.close()
                controller.release()
            }
            preferences.write(saved)
            file.delete()
        }
    }
}
