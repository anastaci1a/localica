package io.github.anastaci1a.localica

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule

class LibraryScrollResetTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: MutableState<NavigationState>
    private var savedPreferences = emptyList<Pair<SharedPreferences, Map<String, *>>>()
    private val rows = SemanticsMatcher("browse media row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-") == true
    }
    private val snapshot = LibrarySnapshot(
        folders = listOf(FolderSelection("root", "Music")),
        tracks = (0 until 120).map { index ->
            val number = index.toString().padStart(3, '0')
            Track("scroll-reset:$number", "Track $number", album = "Track album $number", artist = "Artist",
                fileName = "$number.mp3", relativePath = "$number.mp3")
        })

    @Before fun isolatePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        savedPreferences = listOf("browse-style", "library-query").map { name ->
            val store = context.getSharedPreferences(name, 0)
            store to store.all.toMap()
        }
        savedPreferences.forEach { (store, _) -> store.edit().clear().commit() }
        val preferences = BrowsePreferences(context)
        for (route in listOf(
            BrowseRoute(MainTab.Library),
            BrowseRoute(MainTab.Library, filter = MediaKind.Album),
            BrowseRoute(MainTab.Library, filter = MediaKind.Song),
            BrowseRoute(MainTab.Library, search = true, globalSearch = true, filter = MediaKind.Song),
            BrowseRoute(MainTab.Library, search = true, globalSearch = true, filter = MediaKind.Album),
        )) preferences.write(route, BrowseStyle(listSize = ListSize.Small, quickSearch = false), false)
    }

    @After fun restorePreferences() {
        savedPreferences.forEach { (store, values) ->
            val edit = store.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            edit.commit()
        }
    }

    private fun fixture(root: BrowseRoute) {
        compose.setContent {
            navigation = remember { mutableStateOf(NavigationState(MainTab.Library,
                mapOf(MainTab.Library to listOf(root)))) }
            val holder = rememberSaveableStateHolder()
            val cache = remember { BrowsePresentationCache() }
            val route = navigation.value.route
            fun back() { navigation.value.back(false)?.let { navigation.value = it } }
            BackHandler(navigation.value.stacks.getValue(MainTab.Library).size > 1) { back() }
            EngineTheme(reduced = true) { CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
                key(route.key) { holder.SaveableStateProvider(route.key) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                        { navigation.value = navigation.value.update(it) },
                        { item -> navigation.value = navigation.value.open(BrowseRoute(MainTab.Library, item.ref, item.title)) },
                        {}, { _, _, _ -> }, null, null, true, false,
                        reducedMotion = true, listBackEnabled = true,
                        libraryFilters = LibraryFilters(visible = listOf(LibraryFilter.Albums, LibraryFilter.Songs),
                            allCollections = setOf(MediaKind.Album)),
                        onSearchEdit = { navigation.value = navigation.value.update(it) }, onBack = ::back)
                } }
            } }
        }
        awaitRows()
    }

    private fun awaitRows() {
        compose.waitUntil(10000) {
            val items = compose.onAllNodes(rows, useUnmergedTree = true).fetchSemanticsNodes()
            items.isNotEmpty() && items.any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.waitForIdle()
    }

    private fun position(): Float {
        val range = compose.onNodeWithTag("browser-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        return compose.runOnIdle { range.value() }
    }

    private fun scrollDown() {
        compose.onNodeWithTag("browser-list").performScrollToIndex(45)
        compose.waitForIdle()
        assertTrue("The source list must actually be scrolled", position() > 10f)
    }

    private fun assertTop(reason: String) {
        awaitRows()
        try { compose.waitUntil(5000) { position() <= .0001f } }
        catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$reason; observed scroll=${position()}", timeout)
        }
        assertEquals(reason, 0f, position(), .0001f)
    }

    @Test fun changingFilterBubblesResetsBothDirectionsAndDeselection() {
        fixture(BrowseRoute(MainTab.Library, filter = MediaKind.Album))
        scrollDown()
        compose.onNodeWithTag("filter-Songs").performClick()
        assertTop("Selecting Songs starts at the top")
        scrollDown()
        compose.onNodeWithTag("filter-Albums").performClick()
        assertTop("Returning to Albums starts at the top, not its older offset")
        scrollDown()
        compose.onNodeWithTag("filter-Albums").performClick()
        assertTop("Deselecting a Library filter starts the unfiltered view at the top")
    }

    @Test fun fullLibrarySearchQueryAndScopeResetTheResults() {
        fixture(BrowseRoute(MainTab.Library, search = true, globalSearch = true,
            filter = MediaKind.Song, text = "Track", queryCommitted = true))
        scrollDown()
        // The visible track survives this query, so lazy key anchoring must not keep its offset.
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        assertTop("A changed full-library query starts at the top")
        scrollDown()
        compose.onNodeWithTag("filter-Albums").performClick()
        assertTop("A changed full-library scope starts at the top")
    }

    @Test fun backFirstReturnsToTopThenClearsTheFilterAtTop() {
        fixture(BrowseRoute(MainTab.Library))
        scrollDown()
        compose.onNodeWithTag("filter-Albums").performClick()
        awaitRows()
        scrollDown()
        pressSystemBack()
        assertTop("The first Back returns the filtered list to the top")
        compose.runOnIdle { assertEquals(MediaKind.Album, navigation.value.route.filter) }
        pressSystemBack()
        compose.waitUntil(5000) { compose.runOnIdle { navigation.value.route.filter == null } }
        assertTop("The clearing Back cannot restore the older unfiltered offset")
    }

    @Test fun backFromASubviewRestoresTheParentItemAndPixelOffset() =
        verifyParentBack(BrowseRoute(MainTab.Library, filter = MediaKind.Album))

    @Test fun backFromFullLibrarySearchRestoresTheParentQueryAndPixelOffset() =
        verifyParentBack(BrowseRoute(MainTab.Library, search = true, globalSearch = true,
            filter = MediaKind.Album, text = "Track", queryCommitted = true))

    private fun verifyParentBack(root: BrowseRoute) {
        fixture(root)
        scrollDown()
        val aligned = position()
        compose.onNodeWithTag("browser-list").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 17f) }
        compose.waitForIdle()
        val before = position()
        assertTrue("The parent must include a nonzero pixel offset", before > aligned)
        // Rows draw behind the floating filters; open one below that input strip.
        val album = compose.onNodeWithText("Track album 048")
        album.assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.runOnIdle { navigation.value.route.collection != null } }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        pressSystemBack()
        compose.waitUntil(5000) { compose.runOnIdle { navigation.value.route.collection == null } }
        awaitRows()
        compose.runOnIdle { assertEquals("Subview Back preserves the parent query and scope", root, navigation.value.route) }
        assertEquals("Subview Back restores the parent first item and pixel offset", before, position(), .0001f)
        assertTrue("Subview Back must preserve a genuinely scrolled parent", position() > 10f)
    }
}
