package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #826: with shuffle turned on mid-queue, the full player's Previous button and artwork swipe walk the queue order
 * behind the shuffle-start song on the real controller and ExoPlayer; Next then retraces and resumes the shuffle.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShufflePreviousPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = QueuePreferences(context)
    private val original = preferences.read()
    private val file = File(context.cacheDir, "shuffle-previous-tone.mp3")
    private val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var queues: QueueController
    private lateinit var ids: List<String>

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun journey() = queues.state.value.library.active!!.journey!!
    private fun entries(journey: QueueJourney) = journey.history.map { ids.indexOf(it.entryId) }
    private fun at(index: Int) = await(timeoutMs = 15_000) {
        var ready = false
        main {
            player.playerError?.let { throw AssertionError("Native playback failed", it) }
            ready = player.currentMediaItem?.mediaId == ids[index] &&
                queues.state.value.library.active?.currentEntryId == ids[index] && player.playbackState == Player.STATE_READY
        }
        ready
    }
    private fun visual(color: Color) = PlayerVisual(
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toArgb()) }.asImageBitmap(),
        PlayerPalette(color, color, color))

    @Test fun previousButtonAndSwipeFollowTheQueueOrderBehindTheShuffleStartAndNextResumesTheShuffle() {
        try {
            preferences.write(QueueSettings(shuffle = false, repeat = QueueRepeat.Off))
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
            val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
            var queue = QueueEdits.create(List(7) { song }, 0, "Shuffle previous")
            ids = queue.entries.map { it.id }
            queue = queue.copy(currentEntryId = ids[4], positionMs = 1000,
                journey = QueueJourney.start(ids, ids[4], JourneyModes()))
            runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            main {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            at(4)

            // Shuffle on mid-queue: the journey restarts at the playing song, and its way back is the queue order.
            queues.shuffle()
            await(timeoutMs = 15_000) { queues.state.value.settings.shuffle && journey().modes.shuffle }
            val anchor = journey().current!!
            assertEquals(listOf(4), entries(journey()))
            assertEquals(ids[3], journey().previous?.entryId)
            main {
                val index = player.currentMediaItemIndex
                assertTrue("The unplayed previous song is already in the native timeline", index > 0)
                assertEquals(journey().previous!!.token, player.getMediaItemAt(index - 1).queueVisit)
            }

            val red = visual(Color.Red)
            compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
                val state by queues.state.collectAsState()
                val shown = state.library.active?.journey
                PlayerSurface(PlaybackState(connected = true, title = "Shuffle previous", durationMs = 120000), true, {},
                    InterfaceSettings(), remember { Animatable(1f) }, {}, {}, { queues.previous() }, { queues.next() }, {}, {},
                    visual = red, artworkVisit = shown?.current?.token,
                    previousArtwork = shown?.previous?.let { ArtworkNeighbor(it.token, red) },
                    nextArtwork = shown?.next?.let { ArtworkNeighbor(it.token, red) },
                    onArtworkVisit = { token, complete -> queues.swipeTo(token, complete) })
            } } }

            // The Previous button: before this change it only restarted the song, leaving the player where it was.
            compose.onNodeWithContentDescription("Previous").performClick()
            at(3)
            assertEquals(listOf(3, 4), entries(journey()))
            assertEquals(0, journey().cursor)
            assertEquals(ids[2], journey().previous?.entryId)

            // The artwork swipe applies the same rule: dragging right commits the previewed previous song.
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("artwork-gesture").assertExists() }.isSuccess }
            compose.onNodeWithTag("artwork-gesture").performTouchInput {
                swipe(Offset(width * .2f, centerY), Offset(width * .8f, centerY), 300)
            }
            at(2)
            assertEquals(listOf(2, 3, 4), entries(journey()))
            assertEquals(setOf(ids[4], ids[3], ids[2]), journey().visited)

            // Next retraces the songs walked back through, and only then resumes the shuffle without replaying them.
            compose.onNodeWithContentDescription("Next").performClick()
            at(3)
            compose.onNodeWithContentDescription("Next").performClick()
            at(4)
            assertEquals(anchor, journey().current)
            val resumed = journey().next!!.entryId
            assertFalse("The shuffle never replays a song reached by going back", resumed in setOf(ids[2], ids[3], ids[4]))
            compose.onNodeWithContentDescription("Next").performClick()
            await(timeoutMs = 15_000) { queues.state.value.library.active?.currentEntryId == resumed }
            assertEquals(listOf(2, 3, 4, ids.indexOf(resumed)), entries(journey()))
        } finally {
            main {
                if (::queues.isInitialized && ::player.isInitialized) queues.detach(player)
                if (::player.isInitialized) player.release()
                scope.cancel()
            }
            db.close(); file.delete(); preferences.write(original)
        }
    }
}
