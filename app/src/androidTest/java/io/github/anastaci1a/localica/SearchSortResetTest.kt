package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchSortResetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun globalResetSurvivesResultBackRecreationAndPreferenceNotifications() = resetSearch(true)
    @Test fun scopedResetSurvivesResultBackRecreationAndPreferenceNotifications() = resetSearch(false)

    private fun resetSearch(global: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("browse-style", 0)
        store.edit().clear().commit()
        val preferences = BrowsePreferences(context)
        val root = BrowseRoute(MainTab.Albums)
        val older = root.copy(search = true, globalSearch = global, filter = MediaKind.Album.takeIf { global },
            text = "older", queryCommitted = true, querySort = TrackSort.Year, queryAscending = true, key = "older-query")
        val search = older.copy(text = "Album", queryAscending = false, key = "current-query")
        preferences.write(search, BrowseStyle(sort = TrackSort.Title, ascending = true), false)
        preferences.write(search, BrowseStyle(sort = TrackSort.Year, ascending = false), true)
        val tracks = listOf(
            Track("alpha", "Alpha song", album = "Album Alpha", year = 1990, fileName = "a.mp3", relativePath = "a.mp3"),
            Track("beta", "Beta song", album = "Album Beta", year = 2020, fileName = "b.mp3", relativePath = "b.mp3"),
        )
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks)
        val items = BrowseQueries.evaluate(snapshot, emptyList(), root).items.associateBy { it.title }
        lateinit var navigation: MutableState<NavigationState>
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                navigation = rememberSaveable(stateSaver = Saver(
                    save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
                    mutableStateOf(NavigationState(MainTab.Albums, mapOf(MainTab.Albums to listOf(root, older, search))))
                }
                var sorting by rememberSaveable { mutableStateOf(true) }
                val route = navigation.value.route
                LocalicaTheme {
                    key(route.key) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                            { navigation.value = navigation.value.update(it) },
                            { item -> navigation.value = navigation.value.open(BrowseRoute(MainTab.Albums, item.ref, item.title)) },
                            {}, { _, _, _ -> }, null, null, true, false,
                            sorting = sorting, onSortDismiss = { sorting = false }, reducedMotion = true,
                            onSearchEdit = { navigation.value = navigation.value.editSearch(it) },
                            onSearchCommit = { navigation.value = navigation.value.commitSearch() },
                            onBack = { navigation.value = navigation.value.back(false)!! })
                    }
                }
            }
            compose.onNodeWithText("Use list-type defaults").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                assertFalse(preferences.overridden(search))
                assertEquals(TrackSort.Title, preferences.read(search).sort)
                assertEquals(search.copy(querySort = TrackSort.Title, queryAscending = true), navigation.value.route)
                assertEquals(older, navigation.value.stacks.getValue(MainTab.Albums)[1])
            }
            fun assertTitleOrder() {
                compose.waitUntil(5000) {
                    val alpha = compose.onAllNodesWithTag("media-${items.getValue("Album Alpha").key}").fetchSemanticsNodes()
                    val beta = compose.onAllNodesWithTag("media-${items.getValue("Album Beta").key}").fetchSemanticsNodes()
                    alpha.size == 1 && beta.size == 1 && alpha.single().boundsInRoot.top < beta.single().boundsInRoot.top
                }
            }
            assertTitleOrder()
            // A later notification must use the latest retained route, not the
            // Year/descending route captured when this observer was installed.
            compose.runOnIdle {
                preferences.write(search, BrowseStyle(sort = TrackSort.Year, ascending = false, extraInfo = ExtraInfo.Count, listSize = ListSize.Large), false)
            }
            compose.waitUntil(5000) {
                runCatching {
                    compose.onNodeWithTag("browser-list").assert(SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "List, Large"))
                    compose.onNodeWithTag("media-${items.getValue("Album Alpha").key}").assertIsEnabled()
                }.isSuccess
            }
            assertTitleOrder()
            val alpha = compose.onNodeWithTag("media-${items.getValue("Album Alpha").key}")
            compose.awaitExpectedWindowFocus(alpha)
            alpha.assertIsDisplayed().performClick()
            compose.waitUntil(5000) {
                compose.runOnUiThread { navigation.value.route.collection == items.getValue("Album Alpha").ref }
            }
            compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { compose.runOnUiThread { navigation.value.route.key == search.key } }
            assertTitleOrder()
            restoration.emulateSavedInstanceStateRestore()
            assertTitleOrder()
            compose.runOnIdle {
                assertEquals(TrackSort.Title, navigation.value.route.querySort)
                assertEquals(true, navigation.value.route.queryAscending)
                assertEquals(older, navigation.value.stacks.getValue(MainTab.Albums)[1])
                assertFalse(preferences.overridden(search))
            }
        } finally { store.edit().clear().commit() }
    }
}
