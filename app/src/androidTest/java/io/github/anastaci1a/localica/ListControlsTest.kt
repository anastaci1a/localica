package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ListControlsTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0..99).map { n -> Track("song:$n", "Z song " + n.toString().padStart(3, '0'), year = 2001,
        fileName = "$n.mp3", relativePath = "$n.mp3") }
    @Before @After fun clearPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }
    private fun awaitDisplayed(node: SemanticsNodeInteraction) {
        try {
            compose.waitUntil(5000) {
                // Selection first measures at zero occupancy. Its layout write starts
                // the reveal on the next composition, not on the pointer-up itself.
                compose.mainClock.advanceTimeByFrame()
                runCatching { node.assertIsDisplayed() }.isSuccess
            }
            node.assertIsDisplayed()
        } catch (failure: Throwable) {
            runCatching {
                val roots = compose.onAllNodes(isRoot())
                roots.fetchSemanticsNodes().indices.forEach { roots[it].printToLog("ListControls", maxDepth = 12) }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    @Test fun scrollbarPreviewsNameOrYearOnlyWhileDragging() {
        var route by mutableStateOf(BrowseRoute(MainTab.Songs))
        compose.setContent { LocalicaTheme { key(route.key) { BrowserScreen(
            LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(loading = false), route,
            {}, {}, {}, { _, _, _ -> }, null, null, true, false) } } }
        val bar = compose.onNodeWithTag("browse-scrollbar")
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 500) }
        compose.onNodeWithTag("scroll-preview").assertIsDisplayed()
        compose.onNodeWithText("Z").assertIsDisplayed()
        bar.performTouchInput { up() }
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
        compose.runOnIdle { route = BrowseRoute(MainTab.Songs, querySort = TrackSort.Year) }
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 500) }
        compose.onNodeWithTag("scroll-preview").assertIsDisplayed()
        compose.onNode(hasText("2001") and hasAnyAncestor(hasTestTag("scroll-preview"))).assertIsDisplayed()
        bar.performTouchInput { up() }
        compose.onNodeWithTag("scroll-preview").assertDoesNotExist()
    }
    @Test fun queueCurrentSongButtonReturnsToTheOccurrenceAndHandlesTheEnd() {
        val queue = QueueEdits.create(tracks, 4, "Queue")
        var current by mutableStateOf(queue.entries[4].id)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val playlists = PlaylistState(loading = false)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id))
        val queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        compose.setContent { LocalicaTheme { BrowserScreen(
            library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
            queueState = queues, playingEntryId = current) } }
        awaitDisplayed(compose.onNodeWithTag("media-$current"))
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Z song 070"))
        compose.onNodeWithContentDescription("Current song").assertIsDisplayed()
        val selected = compose.onNodeWithTag("media-${queue.entries[70].id}")
        compose.awaitExpectedWindowFocus(selected)
        val row = selected.fetchSemanticsNode()
        val rowBounds = row.boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val view = (row.root as ViewRootForTest).view
        val (nativeVisibleBounds, systemBarSafeBounds) = compose.runOnUiThread {
            val visible = android.graphics.Rect()
            check(view.getLocalVisibleRect(visible)) { "The input root must have native visible bounds" }
            val rootOrigin = IntArray(2).also(view::getLocationOnScreen)
            val window = view.rootView
            val windowOrigin = IntArray(2).also(window::getLocationOnScreen)
            val bars = checkNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(WindowInsetsCompat.Type.systemBars())
            val windowLeft = (windowOrigin[0] - rootOrigin[0]).toFloat()
            val windowTop = (windowOrigin[1] - rootOrigin[1]).toFloat()
            Rect(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat()) to
                Rect(windowLeft + bars.left, windowTop + bars.top,
                    windowLeft + window.width - bars.right, windowTop + window.height - bars.bottom)
        }
        val viewport = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val returnBounds = compose.onNodeWithContentDescription("Current song").fetchSemanticsNode().boundsInRoot
        val menuBounds = compose.onNodeWithContentDescription("Actions for Z song 070").fetchSemanticsNode().boundsInRoot
        // The text lookup merges into this whole row. Its center overlaps Current
        // song on API36 instead of being an exposed row contact.
        // Keep the same occurrence and viewport; exercise its exposed row instead.
        // Edge-to-edge semantics may also extend behind native navigation chrome.
        val exposedRow = rowBounds.intersect(viewport).intersect(nativeVisibleBounds).intersect(systemBarSafeBounds)
        assertTrue("The occurrence must expose a physical contact area: $exposedRow",
            exposedRow.width > 0f && exposedRow.height > 0f)
        val rootContact = Offset(rowBounds.left + rowBounds.width * .25f, exposedRow.center.y)
        val contact = rootContact - rowBounds.topLeft
        android.util.Log.i("ListControls", "Selection hit path: row=$rowBounds viewport=$viewport root=$rootBounds " +
            "nativeVisible=$nativeVisibleBounds systemBarSafe=$systemBarSafeBounds exposedRow=$exposedRow " +
            "mergedCenter=${rowBounds.center} return=$returnBounds menu=$menuBounds contact=$rootContact")
        assertTrue("Selection hold must remain inside its occurrence", rowBounds.contains(rootContact))
        assertTrue("Selection hold must remain inside the list", viewport.contains(rootContact))
        assertTrue("Selection hold must remain inside the Compose root", rootBounds.contains(rootContact))
        assertTrue("Selection hold must remain natively visible", nativeVisibleBounds.contains(rootContact))
        assertTrue("Selection hold must not belong to system bars", systemBarSafeBounds.contains(rootContact))
        assertFalse("Selection hold must not belong to Current song", returnBounds.contains(rootContact))
        assertFalse("Selection hold must not belong to the row menu", menuBounds.contains(rootContact))
        var held = false
        try {
            selected.performTouchInput { down(contact); held = true; advanceEventTime(650); moveTo(contact) }
            selected.assertIsSelected()
            compose.onNodeWithText("Select song").assertDoesNotExist()
            compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
            compose.onNodeWithText("1 selected").assertDoesNotExist()
            selected.performTouchInput { up(); held = false }
        } finally { if (held) selected.performTouchInput { cancel() } }
        awaitDisplayed(compose.onNodeWithText("1 selected"))
        compose.onNodeWithContentDescription("Close selection").performClick()
        awaitDisplayed(compose.onNodeWithContentDescription("Current song"))
        compose.onNodeWithContentDescription("Current song").performClick()
        awaitDisplayed(compose.onNodeWithTag("media-${queue.entries[4].id}"))
        compose.onNodeWithText("Z song 004").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
        compose.runOnIdle { current = queue.entries.last().id }
        awaitDisplayed(compose.onNodeWithTag("media-${queue.entries.last().id}"))
        compose.onNodeWithText("Z song 099").assertIsDisplayed()
        compose.onNodeWithContentDescription("Current song").assertDoesNotExist()
    }
}
