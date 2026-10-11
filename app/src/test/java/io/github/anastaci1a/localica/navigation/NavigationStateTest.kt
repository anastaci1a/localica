package io.github.anastaci1a.localica.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    @Test fun tabResetKeepsRootIdentityAndPreferencesWithoutTouchingOtherTabsOrHistory() {
        val initial = NavigationState.fresh(listOf(MainTab.Home, MainTab.Library)).switch(MainTab.Library)
        val root = initial.route.copy(filter = MediaKind.Artist)
        val state = initial.update(root).open(BrowseRoute(MainTab.Library, search = true, text = "Artist"))
        val reset = state.resetTab(MainTab.Library)
        assertEquals(root.copy(filter = null), reset.route)
        assertEquals(initial.history, reset.history)
        assertEquals(state.stacks[MainTab.Home], reset.stacks[MainTab.Home])
    }

    @Test fun freshLaunchPrefersHomeAndTabRevisitsRetainDetails() {
        val fresh = NavigationState.fresh(listOf(MainTab.Library, MainTab.Home))
        assertEquals(MainTab.Home, fresh.active)
        val detail = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Album, "album"), "Album")
        val library = fresh.switch(MainTab.Library).open(detail)
        val revisited = library.switch(MainTab.Home).switch(MainTab.Library)
        assertEquals(detail, revisited.route)
        assertEquals(listOf(MainTab.Home, MainTab.Library, MainTab.Home), revisited.history)
        assertSame(revisited, revisited.switch(MainTab.Library))
    }
    @Test fun backPopsSearchThenDetailsThenTabHistory() {
        val library = NavigationState().switch(MainTab.Library)
        val detail = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, "list"))
        val search = detail.copy(search = true, text = "query", key = "search")
        val state = library.open(detail).open(search)
        assertEquals(detail, state.back(false)!!.route)
        assertEquals(library, state.back(false)!!.back(false))
        assertNull(library.back(false))
        assertEquals(MainTab.Home, library.back(true)!!.active)
        assertNull(library.back(true)!!.back(true))
    }
    @Test fun removingTabsPrunesStacksAndHistoryWithoutLosingRevisits() {
        val state = NavigationState().switch(MainTab.Library).switch(MainTab.Songs).switch(MainTab.Library)
        val kept = state.reconcile(listOf(MainTab.Home, MainTab.Library))
        assertEquals(listOf(MainTab.Home, MainTab.Library), kept.history)
        assertFalse(MainTab.Songs in kept.stacks)
        assertEquals(MainTab.Home, kept.reconcile(listOf(MainTab.Home)).active)
    }
}
