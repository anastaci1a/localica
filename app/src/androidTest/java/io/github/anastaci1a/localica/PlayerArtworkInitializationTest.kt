package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerArtworkInitializationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freshAndRestoredPreTokenPreviewsKeepOneInitializationBlend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val file = File.createTempFile("initial-artwork-", ".mp3", context.cacheDir)
        copyTestAsset(instrumentation.context.assets, "tagged.mp3", file)
        val song = Track(file.toURI().toString(), "Initial preview", fileName = file.name, relativePath = file.name, id = "initial-preview")
        val oldNative = PlaybackState(connected = true, trackId = "old-native", trackUri = song.uri,
            title = "Old native", playbackVisit = "old-native-visit", durationMs = 120000)
        var native by mutableStateOf(oldNative)
        var source by mutableStateOf(QueueUiState.Empty)
        var scenario by mutableIntStateOf(0)
        var latest: ArtworkBackgroundFrame? = null
        var observing = false
        val blends = linkedSetOf<Any>()
        fun frames(count: Int = 80) {
            repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        }
        fun settled() {
            assertNotNull(latest)
            assertFalse(latest!!.transitioning)
            assertTrue(latest!!.roles.any { it.incoming && it.visual.artwork != null })
        }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home), reducedMotion = false))
            val visual = runBlocking { PlayerVisualCache.read(context, song.uri) }
            assertNotNull(visual.artwork)
            compose.setContent { key(scenario) { EngineTheme {
                CompositionLocalProvider(LocalPlayerInkCompositionObserver provides { sample ->
                    latest = sample.frame
                    if (observing && sample.frame?.transitioning == true)
                        sample.frame.roles.filter { it.incoming && !it.soft }.forEach { blends += it.identity }
                }) {
                    LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, queues = source)
                }
            } } }
            compose.mainClock.autoAdvance = false
            for (restored in listOf(false, true)) for (nativeFirst in listOf(false, true)) for (expanded in listOf(false, true)) {
                val fresh = QueueEdits.create(listOf(song), 0, "Initial preview")
                val created = QueueLibrary(listOf(fresh), fresh.id)
                val library = if (!restored) created else {
                    val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
                    try { runBlocking { database.queues().save(QueueLibrary(), created); database.queues().read() } }
                    finally { database.close() }
                }
                val before = library.active!!
                assertNull("A fresh/restored queue reaches the existing pre-token boundary", before.journey)
                compose.runOnIdle {
                    observing = false
                    native = oldNative
                    source = QueueUiState(library, loading = false).resolvePlayerQueue()
                    scenario++
                }
                frames()
                compose.onNodeWithText(song.title).assertExists()
                if (expanded) { compose.onNodeWithTag("player-surface").performClick(); frames() }
                settled()
                val initialized = before.copy(journey = QueueJourney.start(before.ids, before.currentEntryId, JourneyModes()))
                fun publishQueue() { compose.runOnIdle {
                    source = source.copy(library = library.update(initialized)).resolvePlayerQueue()
                } }
                fun publishNative() { compose.runOnIdle {
                    native = oldNative.copy(trackId = song.id, title = song.title,
                        playbackVisit = initialized.journey!!.current!!.token)
                } }
                compose.runOnIdle { blends.clear(); observing = true }
                if (nativeFirst) publishNative() else publishQueue()
                frames()
                val firstDelivery = blends.size
                if (nativeFirst) publishQueue() else publishNative()
                frames()
                settled()
                assertEquals("Existing initialization count: restored=$restored nativeFirst=$nativeFirst expanded=$expanded", 1, blends.size)
                assertEquals(visual.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
                android.util.Log.i("ArtworkInitialization1056", "restored=$restored nativeFirst=$nativeFirst expanded=$expanded firstDelivery=$firstDelivery total=${blends.size}")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.write(saved)
            file.delete()
        }
    }
}
