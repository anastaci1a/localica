package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.LibraryUiState
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.QueueScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Bridges native playback, controller publications and the visible Queue; never publishes a fake cursor. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueFollowingPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val list get() = compose.onNodeWithTag("browser-list")
    private val currentReturn get() = compose.onNodeWithContentDescription("Current song")
    private fun row(index: Int) = compose.onNodeWithTag("media-follow-playback-$index")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private class Fixture(val player: ExoPlayer, val queues: QueueController, val entries: List<QueueEntry>) {
        // Mutated and inspected only on the player/application main thread.
        val transitions = mutableListOf<Pair<String?, Int>>()
        var rowTop = 0f
        fun id(index: Int) = entries[index].id
    }

    private fun fixture(reducedMotion: Boolean, test: (Fixture) -> Unit) {
        val preferencesName = "queue-follow-playback-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(preferencesName, mode)
        })
        preferences.write(QueueSettings(shuffle = false, repeat = QueueRepeat.Off))
        val file = File.createTempFile("queue-follow-playback-", ".mp3", context.cacheDir)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: ExoPlayer? = null
        var queues: QueueController? = null
        var mounted by mutableStateOf(true)
        var composed = false
        try {
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
            val track = Track(Uri.fromFile(file).toString(), "Repeated song", fileName = file.name, relativePath = file.name)
            val entries = List(120) { QueueEntry("follow-playback-$it", track) }
            val queue = ListeningQueue(id = preferencesName, name = "Playback following", entries = entries,
                currentEntryId = entries[45].id)
            runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences,
                    (context.applicationContext as LocalicaApplication).library, scope)
            }
            val native = requireNotNull(player)
            val controller = requireNotNull(queues)
            val fixture = Fixture(native, controller, entries)
            runBlocking { withTimeout(10_000) { controller.state.first { !it.loading } } }
            instrumentation.runOnMainSync {
                controller.attach(native)
                native.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        fixture.transitions += mediaItem?.mediaId to reason
                        // Stop on the destination so assertions cannot race a second short fixture track.
                        // The transition itself must already have happened in ExoPlayer.
                        native.pause()
                    }
                })
            }
            val library = LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false)
            val playlists = PlaylistState(loading = false)
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                if (mounted) QueueScreen(state, controller, library, playlists, reducedMotion = reducedMotion)
            } }
            composed = true
            awaitPlayback(fixture, 45)
            compose.waitUntil(10_000) { runCatching { row(45).assertIsDisplayed() }.isSuccess }
            compose.waitForIdle()
            fixture.rowTop = row(45).fetchSemanticsNode().boundsInRoot.top - list.fetchSemanticsNode().boundsInRoot.top
            assertTrue("Initial current row must be near the viewport top", fixture.rowTop >= 0f &&
                fixture.rowTop < list.fetchSemanticsNode().boundsInRoot.height / 2f)
            currentReturn.assertDoesNotExist()
            test(fixture)
        } finally {
            // Dispose collectors and UI before closing the isolated controller/database.
            if (composed) {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            }
            instrumentation.runOnMainSync {
                player?.let { native -> queues?.detach(native); native.release() }
            }
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
            file.delete()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private fun awaitPlayback(f: Fixture, index: Int) {
        compose.waitUntil(10_000) {
            var ready = false
            instrumentation.runOnMainSync {
                f.player.playerError?.let { throw AssertionError("Native playback failed", it) }
                ready = f.player.playbackState == Player.STATE_READY &&
                    f.player.currentMediaItem?.mediaId == f.id(index) &&
                    f.queues.state.value.library.active?.currentEntryId == f.id(index)
            }
            ready
        }
    }

    private fun awaitFollowed(f: Fixture, index: Int) {
        awaitPlayback(f, index)
        compose.waitUntil(10_000) {
            runCatching {
                row(index).assertIsDisplayed()
                abs(row(index).fetchSemanticsNode().boundsInRoot.top -
                    list.fetchSemanticsNode().boundsInRoot.top - f.rowTop) <= 1.1f
            }.getOrDefault(false)
        }
        compose.waitForIdle()
        currentReturn.assertDoesNotExist()
        // Same track ID appears everywhere; reaching the first match would be wrong.
        row(0).assertDoesNotExist()
    }

    private fun naturalAdvance(f: Fixture, from: Int) {
        awaitPlayback(f, from)
        instrumentation.runOnMainSync {
            assertEquals(f.id(from + 1), f.player.getMediaItemAt(f.player.nextMediaItemIndex).mediaId)
            assertTrue("Fixture must have a known playable duration", f.player.duration > 300)
            f.transitions.clear()
            f.player.seekTo(f.player.duration - 150)
            f.player.play()
        }
        awaitPlayback(f, from + 1)
        instrumentation.runOnMainSync {
            assertTrue("The duplicate must arrive by natural native advancement", f.transitions.contains(
                f.id(from + 1) to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
            assertFalse("Destination is paused to bound the fixture", f.player.playWhenReady)
        }
    }

    @Test fun naturalPlaybackAdvancesAndAlignsTheExactDuplicateOccurrence() = fixture(reducedMotion = false) { f ->
        val before = position()
        naturalAdvance(f, 45)
        awaitFollowed(f, 46)
        assertTrue("Following must move the viewport, not only its marker", position() > before)
        naturalAdvance(f, 46)
        awaitFollowed(f, 47)
    }

    @Test fun manualScrollDetachesAcrossTransportAndReturnReattachesForNaturalAdvancement() =
        fixture(reducedMotion = false) { f ->
            // A slow native pointer gesture avoids a lingering fling in the stationary assertion.
            list.performTouchInput {
                down(center)
                repeat(16) { step ->
                    moveTo(center.copy(y = centerY - height * .35f * (step + 1) / 16f), delayMillis = 50)
                }
                advanceEventTime(300)
                up()
            }
            compose.waitForIdle()
            currentReturn.assertIsDisplayed()
            val detached = position()
            naturalAdvance(f, 45)
            compose.waitForIdle()
            assertEquals("Natural advancement preserves a detached viewport", detached, position(), 0f)
            currentReturn.assertIsDisplayed()

            // Real command actor/native preparation, not a fabricated QueueUiState publication.
            f.queues.selectEntry(f.id(95))
            awaitPlayback(f, 95)
            instrumentation.runOnMainSync { f.player.pause() }
            compose.waitForIdle()
            assertEquals("Transport cannot reattach or move the detached viewport", detached, position(), 0f)
            row(95).assertDoesNotExist()
            f.queues.next()
            awaitPlayback(f, 96)
            instrumentation.runOnMainSync { f.player.pause() }
            compose.waitForIdle()
            assertEquals("Next also preserves detachment", detached, position(), 0f)
            row(96).assertDoesNotExist()
            currentReturn.assertIsDisplayed().performClick()
            awaitFollowed(f, 96)
            val returned = position()
            assertTrue("Return must leave the detached viewport", returned != detached)
            naturalAdvance(f, 96)
            awaitFollowed(f, 97)
            assertTrue("Subsequent advancement follows without another Return", position() > returned)
        }
}
