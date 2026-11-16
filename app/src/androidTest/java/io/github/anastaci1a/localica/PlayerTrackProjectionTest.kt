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
        var source by mutableStateOf(QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false))
        compose.setContent { EngineTheme(reduced = true) { QueueScreen(source, null, reducedMotion = true) } }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            compose.onNodeWithTag("async-section-loading").assertExists()
            compose.onNodeWithText("2 / 3").assertDoesNotExist()
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
            compose.onNodeWithText(songs[0].title).assertExists()
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
        val preview = QueueEdits.create(listOf(track("preview").copy(uri = android.net.Uri.fromFile(file).toString(), fileName = file.name, relativePath = file.name)), 0, "Preview")
        val other = QueueEdits.create(listOf(track("other")), 0, "Other")
        val catalog = Counted(listOf(preview, other))
        var source by mutableStateOf(QueueUiState(QueueLibrary(catalog, preview.id), loading = false))
        val native = io.github.anastaci1a.localica.playback.PlaybackState()
        var progress = Float.NaN
        fun publish(next: QueueUiState, ready: SemanticsMatcher, afterRelease: () -> Unit = {}, settled: (() -> Unit)? = null, choices: () -> Unit) {
            val hook = AtomicReference<(() -> Unit)?>(null)
            val incoming = Counted(next.library.queues)
            incoming.beforeRead = { hook.getAndSet(null)?.invoke() }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val publication = runBlocking { withContext(Dispatchers.Default) {
                next.copy(library = next.library.copy(queues = incoming)).resolvePlayerQueue()
            } }
            hook.set { started.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            try {
                compose.runOnIdle { source = publication }
                compose.waitForIdle()
                assertTrue(started.await(10, TimeUnit.SECONDS))
                choices()
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
            publish(source.copy(settings = source.settings.copy(preloadNext = false)), hasContentDescription("Track 1 of 1")) {
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
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("player-actions", 0).edit().clear().commit()
        PlayerActionPreferences(context).write(PlayerActionSettings().place(PlayerAction.Count, ActionSlot(0, 1)))
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
            trackId = old.id, trackUri = old.uri, title = "Audible old", durationMs = 125000))
        compose.setContent { EngineTheme(reduced = true) {
            LocalicaScreen(LibraryUiState(loading = false), native, {}, {}, {}, {}, {}, {}, {}, queues = source)
        } }
        fun information(expected: String) {
            compose.onNodeWithContentDescription("Information").performClick()
            compose.onNodeWithTag("song-information").assertExists()
            compose.onNodeWithText(expected).assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("song-information").fetchSemanticsNodes().isEmpty() }
        }
        try {
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
        } finally { release.countDown(); hook.set(null); file.delete(); replacement.delete() }
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
}
