package io.github.anastaci1a.localica

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.text.DateFormat
import java.util.Date

/** #720: library data export/import through the Settings rows, Room transaction and Sort by date added. */
class LibraryDataExchangeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    private val store = RoomLibraryStore(database.libraryDao())
    private val repository = LibraryRepository(store, TreeScanner(AndroidDocuments(context)), PersistedFolderPermissions(context.contentResolver))
    private val folder = FolderSelection("content://localica.test.invalid/tree/music", "Music")

    private fun song(id: String, title: String, duration: Long = 180_000) = Track("content://localica.test.invalid/document/$id", title,
        "Exchange Artist", "Exchange Album", duration, "$id.mp3", "Exchange/$id.mp3", id = "exchange:$id")

    @After fun close() = database.close()

    @Test fun importedDatesApplyAtomicallyAndSurviveFullQuickAndTagRescans() = runBlocking {
        val tracks = listOf(song("one", "One"), song("two", "Two"))
        store.replaceRoot(ScanResult(folder, tracks, 0))
        val scanned = store.current().tracks.associate { it.id to it.addedAt!! }
        val bytes = java.io.ByteArrayOutputStream().also { writeLibraryData(store.current(), it, 1L, "test") }.toByteArray()
        val unchanged = LibraryDataCodec.read(bytes.inputStream())
        assertEquals(0, repository.previewLibraryData(unchanged).changes)
        val edited = JSONObject(bytes.toString(Charsets.UTF_8)).apply {
            val list = getJSONArray("tracks")
            for (i in 0 until list.length()) list.getJSONObject(i).put("addedAt", 1_000_000_000_000L + i)
        }
        val plan = repository.importLibraryData(LibraryDataCodec.decode(edited.toString()))
        assertEquals(2, plan.changes)
        val imported = store.current().tracks.associate { it.id to it.addedAt }
        assertEquals(setOf(1_000_000_000_000L, 1_000_000_000_001L), imported.values.toSet())
        // Observers see the write.
        assertEquals(imported, repository.snapshots.first().tracks.associate { it.id to it.addedAt })
        // Full scan (retagged), Quick Scan (merge with a new song) and a tag edit keep imported dates.
        store.replaceRoot(ScanResult(folder, tracks.map { it.copy(title = it.title + " (retagged)") }, 0))
        store.mergeRoot(ScanResult(folder, tracks + song("three", "Three"), 0))
        store.updateMetadata(store.current().tracks.first { it.id == "exchange:one" }.copy(album = "Edited"))
        val after = store.current().tracks.associate { it.id to it.addedAt }
        assertEquals(imported["exchange:one"], after["exchange:one"])
        assertEquals(imported["exchange:two"], after["exchange:two"])
        assertTrue(after.getValue("exchange:three")!! >= scanned.values.max())
        assertEquals(0, repository.previewLibraryData(LibraryDataCodec.decode(edited.toString())).changes)
    }

    @Test fun settingsRowsExportAndImportThroughPickerResultsAndSortByDateAddedShowsImportedDates() {
        val titles = listOf("Alpha", "Beta", "Gamma")
        runBlocking { store.replaceRoot(ScanResult(folder, titles.map { song(it.lowercase(), "Exchange $it") }, 0)) }
        val exported = File(context.cacheDir, "library-data-export-720.json").apply { delete() }
        val edited = File(context.cacheDir, "library-data-edited-720.json").apply { delete() }
        val requests = mutableListOf<Intent>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                // The real contract builds the system picker request; the picker's answer is a chosen document.
                val request = contract.createIntent(context, input)
                requests += request
                val answer = if (request.action == Intent.ACTION_CREATE_DOCUMENT) exported else edited
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(answer)))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry get() = registry }
        val route = BrowseRoute(MainTab.Songs)
        val preferences = BrowsePreferences(context)
        val previous = preferences.read(route)
        val overridden = preferences.overridden(route)
        var browsing by mutableStateOf(false)
        try {
            preferences.write(route, BrowseStyle(sort = TrackSort.Added, ascending = false, extraInfo = ExtraInfo.Added), false)
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { LocalicaTheme {
                val snapshot by repository.snapshots.collectAsState(LibrarySnapshot())
                if (!browsing) Column(Modifier.verticalScroll(rememberScrollState())) { LibraryDataSettings(repository) }
                else Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
                }
            } } }
            compose.onNodeWithText("Export library data").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Library data exported").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Saved 3 songs", substring = true).assertExists()
            compose.onNodeWithText("Close").performClick()
            val create = requests.single()
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
            assertEquals("application/json", create.type)
            assertTrue(create.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("localica-library-data-"))

            // An external tool rewrites dates: Alpha oldest, Gamma newest.
            val dates = mapOf("Exchange Alpha" to 1_000_000_000_000L, "Exchange Beta" to 1_300_000_000_000L, "Exchange Gamma" to 1_600_000_000_000L)
            val json = JSONObject(exported.readText())
            val list = json.getJSONArray("tracks")
            assertEquals(3, list.length())
            for (i in 0 until list.length()) list.getJSONObject(i).let { it.put("addedAt", dates.getValue(it.getString("title"))) }
            list.put(JSONObject().put("title", "Not in library").put("durationMs", 1).put("addedAt", 5))
            edited.writeText(json.toString())

            compose.onNodeWithText("Import library data").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Import library data?").fetchSemanticsNodes().isNotEmpty() }
            val open = requests.last()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
            compose.onNodeWithText("Records in file: 4", substring = true).assertExists()
            compose.onNodeWithText("Matched: 3 (3 by ID", substring = true).assertExists()
            compose.onNodeWithText("Unmatched: 1 · Ambiguous: 0", substring = true).assertExists()
            compose.onNodeWithText("Date added: 3 matched, 3 changed, 0 unchanged, 1 unmatched, 0 ambiguous", substring = true).assertExists()
            // Nothing is written before Apply.
            assertTrue(runBlocking { store.current() }.tracks.none { it.addedAt in dates.values })
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Library data imported").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Updated date added for 3 songs.", substring = true).assertExists()
            compose.onNodeWithText("Close").performClick()
            assertEquals(dates, runBlocking { store.current() }.tracks.associate { it.title to it.addedAt })

            // Import again: nothing left to change.
            compose.onNodeWithText("Import library data").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing to import").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close").performClick()

            compose.runOnIdle { browsing = true }
            val inList = hasAnyAncestor(hasTestTag("browser-list"))
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Exchange Alpha") and inList).fetchSemanticsNodes().isNotEmpty() }
            val order = titles.map { "Exchange $it" }.sortedBy { title ->
                compose.onNode(hasText(title) and inList).fetchSemanticsNode().boundsInRoot.top }
            assertEquals(listOf("Exchange Gamma", "Exchange Beta", "Exchange Alpha"), order)
            val shown = DateFormat.getDateInstance().format(Date(1_000_000_000_000L))
            compose.onNode(hasText(shown, substring = true) and inList).assertExists()
        } finally {
            preferences.write(route, previous, overridden)
            exported.delete(); edited.delete()
        }
    }
}
