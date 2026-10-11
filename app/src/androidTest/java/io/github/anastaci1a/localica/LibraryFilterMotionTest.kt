package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryFilterMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionLeadsAndDeselectingRestoresConfiguredOrderThroughInterruptions() {
        val filters = listOf(LibraryFilter.Artists, LibraryFilter.Songs, LibraryFilter.Albums)
        var selected by mutableStateOf<MediaKind?>(null)
        compose.setContent { LocalicaTheme {
            LibraryFilterBubbles(filters, selected, false, { selected = it }, {})
        } }
        fun left(name: String) = compose.onNodeWithTag("filter-$name").fetchSemanticsNode().boundsInRoot.left
        val original = listOf(left("Artists"), left("Songs"), left("Albums"))
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Albums").assertIsSelected()
        assertTrue(left("Albums") < left("Artists"))
        compose.onNodeWithText("Albums").performClick()
        assertEquals(null, selected)
        assertEquals(original[0], left("Artists"), 1f)
        assertEquals(original[1], left("Songs"), 1f)
        assertEquals(original[2], left("Albums"), 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = MediaKind.Album }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { selected = MediaKind.Song }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { selected = null }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(original[0], left("Artists"), 1f)
        assertEquals(original[1], left("Songs"), 1f)
        assertEquals(original[2], left("Albums"), 1f)
    }

    @Test fun filtersAndSearchHaveEqualBodiesAndPlacementAtLargeTextSizes() {
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Column {
                    Box(Modifier.fillMaxWidth().testTag("filters-slot")) {
                        LibraryFilterBubbles(listOf(LibraryFilter.Albums), null, true, {}, {})
                    }
                    Box(Modifier.fillMaxWidth().testTag("search-slot")) {
                        ListSearchPill("Search Albums", {}, Modifier.padding(horizontal = 12.dp, vertical = ListPillVerticalInset))
                    }
                }
            }
        } }
        val filterSlot = compose.onNodeWithTag("filters-slot").fetchSemanticsNode().boundsInRoot
        val searchSlot = compose.onNodeWithTag("search-slot").fetchSemanticsNode().boundsInRoot
        val filter = compose.onNodeWithTag("filter-Albums").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
        assertEquals(filterSlot.height, searchSlot.height, 1f)
        assertEquals(filter.height, search.height, 1f)
        assertEquals(filter.top - filterSlot.top, search.top - searchSlot.top, 1f)
    }

    @Test fun legacyAllIsRemovedWithoutChangingOrderOrCombinedContents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LibraryFilterPreferences(context)
        val old = preferences.read()
        val store = context.getSharedPreferences("library-filters", 0)
        try {
            store.edit().putString("visible", "Albums,All,Songs")
                .putStringSet("all", setOf("Artist", "Folder")).commit()
            assertEquals(listOf(LibraryFilter.Albums, LibraryFilter.Songs), preferences.read().visible)
            assertEquals(setOf(MediaKind.Artist, MediaKind.Folder), preferences.read().allCollections)
            store.edit().putString("visible", "All").commit()
            assertTrue(preferences.read().visible.isEmpty())
            preferences.write(preferences.read())
            assertTrue(preferences.read().visible.isEmpty())
        } finally { preferences.write(old) }
    }
}
