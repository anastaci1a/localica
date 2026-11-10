package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
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
    private lateinit var records: File
    private lateinit var access: MotionAccess
    private data class FrameRecord(val source: String, val api: Int, val frameMs: Long,
        val access: MotionAccess, val referenceTravel: Float, val phase: Float, val queuePhase: Float,
        val basisJoins: Int, val contentEvents: List<Pair<String, Boolean>>)

    private class SheetEvents : MotionEvents {
        var phase: MotionFloat? = null
        var queuePhase: MotionFloat? = null
        var checking = false
        var basisJoins = 0
        val movingJoins = mutableListOf<MotionJoin>()
        override fun created(motion: MotionFloat) {}
        override fun retired(motion: MotionFloat, stamp: FrameStamp) {}
        override fun joined(motion: MotionFloat, join: MotionJoin) {
            if (motion.key == ("player" to "phase")) {
                phase = motion
                if (checking && join.afterKind != 0) movingJoins += join
            }
            if (motion.key == ("queue" to "phase")) {
                queuePhase = motion
                if (checking && join.afterKind != 0) movingJoins += join
            }
            if (checking && motion.key == (("player" to "frame") to "edge1")) basisJoins++
        }
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
        }) { LocalicaTheme {
            val clock = LocalUiClock.current
            SideEffect { clock.events = events }
            LocalicaScreen(LibraryUiState(snapshot, loading = false), playback, {}, {}, {}, {}, {}, {},
                playlist = PlaylistState(listOf(playlist), loading = false))
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { events.checking = true }
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
            assertTrue("No retained Player or Queue content may mount, even with cleared semantics", contentEvents.isEmpty())
            FrameRecord(BuildConfig.SOURCE_SHA, Build.VERSION.SDK_INT, compose.mainClock.currentTime,
                context.access, context.fullPlayerReferenceTravelPx, phase.value(), queue.value(),
                events.basisJoins, contentEvents.toList())
        }
        records.appendText(listOf(record.source, record.api, record.frameMs, record.access,
            record.referenceTravel, record.phase, record.queuePhase, record.basisJoins,
            record.contentEvents).joinToString("\t") + "\n")
        compose.onAllNodesWithTag("queue-surface", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Collapse player", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("browser-list").assertCountEquals(1)
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        assertClosedFrame()
    }

    private fun openAlbum() {
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val row = hasTestTag("media-Album:${album.id}")
        compose.onAllNodes(row).assertCountEquals(1)
        compose.onNode(row).performClick()
    }

    private fun navigation(access: MotionAccess, loaded: Boolean) {
        start(access, loaded)
        if (loaded) compose.onNodeWithTag("player-surface").assertIsDisplayed()
        openAlbum()
        frames(32)
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(1)
        compose.onNodeWithContentDescription("Back").performClick()
        frames(32)
        compose.runOnIdle { assertTrue("The control must exercise real basis replacements", events.basisJoins > 1) }
        // Positive visibility control, including Reduced: the retained Queue host must appear on a real opening.
        if (loaded) {
            compose.runOnIdle { events.checking = false }
            compose.onNodeWithTag("player-surface").performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            compose.onNodeWithTag("queue-surface").assertExists()
            compose.runOnIdle {
                assertTrue(contentEvents.contains("player-overlay" to true))
                assertTrue(contentEvents.contains("queue-surface" to true))
            }
            compose.onNodeWithContentDescription("Open queue").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertTrue("The Queue body visibility control must really compose",
                contentEvents.contains("queue-screen" to true)) }
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

    private fun interactions(access: MotionAccess, input: Input) {
        start(access, true, MainTab.Playlists, input.name)
        val row = hasText(playlist.name) and hasClickAction()
        compose.onAllNodes(row).assertCountEquals(1)
        val navigationFrame = compose.mainClock.currentTime
        compose.onNode(row).performClick()
        frames(2)
        val list = compose.onNodeWithTag("browser-list")
        val inputFrame = compose.mainClock.currentTime
        assertTrue("Input must begin inside both Standard and Faster chrome transitions",
            inputFrame - navigationFrame in 1..100)
        when (input) {
            Input.Pinch -> {
                val before = list.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
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
                pressSystemBack()
                frames(40)
                compose.runOnIdle { assertEquals("Back must return the collection list to its home", 0f, range.value(), 0f) }
                compose.onNodeWithContentDescription("Playlist actions").assertIsDisplayed()
            }
            Input.Search -> {
                compose.onAllNodesWithText("Search ${playlist.name}").assertCountEquals(1)
                compose.onNodeWithText("Search ${playlist.name}").assertIsDisplayed().performClick()
                repeat(6) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle {
                        assertTrue(events.movingJoins.isEmpty())
                        assertTrue(contentEvents.isEmpty())
                    }
                }
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
                compose.onNode(hasSetTextAction()).assertIsEnabled().performTextInput("Song")
            }
        }
        records.appendText("input\t$input\tnavigation=$navigationFrame\tinput=$inputFrame\n")
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
}
