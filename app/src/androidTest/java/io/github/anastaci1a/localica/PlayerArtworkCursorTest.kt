package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.github.anastaci1a.localica.queue.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PlayerArtworkCursorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentVisitArtworkDoesNotRestartAcrossPendingCursorPublications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val file = File.createTempFile("cursor-artwork-", ".mp3", context.cacheDir)
        copyTestAsset(instrumentation.context.assets, "tagged.mp3", file)
        val replacementFile = File.createTempFile("replacement-artwork-", ".png",
            File(context.filesDir, "playlist-covers").apply { mkdirs() })
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        try { replacementFile.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val song = Track(file.toURI().toString(), "cursor-artwork", fileName = file.name, relativePath = file.name, id = "cursor-artwork")
        val replacementSong = Track(replacementFile.toURI().toString(), "replacement-artwork", fileName = replacementFile.name, relativePath = replacementFile.name, id = "replacement-artwork", durationMs = 120000)
        val entries = ProjectionCounted(List(3) { QueueEntry("entry-$it", song) })
        val original = ListeningQueue(id = "cursor-artwork", name = "Artwork", entries = entries,
            currentEntryId = "entry-0").let { it.copy(journey = QueueJourney.start(it.ids, it.currentEntryId, JourneyModes())) }
        var source by mutableStateOf(QueueUiState(QueueLibrary(listOf(original), original.id), loading = false).resolvePlayerQueue())
        var native by mutableStateOf(io.github.anastaci1a.localica.playback.PlaybackState(connected = true,
            trackId = song.id, trackUri = song.uri, title = song.title, durationMs = 120000,
            playbackVisit = original.journey!!.current!!.token))
        var latest: ArtworkBackgroundFrame? = null
        var progress = 0f
        var observing = false
        val blends = linkedSetOf<Any>()
        val seeks = mutableListOf<Pair<Long, String?>>()
        val hook = AtomicReference<(() -> Unit)?>(null)
        entries.beforeRead = {
            if (Thread.currentThread().stackTrace.any { it.className == "io.github.anastaci1a.localica.ui.PlayerTrackProjectionKt" && it.methodName == "prepareQueuePresentation" })
                hook.getAndSet(null)?.invoke()
        }
        fun frames(count: Int = 50) {
            repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        }
        fun settled() {
            assertNotNull(latest)
            assertFalse("Unchanged artwork must stay settled", latest!!.transitioning)
            assertEquals(1f, latest!!.clock, 0f)
            assertTrue(latest!!.roles.any { it.incoming && it.visual.artwork != null })
        }
        fun publish(queue: ListeningQueue) {
            compose.runOnIdle { source = source.copy(library = source.library.update(queue)).resolvePlayerQueue() }
        }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home), reducedMotion = false))
            val ready = runBlocking { PlayerVisualCache.read(context, song.uri) }
            assertNotNull("The fixture has real decoded cover pixels", ready.artwork)
            val replacementVisual = runBlocking { PlayerVisualCache.read(context, replacementSong.uri) }
            assertNotEquals(ready.contentKey, replacementVisual.contentKey)
            compose.setContent { EngineTheme {
                CompositionLocalProvider(LocalPlayerInkCompositionObserver provides { sample ->
                    latest = sample.frame
                    if (observing && sample.frame?.transitioning == true)
                        sample.frame.roles.filter { it.incoming && !it.soft }.forEach { blends += it.identity }
                }, LocalPlayerWorkObserver provides { kind, value -> if (kind == "root" && value != null) progress = value }) {
                    LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, queues = source,
                        onSeekRequest = { target, visit -> seeks += target to visit; seeks.size.toLong() })
                }
            } }
            compose.waitUntil(10000) { latest?.roles?.any { it.visual.artwork != null } == true }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            frames()
            settled()
            for (expanded in listOf(false, true)) {
                if (expanded) { compose.onNodeWithTag("player-surface").performClick(); frames(80); assertEquals(1f, progress, 0f) }
                compose.runOnIdle { blends.clear(); observing = true }
                repeat(3) { index ->
                    val queue = source.library.active!!
                    publish(queue.copy(positionMs = queue.positionMs + 5000))
                    frames()
                    settled()
                    assertEquals("A cursor save must not admit a blend (expanded=$expanded, save=$index)", 0, blends.size)
                }
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val queue = source.library.active!!
                // Equal visit ownership with fresh prepared windows makes the async gap deterministic.
                val pending = queue.copy(positionMs = queue.positionMs + 5000,
                    journey = queue.journey!!.copy().reconcile(queue.ids, queue.currentEntryId, JourneyModes()))
                assertEquals(queue.journey!!.current, pending.journey!!.current)
                hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                try {
                    publish(pending)
                    frames(1)
                    assertTrue("Preparation reaches the controlled pending window", started.await(10, TimeUnit.SECONDS))
                    frames()
                    settled()
                    assertEquals("Pending preparation keeps the same visit without blur", 0, blends.size)
                } finally { release.countDown(); hook.set(null) }
                frames()
                settled()
                assertEquals("Completing preparation cannot start a second blur", 0, blends.size)
                assertSame("Cursor publications retain the prepared cover", ready.artwork, PlayerVisualCache.peek(song.uri)?.artwork)
                compose.runOnIdle { observing = false }
            }
            compose.runOnIdle { blends.clear(); observing = true }
            val queue = source.library.active!!
            val next = queue.journey!!.next!!
            publish(queue.copy(currentEntryId = next.entryId, positionMs = 0,
                journey = queue.journey.arrive(next.token, queue.ids)))
            frames()
            assertEquals("A repeated track keeps its native occurrence until native arrival", 0, blends.size)
            compose.runOnIdle { native = native.copy(playbackVisit = next.token) }
            frames(80)
            settled()
            assertEquals("A different occurrence with identical pixels still has exactly one blend", 1, blends.size)

            val replacementEntries = ProjectionCounted(List(3) { QueueEntry("replacement-entry-$it", replacementSong) })
            replacementEntries.beforeRead = entries.beforeRead
            val replacement = ListeningQueue(id = "replacement", name = "Replacement", entries = replacementEntries,
                currentEntryId = "replacement-entry-0").let { it.copy(journey = QueueJourney.start(it.ids, it.currentEntryId, JourneyModes())) }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            try {
                compose.runOnIdle {
                    blends.clear()
                    source = source.copy(library = QueueLibrary(listOf(replacement), replacement.id)).resolvePlayerQueue()
                }
                frames(1)
                assertTrue("Replacement presentation is held while native playback still owns the old song", started.await(10, TimeUnit.SECONDS))
                frames()
                assertEquals("A new queue token cannot start a blend of the old native artwork", 0, blends.size)
            } finally { release.countDown(); hook.set(null) }
            frames(80)
            settled()
            assertEquals("The prepared replacement admits exactly one blend", 1, blends.size)
            assertEquals(replacementVisual.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
            compose.onNodeWithContentDescription("Collapse player").performClick()
            frames(80)
            for (expanded in listOf(false, true)) {
                if (expanded) { compose.onNodeWithTag("player-surface").performClick(); frames(80) }
                if (expanded) {
                    compose.onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(45000f)) }
                    frames(1)
                    assertEquals("Preview seeks remain bound to the native occurrence", 45000L to native.playbackVisit, seeks.last())
                }
                val preview = source.library.active!!
                assertNotNull("The counted preparation fixture has a neighbor to read", preview.journey!!.next)
                val preparing = CountDownLatch(1)
                val finish = CountDownLatch(1)
                hook.set { preparing.countDown(); check(finish.await(30, TimeUnit.SECONDS)) }
                try {
                    publish(preview.copy(positionMs = preview.positionMs + 5000,
                        journey = preview.journey!!.copy().reconcile(preview.ids, preview.currentEntryId, JourneyModes())))
                    frames(1)
                    assertTrue("Preview cursor preparation is held", preparing.await(10, TimeUnit.SECONDS))
                    frames()
                    settled()
                    assertEquals("A pending preview cursor keeps its pixels", replacementVisual.contentKey,
                        latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
                    assertEquals("Preview cursor publication adds no blend (expanded=$expanded)", 1, blends.size)
                    if (expanded) assertEquals("Pending preparation retains the committed seek", 45000f,
                        compose.onNodeWithContentDescription("Seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
                } finally { finish.countDown(); hook.set(null) }
                frames()
                settled()
                assertEquals("Completing preview preparation adds no blend", 1, blends.size)
                if (expanded) assertEquals("Completing preparation retains the committed seek", 45000f,
                    compose.onNodeWithContentDescription("Seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
            }
            compose.runOnIdle {
                native = native.copy(trackId = replacementSong.id, trackUri = replacementSong.uri,
                    title = replacementSong.title, playbackVisit = replacement.journey!!.current!!.token)
            }
            frames(80)
            settled()
            assertEquals("Matching native ownership cannot blend the prepared replacement again", 1, blends.size)

            compose.runOnIdle {
                blends.clear()
                native = native.copy(trackId = song.id, trackUri = song.uri, title = song.title,
                    playbackVisit = original.journey!!.current!!.token)
            }
            frames()
            assertEquals("Native arrival cannot replace an admitted queued preview early", 0, blends.size)
            compose.runOnIdle {
                source = source.copy(library = QueueLibrary(listOf(original), original.id)).resolvePlayerQueue()
            }
            frames(80)
            settled()
            assertEquals("Native-first queue handoff admits exactly one displayed change", 1, blends.size)
            assertEquals(ready.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
            val equalCoverSong = song.copy(id = "distinct-track-equal-cover", title = "Equal cover")
            val equalCover = QueueEdits.create(listOf(equalCoverSong), 0, "Equal cover").let {
                it.copy(journey = QueueJourney.start(it.ids, it.currentEntryId, JourneyModes()))
            }
            compose.runOnIdle {
                blends.clear()
                source = source.copy(library = QueueLibrary(listOf(equalCover), equalCover.id)).resolvePlayerQueue()
            }
            frames(80)
            assertEquals("Distinct track IDs sharing pixels still blend once", 1, blends.size)
            assertEquals(ready.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
            compose.runOnIdle {
                native = native.copy(trackId = equalCoverSong.id, title = equalCoverSong.title,
                    playbackVisit = equalCover.journey!!.current!!.token)
            }
            frames(80)
            settled()
            assertEquals("Equal-cover native receipt does not add a second blend", 1, blends.size)
        } finally {
            hook.set(null)
            compose.mainClock.autoAdvance = true
            preferences.write(saved)
            file.delete()
            replacementFile.delete()
        }
    }
}
