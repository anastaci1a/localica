package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerArtworkInitializationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun publishedFreshRestoredAndSwitchedQueuesKeepTheirFirstArtworkVisit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val queuePreferences = QueuePreferences(context)
        val savedQueues = queuePreferences.read()
        val file = File.createTempFile("initial-artwork-", ".mp3", context.cacheDir)
        copyTestAsset(instrumentation.context.assets, "tagged.mp3", file)
        val song = Track(file.toURI().toString(), "Initial native", fileName = file.name, relativePath = file.name,
            id = "initial-native", durationMs = 20000)
        lateinit var player: ExoPlayer
        lateinit var database: QueueDatabase
        lateinit var scope: CoroutineScope
        lateinit var queues: QueueController
        var controllerOpen = false
        var attached = false
        var displayed by mutableStateOf<QueueController?>(null)
        var native by mutableStateOf(PlaybackState())
        var latest: ArtworkBackgroundFrame? = null
        var progress = 0f
        var observing = false
        val blends = linkedSetOf<Any>()
        val publications = CopyOnWriteArrayList<Pair<String, String?>>()
        fun frames(count: Int = 80) {
            repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        }
        fun sample(): PlaybackState {
            val item = requireNotNull(player.currentMediaItem)
            val extras = requireNotNull(item.mediaMetadata.extras)
            return PlaybackState(connected = true, trackId = extras.getString("trackId"),
                trackUri = extras.getString("trackUri"), title = item.mediaMetadata.title.toString(),
                playbackVisit = item.queueVisit, positionMs = player.currentPosition, durationMs = 20000)
        }
        fun openLibrary(library: QueueLibrary) {
            database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            runBlocking { database.queues().save(QueueLibrary(), library) }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            instrumentation.runOnMainSync {
                queues = QueueController(database.queues(), queuePreferences,
                    (context.applicationContext as LocalicaApplication).library, scope)
                queues.state.onEach { state -> if (!state.loading) state.library.active?.let {
                    publications += it.id to it.journey?.current?.token
                } }.launchIn(scope)
                displayed = queues
            }
            controllerOpen = true
            compose.waitUntil(15000) { !queues.state.value.loading }
        }
        fun close(): ListeningQueue {
            controllerOpen = false
            if (attached) {
                runBlocking { withContext(Dispatchers.Main) { queues.detach(player) }.await() }
                attached = false
            }
            val current = queues.state.value.library.active!!
            runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
            database.close()
            return current
        }
        fun attach(token: String? = null) {
            instrumentation.runOnMainSync { queues.attach(player); attached = true }
            compose.waitUntil(15000) {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = player.playbackState == Player.STATE_READY && player.currentMediaItem?.queueVisit != null &&
                        (token == null || player.currentMediaItem?.queueVisit == token)
                }
                ready
            }
        }
        instrumentation.runOnMainSync { player = ExoPlayer.Builder(context).build().apply { volume = 0f } }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home), reducedMotion = false))
            queuePreferences.write(QueueSettings(switching = QueueSwitch.Paused))
            val visual = runBlocking { PlayerVisualCache.read(context, song.uri) }
            assertNotNull(visual.artwork)
            val initial = QueueEdits.create(listOf(song, song), 0, "Initial native")
            openLibrary(QueueLibrary(listOf(initial), initial.id))
            attach()
            instrumentation.runOnMainSync { native = sample() }
            compose.setContent { EngineTheme {
                val source by displayed!!.presentation.collectAsState()
                CompositionLocalProvider(LocalPlayerInkCompositionObserver provides { sample ->
                    latest = sample.frame
                    if (observing && sample.frame?.transitioning == true)
                        sample.frame.roles.filter { it.incoming && !it.soft }.forEach { blends += it.identity }
                }, LocalPlayerWorkObserver provides { kind, value -> if (kind == "root" && value != null) progress = value }) {
                    LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, queues = source)
                }
            } }
            compose.waitUntil(10000) { latest?.roles?.any { it.visual.artwork != null } == true }
            compose.mainClock.autoAdvance = false
            frames()
            for (kind in listOf("fresh", "restored", "switched")) for (expanded in listOf(false, true)) {
                if (expanded && progress == 0f) compose.onNodeWithTag("player-surface").performClick()
                if (!expanded && progress == 1f) compose.onNodeWithContentDescription("Collapse player").performClick()
                frames()
                assertEquals(if (expanded) 1f else 0f, progress, 0f)
                compose.runOnIdle { observing = false; blends.clear() }
                val previous = close()
                val nextSong = song.copy(id = "$kind-$expanded", title = "$kind $expanded")
                val cold = QueueEdits.create(listOf(nextSong, nextSong), 0, nextSong.title)
                assertNull("No target journey is seeded by the fixture", cold.journey)
                val inactive = QueueEdits.create(listOf(song), 0, "Cold inactive")
                publications.clear()
                if (kind == "restored") compose.runOnIdle { observing = true }
                openLibrary(when (kind) {
                    "restored" -> QueueLibrary(listOf(cold, inactive), cold.id)
                    "switched" -> QueueLibrary(listOf(previous, cold), previous.id)
                    else -> QueueLibrary(listOf(previous), previous.id)
                })
                if (kind != "restored") {
                    frames()
                    if (kind == "switched") assertNull(queues.state.value.library.queues.single { it.id == cold.id }.journey)
                    compose.runOnIdle {
                        observing = true
                        if (kind == "fresh") queues.tap(listOf(nextSong, nextSong), 0)
                        else queues.switchTo(cold.id)
                    }
                }
                compose.waitUntil(15000) { queues.state.value.library.active?.entries?.firstOrNull()?.track?.id == nextSong.id }
                val published = queues.state.value.library.active!!
                val token = published.journey?.current?.token
                assertNotNull("First nonempty active publication owns its visit ($kind, expanded=$expanded)", token)
                assertTrue(publications.any { it.first == published.id })
                assertTrue("No identityless publication", publications.filter { it.first == published.id }.all { it.second == token })
                if (kind == "restored") assertNull(queues.state.value.library.queues.single { it.id == inactive.id }.journey)
                frames()
                assertFalse(latest!!.transitioning)
                assertEquals("First queued preview blends once ($kind, expanded=$expanded)", 1, blends.size)
                assertEquals(visual.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
                attach(token)
                compose.runOnIdle { native = sample() }
                frames()
                assertFalse(latest!!.transitioning)
                assertEquals("Native attachment cannot initialize another artwork occurrence", 1, blends.size)
                assertEquals(token, native.playbackVisit)
                assertEquals(token, queues.state.value.library.active!!.journey!!.current!!.token)
                assertTrue(publications.filter { it.first == published.id }.all { it.second == token })
                android.util.Log.i("ArtworkInitialization1056", "kind=$kind expanded=$expanded token=$token publications=${publications.size} blends=${blends.size}")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            try { if (controllerOpen) close() } finally {
                instrumentation.runOnMainSync { player.release() }
                preferences.write(saved)
                queuePreferences.write(savedQueues)
                file.delete()
            }
        }
    }
}
