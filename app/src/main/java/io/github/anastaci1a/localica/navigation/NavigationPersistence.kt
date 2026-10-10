package io.github.anastaci1a.localica.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class InterfacePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    fun read(): InterfaceSettings {
        val tabs = preferences.getString("tabs", null)?.split(',')?.mapNotNull { name -> MainTab.entries.firstOrNull { it.name == name } }
            ?.distinct()?.takeIf { it.isNotEmpty() } ?: InterfaceSettings().tabs
        return InterfaceSettings(tabs, preferences.getBoolean("history", true), preferences.getBoolean("confirm-exit", false),
            preferences.getBoolean("expand-on-play", true), preferences.getBoolean("album-artist", true),
            preferences.getBoolean("reduced-motion", false))
    }
    fun write(value: InterfaceSettings) {
        require(value.tabs.isNotEmpty() && value.tabs.distinct() == value.tabs)
        preferences.edit().putString("tabs", value.tabs.joinToString(",") { it.name }).putBoolean("history", value.tabHistory)
            .putBoolean("confirm-exit", value.confirmExit).putBoolean("expand-on-play", value.expandOnPlay)
            .putBoolean("album-artist", value.albumArtistFirst).putBoolean("reduced-motion", value.reducedMotion).apply()
    }
}

/** Saved-instance state retains route IDs so Compose can restore each route's scroll position. */
object NavigationCodec {
    fun encode(state: NavigationState): String = JSONObject().apply {
        put("active", state.active.name)
        put("history", JSONArray(state.history.map { it.name }))
        put("stacks", JSONObject().apply { state.stacks.forEach { (tab, routes) ->
            put(tab.name, JSONArray().apply { routes.forEach { route -> put(JSONObject().apply {
                put("tab", route.tab.name); put("key", route.key); put("title", route.title)
                put("search", route.search); put("global", route.globalSearch); put("text", route.text)
                put("filter", route.filter?.name); put("kind", route.collection?.kind?.name); put("id", route.collection?.id)
            }) } })
        } })
    }.toString()
    fun decode(text: String): NavigationState = JSONObject(text).run {
        val stacksJson = getJSONObject("stacks")
        val stacks = stacksJson.keys().asSequence().associate { key ->
            val routes = stacksJson.getJSONArray(key)
            MainTab.valueOf(key) to (0 until routes.length()).map { index -> routes.getJSONObject(index).run {
                BrowseRoute(MainTab.valueOf(getString("tab")),
                    if (isNull("id")) null else MediaRef(MediaKind.valueOf(getString("kind")), getString("id")),
                    if (isNull("title")) null else getString("title"), getBoolean("search"), getBoolean("global"), getString("text"),
                    if (isNull("filter")) null else MediaKind.valueOf(getString("filter")), getString("key"))
            } }
        }
        val history = getJSONArray("history")
        NavigationState(MainTab.valueOf(getString("active")), stacks,
            (0 until history.length()).map { MainTab.valueOf(history.getString(it)) })
    }
}
