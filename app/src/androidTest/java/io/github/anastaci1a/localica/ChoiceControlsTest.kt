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

    @Test fun sortAndViewTabsKeepOneDraftAndSaveBothTogether() {
        var saved: BrowseStyle? = null
        compose.setContent { LocalicaTheme {
            BrowseSortDialog(BrowseRoute(MainTab.Albums), BrowseStyle(), false, {}, { value, _ -> saved = value }, {})
        } }
        compose.onNodeWithText("Sort by").performClick()
        compose.onNodeWithText("Year").performScrollTo().performClick()
        compose.onNodeWithText("Select", substring = false).performClick()
        val body = compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Save only for this list").assertIsDisplayed().performClick().assertIsOn()
        compose.onNodeWithText("View", substring = false).performClick().assertIsSelected()
        assertEquals(body, compose.onNodeWithTag("browse-options-body").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Save only for this list").assertIsOn()
        compose.onNodeWithText("Gallery", substring = false).performClick()
        compose.onNodeWithContentDescription("Items per row").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.onNodeWithText("Sort", substring = false).performClick().assertIsSelected()
        compose.onNodeWithText("Year").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(TrackSort.Year, saved!!.sort)
            assertEquals(Presentation.Standard, saved!!.presentation)
            assertEquals(3, saved!!.itemsPerRow)
        }
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
