package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchSortResetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepareWindow() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun globalResetSurvivesResultBackRecreationAndPreferenceNotifications() = resetSearch(true)
    @Test fun scopedResetSurvivesResultBackRecreationAndPreferenceNotifications() = resetSearch(false)

    private fun resetSearch(global: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("browse-style", 0)
        val originalPreferences = store.all.toMap()
        val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()
        fun record(event: String) {
            trace += "${android.os.SystemClock.uptimeMillis()} $event"
        }
        record("inherited=${store.all}")
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            record("preference $key=${store.all[key]}")
        }
        store.registerOnSharedPreferenceChangeListener(listener)
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
        lateinit var sorting: MutableState<Boolean>
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                navigation = rememberSaveable(stateSaver = Saver(
                    save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
                    mutableStateOf(NavigationState(MainTab.Albums, mapOf(MainTab.Albums to listOf(root, older, search))))
                }
                sorting = rememberSaveable { mutableStateOf(false) }
                val route = navigation.value.route
                EngineTheme(reduced = true) {
                    key(route.key) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                            { record("route sort=${it.querySort} ascending=${it.queryAscending}"); navigation.value = navigation.value.update(it) },
                            { item -> navigation.value = navigation.value.open(BrowseRoute(MainTab.Albums, item.ref, item.title)) },
                            {}, { _, _, _ -> }, null, null, true, false,
                            sorting = sorting.value, onSortDismiss = {
                                record("sort-dismiss overridden=${preferences.overridden(search)} route=${navigation.value.route.querySort}")
                                sorting.value = false
                            }, reducedMotion = true,
                            onSearchEdit = { navigation.value = navigation.value.editSearch(it) },
                            onSearchCommit = { navigation.value = navigation.value.commitSearch() },
                            onBack = { navigation.value = navigation.value.back(false)!! })
                    }
                }
            }
            // A new search requests native input outside Compose's clock. Finish
            // that handoff before opening Sort, just as a committed search does.
            compose.awaitSearchImeHandoff { compose.onNode(hasSetTextAction()).performImeAction() }
            compose.runOnIdle {
                assertEquals(search, navigation.value.route)
                assertTrue("Preparing the editor must not reset its sort", preferences.overridden(search))
                sorting.value = true
            }
            val reset = compose.onNodeWithText("Use list-type defaults").assertIsDisplayed().assertIsEnabled()
            compose.awaitExpectedWindowFocus(reset)
            record("before-click")
            reset.performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                assertFalse("Reset trace=${trace.joinToString(" | ")} remaining=${store.all}", preferences.overridden(search))
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
        } finally {
            android.util.Log.i("SearchSortReset", trace.joinToString(" | "))
            store.unregisterOnSharedPreferenceChangeListener(listener)
            store.edit().clear().apply {
                originalPreferences.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }
}
