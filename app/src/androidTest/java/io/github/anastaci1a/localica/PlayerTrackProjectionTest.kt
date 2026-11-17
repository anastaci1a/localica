package io.github.anastaci1a.localica

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PlayerTrackProjectionTest {
    @get:Rule val compose = createComposeRule()
    private fun track(id: String, title: String = id) = Track("content://$id", title,
        fileName = "$id.mp3", relativePath = "$id.mp3", id = id)

    private class Counted<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        val mainReads = AtomicInteger()
        var beforeRead: (() -> Unit)? = null
        override val size get() = values.size
        override fun get(index: Int): T {
            reads++
            if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet()
            beforeRead?.invoke()
            return values[index]
        }
    }

    @Test fun positionOnlyPublicationsReuseOccurrenceAndLibraryLookups() {
        val tracks = Counted(List(13_313) { track("$it") })
        val saved = track("13312", "Saved")
        val entries = Counted(List(100) { QueueEntry("entry-$it", saved) })
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = entries, currentEntryId = "entry-99")
        val state = mutableStateOf(QueueLibrary(listOf(queue), queue.id))
        val library = LibrarySnapshot(tracks = tracks)
        var publishedPosition = -1L
        var observed: PlayerTrackProjection? = null
        compose.setContent {
            val current = state.value
            val source = remember(current) { QueueUiState(current, loading = false) }
            val result = rememberQueuePresentation(source, library).value
            SideEffect { observed = result?.tracks; publishedPosition = result?.active?.queue?.positionMs ?: -1 }
        }
        compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); observed != null }
        compose.waitForIdle()
        val initial = requireNotNull(observed)
        compose.runOnIdle { tracks.reads = 0; entries.reads = 0 }
        repeat(10) { index ->
            val position = (index + 1) * 250L
            compose.runOnIdle { state.value = QueueLibrary(listOf(queue.copy(positionMs = position)), queue.id) }
            compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); publishedPosition == position }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(position, publishedPosition)
                assertSame(initial, observed)
                assertEquals("No repeated library scan during position updates", 0, tracks.reads)
                assertEquals("No repeated occurrence scan during position updates", 0, entries.reads)
            }
        }
        assertEquals("13312", initial.indexed!!.title)
        assertEquals("Saved", initial.current!!.title)
    }

    @Test fun occurrenceAndMetadataChangesPreserveFirstMatchAndDistinctUnavailableFallbacks() {
        val savedA = track("same", "Saved A").copy(playCount = 90)
        val savedB = track("same", "Saved B").copy(playCount = 80)
        val queue = ListeningQueue(id = "queue", name = "Queue", entries = listOf(
            QueueEntry("a", savedA), QueueEntry("b", savedB)), currentEntryId = "a")
        val queues = mutableStateOf(QueueLibrary(listOf(queue), queue.id))
        val library = mutableStateOf(LibrarySnapshot(trackPlays = mapOf("same" to 4L)))
        var observed: PlayerTrackProjection? = null
        compose.setContent {
            val source = remember(queues.value) { QueueUiState(queues.value, loading = false) }
            val result = rememberQueuePresentation(source, library.value).value
            SideEffect { observed = result?.tracks }
        }
        fun assertProjection(current: Track?, indexed: Track?, lyrics: Track?) {
            compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); observed == PlayerTrackProjection(current, indexed, lyrics) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(PlayerTrackProjection(current, indexed, lyrics), observed) }
        }
        assertProjection(savedA, savedA.copy(playCount = 4), savedA)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue.copy(currentEntryId = "b")), queue.id) }
        assertProjection(savedB, savedB.copy(playCount = 4), savedB)
        val first = track("same", "First available").copy(playCount = 7)
        val last = first.copy(title = "Last duplicate")
        compose.runOnIdle { library.value = LibrarySnapshot(tracks = listOf(first, last)) }
        assertProjection(savedB, first, first)
        val edited = first.copy(title = "Edited", durationMs = 1234)
        compose.runOnIdle { library.value = library.value.copy(tracks = listOf(edited, last)) }
        assertProjection(savedB, edited, edited)
        compose.runOnIdle { library.value = LibrarySnapshot(trackPlays = mapOf("same" to 9L)) }
        assertProjection(savedB, savedB.copy(playCount = 9), savedB)
        compose.runOnIdle { library.value = LibrarySnapshot() }
        assertProjection(savedB, savedB.copy(playCount = 0), savedB)
        // Malformed duplicate occurrence/queue identities retain the old first-match rules.
        val duplicates = queue.copy(entries = listOf(QueueEntry("b", savedA), QueueEntry("b", savedB)), currentEntryId = "b")
        compose.runOnIdle { queues.value = QueueLibrary(listOf(duplicates, queue.copy(currentEntryId = "b")), queue.id) }
        assertProjection(savedA, savedA.copy(playCount = 0), savedA)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue.copy(currentEntryId = "absent")), queue.id) }
        assertProjection(null, null, null)
        compose.runOnIdle { queues.value = QueueLibrary(listOf(queue), "absent") }
        assertProjection(null, null, null)
    }

    @Test fun queueScreenColdLoadingBecomesTheActualReadyHeaderAndRows() {
        val songs = List(3) { track("ready-$it").copy(durationMs = 1000) }
        val entries = Counted(songs.mapIndexed { index, song -> QueueEntry("ready-$index", song) })
        val queue = ListeningQueue(id = "ready", name = "Ready", entries = entries, currentEntryId = "ready-1")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hook = AtomicReference<(() -> Unit)?>({ started.countDown(); check(release.await(30, TimeUnit.SECONDS)) })
        entries.beforeRead = { hook.getAndSet(null)?.invoke() }
        var source by mutableStateOf(runBlocking(Dispatchers.Default) {
            QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false).resolvePlayerQueue()
        })
        compose.setContent { EngineTheme(reduced = true) { QueueScreen(source, null, reducedMotion = true) } }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            compose.onNodeWithTag("async-section-loading").assertExists()
            compose.onNodeWithText("2 / 3").assertDoesNotExist()
            compose.onNodeWithContentDescription("Save queue to playlist").assertIsDisplayed().assertIsEnabled()
            assertEquals("Save is available while entry preparation is still blocked", 1L, release.count)
            assertEquals(0, entries.mainReads.get())
            release.countDown()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1. Ready").assertExists()
            compose.onNodeWithText("0:03").assertExists()
            compose.onNodeWithText(songs[0].title).assertExists()
            compose.runOnIdle { source = source.copy(library = QueueLibrary(listOf(queue.copy(currentEntryId = "ready-2")), queue.id)) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("0:03").assertExists()
        } finally { release.countDown(); hook.set(null) }
    }

    @Test fun queueScreenColdLoadingWaitsForTheSharedNormalMotionHandoff() {
        val songs = List(3) { track("motion-$it").copy(durationMs = 1000) }
        val queue = QueueEdits.create(songs, 0, "Motion queue")
        val source = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val ready = runBlocking { withContext(Dispatchers.Default) { prepareQueuePresentation(source, LibrarySnapshot(), null) } }
        val result = mutableStateOf<QueuePresentation?>(null)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { EngineTheme {
                CompositionLocalProvider(LocalReducedMotion provides false) { QueueScreen(source, null, presentation = result) }
            } }
            compose.onNodeWithTag("async-section-loading").assertExists()
            compose.runOnIdle { result.value = ready }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("async-section-loading").assertExists()
            compose.onNodeWithText("1. Motion queue").assertDoesNotExist()
            compose.onNodeWithText(songs[0].title).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(1920)
            compose.onNodeWithTag("async-section-loading").assertDoesNotExist()
            compose.onNodeWithText("1. Motion queue").assertExists()
            compose.onNodeWithText("1 / 3").assertExists()
            try {
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithText(songs[0].title).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(songs[0].title).assertExists()
            } catch (failure: Throwable) {
                println(compose.onRoot(true).printToString())
                throw failure
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun pendingUnrelatedPublicationKeepsTheExpandedSavedPreviewAndOpenDrawer() = assertUnrelatedPublications(null)
    @Test fun unrelatedPublicationsKeepTheAlreadyOpenInformationDialog() = assertUnrelatedPublications(PlayerAction.Info)
    @Test fun unrelatedPublicationsKeepTheAlreadyOpenLyrics() = assertUnrelatedPublications(PlayerAction.Lyrics)

    private fun assertUnrelatedPublications(mode: PlayerAction?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val actionPreferences = PlayerActionPreferences(context)
        val savedActions = actionPreferences.read()
        val file = File.createTempFile("preview-admission-", ".opus", context.cacheDir)
        copyTestAsset(InstrumentationRegistry.getInstrumentation().context.assets, "lyrics.opus", file)
        val preview = QueueEdits.create(listOf(track("preview").copy(uri = android.net.Uri.fromFile(file).toString(), fileName = file.name, relativePath = file.name)), 0, "Preview").let { it.copy(entries = Counted(it.entries)) }
        val entries = preview.entries as Counted<QueueEntry>
        val other = QueueEdits.create(listOf(track("other")), 0, "Other")
        val catalog = Counted(listOf(preview, other))
        var source by mutableStateOf(QueueUiState(QueueLibrary(catalog, preview.id), loading = false))
        val native = io.github.anastaci1a.localica.playback.PlaybackState()
        var progress = Float.NaN
        fun publish(next: QueueUiState, ready: SemanticsMatcher, afterRelease: () -> Unit = {}, settled: (() -> Unit)? = null, pending: () -> Unit = {}, choices: () -> Unit) {
            val hook = AtomicReference<(() -> Unit)?>(null)
            entries.beforeRead = {
                if (Thread.currentThread().stackTrace.any { it.className == "io.github.anastaci1a.localica.ui.PlayerTrackProjectionKt" && it.methodName == "prepareQueuePresentation" })
                    hook.getAndSet(null)?.invoke()
            }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val publication = runBlocking { withContext(Dispatchers.Default) {
                val incoming = next.library.queues.map { queue ->
                    if (queue.id != preview.id) queue else queue.copy(journey = (queue.journey ?: QueueJourney()).copy()
                        .reconcile(queue.ids, queue.currentEntryId, JourneyModes.from(next.settings)))
                }
                next.copy(library = next.library.copy(queues = incoming)).resolvePlayerQueue()
            } }
            hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            try {
                compose.runOnIdle { source = publication }
                compose.waitForIdle()
                assertTrue(started.await(10, TimeUnit.SECONDS))
                choices()
                pending()
                assertEquals("Presentation preparation remains held through the pending assertions", 1L, release.count)
                release.countDown()
                afterRelease()
                compose.waitUntil(10_000) { compose.onAllNodes(ready, true).fetchSemanticsNodes().isNotEmpty() }
                (settled ?: choices)()
            } finally { release.countDown(); hook.set(null) }
        }
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Home), reducedMotion = true))
            actionPreferences.write(PlayerActionSettings().place(PlayerAction.Count, ActionSlot(0, 1)))
            compose.setContent { EngineTheme(reduced = true) {
                CompositionLocalProvider(LocalPlayerWorkObserver provides { kind, p -> if (kind == "root" && p != null) progress = p }) {
                    LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, queues = source)
                }
            } }
            assertNull(native.trackUri)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-surface").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-surface").performClick()
            compose.waitUntil(10_000) { progress == 1f }
            val title = hasText("preview") and (hasTestTag("player-title") or hasAnyAncestor(hasTestTag("player-title")))
            publish(source.copy(settings = source.settings.copy(preloadNext = false, shuffle = true, repeat = QueueRepeat.All)), hasContentDescription("Track 1 of 1"), pending = {
                compose.onNodeWithText("…", true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Track count loading").assertExists()
                compose.onNodeWithText("1/1", true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Track 1 of 1").assertDoesNotExist()
            }) {
                compose.onNodeWithContentDescription("Shuffle: on").assertIsDisplayed()
                compose.onNodeWithContentDescription("Repeat: All").assertIsDisplayed()
                compose.onNodeWithTag("player-surface").assertExists()
                compose.onNode(title, true).assertTextEquals("preview")
                compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
                assertEquals(1f, progress, 0f)
            }
            fun expandedPreview() {
                compose.onNode(title, true).assertTextEquals("preview")
                compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
                assertEquals(1f, progress, 0f)
            }
            val position = source.copy(library = source.library.update(preview.copy(positionMs = 9000)))
            if (mode == null) publish(position, hasContentDescription("Track 1 of 1")) { expandedPreview() }
            else for (positionOnly in listOf(false, true)) {
                val next = if (positionOnly) source.copy(library = source.library.update(preview.copy(positionMs = 9000)))
                    else source.copy(settings = source.settings.copy(preloadNext = true))
                if (mode == PlayerAction.Info) {
                    compose.onNodeWithContentDescription("Information").performClick()
                    compose.onNodeWithTag("song-information").assertExists()
                } else {
                    compose.onNodeWithTag("player-lyrics-title-target", true).performSemanticsAction(SemanticsActions.OnClick)
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-lines", true).fetchSemanticsNodes().isNotEmpty() }
                }
                publish(next, hasContentDescription("Track 1 of 1"), afterRelease = {
                    if (mode == PlayerAction.Info) compose.onNodeWithText("Done").performClick()
                    else compose.onNodeWithContentDescription("Hide lyrics").performClick()
                }, settled = ::expandedPreview) {
                    if (mode == PlayerAction.Info) {
                        compose.onNodeWithTag("song-information").assertExists()
                        compose.onNode(hasText("preview") and hasAnyAncestor(hasTestTag("song-information"))).assertExists()
                    } else compose.onNodeWithTag("lyric-lines", true).assertExists()
                    assertEquals(1f, progress, 0f)
                }
            }
            compose.onNodeWithContentDescription("Open queue").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Minimize queue").fetchSemanticsNodes().isNotEmpty() }
            publish(source.copy(browsedId = other.id, settings = source.settings.copy(preloadNext = true)), hasText("2. Other")) {
                compose.onNodeWithContentDescription("Minimize queue").assertIsDisplayed()
                assertEquals(1f, progress, 0f)
            }
            compose.onNodeWithContentDescription("Minimize queue").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-surface", true).assertExists()
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            assertEquals(1f, progress, 0f)
            compose.runOnIdle { source = QueueUiState(loading = false) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-surface").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("queue-surface", true).assertDoesNotExist()
        } finally { preferences.write(saved); actionPreferences.write(savedActions); file.delete() }
    }

    @Test fun nativeIdentityOwnsArtworkActionsAndLyricsWhileTheReplacementPrepares() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = InterfacePreferences(context)
        val saved = preferences.read()
        val actionPreferences = PlayerActionPreferences(context)
        val savedActions = actionPreferences.read()
        val modeStore = "projection-modes-" + java.util.UUID.randomUUID()
        val queuePreferences = QueuePreferences(object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(modeStore, mode)
        })
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: QueueController
        val seeks = mutableListOf<Long>()
        val file = File.createTempFile("projection-old-", ".opus", context.cacheDir)
        val replacement = File.createTempFile("projection-native-", ".opus", context.cacheDir)
        copyTestAsset(instrumentation.context.assets, "lyrics.opus", file)
        copyTestAsset(instrumentation.context.assets, "queue-tone.mp3", replacement)
        val old = Track(android.net.Uri.fromFile(file).toString(), "Saved old occurrence", id = "projection-old",
            fileName = file.name, relativePath = file.name)
        val fresh = old.copy(id = "projection-new", uri = android.net.Uri.fromFile(replacement).toString(), title = "New native")
        val oldQueue = ListeningQueue(id = "old", name = "Old", entries = listOf(QueueEntry("old", old)))
        val blocked = Counted(listOf(QueueEntry("new", old.copy(title = "Replacement occurrence"))))
        val pending = ListeningQueue(id = "pending", name = "Pending", entries = blocked)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hook = AtomicReference<(() -> Unit)?>(null)
        blocked.beforeRead = { hook.getAndSet(null)?.invoke() }
        var source by mutableStateOf(QueueUiState(QueueLibrary(listOf(oldQueue), oldQueue.id), loading = false))
        var native by mutableStateOf(io.github.anastaci1a.localica.playback.PlaybackState(connected = true,
            trackId = old.id, trackUri = old.uri, title = "Audible old", positionMs = 5000, durationMs = 125000, isPlaying = true))
        fun information(expected: String) {
            compose.onNodeWithContentDescription("Information").performClick()
            compose.onNodeWithTag("song-information").assertExists()
            compose.onNodeWithText(expected).assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("song-information").fetchSemanticsNodes().isEmpty() }
        }
        try {
            preferences.write(InterfaceSettings())
            actionPreferences.write(PlayerActionSettings(small = true).place(PlayerAction.Count, ActionSlot(0, 1))
                .place(PlayerAction.BackTen, ActionSlot(0, 2)).place(PlayerAction.ForwardTen, ActionSlot(0, 6)))
            instrumentation.runOnMainSync {
                controller = QueueController(database.queues(), queuePreferences,
                    (context.applicationContext as LocalicaApplication).library, actor)
            }
            compose.waitUntil(10_000) { !controller.state.value.loading }
            compose.setContent { EngineTheme(reduced = true) {
                LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, { seeks += it }, {}, {}, {}, {}, queues = source,
                    queueController = controller, playbackSample = { native.copy(positionMs = 25000) })
            } }
            compose.waitUntil(10_000) { PlayerVisualCache.peek(old.uri) != null }
            compose.onNodeWithTag("player-surface").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Information").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1/1", true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Track 1 of 1").assertExists()
            information(old.title)
            compose.onNodeWithTag("player-lyrics-title-target", true).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-lines", true).fetchSemanticsNodes().isNotEmpty() }
            hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            compose.runOnIdle { source = QueueUiState(QueueLibrary(listOf(pending), pending.id), loading = false) }
            compose.waitForIdle()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            // A replacement raw owner cannot expose the retained occurrence’s actions or lyrics.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-lines", true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Hide lyrics").performClick()
            compose.onNodeWithText("…", true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Track count loading").assertExists()
            compose.onNodeWithText("1/1", true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Track 1 of 1").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back 10 seconds").performClick()
            compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
            compose.runOnIdle { assertEquals(listOf(15000L, 35000L), seeks) }
            compose.onNodeWithContentDescription("Shuffle: off").performClick()
            compose.waitUntil(10_000) { controller.presentation.value.settings.shuffle }
            compose.onNodeWithContentDescription("Repeat: Off").performClick()
            compose.waitUntil(10_000) { controller.presentation.value.settings.repeat == QueueRepeat.All }
            assertEquals("Preparation is still blocked when independent commands are accepted", 1L, release.count)
            compose.onNodeWithContentDescription("Information").performClick()
            compose.onNodeWithTag("song-information").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-lines", true).fetchSemanticsNodes().isEmpty() }
            for ((id, uri, title) in listOf(Triple(fresh.id, fresh.uri, fresh.title),
                Triple(old.id, fresh.uri + "?same-id", "Same ID, different URI"), Triple(null, fresh.uri + "?uri-only", "URI only"))) {
                compose.runOnIdle { native = native.copy(trackId = id, trackUri = uri, title = title) }
                compose.onNode(hasText(title) and (hasTestTag("player-title") or hasAnyAncestor(hasTestTag("player-title"))), true).assertTextEquals(title)
                compose.onNodeWithText("…", true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Track count loading").assertExists()
                compose.onNodeWithText("1/1", true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Track 1 of 1").assertDoesNotExist()
                compose.waitUntil(10_000) { PlayerVisualCache.peek(uri) != null }
                compose.onNodeWithContentDescription("Information").performClick()
                compose.onNodeWithTag("song-information").assertDoesNotExist()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("lyric-lines", true).fetchSemanticsNodes().isEmpty() }
            }
            compose.runOnIdle { native = native.copy(trackId = old.id, trackUri = old.uri, title = "Audible old") }
            compose.onNodeWithContentDescription("Information").performClick()
            compose.onNodeWithTag("song-information").assertDoesNotExist()
            // A matching native ID keeps its native title; expose the queued preview for this publication witness.
            compose.runOnIdle { native = native.copy(trackId = null) }
            release.countDown()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Replacement occurrence") and
                (hasTestTag("player-title") or hasAnyAncestor(hasTestTag("player-title"))), true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1/1", true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Track 1 of 1").assertExists()
            compose.onNodeWithContentDescription("Track count loading").assertDoesNotExist()
            compose.onNodeWithContentDescription("Information").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Replacement occurrence").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Replacement occurrence") and hasAnyAncestor(hasTestTag("song-information"))).assertExists()
        } finally {
            release.countDown(); hook.set(null); file.delete(); replacement.delete()
            runBlocking { actor.coroutineContext[Job]!!.cancelAndJoin() }
            database.close(); context.deleteSharedPreferences(modeStore)
            preferences.write(saved); actionPreferences.write(savedActions)
        }
    }

    @Test fun millionEntryPreparationIsOffMainAndNativeMetadataDoesNotWaitForIt() {
        val residentTracks = List(50_000) { track("$it").copy(durationMs = 1000L) }
        val tracks = Counted(residentTracks)
        val entries = Counted(List(1_000_000) { QueueEntry("entry-$it", residentTracks[it % residentTracks.size]) })
        val queue = runBlocking { withContext(Dispatchers.Default) {
            val saved = ListeningQueue(id = "scale", name = "Scale", entries = entries, currentEntryId = "entry-999999")
            saved.copy(journey = QueueJourney().reconcile(saved.ids, saved.currentEntryId, JourneyModes(loop = true)))
        } }
        val empty = ListeningQueue(id = "other", name = "Other", entries = emptyList())
        val catalog = Counted(object : AbstractList<ListeningQueue>() {
            override val size = 50_000
            override fun get(index: Int) = if (index == lastIndex) queue else empty
        })
        val initial = QueueUiState(QueueLibrary(catalog, queue.id), loading = false)
        val snapshot = LibrarySnapshot(tracks = tracks)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { assertEquals(999_999, entries.indexOfFirst { it.id == queue.currentEntryId }) }
        assertEquals(1_000_000, entries.mainReads.get())
        entries.mainReads.set(0)
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")
        var prepareNanos = 0L
        val pure = runBlocking {
            withContext(Dispatchers.Default) {
                val start = SystemClock.elapsedRealtimeNanos()
                prepareQueuePresentation(initial, snapshot, null).also { prepareNanos = SystemClock.elapsedRealtimeNanos() - start }
            }
        }
        assertEquals(999_999, pure.active!!.index)
        assertEquals(1_000_000_000L, pure.browsed!!.duration)
        assertEquals(50_000, pure.number)
        assertSame(residentTracks.last(), pure.tracks.indexed)
        val expectedNeighbors = listOf(residentTracks[0].uri, residentTracks[49_998].uri)
        val expectedPreload = listOf(0, 49_998, 1, 49_997, 2, 49_996, 3, 49_995).map { residentTracks[it].uri }
        assertEquals(expectedNeighbors, pure.neighbors)
        assertEquals(expectedPreload, pure.preload)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hook = AtomicReference<(() -> Unit)?>(null)
        entries.beforeRead = { hook.getAndSet(null)?.invoke() }
        hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        var source by mutableStateOf(initial)
        var root by mutableStateOf(false)
        var native by mutableStateOf(io.github.anastaci1a.localica.playback.PlaybackState())
        var observed: QueuePresentation? = null
        compose.setContent { EngineTheme(reduced = true) {
            if (root) LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, {}, queues = source)
            else {
                val result = rememberQueuePresentation(source, snapshot)
                val ready = result.value
                if (ready == null) QueueScreen(source, null, LibraryUiState(snapshot, loading = false), presentation = result)
                SideEffect { observed = ready }
            }
        } }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            compose.onNodeWithTag("async-section-loading").assertExists()
            release.countDown()
            compose.waitUntil(30_000) { compose.waitForIdle(); observed?.state === initial }
            compose.runOnIdle {
                assertEquals(999_999, observed!!.active!!.index)
                assertSame(residentTracks.last(), observed!!.tracks.current)
                assertEquals(expectedNeighbors, observed!!.neighbors)
                assertEquals(expectedPreload, observed!!.preload)
                entries.reads = 0
                tracks.reads = 0
            }
            repeat(4) { iteration ->
                val next = runBlocking { withContext(Dispatchers.Default) {
                    initial.copy(library = QueueLibrary(listOf(queue.copy(positionMs = iteration + 1L)), queue.id)).resolvePlayerQueue()
                } }
                instrumentation.runOnMainSync { assertSame(pure.tracks, pure.playerTracks(next, snapshot)) }
                compose.runOnIdle { source = next }
                compose.waitUntil(10_000) { compose.waitForIdle(); observed?.state === next }
                compose.runOnIdle {
                    assertEquals(iteration + 1L, observed!!.active!!.queue.positionMs)
                    assertEquals(1_000_000_000L, observed!!.browsed!!.duration)
                    assertEquals(expectedNeighbors, observed!!.neighbors)
                    assertEquals(expectedPreload, observed!!.preload)
                    assertEquals(0, entries.reads)
                    assertEquals(0, tracks.reads)
                }
            }
            assertEquals(0, entries.mainReads.get())
            assertEquals(0, tracks.mainReads.get())
            assertEquals(0, catalog.mainReads.get())
            val metrics = JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("entries", 1_000_000)
                .put("tracks", 50_000).put("catalog", 50_000).put("prepareNanos", prepareNanos)
                .put("heapBefore", heapBefore).put("heapAfter", runtime.totalMemory() - runtime.freeMemory())
                .put("gcBefore", gcBefore).put("gcAfter", Debug.getRuntimeStat("art.gc.gc-count"))
                .put("mainEntryReads", entries.mainReads.get()).put("mainTrackReads", tracks.mainReads.get())
                .put("mainCatalogReads", catalog.mainReads.get())
            val evidence = nativeEvidenceDirectory("player-presentation-1020", instrumentation.targetContext.getExternalFilesDir(null))
            File(evidence, "scale-api${android.os.Build.VERSION.SDK_INT}.json").writeText(metrics.toString(2))
            println("PlayerPresentation1020 $metrics")

            val old = track("native-old", "Old native")
            val fresh = track("native-new", "New native")
            val oldQueue = ListeningQueue(id = "old", name = "Old", entries = listOf(QueueEntry("old", old)))
            compose.runOnIdle {
                source = QueueUiState(QueueLibrary(listOf(oldQueue), oldQueue.id), loading = false)
                native = io.github.anastaci1a.localica.playback.PlaybackState(connected = true, trackId = old.id, trackUri = old.uri, title = old.title)
                root = true
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-title", true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(old.title) and (hasTestTag("player-title") or hasAnyAncestor(hasTestTag("player-title"))), true).assertTextEquals(old.title)
            val pending = CountDownLatch(1)
            val continuePreparation = CountDownLatch(1)
            hook.set { pending.countDown(); check(continuePreparation.await(30, TimeUnit.SECONDS)) }
            try {
                compose.runOnIdle {
                    source = initial
                    native = native.copy(trackId = fresh.id, trackUri = fresh.uri, title = fresh.title)
                }
                compose.waitForIdle()
                assertTrue(pending.await(10, TimeUnit.SECONDS))
                compose.onNode(hasText(fresh.title) and (hasTestTag("player-title") or hasAnyAncestor(hasTestTag("player-title"))), true).assertTextEquals(fresh.title)
                assertEquals(0, entries.mainReads.get())
                assertEquals(0, tracks.mainReads.get())
                assertEquals(0, catalog.mainReads.get())
            } finally { continuePreparation.countDown() }
        } finally { release.countDown(); hook.set(null) }
    }

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
        val song = track("cursor-artwork").copy(uri = file.toURI().toString())
        val replacementSong = track("replacement-artwork").copy(uri = replacementFile.toURI().toString())
        val entries = Counted(List(3) { QueueEntry("entry-$it", song) })
        val original = ListeningQueue(id = "cursor-artwork", name = "Artwork", entries = entries,
            currentEntryId = "entry-0").let { it.copy(journey = QueueJourney.start(it.ids, it.currentEntryId, JourneyModes())) }
        var source by mutableStateOf(QueueUiState(QueueLibrary(listOf(original), original.id), loading = false).resolvePlayerQueue())
        val native = io.github.anastaci1a.localica.playback.PlaybackState(connected = true,
            trackId = song.id, trackUri = song.uri, title = song.title, durationMs = 120000)
        var latest: ArtworkBackgroundFrame? = null
        var progress = 0f
        var observing = false
        val blends = linkedSetOf<Any>()
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
                    LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, queues = source)
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
                    compose.waitForIdle()
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
            frames(80)
            settled()
            assertEquals("A different occurrence with identical pixels still has exactly one blend", 1, blends.size)

            val replacementEntries = Counted(listOf(QueueEntry("replacement-entry", replacementSong)))
            replacementEntries.beforeRead = entries.beforeRead
            val replacement = ListeningQueue(id = "replacement", name = "Replacement", entries = replacementEntries,
                currentEntryId = "replacement-entry").let { it.copy(journey = QueueJourney.start(it.ids, it.currentEntryId, JourneyModes())) }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            try {
                compose.runOnIdle {
                    blends.clear()
                    source = source.copy(library = QueueLibrary(listOf(replacement), replacement.id)).resolvePlayerQueue()
                }
                compose.waitForIdle()
                assertTrue("Replacement presentation is held while native playback still owns the old song", started.await(10, TimeUnit.SECONDS))
                frames()
                assertEquals("A new queue token cannot start a blend of the old native artwork", 0, blends.size)
            } finally { release.countDown(); hook.set(null) }
            frames(80)
            settled()
            assertEquals("The prepared replacement admits exactly one blend", 1, blends.size)
            assertEquals(replacementVisual.contentKey, latest!!.roles.first { it.incoming && !it.soft }.visual.contentKey)
        } finally {
            hook.set(null)
            compose.mainClock.autoAdvance = true
            preferences.write(saved)
            file.delete()
            replacementFile.delete()
        }
    }

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
        val song = track("playing-artwork").copy(uri = file.toURI().toString(), durationMs = 20000)
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
