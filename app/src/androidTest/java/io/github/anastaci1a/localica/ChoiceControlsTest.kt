package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChoiceControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sortChoicesExposeSelectionAndSaveDirection() {
        var saved: BrowseStyle? = null
        compose.setContent { LocalicaTheme {
            BrowseSortDialog(BrowseRoute(MainTab.Songs), BrowseStyle(), false, {}, { value, _ -> saved = value }, {})
        } }
        compose.onNodeWithText("Sort by").performClick()
        compose.onNodeWithText("Title").assertIsSelected()
        compose.onNodeWithText("Year").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.onNodeWithText("Descending").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Ascending").assertIsNotSelected()
        compose.onNodeWithText("Save").performClick()
        assertEquals(TrackSort.Year, saved!!.sort)
        assertFalse(saved!!.ascending)
    }

    private fun choicePhase(name: String, action: () -> Unit) {
        android.util.Log.i("ChoiceControlsTest", "phase=$name")
        try { action() } catch (failure: Throwable) {
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().forEachIndexed { index, node ->
                    val view = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
                    val native = compose.runOnUiThread { "attached=${view.isAttachedToWindow} focused=${view.hasWindowFocus()}" }
                    android.util.Log.e("ChoiceControlsTest", "failed phase=$name root=$index bounds=${node.boundsInRoot} native=$native")
                    roots[index].printToLog("ChoiceControlsTest")
                }
                android.util.Log.e("ChoiceControlsTest", "windows=" + androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.windows.joinToString { "id=${it.id} focused=${it.isFocused} active=${it.isActive} type=${it.type} package=${it.root?.packageName}" })
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Test fun sortAndViewTabsKeepOneDraftAndSaveBothTogether() {
        val trace = ChoiceTraceBuffer()
        BufferedChoiceTrace.active = trace
        var primaryFailure: Throwable? = null
        try {
            var saved: BrowseStyle? = null
            compose.setContent { LocalicaTheme {
                BrowseSortDialog(BrowseRoute(MainTab.Albums), BrowseStyle(), false, {}, { value, _ -> saved = value }, {})
            } }
            choicePhase("open sort choices") { compose.onNodeWithText("Sort by").performClick() }
            choicePhase("single Year tap selects draft") {
                val year = compose.onNodeWithText("Year").performScrollTo()
                android.util.Log.i("ChoiceControlsTest", "Year tap bounds=${year.fetchSemanticsNode().boundsInRoot}")
                year.performClick().assertIsSelected()
            }
            choicePhase("Select commits Year to parent") {
                compose.onNodeWithText("Select", substring = false).performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("Select", substring = false).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Year").assertIsDisplayed()
            }
            val body = compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Save only for this list").assertDoesNotExist()
            compose.onNodeWithText("View", substring = false).performClick().assertIsSelected()
            assertEquals(body, compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("Save only for this list").assertDoesNotExist()
            compose.onNodeWithText("Gallery", substring = false).performClick()
            compose.onNodeWithContentDescription("Items per row").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
            compose.onNodeWithText("Sort", substring = false).performClick().assertIsSelected()
            choicePhase("return to Sort retains Year") {
                // Selected tab semantics update before the incoming page is placed.
                compose.waitUntil(5000) {
                    runCatching { compose.onNodeWithText("Year").assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithText("Year").assertIsDisplayed()
            }
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle {
                assertEquals(TrackSort.Year, saved!!.sort)
                assertEquals(Presentation.Standard, saved!!.presentation)
                assertEquals(3, saved!!.itemsPerRow)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // No added synchronization or logging until the original test flow has ended.
            val exportFailure = runCatching {
                try { compose.runOnUiThread { BufferedChoiceTrace.active = null } }
                catch (detachFailure: Throwable) {
                    runCatching { android.util.Log.e("ChoiceOriginalBuffer",
                        "BUFFER unavailable: UI-thread detach failed; unsafe export omitted", detachFailure) }
                        .exceptionOrNull()?.let(detachFailure::addSuppressed)
                    throw detachFailure
                }
                trace.export().forEach { android.util.Log.i("ChoiceOriginalBuffer", it) }
            }.exceptionOrNull()
            BufferedChoiceTrace.active = null
            if (exportFailure != null) {
                val primary = primaryFailure
                if (primary != null) primary.addSuppressed(exportFailure) else throw exportFailure
            }
        }
    }

    @Test fun mutableSortingAppliesWithoutClosingAndViewKeepsItsOwnSaveScope() {
        var order by mutableStateOf(io.github.anastaci1a.localica.library.SavedOrder(listOf("b", "a")))
        var closed = false
        var saves = 0
        var randomizations = 0
        compose.setContent { LocalicaTheme {
            BrowseSortDialog(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix")), BrowseStyle(), false,
                { closed = true }, { _, _ -> saves++ }, {},
                onRandomize = { done -> randomizations++; order = order.copy(sort = TrackSort.Manual); done(true) },
                mutableOrder = order, onApplySort = { method, ascending, done ->
                    order = order.copy(sort = method, ascending = ascending); done(true)
                })
        } }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Save only for this list").assertDoesNotExist()
        compose.onNodeWithText("Sort by").performClick()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.runOnIdle { assertEquals(TrackSort.Manual, order.sort) }
        compose.onNodeWithText("Apply").assertIsEnabled().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(TrackSort.Title, order.sort); assertFalse(closed) }
        compose.onNodeWithText("Randomize", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, randomizations); assertEquals(TrackSort.Manual, order.sort); assertFalse(closed) }
        val body = compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("View", substring = false).performClick()
        assertEquals(body, compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Save only for this list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun galleryCountSnapsPersistsAndOverridesWidthPresets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Albums)
        val preferences = BrowsePreferences(context)
        val previous = preferences.read(route)
        compose.setContent { LocalicaTheme {
            BrowseSortDialog(route, BrowseStyle(presentation = Presentation.Standard), false, {},
                { value, override -> preferences.write(route, value, override) }, {}, presentationOnly = true)
        } }
        try {
            compose.onNodeWithContentDescription("Items per row").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4.1f) }
            compose.onNodeWithText("Items per row: 4").assertIsDisplayed()
            compose.onNodeWithText("Save").performClick()
            val saved = preferences.read(route)
            assertEquals(4, saved.itemsPerRow)
            assertEquals(4, galleryColumns(saved.presentation, 360f, saved.itemsPerRow))
            assertEquals(4, galleryColumns(saved.presentation, 900f, saved.itemsPerRow))
            assertEquals(1, galleryColumns(Presentation.List, 900f, saved.itemsPerRow))
        } finally { preferences.write(route, previous, false) }
    }
}
