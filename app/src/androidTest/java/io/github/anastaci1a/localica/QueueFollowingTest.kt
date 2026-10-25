package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowsePreferences
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.Presentation
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real browser/queue composition: attachment is user intent, not current-row visibility. */
class QueueFollowingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tracks = (0 until 120).map { index ->
        Track("following:$index", "Song ${index.toString().padStart(3, '0')}", artist = "Following artist",
            fileName = "$index.mp3", relativePath = "$index.mp3")
    }
    // Occurrences 5, 45 and 95 deliberately share the same media identity.
    private val entries = tracks.indices.map { index ->
        QueueEntry("follow-entry-$index", if (index in listOf(5, 45, 95)) tracks[5].copy(title = "Repeated current") else tracks[index])
    }
    private val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "following"),
        title = "Following queue", key = "following-route")
    private val list get() = compose.onNodeWithTag("browser-list")
    private val currentReturn get() = compose.onNodeWithContentDescription("Current song")
    private fun row(index: Int) = compose.onNodeWithTag("media-${entries[index].id}")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun awaitCurrent(index: Int) {
        compose.waitUntil(5000) { runCatching { row(index).assertIsDisplayed() }.isSuccess }
        compose.waitForIdle()
        row(index).assertIsDisplayed()
    }

    private fun pressCurrentReturn() {
        currentReturn.assertIsDisplayed().performTouchInput { down(center) }
        // Force the held-contact state through composition before the real release.
        // A semantics click would bypass this eligibility/gesture ordering entirely.
        compose.waitForIdle()
        currentReturn.performTouchInput { up() }
    }

    private fun awaitMovementFrom(start: Float) {
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            position() != start
        }
    }

    private class QueryDispatcher : CoroutineDispatcher() {
        @Volatile var held = false
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) tasks.add(block) else Dispatchers.Default.dispatch(context, block)
        }
        fun next() = requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected a held browse query" }
        fun release() {
            held = false
            while (true) (tasks.poll() ?: break).run()
        }
    }
    private val queries = QueryDispatcher()

    private class Fixture(queue: ListeningQueue, route: BrowseRoute, active: Boolean, library: LibraryUiState) {
        var library by mutableStateOf(library)
        var playlists by mutableStateOf(PlaylistState(loading = false))
        var queueState by mutableStateOf(QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false))
        var playing by mutableStateOf(queue.currentEntryId)
        var route by mutableStateOf(route)
        var active by mutableStateOf(active)
        var playerOpen by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var modal by mutableStateOf(false)
        var topRequest by mutableIntStateOf(0)
        val selection = SessionSelection()
        val dragHost = SongDragHost()
        var playCalls = 0
        var routeChanges = 0
        val queue get() = queueState.library.queues.single()
        fun publish(index: Int) {
            val current = queue.entries[index].id
            queueState = queueState.copy(library = queueState.library.update(queue.copy(currentEntryId = current, positionMs = 0)))
            playing = current
        }
        fun checkpoint() {
            queueState = queueState.copy(library = queueState.library.update(queue.copy(positionMs = queue.positionMs + 1000)))
        }
    }

    @Before fun configureNativeInputWindow() {
        compose.activityRule.scenario.onActivity {
            // This search editor is in the activity, not a resize-configured popup.
            // Match MainActivity's edge-to-edge content as well as its resize contract.
            // A fitted test decor can consume the IME bottom while reporting it visible.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            assertEquals("Direct search uses the production native resize contract",
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                it.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        }
    }

    @Before fun clearPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }
    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        queries.release()
        clearPreferences()
    }

    private fun fixture(initial: Int = 45, reducedMotion: Boolean = true, queueScreen: Boolean = false,
        active: Boolean = true, text: String = "", search: Boolean = false,
        mounted: Boolean = true, modal: Boolean = false, style: BrowseStyle = BrowseStyle(quickSearch = false)): Fixture {
        val queue = ListeningQueue(id = "following", name = "Following queue", entries = entries,
            currentEntryId = entries[initial].id)
        val library = LibraryUiState(LibrarySnapshot(tracks = entries.map { it.track }.distinctBy { it.id }), loading = false)
        val fixture = Fixture(queue, route.copy(text = text, search = search, queryCommitted = search), active, library).also {
            it.mounted = mounted; it.modal = modal
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BrowsePreferences(context).write(route, style.copy(quickSearch = false), true)
        // Stable source identities matter: incidental fixture recomposition must not fabricate queries.
        compose.setContent { LocalicaTheme {
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalSessionSelection provides fixture.selection, LocalSongDragHost provides fixture.dragHost) {
                Box(Modifier.fillMaxSize()) {
                    if (fixture.mounted) holder.SaveableStateProvider(fixture.route.key) {
                        if (queueScreen) QueueScreen(fixture.queueState, null, fixture.library, fixture.playlists,
                            selectionVisible = fixture.active, isPlaying = false, reducedMotion = reducedMotion)
                        else BrowserScreen(fixture.library, fixture.playlists, fixture.route,
                            { fixture.routeChanges++; fixture.route = it }, {}, {}, { _, _, _ -> fixture.playCalls++ }, null, null,
                            true, fixture.playerOpen, queueState = fixture.queueState,
                            playingTrackId = fixture.queue.entries.firstOrNull { it.id == fixture.playing }?.track?.id,
                            playingEntryId = fixture.playing, selectionVisible = fixture.active,
                            reducedMotion = reducedMotion, scrollToTopRequest = fixture.topRequest, queryDispatcher = queries)
                    } else Text("Another route")
                    ForegroundSongDragOverlay(fixture.dragHost)
                }
            }
            GlassMorphPopup(fixture.modal, rememberGlassAnchor(), { fixture.modal = false }, GlassPlacement.Center) {
                ActionTextButton({ fixture.modal = false }) { Text("Dismiss follow test modal") }
            }
        } }
        if (mounted) {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            if (active && text.isEmpty() && !search && !modal) awaitCurrent(initial)
        }
        return fixture
    }

    @Test fun browserDefaultsToFollowingTheExactCurrentOccurrence() {
        val fixture = fixture()
        row(45).assertIsDisplayed()
        row(5).assertDoesNotExist()
        row(95).assertDoesNotExist()
        currentReturn.assertDoesNotExist()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.playCalls); assertEquals(0, fixture.routeChanges) }
    }

    @Test fun externalCurrentPublicationsFollowBothDirectionsWithoutStartingPlayback() {
        val fixture = fixture()
        // Natural completion, manual skip and headset transport publish this same occurrence state.
        for (next in listOf(95, 5, 85, 25)) {
            compose.runOnIdle { fixture.publish(next) }
            awaitCurrent(next)
            currentReturn.assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, fixture.playCalls); assertEquals(0, fixture.routeChanges) }
    }

    @Test fun galleryFollowsTheExactOccurrenceAndRetainsTheSameDetachReturnContract() {
        val fixture = fixture(style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3))
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Gallery, 3 columns"))
        compose.runOnIdle { fixture.publish(95) }
        awaitCurrent(95)
        row(45).assertDoesNotExist()
        row(5).assertDoesNotExist()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 8f)) }
        row(95).assertIsDisplayed()
        currentReturn.assertIsDisplayed()
        val detached = position()
        compose.runOnIdle { fixture.publish(5) }
        compose.waitForIdle()
        assertEquals(detached, position(), 0f)
        currentReturn.performClick()
        awaitCurrent(5)
        currentReturn.assertDoesNotExist()
    }

    @Test fun pausedQueueScreenFollowsAnExternallyPublishedCurrentOccurrence() {
        val fixture = fixture(queueScreen = true)
        compose.runOnIdle { fixture.publish(95) }
        awaitCurrent(95)
        row(5).assertDoesNotExist()
        row(45).assertDoesNotExist()
        currentReturn.assertDoesNotExist()
    }

    @Test fun normalFollowEasesAndPositionCheckpointsDoNotRestartItsJourney() {
        val fixture = fixture(initial = 5, reducedMotion = false)
        val start = position()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { fixture.publish(95) }
        awaitMovementFrom(start)
        val positions = mutableListOf<Float>()
        repeat(36) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            positions += position()
            if (frame in listOf(8, 14, 20)) compose.runOnIdle { fixture.checkpoint() }
        }
        assertTrue("A distant follow must ease through several native layout frames", positions.distinct().size > 4)
        assertTrue(position() > start)
        row(95).assertIsDisplayed()
        val settled = position()
        compose.runOnIdle { fixture.checkpoint() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("Position-only publication must not enqueue another journey", settled, position(), 0f)
        currentReturn.assertDoesNotExist()
    }

    @Test fun statisticsAndUnrelatedPlaylistPublicationsDoNotInterruptAnAdmittedFollow() {
        val fixture = fixture(initial = 5, reducedMotion = false)
        compose.mainClock.autoAdvance = false
        val start = position()
        compose.runOnIdle { fixture.publish(95) }
        awaitMovementFrom(start)
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        row(95).assertDoesNotExist()
        val beforeStatistics = position()
        compose.runOnIdle {
            fixture.library = fixture.library.copy(snapshot = fixture.library.snapshot.copy(
                tracks = fixture.library.snapshot.tracks.map { it.copy(playCount = it.playCount + 1) },
                trackPlays = mapOf(entries[95].track.id to 1L)))
            fixture.playlists = fixture.playlists.copy(playlists = listOf(ManualPlaylist(name = "Unrelated playlist")))
        }
        val positions = mutableListOf(beforeStatistics)
        repeat(35) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            positions += position()
        }
        row(95).assertIsDisplayed()
        currentReturn.assertDoesNotExist()
        assertTrue("The original ease must continue across source publications", positions.distinct().size > 4)
        assertTrue("Unrelated publications cannot rewind or restart a follow", positions.zipWithNext().all { (a, b) -> b >= a })
        val settled = position()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(settled, position(), 0f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { fixture.publish(45) }
        awaitCurrent(45)
    }

    @Test fun aNewerOccurrenceCancelsTheInFlightDestination() {
        val fixture = fixture(initial = 5, reducedMotion = false)
        compose.mainClock.autoAdvance = false
        val start = position()
        compose.runOnIdle { fixture.publish(95) }
        awaitMovementFrom(start)
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val moving = position()
        assertTrue(moving > 0f)
        row(95).assertDoesNotExist()
        compose.runOnIdle { fixture.publish(45) }
        repeat(50) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        row(45).assertIsDisplayed()
        row(95).assertDoesNotExist()
        val settled = position()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("An old destination must not resume after the latest one settles", settled, position(), 0f)
    }

    @Test fun nativeScrollByDetachesEvenWhileCurrentRemainsVisibleAndReturnResumesFollowing() {
        val fixture = fixture()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 8f)) }
        row(45).assertIsDisplayed()
        currentReturn.assertIsDisplayed()
        val detached = position()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals("Detached playback changes must not move the user's viewport", detached, position(), 0f)
        pressCurrentReturn()
        awaitCurrent(95)
        currentReturn.assertDoesNotExist()
        compose.runOnIdle { fixture.publish(5) }
        awaitCurrent(5)
        currentReturn.assertDoesNotExist()
    }

    @Test fun holdingTheCurrentReturnNeverSelectsOrDragsTheObscuredSong() {
        val fixture = fixture()
        list.performScrollToIndex(30)
        val before = position()
        var held = false
        try {
            // Positive control: the same injected hold really acquires a row, so the
            // floating-control check cannot pass merely because its timeout never ran.
            row(30).performTouchInput { down(center); held = true; advanceEventTime(700); moveTo(center) }
            compose.runOnIdle {
                assertTrue(fixture.selection.active)
                assertEquals(listOf(entries[30].id), fixture.selection.entries.map { it.row.id })
                assertNull(fixture.dragHost.state?.drag)
            }
            compose.onRoot().performTouchInput { cancel(); held = false }
            compose.runOnIdle { fixture.selection.clear() }
            assertEquals(before, position(), 0f)

            val buttonBounds = currentReturn.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val coveredSong = compose.onAllNodes(SemanticsMatcher("Queue occurrence") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-follow-entry-") == true
            }).fetchSemanticsNodes().map { it.boundsInRoot.intersect(buttonBounds) }
                .filter { it.width > 8f && it.height > 8f }.maxByOrNull { it.height }
            assertNotNull("The hold must exercise a real song behind the floating control", coveredSong)
            val contact = requireNotNull(coveredSong).center - buttonBounds.topLeft
            currentReturn.performTouchInput { down(contact); held = true; advanceEventTime(700); moveTo(contact) }
            compose.runOnIdle {
                assertFalse("The floating control is not an underlying row hold", fixture.selection.active)
                assertTrue(fixture.selection.entries.isEmpty())
                assertNull(fixture.dragHost.state?.drag)
            }
            currentReturn.assertIsDisplayed()
            assertEquals(before, position(), 0f)
            currentReturn.performTouchInput { moveBy(Offset(0f, -40f)); cancel(); held = false }
            compose.runOnIdle {
                assertFalse(fixture.selection.active)
                assertNull(fixture.dragHost.state?.drag)
            }
            assertEquals("A cancelled return-control drag cannot scroll the list", before, position(), 0f)
        } finally {
            if (held) compose.onRoot().performTouchInput { cancel() }
        }
        pressCurrentReturn()
        awaitCurrent(45)
        currentReturn.assertDoesNotExist()
    }

    @Test fun nativeScrollToIndexDetachesEvenWhenItRequestsTheAlreadyVisibleCurrent() {
        fixture()
        list.performScrollToIndex(46) // Full-span notice precedes occurrence 45.
        row(45).assertIsDisplayed()
        currentReturn.assertIsDisplayed()
    }

    @Test fun aSmallManualSwipeDetachesBeforeTheCurrentLeavesTheViewport() {
        val fixture = fixture()
        list.performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY - 36f), 600)
        }
        row(45).assertIsDisplayed()
        currentReturn.assertIsDisplayed()
        val detached = position()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals(detached, position(), 0f)
    }

    @Test fun aManualFlingKeepsItsViewportAfterPlaybackAdvances() {
        val fixture = fixture()
        list.performTouchInput { swipeUp(durationMillis = 90) }
        currentReturn.assertIsDisplayed()
        val detached = position()
        compose.runOnIdle { fixture.publish(5) }
        compose.waitForIdle()
        assertEquals(detached, position(), 0f)
        currentReturn.performClick()
        awaitCurrent(5)
    }

    @Test fun scrollbarAccessibleProgressDetachesEvenWithoutMovingTheCurrentOutOfView() {
        fixture()
        val bar = compose.onNodeWithTag("browse-scrollbar")
        bar.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(45f / entries.lastIndex)) }
        row(45).assertIsDisplayed()
        currentReturn.assertIsDisplayed()
    }

    @Test fun draggingTheScrollbarDetachesAndNeverReattachesOnRelease() {
        val fixture = fixture()
        val bar = compose.onNodeWithTag("browse-scrollbar")
        bar.performTouchInput {
            down(Offset(centerX, height * .4f))
            moveTo(Offset(centerX, height * .7f), delayMillis = 300)
            up()
        }
        currentReturn.assertIsDisplayed()
        val detached = position()
        compose.runOnIdle { fixture.publish(5) }
        compose.waitForIdle()
        assertEquals(detached, position(), 0f)
    }

    @Test fun stationaryContactDoesNotDetachAnIdleAttachedQueue() {
        val fixture = fixture()
        list.performTouchInput { down(center); cancel() }
        currentReturn.assertDoesNotExist()
        compose.runOnIdle { fixture.publish(95) }
        awaitCurrent(95)
    }

    @Test fun aRestingHeldContactPreventsFollowingAndReleaseDoesNotReplayTheChange() {
        val fixture = fixture()
        val before = position()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            list.performTouchInput { down(center); held = true }
            compose.runOnIdle { fixture.publish(95) }
            repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertEquals("Playback cannot move the row underneath a resting finger", before, position(), 0f)
            list.performTouchInput { cancel(); held = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals("Releasing a stationary contact cannot replay its consumed target", before, position(), 0f)
            currentReturn.assertDoesNotExist()
            compose.runOnIdle { fixture.publish(5) }
            awaitCurrent(5)
        } finally {
            if (held) list.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun scopedQueueSearchRetainsExplicitExactOccurrenceReturnWithoutAutomaticFollowing() {
        val fixture = fixture(initial = 95, text = "Following", search = true)
        // Submit as soon as the editor is ready, even if the native show is still
        // in flight. Only its real hidden/focus handoff admits the return gesture.
        compose.awaitSearchImeHandoff { compose.onNode(hasSetTextAction()).performImeAction() }
        compose.waitForIdle()
        row(95).assertDoesNotExist()
        pressCurrentReturn()
        awaitCurrent(95)
        row(5).assertDoesNotExist()
        row(45).assertDoesNotExist()
        currentReturn.assertDoesNotExist()
        val explicitDestination = position()
        compose.runOnIdle { fixture.publish(45) }
        compose.waitForIdle()
        assertEquals("An explicit search return must remain one-shot", explicitDestination, position(), 0f)
        currentReturn.assertIsDisplayed().performClick()
        awaitCurrent(45)
        compose.runOnIdle {
            assertTrue(fixture.route.search)
            assertEquals("Following", fixture.route.text)
            assertEquals(0, fixture.playCalls)
        }
    }

    @Test fun scopedQueueSearchAfterNativeKeyboardArrivalRetainsExactOccurrenceReturn() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        val key = "show_ime_with_hard_keyboard"
        val original = shell("settings get secure $key")
        check(original == "null" || original.matches(Regex("-?[0-9]+")))
        try {
            if (original != "1") shell("settings put secure $key 1")
            assertEquals("1", shell("settings get secure $key"))
            val fixture = fixture(initial = 95, text = "Following", search = true)
            val editor = compose.onNode(hasSetTextAction()).assertExists()
            val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
            val input = requireNotNull(compose.activity.getSystemService(InputMethodManager::class.java))
            compose.runOnUiThread { assertSame("Exercise the direct activity host", compose.activity.window.decorView, view.rootView) }
            var nextShowRequest = 0L
            var lastReadiness: String? = null
            compose.waitUntil(5000) {
                val editorFocused = editor.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
                compose.runOnUiThread {
                    val native = ViewCompat.getRootWindowInsets(view)
                    val shown = editorFocused && view.isAttachedToWindow && view.hasWindowFocus() && input.isActive(view) &&
                        native?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                        native.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
                    val readiness = "editorFocus=$editorFocused attached=${view.isAttachedToWindow} windowFocus=${view.hasWindowFocus()} " +
                        "active=${input.isActive(view)} imeVisible=${native?.isVisible(WindowInsetsCompat.Type.ime())} " +
                        "imeBottom=${native?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} view=${view.javaClass.name}"
                    if (readiness != lastReadiness) {
                        android.util.Log.i("QueueSearchReadiness", "${SystemClock.uptimeMillis()} $readiness")
                        lastReadiness = readiness
                    }
                    if (!shown && editorFocused && view.hasWindowFocus() && SystemClock.uptimeMillis() >= nextShowRequest) {
                        // Retry only the real editor if its earlier request preceded
                        // the keyboard policy notification; never synthesize insets.
                        input.showSoftInput(view, 0)
                        nextShowRequest = SystemClock.uptimeMillis() + 1000L
                    }
                    shown
                }
            }
            editor.assertIsFocused()
            // Unlike the immediate-submit case above, first prove that the
            // independent observer has cached the real visible-IME snapshot.
            compose.awaitSearchImeHandoff(requireShownBeforeAction = true) { editor.performImeAction() }
            compose.waitForIdle()
            row(95).assertDoesNotExist()
            pressCurrentReturn()
            awaitCurrent(95)
            row(5).assertDoesNotExist()
            row(45).assertDoesNotExist()
            currentReturn.assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(fixture.route.search)
                assertEquals("Following", fixture.route.text)
                assertEquals(0, fixture.playCalls)
            }
        } finally {
            if (original == "null") shell("settings delete secure $key")
            else if (original != "1") shell("settings put secure $key $original")
            assertEquals("Restore native keyboard policy", original, shell("settings get secure $key"))
        }
    }

    @Test fun filteredOutCurrentHasNoFallbackToAnotherDuplicateOrTop() {
        val fixture = fixture(text = "Song") // All three duplicate occurrences are excluded.
        list.performScrollToIndex(60)
        val detached = position()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals(detached, position(), 0f)
        row(5).assertDoesNotExist()
        row(45).assertDoesNotExist()
        row(95).assertDoesNotExist()
        currentReturn.assertDoesNotExist()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Song", fixture.route.text); assertEquals(0, fixture.routeChanges) }
    }

    @Test fun detachedIntentAndPositionSurviveARecreatedRetainedRoute() {
        val fixture = fixture()
        list.performScrollToIndex(25)
        val detached = position()
        compose.runOnIdle { fixture.mounted = false; fixture.publish(95) }
        list.assertDoesNotExist()
        compose.runOnIdle { fixture.mounted = true }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("Returning to a retained route must not silently reattach", detached, position(), 0f)
        currentReturn.assertIsDisplayed().performClick()
        awaitCurrent(95)
    }

    @Test fun inactivePageDoesNotFollowOrReplayAConsumedOccurrenceOnReturn() = blockedPublication(
        block = { it.active = false }, unblock = { it.active = true })

    @Test fun expandedPlayerDoesNotFollowOrReplayAConsumedOccurrenceOnClose() = blockedPublication(
        block = { it.playerOpen = true }, unblock = { it.playerOpen = false })

    @Test fun selectionDoesNotFollowOrReplayAConsumedOccurrenceAfterClearing() = blockedPublication(
        block = { it.selection.select(route.collection, listOf(SongRow(entries[45].id, entries[45].track))) },
        unblock = { it.selection.clear() })

    @Test fun modalDoesNotFollowOrReplayAConsumedOccurrenceAfterDismissal() = blockedPublication(
        block = { it.modal = true }, unblock = { it.modal = false })

    private fun blockedPublication(block: (Fixture) -> Unit, unblock: (Fixture) -> Unit) {
        val fixture = fixture()
        val before = position()
        compose.runOnIdle { block(fixture) }
        compose.waitForIdle()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals("The foreground owner must keep the background list still", before, position(), 0f)
        currentReturn.assertDoesNotExist()
        compose.runOnIdle { unblock(fixture) }
        compose.waitForIdle()
        assertEquals("Releasing ownership must not revive a consumed occurrence", before, position(), 0f)
        compose.runOnIdle { fixture.publish(5) }
        awaitCurrent(5)
    }

    @Test fun aQueueMountedBehindAnExistingModalFollowsOnlyAfterItsFirstAdmission() {
        val fixture = fixture(initial = 95, mounted = false, modal = true)
        compose.onNodeWithText("Dismiss follow test modal").assertIsDisplayed()
        compose.runOnIdle { fixture.mounted = true }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        val before = position()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("A newly mounted background queue must wait for the modal", before, position(), 0f)
        row(95).assertDoesNotExist()
        compose.onNodeWithText("Dismiss follow test modal").performClick()
        awaitCurrent(95)
        currentReturn.assertDoesNotExist()
    }

    @Test fun anInitiallyHiddenQueueWaitsForItsFirstVisibleOwnership() {
        val fixture = fixture(active = false)
        val before = position()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals(before, position(), 0f)
        compose.runOnIdle { fixture.active = true }
        awaitCurrent(95)
    }

    @Test fun freshTouchCancelsAQueryDelayedFollowAndItsReadinessFrames() {
        val fixture = fixture(initial = 5)
        val before = position()
        queries.held = true
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        val pending = queries.next()
        list.performTouchInput { down(center); cancel() }
        pending.run()
        queries.release()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("Old query readiness cannot start a cancelled request", before, position(), 0f)
        currentReturn.assertIsDisplayed().performClick()
        awaitCurrent(95)
        compose.runOnIdle { fixture.publish(45) }
        awaitCurrent(45)
    }

    @Test fun anObsoleteQueryCannotConsumeTheLatestOccurrenceRequest() {
        val fixture = fixture(initial = 5)
        val before = position()
        queries.held = true
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        val old = queries.next()
        compose.runOnIdle { fixture.publish(45) }
        compose.waitForIdle()
        val latest = queries.next()
        old.run()
        compose.waitForIdle()
        assertEquals("A stale presentation cannot navigate", before, position(), 0f)
        latest.run()
        queries.release()
        awaitCurrent(45)
        row(95).assertDoesNotExist()
    }

    @Test fun heldDragOwnsItsRowsAcrossCurrentChangesAndCancellation() {
        val fixture = fixture()
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = row(47).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        var held = false
        try {
            list.performTouchInput { down(from); held = true; advanceEventTime(650); moveTo(from + Offset(0f, 40f)) }
            compose.onNodeWithTag("drag-stack").assertExists()
            val transaction = compose.runOnIdle { requireNotNull(fixture.dragHost.state?.drag) }
            val before = position()
            compose.runOnIdle { fixture.publish(95) }
            compose.waitForIdle()
            assertEquals(before, position(), 0f)
            compose.runOnIdle { assertSame(transaction, fixture.dragHost.state?.drag) }
            currentReturn.assertDoesNotExist()
            list.performTouchInput { cancel(); held = false }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { fixture.selection.clear() }
            compose.waitForIdle()
            assertEquals("Cancelling the drag cannot revive playback movement", before, position(), 0f)
            compose.runOnIdle { fixture.publish(5) }
            awaitCurrent(5)
        } finally { if (held) list.performTouchInput { cancel() } }
    }

    @Test fun heldPinchOwnsItsAnchorAndDoesNotReplayAnOccurrenceAfterRelease() {
        val fixture = fixture()
        var held = false
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(0, Offset(centerX - 50f, centerY)); down(1, Offset(centerX + 50f, centerY)); held = true
                repeat(8) { step ->
                    val distance = 50f + (step + 1) * 7.5f
                    moveTo(0, Offset(centerX - distance, centerY), delayMillis = 24)
                    moveTo(1, Offset(centerX + distance, centerY), delayMillis = 24)
                }
            }
            // Frozen artwork readback and the matching query are real-thread work.
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                runCatching { list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "List, Large")) }.isSuccess
            }
            compose.mainClock.advanceTimeBy(64)
            val before = position()
            compose.runOnIdle { fixture.publish(95) }
            compose.waitForIdle()
            repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertEquals("Playback must not steal the held pinch anchor", before, position(), .01f)
            currentReturn.assertDoesNotExist()
            list.performTouchInput { up(0); up(1); held = false }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            row(95).assertDoesNotExist()
            compose.runOnIdle { fixture.publish(5) }
            awaitCurrent(5)
        } finally {
            if (held) list.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun anExplicitTopRequestDetachesWithoutChangingTheQueueOrItsQuery() {
        val fixture = fixture()
        compose.runOnIdle { fixture.topRequest++ }
        // With quick search disabled the notice is zero-height. LazyGrid skips that
        // slot at the top, so its semantic position starts at index 1, not index 0.
        awaitCurrent(0)
        val rowInset = with(compose.density) { 4.dp.roundToPx() }
        fun assertFirstRowAtTop() {
            assertEquals(list.fetchSemanticsNode().boundsInRoot.top + rowInset,
                row(0).fetchSemanticsNode().boundsInRoot.top, 0.01f)
        }
        assertFirstRowAtTop()
        val top = position()
        currentReturn.assertIsDisplayed()
        compose.runOnIdle { fixture.publish(95) }
        compose.waitForIdle()
        assertEquals(top, position(), 0f)
        assertFirstRowAtTop()
        compose.runOnIdle { assertEquals(route, fixture.route); assertEquals(0, fixture.routeChanges) }
        currentReturn.performClick()
        awaitCurrent(95)
    }
}
