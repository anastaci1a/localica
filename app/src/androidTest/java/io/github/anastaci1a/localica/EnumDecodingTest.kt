package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.home.PinPreferences
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.SavedOrderCodec
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current decoding contracts only; no damaged-navigation recovery policy is introduced. */
@RunWith(AndroidJUnit4::class)
class EnumDecodingTest {
    @Test fun savedOrderFallbackPreservesBaselineDirectionAndMalformedRejection() {
        val ids = listOf("b", "added", "a")
        val baseline = listOf("a", "removed", "b")
        val reconciled = listOf("a", "b", "added")
        for (sort in TrackSort.entries) {
            val encoded = SavedOrderCodec.encode(SavedOrder(baseline, sort, false))
            assertEquals(SavedOrder(reconciled, sort, false), SavedOrderCodec.decode(encoded, ids))
        }
        val original = SavedOrderCodec.encode(SavedOrder(baseline, TrackSort.Year, false))
        for (name in listOf(null, JSONObject.NULL, "", "FutureSort", "year", "Year ")) {
            val json = JSONObject(original)
            if (name == null) json.remove("sort") else json.put("sort", name)
            assertEquals(SavedOrder(reconciled, TrackSort.Manual, false), SavedOrderCodec.decode(json.toString(), ids))
        }
        assertEquals(SavedOrder(ids), SavedOrderCodec.decode(" \n\t", ids))
        for (text in listOf("not-json", "{}", """{"manual":42}""")) {
            assertThrows(JSONException::class.java) { SavedOrderCodec.decode(text, ids) }
        }
    }

    @Test fun navigationOptionalSortPreservesStateWhileRequiredEnumsRemainStrict() {
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, "list"), "Mix",
            search = true, text = "term", filter = MediaKind.Song, key = "search-route",
            queryCommitted = true, querySort = TrackSort.Year, queryAscending = false)
        val state = NavigationState(MainTab.Library, linkedMapOf(
            MainTab.Home to listOf(BrowseRoute(MainTab.Home, key = "home-root")),
            MainTab.Library to listOf(BrowseRoute(MainTab.Library, key = "library-root"), route)),
            listOf(MainTab.Home, MainTab.Library, MainTab.Home))
        val encoded = NavigationCodec.encode(state)
        assertEquals(state, NavigationCodec.decode(encoded))
        fun detail(json: JSONObject) = json.getJSONObject("stacks").getJSONArray("Library").getJSONObject(1)
        for (name in listOf(null, JSONObject.NULL, "", "FutureSort", "year", "Year ")) {
            val json = JSONObject(encoded)
            if (name == null) detail(json).remove("sort") else detail(json).put("sort", name)
            assertEquals(state.update(route.copy(querySort = null)), NavigationCodec.decode(json.toString()))
        }
        for (text in listOf("", "term", " ")) {
            val json = JSONObject(encoded)
            detail(json).remove("committed")
            detail(json).put("text", text)
            assertEquals(state.update(route.copy(text = text, queryCommitted = text.isNotEmpty())),
                NavigationCodec.decode(json.toString()))
        }
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.put("active", "FutureTab") },
            { json -> json.getJSONObject("stacks").let { it.put("FutureTab", it.remove("Home")) } },
            { detail(it).put("tab", "library") },
            { detail(it).put("kind", "playlist") },
            { detail(it).put("filter", "song") },
            { it.put("history", JSONArray().put("home")) })
        for (corrupt in corruptions) {
            val json = JSONObject(encoded)
            corrupt(json)
            assertThrows(IllegalArgumentException::class.java) { NavigationCodec.decode(json.toString()) }
        }
        val withoutId = JSONObject(encoded)
        detail(withoutId).remove("id")
        detail(withoutId).put("kind", "FutureKind")
        assertEquals(state.update(route.copy(collection = null)), NavigationCodec.decode(withoutId.toString()))
        val knownHiddenTab = NavigationState(MainTab.MostPlayed,
            mapOf(MainTab.MostPlayed to listOf(BrowseRoute(MainTab.MostPlayed, key = "known-hidden-root"))))
        assertEquals(knownHiddenTab, NavigationCodec.decode(NavigationCodec.encode(knownHiddenTab)))
        val missingText = JSONObject(encoded)
        detail(missingText).remove("text")
        for (text in listOf("not-json", "{}", missingText.toString())) {
            assertThrows(JSONException::class.java) { NavigationCodec.decode(text) }
        }
    }

    @Test fun interfaceTabsKeepAvailableOrderAndDefaultOnlyWhenEmpty() = withPreferences("interface") { context, raw ->
        val preferences = InterfacePreferences(context)
        val original = InterfaceSettings(tabs = listOf(MainTab.Songs), tabHistory = false, confirmExit = true,
            artworkTap = ArtworkAction.Change, lyricDelayMs = -250)
        preferences.write(original)
        val cases = listOf(
            "Library,FutureTab,Home,Library,MostPlayed" to listOf(MainTab.Library, MainTab.Home),
            "Songs" to listOf(MainTab.Songs),
            "home, Home,Home " to InterfaceSettings().tabs,
            "MostPlayed,FutureTab" to InterfaceSettings().tabs,
            "" to InterfaceSettings().tabs)
        for ((text, tabs) in cases) {
            raw.edit().putString("tabs", text).commit()
            assertEquals(original.copy(tabs = tabs), preferences.read())
            assertEquals(text, raw.getString("tabs", null))
        }
        raw.edit().remove("tabs").commit()
        assertEquals(original.copy(tabs = InterfaceSettings().tabs), preferences.read())
    }

    @Test fun pinsSkipUnsupportedKindsBeforeIdAndKeepSupportedOrder() = withPreferences("pins") { context, raw ->
        fun pin(kind: String, id: String? = null) = JSONObject().put("kind", kind).apply {
            if (id != null) put("id", id)
        }
        val encoded = JSONArray().put(pin("Song", "same")).put(pin("FutureKind"))
            .put(pin("Album", "same")).put(pin("Queue")).put(pin("Song", "same"))
            .put(pin("Playlist", "mix")).put(pin("song")).put(pin(" Song")).put(JSONObject()).toString()
        raw.edit().putString("items", encoded).commit()
        val preferences = PinPreferences(context)
        assertEquals(listOf(MediaRef(MediaKind.Song, "same"), MediaRef(MediaKind.Album, "same"),
            MediaRef(MediaKind.Playlist, "mix")), preferences.read())
        assertEquals(encoded, raw.getString("items", null))
        for (text in listOf("not-json", JSONArray().put(pin("Song")).toString())) {
            raw.edit().putString("items", text).commit()
            assertThrows(JSONException::class.java) { preferences.read() }
        }
    }

    // Same owned-preference pattern as GlassRendererPreferenceTest; never opens the real stores.
    private fun withPreferences(storeName: String, test: (Context, SharedPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val ownedName = "enum-decoding-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == storeName)
                return base.getSharedPreferences(ownedName, mode)
            }
        }
        val raw = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        try { test(context, raw) } finally {
            raw.edit().clear().commit()
            base.deleteSharedPreferences(ownedName)
        }
    }
}
