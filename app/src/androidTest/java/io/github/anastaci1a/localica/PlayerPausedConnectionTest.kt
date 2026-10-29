package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.documentIdentity
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.PreviousBehavior
import io.github.anastaci1a.localica.queue.QueuePreferences
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.queue.SongTap
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.PlayerSurface
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real connection -> service/Media3 -> published state -> displayed PlayerSurface transport.
 * Assertions cover collected publications and settled presentation checkpoints, not every
 * native event/frame or audible output. No decoder delay forces a buffering interval.
 */
class PlayerPausedConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun miniPlayerRealPausedSkipsKeepTheirVisitsAndPlayGlyph() = pausedSkips(false)
    @Test fun fullPlayerRealPausedSkipsKeepTheirVisitsAndPlayGlyph() = pausedSkips(true)

    private fun <T> main(block: () -> T): T = compose.runOnUiThread(block)

    private fun await(description: String, condition: () -> Boolean) {
        try { compose.waitUntil(15_000) { main(condition) } }
        catch (failure: Throwable) { throw AssertionError(description, failure) }
    }

    private fun actorBarrier(playback: PlaybackConnection) {
        val applied = AtomicReference<Boolean?>()
        // Existing completion seam: this non-UUID cannot equal a generated visit token.
        // The actor rejects it before native seek, without touching pending creation.
        main { playback.queues.requestSeek(0, "paused-connection-cleanup-not-a-visit") { accepted, _ -> applied.set(accepted) } }
        await("Queued fixture commands must finish before cleanup") { applied.get() != null }
        assertEquals("Cleanup barrier must not seek any playback", false, applied.get())
    }

    private fun pausedSkips(expanded: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as LocalicaApplication
        val audio = File.createTempFile("paused-connection-", ".mp3", context.cacheDir)
        val uri = audio.toURI().toString()
        val track = Track(uri, "Paused connection", fileName = audio.name, relativePath = audio.name, id = uri)
        var connection: PlaybackConnection? = null
        var savedSettings: QueueSettings? = null
        var existingIds = emptySet<String>()
        var creationRequested = false
        var mounted by mutableStateOf(true)
        var observing = false
        val publications = mutableListOf<PlaybackState>()
        val presentations = mutableListOf<PlaybackState>()
        var nextCalls = 0
        var previousCalls = 0
        var toggleCalls = 0
        var failure: Throwable? = null
        try {
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> audio.outputStream().use(input::copyTo) }
            val playback = main { PlaybackConnection(context) }.also { connection = it }
            val queues = playback.queues
            await("Real service connection and queue restoration") { playback.state.value.connected && !queues.state.value.loading }
            main {
                assertNull("Do not consume another owner's pending creation", queues.state.value.pending)
                assertNull("Queue setup must be healthy", queues.state.value.problem)
                savedSettings = queues.state.value.settings
                existingIds = queues.state.value.library.queues.map { it.id }.toSet()
                // Keep traversal modes and unrelated queue data; bypass tap policy explicitly.
                queues.settings(savedSettings!!.copy(maximum = null, previousBehavior = PreviousBehavior.PreviousSong))
            }
            compose.setContent { LocalicaTheme {
                if (mounted) {
                    val state by playback.state.collectAsState()
                    LaunchedEffect(playback) {
                        playback.state.collect { if (observing) publications += it }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Observe in the same scope that reads and supplies the player state.
                        val presented = state
                        SideEffect { if (observing) presentations += presented }
                        PlayerSurface(presented, expanded, {}, InterfaceSettings(),
                            remember { Animatable(if (expanded) 1f else 0f) },
                            { toggleCalls++; playback.toggle() }, playback::seekTo,
                            { previousCalls++; playback.previous() }, { nextCalls++; playback.next() }, {}, {})
                    }
                }
            } }
            val created = AtomicReference<Boolean?>()
            main {
                creationRequested = true
                // Same file/song, three distinct occurrences: track ID alone cannot pass.
                queues.act(List(3) { track }, SongTap.NewQueue) { created.set(it) }
            }
            await("Owned queue must be accepted and actually playing before Pause") {
                assertNull(playback.state.value.problem)
                assertNull(queues.state.value.problem)
                created.get() == true && playback.state.value.trackId == uri && playback.state.value.isPlaying &&
                    !playback.state.value.isBuffering && !playback.state.value.ended
            }
            val ownedQueue = main { requireNotNull(queues.state.value.library.active) }
            assertFalse(existingIds.contains(ownedQueue.id))
            assertEquals(3, ownedQueue.entries.map { it.id }.distinct().size)
            assertEquals(List(3) { uri }, ownedQueue.entries.map { it.track.id })
            compose.waitForIdle()
            assertTransportGlyph(play = false, expanded = expanded)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
            await("Displayed Pause must publish paused intent on the real connection") {
                assertNull(playback.state.value.problem)
                val state = playback.state.value
                state.trackId == uri && !state.playWhenReady && !state.isPlaying && !state.isBuffering && !state.ended
            }
            main { observing = true; publications += playback.state.value }
            val initialVisit = main { requireNotNull(playback.state.value.playbackVisit) }
            compose.waitForIdle()
            assertTransportGlyph(play = true, expanded = expanded)

            for (direction in listOf("Next", "Next", "Previous", "Previous")) {
                val expected = main {
                    val active = requireNotNull(queues.state.value.library.active)
                    assertEquals(ownedQueue.id, active.id)
                    val journey = requireNotNull(active.journey)
                    requireNotNull(if (direction == "Next") journey.next else journey.previous).also {
                        assertNotEquals("Each command must leave the current duplicate occurrence", active.currentEntryId, it.entryId)
                        assertNotEquals(journey.current?.token, it.token)
                    }
                }
                compose.onNodeWithContentDescription(direction).assertIsDisplayed().assertIsEnabled().performClick()
                await("$direction must publish exact occurrence ${expected.entryId} / visit ${expected.token} paused") {
                    assertNull(playback.state.value.problem)
                    assertNull(queues.state.value.problem)
                    val state = playback.state.value
                    val active = queues.state.value.library.active
                    active?.id == ownedQueue.id && active.currentEntryId == expected.entryId &&
                        active.journey?.current?.token == expected.token &&
                        queues.playbackSample()?.playbackVisit == expected.token && state.playbackVisit == expected.token &&
                        state.connected && state.trackId == uri && !state.playWhenReady && !state.isPlaying &&
                        !state.isBuffering && !state.ended
                }
                compose.waitForIdle()
                assertTransportGlyph(play = true, expanded = expanded)
                main {
                    assertEquals(expected.token, playback.state.value.playbackVisit)
                    assertEquals(ownedQueue.entries.map { it.id }, queues.state.value.library.active!!.entries.map { it.id })
                    assertTrue("Observed connection publications cannot request or report playback", publications.all {
                        !it.playWhenReady && !it.isPlaying && !it.ended && it.problem == null
                    })
                    assertTrue("Observed compositions retain paused intent", presentations.all { !it.playWhenReady && !it.isPlaying })
                    assertTrue("Exact visit reached the rendered player's composition", presentations.any { it.playbackVisit == expected.token })
                }
            }
            main {
                assertEquals(2, nextCalls); assertEquals(2, previousCalls); assertEquals(1, toggleCalls)
                assertEquals("Two Previous commands return to the original visit", initialVisit, playback.state.value.playbackVisit)
                assertTrue(publications.isNotEmpty()); assertTrue(presentations.isNotEmpty())
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (caught: Throwable) { cleanupFailures += caught } }
            var unmounted = false
            var removed = !creationRequested
            cleanup { main { observing = false; mounted = false }; compose.waitForIdle(); unmounted = true }
            connection?.let { playback ->
                val queues = playback.queues
                var drained = false
                cleanup { actorBarrier(playback); drained = true }
                if (drained) cleanup {
                    main {
                        val owned = queues.state.value.library.queues.filter { queue ->
                            queue.entries.isNotEmpty() && queue.entries.all { it.track.id == uri && it.track.uri == uri }
                        }.map { it.id }.toSet() - existingIds
                        // Removing an active queue otherwise activates an unrelated retained queue.
                        if (queues.state.value.library.activeId in owned) playback.clear()
                        if (owned.isNotEmpty()) queues.removeQueues(owned)
                    }
                    actorBarrier(playback)
                    await("Owned playback must be released before deleting its file") {
                        playback.state.value.trackUri != uri && queues.state.value.library.queues.none { queue ->
                            queue.entries.any { it.track.id == uri }
                        }
                    }
                    removed = true
                }
                savedSettings?.let { original -> cleanup {
                    main { queues.settings(original) }
                    actorBarrier(playback)
                    main {
                        assertEquals(original, queues.state.value.settings)
                        assertEquals(original, QueuePreferences(context).read())
                        assertTrue("Preserve every unrelated retained queue", queues.state.value.library.queues.map { it.id }.containsAll(existingIds))
                    }
                } }
                cleanup { main { playback.close() } }
            }
            if (removed && unmounted) cleanup {
                val block = app.nativeReaders.block(documentIdentity(uri))
                try {
                    runBlocking { withTimeout(15_000) { block.awaitClosed() } }
                    assertTrue("Delete only the closed owned fixture: $audio", audio.delete() || !audio.exists())
                } finally { block.release() }
            }
            // On unconfirmed command/reader completion retain the owned file rather than
            // race a late decoder open. Report cleanup failures without hiding the first failure.
            if (cleanupFailures.isNotEmpty()) {
                val first = failure ?: cleanupFailures.removeAt(0)
                cleanupFailures.forEach(first::addSuppressed)
                if (failure == null) throw first
            }
        }
    }

    private fun assertTransportGlyph(play: Boolean, expanded: Boolean) {
        val label = if (play) "Play" else "Pause"
        val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription(if (play) "Pause" else "Play").assertDoesNotExist()
        val pixels = node.captureToImage().toPixelMap()
        val side = with(compose.density) { (if (expanded) 40.dp else 24.dp).toPx() }
        fun pixel(x: Float, y: Float): Color {
            val px = ((pixels.width - side) / 2f + side * x).roundToInt().coerceIn(0, pixels.width - 1)
            val py = ((pixels.height - side) / 2f + side * y).roundToInt().coerceIn(0, pixels.height - 1)
            return pixels[px, py]
        }
        fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        // Both contours fill the left stem. Only Play fills its center; Pause leaves a
        // center gap. Compare to this actual surface's nearby backdrop, not a fake icon.
        val ink = pixel(.28f, .5f)
        val backdrop = pixel(.08f, .5f)
        val center = pixel(.5f, .5f)
        assertTrue("Transport witness needs visible contrast", distance(ink, backdrop) > .35f)
        assertTrue("Actual $label center contour", distance(center, if (play) ink else backdrop) < .15f)
        assertTrue("Reject the opposite transport contour", distance(center, if (play) backdrop else ink) > .2f)
    }
}
