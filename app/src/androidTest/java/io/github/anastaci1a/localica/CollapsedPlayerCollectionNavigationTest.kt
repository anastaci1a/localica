package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.LocalSheetContentObserver
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import kotlin.math.roundToInt

/** #837: collection chrome may move the closed sheet's basis without opening its retained body. */
class CollapsedPlayerCollectionNavigationTest {
    private val compose = localicaComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        BrowsePreferenceIsolation(listOf("interface", "browse-style", "library-query"))).around(compose)
    private val tracks = List(60) { Track("basis:$it", "Song ${it.toString().padStart(2, '0')}",
        album = "Basis album", artist = "Basis artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val snapshot = LibrarySnapshot(listOf(FolderSelection("basis-root", "Music")), tracks)
    private val playlist = ManualPlaylist(id = "basis-playlist", name = "Basis playlist",
        entries = tracks.map { SongRow(it.id, it) })
    private val events = SheetEvents()
    private val contentEvents = mutableListOf<Pair<String, Boolean>>()
    private val liveRetainedHosts = mutableMapOf<String, Int>()
    private var navigationContentBoundary = 0
    private var requestedDestination: String? = null
    private var navigationFrame = 0L
    private var nativeRoot: NativeRootReferences? = null
    private lateinit var records: File
    private lateinit var access: MotionAccess
    private data class FrameRecord(val source: String, val api: Int, val frameMs: Long,
        val access: MotionAccess, val referenceTravel: Float, val phase: Float, val queuePhase: Float,
        val basisJoins: Int, val contentEvents: List<Pair<String, Boolean>>)
    private data class StartupRecord(val frame: FrameRecord, val phaseSettled: Boolean,
        val queueSettled: Boolean, val liveHosts: Map<String, Int>)
    private data class PhaseJoinRecord(val owner: String, val target: Float, val epoch: Int,
        val stampNanos: Long, val before: Kinematic, val after: Kinematic,
        val beforeKind: Int, val afterKind: Int, val policy: String,
        val contextAccess: MotionAccess?, val specAccess: MotionAccess, val motionId: Long,
        val clockIdentity: Int, val edgeName: String? = null)
    private data class SearchFrameRecord(val frameMs: Long, val playerValue: Float,
        val playerTarget: Float, val playerSettled: Boolean, val queueValue: Float,
        val queueTarget: Float, val queueSettled: Boolean, val contextAccess: MotionAccess?,
        val specAccess: MotionAccess, val movingJoins: List<PhaseJoinRecord>,
        val contentEvents: List<Pair<String, Boolean>>, val navigationBoundary: Int,
        val nativeRoot: NativeRootRecord?, val playerId: Long, val queueId: Long,
        val playerEdgeJoins: List<PhaseJoinRecord>)
    private data class NativeRootRecord(val width: Int, val height: Int, val density: Float,
        val fontScale: Float, val imeBottom: Int, val navigationBottom: Int, val referenceTravel: Float?)
    private class NativeRootReferences(val window: WindowInfo, val density: Density,
        val ime: WindowInsets, val navigation: WindowInsets) {
        fun snapshot(clock: UiClock) = NativeRootRecord(window.containerSize.width,
            window.containerSize.height, density.density, density.fontScale,
            ime.getBottom(density), navigation.getBottom(density), clock.context?.fullPlayerReferenceTravelPx)
    }

    private class SheetEvents : MotionEvents {
        var phase: MotionFloat? = null
        var queuePhase: MotionFloat? = null
        var checking = false
        var basisJoins = 0
        val movingJoins = mutableListOf<MotionJoin>()
        val movingPhaseRecords = mutableListOf<PhaseJoinRecord>()
        val playerEdgeRecords = mutableListOf<PhaseJoinRecord>()
        override fun created(motion: MotionFloat) {}
        override fun retired(motion: MotionFloat, stamp: FrameStamp) {}
        override fun joined(motion: MotionFloat, join: MotionJoin) {
            if (motion.key == ("player" to "phase")) {
                phase = motion
                if (checking && join.afterKind != 0) {
                    movingJoins += join
                    record("player", motion, join)
                }
            }
            if (motion.key == ("queue" to "phase")) {
                queuePhase = motion
                if (checking && join.afterKind != 0) {
                    movingJoins += join
                    record("queue", motion, join)
                }
            }
            if (checking && motion.key == (("player" to "frame") to "edge1")) basisJoins++
            val key = motion.key as? Pair<*, *>
            if (checking && key != null && key.first == ("player" to "frame")) {
                playerEdgeRecords += capture("player-frame", motion, join, key.second as? String)
            }
        }
        private fun record(owner: String, motion: MotionFloat, join: MotionJoin) {
            movingPhaseRecords += capture(owner, motion, join)
        }
        private fun capture(owner: String, motion: MotionFloat, join: MotionJoin, edge: String? = null) =
            PhaseJoinRecord(owner, motion.target, join.epoch, join.stamp.nanos,
                join.before, join.after, join.beforeKind, join.afterKind, join.policy,
                motion.clock.context?.access, motion.clock.spec.access, motion.id,
                System.identityHashCode(motion.clock), edge)
    }

    private fun start(access: MotionAccess, loaded: Boolean, tab: MainTab = MainTab.Albums, input: String = "navigation") {
        this.access = access
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(context.getExternalFilesDir(null)).absolutePath, "collapsed-collection-navigation")
        output.mkdirs()
        records = File(output, "$access-$loaded-$tab-$input.tsv")
        records.writeText("source\tapi\tframeMs\taccess\treferenceTravel\tphase\tqueuePhase\tbasisJoins\tcontentEvents\n")
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(tab),
            fasterMotion = access == MotionAccess.Faster, reducedMotion = access == MotionAccess.Reduced))
        BrowsePreferences(context).write(BrowseRoute(tab), BrowseStyle(listSize = ListSize.Small, quickSearch = true), false)
        val track = tracks.first()
        val playback = if (loaded) PlaybackState(connected = true, trackId = track.id, trackUri = track.uri,
            title = track.title) else PlaybackState(connected = true)
        compose.setContent { CompositionLocalProvider(LocalSheetContentObserver provides { name, mounted ->
            contentEvents += name to mounted
            val count = (liveRetainedHosts[name] ?: 0) + if (mounted) 1 else -1
            check(count >= 0)
            if (count == 0) liveRetainedHosts.remove(name) else liveRetainedHosts[name] = count
        }) { LocalicaTheme {
            val clock = LocalUiClock.current
            val nativeWindow = LocalWindowInfo.current
            val density = LocalDensity.current
            val ime = WindowInsets.ime
            val navigation = WindowInsets.navigationBars
            SideEffect {
                clock.events = events
                nativeRoot = NativeRootReferences(nativeWindow, density, ime, navigation)
            }
            LocalicaScreen(LibraryUiState(snapshot, loading = false), playback, {}, {}, {}, {}, {}, {},
                playlist = PlaylistState(listOf(playlist), loading = false))
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val startup = compose.runOnIdle {
            val phase = checkNotNull(events.phase)
            val queue = checkNotNull(events.queuePhase)
            val context = checkNotNull(phase.clock.context)
            StartupRecord(FrameRecord(BuildConfig.SOURCE_SHA, Build.VERSION.SDK_INT,
                compose.mainClock.currentTime, context.access, context.fullPlayerReferenceTravelPx,
                phase.value(), queue.value(), events.basisJoins, contentEvents.toList()),
                phase.settled, queue.settled, liveRetainedHosts.toMap())
        }
        records.appendText("startup\t" + listOf(startup.frame, startup.phaseSettled,
            startup.queueSettled, startup.liveHosts).joinToString("\t") + "\n")
        compose.runOnIdle {
            assertEquals(access, startup.frame.access)
            assertTrue("Navigation begins at an exactly resting player", startup.phaseSettled)
            assertEquals(0f, startup.frame.phase, 0f)
            assertTrue("Navigation begins at an exactly resting Queue", startup.queueSettled)
            assertEquals(0f, startup.frame.queuePhase, 0f)
            assertTrue("Navigation begins without any live retained host", startup.liveHosts.isEmpty())
            navigationContentBoundary = startup.frame.contentEvents.size
            events.checking = true
        }
        compose.onAllNodesWithTag("browser-list").assertCountEquals(1)
    }

    private fun assertClosedFrame() {
        val record = compose.runOnIdle {
            val phase = checkNotNull(events.phase)
            val queue = checkNotNull(events.queuePhase)
            val context = checkNotNull(phase.clock.context)
            assertEquals(access, context.access)
            assertTrue(context.fullPlayerReferenceTravelPx > 1f)
            assertTrue("A collection basis replacement must leave the player at rest", phase.settled)
            assertEquals(0f, phase.value(), 0f)
            assertTrue("The Queue must stay at its closed rest", queue.settled)
            assertEquals(0f, queue.value(), 0f)
            assertTrue("No transient phase segment is admitted", events.movingJoins.isEmpty())
            assertTrue("No retained Player or Queue content may mount, even with cleared semantics",
                contentEvents.drop(navigationContentBoundary).isEmpty())
            FrameRecord(BuildConfig.SOURCE_SHA, Build.VERSION.SDK_INT, compose.mainClock.currentTime,
                context.access, context.fullPlayerReferenceTravelPx, phase.value(), queue.value(),
                events.basisJoins, contentEvents.toList())
        }
        records.appendText(listOf(record.source, record.api, record.frameMs, record.access,
            record.referenceTravel, record.phase, record.queuePhase, record.basisJoins,
            record.contentEvents).joinToString("\t") + "\n")
        compose.onAllNodesWithTag("queue-surface", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Collapse player", useUnmergedTree = true).assertCountEquals(0)
        val destination = requestedDestination
        if (destination != null) {
            val browse = compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes()
            val rows = compose.onAllNodesWithTag(destination).fetchSemanticsNodes()
            assertTrue("A requested handoff must never admit ambiguous browse owners", browse.size <= 1)
            assertTrue("A requested handoff must never admit ambiguous destination rows", rows.size <= 1)
            records.appendText("handoff\t${compose.mainClock.currentTime}\t$destination\t${browse.size}\t${rows.size}\n")
            assertTrue("Route admission stays inside the original early input window",
                compose.mainClock.currentTime - navigationFrame <= 100L)
            if (browse.size == 1 && rows.size == 1 &&
                rows.single().config.getOrNull(SemanticsProperties.Disabled) == null) requestedDestination = null
        }
        if (requestedDestination == null) compose.onAllNodesWithTag("browser-list").assertCountEquals(1)
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        assertClosedFrame()
    }

    private fun openAlbum() {
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val row = hasTestTag("media-Album:${album.id}")
        compose.onAllNodes(row).assertCountEquals(1)
        beginHandoff("media-basis:0")
        compose.onNode(row).performClick()
    }

    private fun beginHandoff(destination: String) {
        check(requestedDestination == null)
        navigationFrame = compose.mainClock.currentTime
        requestedDestination = destination
        records.appendText("handoff-request\t$navigationFrame\t$destination\n")
    }

    private fun awaitRouteAdmission(destination: String) {
        while (requestedDestination != null) frames(1)
        compose.onAllNodesWithTag("browser-list").assertCountEquals(1)
        compose.onAllNodesWithTag(destination).assertCountEquals(1)
        compose.onNodeWithTag(destination).assertIsEnabled().assertIsDisplayed()
    }

    private fun navigation(access: MotionAccess, loaded: Boolean) {
        start(access, loaded)
        if (loaded) compose.onNodeWithTag("player-surface").assertIsDisplayed()
        openAlbum()
        awaitRouteAdmission("media-basis:0")
        frames(32)
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(1)
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        beginHandoff("media-Album:${album.id}")
        compose.onNodeWithContentDescription("Back").performClick()
        awaitRouteAdmission("media-Album:${album.id}")
        frames(32)
        compose.runOnIdle { assertTrue("The control must exercise real basis replacements", events.basisJoins > 1) }
        // Positive visibility control, including Reduced: the retained Queue host must appear on a real opening.
        if (loaded) {
            val positiveBoundary = compose.runOnIdle { events.checking = false; contentEvents.size }
            compose.onNodeWithTag("player-surface").performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            compose.onNodeWithTag("queue-surface").assertExists()
            compose.runOnIdle {
                assertTrue(contentEvents.drop(positiveBoundary).contains("player-overlay" to true))
                assertTrue(contentEvents.drop(positiveBoundary).contains("queue-surface" to true))
            }
            compose.onNodeWithContentDescription("Open queue").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertTrue("The Queue body visibility control must really compose",
                contentEvents.drop(positiveBoundary).contains("queue-screen" to true)) }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun standardCollapsedPlayerNeverComposesTheQueueDuringCollectionNavigation() = navigation(MotionAccess.Standard, true)
    @Test fun fasterCollapsedPlayerNeverComposesTheQueueDuringCollectionNavigation() = navigation(MotionAccess.Faster, true)
    @Test fun reducedCollapsedPlayerNeverComposesTheQueueDuringCollectionNavigation() = navigation(MotionAccess.Reduced, true)
    @Test fun standardEmptyPlayerRemainsClosedDuringCollectionNavigation() = navigation(MotionAccess.Standard, false)
    @Test fun fasterEmptyPlayerRemainsClosedDuringCollectionNavigation() = navigation(MotionAccess.Faster, false)
    @Test fun reducedEmptyPlayerRemainsClosedDuringCollectionNavigation() = navigation(MotionAccess.Reduced, false)

    private enum class Input { Pinch, Back, Search }

    private fun recordAction(input: Input) {
        val actionFrame = compose.mainClock.currentTime
        records.appendText("input\t$input\tnavigation=$navigationFrame\taction=$actionFrame\n")
        assertTrue("Input must begin inside both Standard and Faster chrome transitions",
            actionFrame - navigationFrame in 1L..100L)
    }

    private fun interactions(access: MotionAccess, input: Input) {
        start(access, true, MainTab.Playlists, input.name)
        val row = hasText(playlist.name) and hasClickAction()
        compose.onAllNodes(row).assertCountEquals(1)
        beginHandoff("media-basis:0")
        compose.onNode(row).performClick()
        frames(2)
        awaitRouteAdmission("media-basis:0")
        val list = compose.onNodeWithTag("browser-list")
        when (input) {
            Input.Pinch -> {
                val before = list.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                recordAction(input)
                list.performTouchInput {
                    down(0, Offset(centerX - 50f, centerY)); down(1, Offset(centerX + 50f, centerY))
                    moveTo(0, Offset(centerX - 60f, centerY)); moveTo(1, Offset(centerX + 60f, centerY))
                }
                frames(2)
                assertNotEquals("The list must admit pinch recognition during navigation", before,
                    list.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                list.performTouchInput { up(0); up(1) }
                frames(32)
            }
            Input.Back -> {
                list.performScrollToIndex(35)
                frames(1)
                val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                compose.runOnIdle { assertTrue("Back starts with a scrolled list", range.value() > 0f) }
                recordAction(input)
                pressSystemBack()
                frames(40)
                compose.runOnIdle { assertEquals("Back must return the collection list to its home", 0f, range.value(), 0f) }
                compose.onNodeWithContentDescription("Playlist actions").assertIsDisplayed()
            }
            Input.Search -> {
                compose.onAllNodesWithText("Search ${playlist.name}").assertCountEquals(1)
                val search = compose.onNodeWithText("Search ${playlist.name}").assertIsDisplayed()
                compose.onAllNodesWithTag("player-surface").assertCountEquals(1)
                val searchNode = search.fetchSemanticsNode()
                val playerNode = compose.onNodeWithTag("player-surface").fetchSemanticsNode()
                val searchBounds = searchNode.boundsInRoot
                val playerBounds = playerNode.boundsInRoot
                // Match the pinned Compose click implementation's visible-size integer conversion.
                // Raw bounds are also exported unchanged, with every Float bit retained below.
                val frameworkClick = searchBounds.topLeft + Offset(searchBounds.width.roundToInt() / 2f,
                    searchBounds.height.roundToInt() / 2f)
                // Use the largest exposed rectangle of the pill, preserving real touch routing.
                val exposed = listOf(
                    Rect(searchBounds.left, searchBounds.top, searchBounds.right,
                        playerBounds.top.coerceIn(searchBounds.top, searchBounds.bottom)),
                    Rect(searchBounds.left, playerBounds.bottom.coerceIn(searchBounds.top, searchBounds.bottom),
                        searchBounds.right, searchBounds.bottom),
                    Rect(searchBounds.left, searchBounds.top,
                        playerBounds.left.coerceIn(searchBounds.left, searchBounds.right), searchBounds.bottom),
                    Rect(playerBounds.right.coerceIn(searchBounds.left, searchBounds.right), searchBounds.top,
                        searchBounds.right, searchBounds.bottom)
                ).filter { it.width > 0f && it.height > 0f }
                    .maxByOrNull { it.width * it.height }
                val clickPoint = checkNotNull(exposed) { "The Search pill must have an exposed touch point" }.center
                assertTrue("The Search touch must stay inside the pill", searchBounds.contains(clickPoint))
                assertFalse("The Search pill touch must not be covered by the collapsed player",
                    playerBounds.contains(clickPoint))
                val geometry = listOf(searchBounds.left, searchBounds.top, searchBounds.right, searchBounds.bottom,
                    playerBounds.left, playerBounds.top, playerBounds.right, playerBounds.bottom,
                    searchBounds.center.x, searchBounds.center.y,
                    searchNode.positionInRoot.x, searchNode.positionInRoot.y,
                    playerNode.positionInRoot.x, playerNode.positionInRoot.y,
                    frameworkClick.x, frameworkClick.y, clickPoint.x, clickPoint.y)
                records.appendText("search-geometry\t${compose.mainClock.currentTime}\t" +
                    geometry.flatMap { listOf(it.toString(), it.toRawBits().toString()) }.joinToString("\t") +
                    "\tsemantic-center-inside-player=${playerBounds.contains(searchBounds.center)}" +
                    "\tframework-click-inside-player=${playerBounds.contains(frameworkClick)}" +
                    "\tactual-click-inside-player=${playerBounds.contains(clickPoint)}\n")
                val beforeClick = compose.runOnIdle { searchSnapshot() }
                records.appendText("search-before\t$beforeClick\n")
                recordAction(input)
                search.performTouchInput { click(clickPoint - searchBounds.topLeft) }
                repeat(6) {
                    compose.mainClock.advanceTimeByFrame()
                    var observed: SearchFrameRecord? = null
                    var originalFailure: Throwable? = null
                    try {
                        compose.runOnIdle {
                            observed = searchSnapshot()
                            assertTrue(events.movingJoins.isEmpty())
                            assertTrue(contentEvents.drop(navigationContentBoundary).isEmpty())
                        }
                    } catch (failure: Throwable) {
                        originalFailure = failure
                        throw failure
                    } finally {
                        try {
                            val frame = observed
                            if (frame != null) records.appendText("search-frame\t$frame\n")
                        } catch (exportFailure: Throwable) {
                            val failure = originalFailure
                            if (failure != null) failure.addSuppressed(exportFailure) else throw exportFailure
                        }
                    }
                }
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
                compose.onNode(hasSetTextAction()).assertIsEnabled().performTextInput("Song")
            }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun standardCollapsedCollectionAdmitsPinchDuringNavigation() = interactions(MotionAccess.Standard, Input.Pinch)
    @Test fun fasterCollapsedCollectionAdmitsPinchDuringNavigation() = interactions(MotionAccess.Faster, Input.Pinch)
    @Test fun reducedCollapsedCollectionAdmitsPinchDuringNavigation() = interactions(MotionAccess.Reduced, Input.Pinch)
    @Test fun standardCollapsedCollectionAdmitsListBackDuringNavigation() = interactions(MotionAccess.Standard, Input.Back)
    @Test fun fasterCollapsedCollectionAdmitsListBackDuringNavigation() = interactions(MotionAccess.Faster, Input.Back)
    @Test fun reducedCollapsedCollectionAdmitsListBackDuringNavigation() = interactions(MotionAccess.Reduced, Input.Back)
    @Test fun standardCollapsedCollectionAdmitsSearchDuringNavigation() = interactions(MotionAccess.Standard, Input.Search)
    @Test fun fasterCollapsedCollectionAdmitsSearchDuringNavigation() = interactions(MotionAccess.Faster, Input.Search)
    @Test fun reducedCollapsedCollectionAdmitsSearchDuringNavigation() = interactions(MotionAccess.Reduced, Input.Search)

    /** Called only in a Main snapshot block; the returned record owns immutable copied values. */
    private fun searchSnapshot(): SearchFrameRecord {
        val phase = checkNotNull(events.phase)
        val queue = checkNotNull(events.queuePhase)
        return SearchFrameRecord(compose.mainClock.currentTime, phase.value(), phase.target,
            phase.settled, queue.value(), queue.target, queue.settled, phase.clock.context?.access,
            phase.clock.spec.access, events.movingPhaseRecords.toList(), contentEvents.toList(),
            navigationContentBoundary, nativeRoot?.snapshot(phase.clock), phase.id, queue.id,
            events.playerEdgeRecords.toList())
    }
}
