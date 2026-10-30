package io.github.anastaci1a.localica

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private fun valid() = NavigationState(MainTab.Library, linkedMapOf(
        MainTab.Home to listOf(BrowseRoute(MainTab.Home, key = "home")),
        MainTab.Library to listOf(BrowseRoute(MainTab.Library, key = "library"),
            BrowseRoute(MainTab.Library, MediaRef(MediaKind.Album, "album"), "Album",
                search = true, text = "query", filter = MediaKind.Song, key = "detail", queryCommitted = true,
                querySort = io.github.anastaci1a.localica.library.TrackSort.Year, queryAscending = false))),
        listOf(MainTab.Home))
    private fun detail(json: JSONObject) = json.getJSONObject("stacks").getJSONArray("Library").getJSONObject(1)

    @Test fun validSnapshotPreservesEveryRouteAndBackDestination() {
        val state = valid()
        val restored = NavigationCodec.decodeOrNull(NavigationCodec.encode(state))!!
        assertEquals(state, restored)
        assertEquals(state.route, restored.route)
        assertEquals(state.back(true), restored.back(true))
        val hidden = NavigationState.fresh(listOf(MainTab.MostPlayed))
        assertEquals(hidden, NavigationCodec.decodeOrNull(NavigationCodec.encode(hidden)))
    }

    @Test fun malformedJsonAndEveryUnknownRequiredEnumRejectTheWholeSnapshot() {
        for (text in listOf("", "not-json", "{}", "[]")) assertNull(NavigationCodec.decodeOrNull(text))
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("active", "FutureTab") },
            { it.getJSONObject("stacks").put("FutureTab", JSONArray()) },
            { detail(it).put("tab", "library") },
            { detail(it).put("kind", "FutureKind") },
            { detail(it).put("filter", "FutureFilter") },
            { it.put("history", JSONArray().put("FutureTab")) },
            { detail(it).remove("text") })
        for (mutate in mutations) {
            val json = JSONObject(NavigationCodec.encode(valid()))
            mutate(json)
            assertNull(json.toString(), NavigationCodec.decodeOrNull(json.toString()))
        }
    }

    @Test fun emptyStacksAndUnreachableActiveOrHistoryRejectTheWholeSnapshot() {
        val state = valid()
        val invalid = listOf(
            state.copy(stacks = emptyMap()),
            state.copy(stacks = state.stacks - MainTab.Library),
            state.copy(stacks = state.stacks + (MainTab.Library to emptyList())),
            state.copy(stacks = state.stacks + (MainTab.Home to emptyList())),
            state.copy(history = listOf(MainTab.Queues)))
        for (value in invalid) assertNull(NavigationCodec.decodeOrNull(NavigationCodec.encode(value)))
    }

    @Test fun optionalLegacyFieldsKeepTheirEstablishedFallbacks() {
        val state = valid()
        val json = JSONObject(NavigationCodec.encode(state))
        detail(json).remove("committed")
        detail(json).put("sort", "FutureSort")
        detail(json).remove("ascending")
        assertEquals(state.update(state.route.copy(querySort = null, queryAscending = null)), NavigationCodec.decodeOrNull(json.toString()))
        detail(json).remove("id")
        detail(json).put("kind", "FutureKind")
        assertEquals(state.update(state.route.copy(collection = null, querySort = null, queryAscending = null)), NavigationCodec.decodeOrNull(json.toString()))
    }

    @Test fun rejectedSaverValueReinitializesEachOwnerInsteadOfPublishingPartialState() {
        val restoration = StateRestorationTester(compose)
        val initializers = IntArray(3)
        val observed = arrayOfNulls<NavigationState>(3)
        val roots = listOf(
            NavigationState.fresh(listOf(MainTab.Songs)),
            NavigationState(MainTab.Albums, mapOf(MainTab.Albums to listOf(
                BrowseRoute(MainTab.Albums, search = true, key = "search-root")))),
            NavigationState(MainTab.Queues, mapOf(MainTab.Queues to listOf(
                BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "current-queue"), key = "queue-root")))))
        restoration.setContent {
            roots.forEachIndexed { index, root ->
                val state = rememberSaveable(stateSaver = Saver<NavigationState, String>(
                    save = { "{broken" }, restore = { NavigationCodec.decodeOrNull(it) })) {
                    initializers[index]++
                    mutableStateOf(root)
                }
                observed[index] = state.value
            }
        }
        compose.runOnIdle { assertArrayEquals(intArrayOf(1, 1, 1), initializers) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(2, 2, 2), initializers)
            roots.forEachIndexed { index, root -> assertEquals(root, observed[index]) }
        }
    }
}
