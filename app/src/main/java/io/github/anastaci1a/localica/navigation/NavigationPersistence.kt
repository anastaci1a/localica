package io.github.anastaci1a.localica.navigation

import io.github.anastaci1a.localica.library.enumValue
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
            preferences.getBoolean("reduced-motion", false),
            preferences.enumValue<ArtworkAction>("artwork-tap") ?: ArtworkAction.Lyrics,
            preferences.enumValue<ArtworkAction>("artwork-hold") ?: ArtworkAction.Menu,
            fasterMotion = preferences.getBoolean("faster-motion", false),
            artworkLayout = preferences.enumValue<ArtworkLayout>("artwork-layout") ?: ArtworkLayout.FullBackground,
            artworkTransition = preferences.enumValue<ArtworkTransition>("artwork-transition") ?: ArtworkTransition.SoftWash,
            glass = GlassAppearance(preferences.getFloat("glass-blur", .2f), preferences.getFloat("glass-white-tint", .06f),
                preferences.getFloat("glass-shadow-strength", .12f), preferences.getFloat("glass-shadow-spread", .6f),
                preferences.getFloat("glass-contrast", .4f),
                preferences.enumValue<TabLensStyle>("tab-lens-style") ?: TabLensStyle.Adjustable,
                preferences.getFloat("tab-lens-blur", .3f), preferences.getFloat("tab-lens-tint", .16f),
                preferences.getFloat("glass-shape-damping", 8f), preferences.getFloat("glass-depth", 1.35f)).normalized(),
            grayTone = preferences.getFloat("gray-tone", .4f).normalizedGrayTone())
    }
    fun write(value: InterfaceSettings) {
        require(value.tabs.isNotEmpty() && value.tabs.distinct() == value.tabs)
        val glass = value.glass.normalized()
        preferences.edit().putString("tabs", value.tabs.joinToString(",") { it.name }).putBoolean("history", value.tabHistory)
            .putBoolean("confirm-exit", value.confirmExit).putBoolean("expand-on-play", value.expandOnPlay)
            .putBoolean("album-artist", value.albumArtistFirst).putBoolean("reduced-motion", value.reducedMotion)
            .putBoolean("faster-motion", value.fasterMotion).putString("artwork-layout", value.artworkLayout.name)
            .putString("artwork-transition", value.artworkTransition.name)
            .putFloat("gray-tone", value.grayTone.normalizedGrayTone())
            .putFloat("glass-blur", glass.blur).putFloat("glass-white-tint", glass.whiteTint)
            .putFloat("glass-shadow-strength", glass.shadowStrength).putFloat("glass-shadow-spread", glass.shadowSpread)
            .putFloat("glass-contrast", glass.contrast).putString("tab-lens-style", glass.tabStyle.name)
            .putFloat("tab-lens-blur", glass.tabBlur).putFloat("tab-lens-tint", glass.tabWhiteTint)
            .putFloat("glass-shape-damping", glass.shapeDamping).putFloat("glass-depth", glass.depth)
            .putString("artwork-tap", value.artworkTap.name).putString("artwork-hold", value.artworkHold.name).apply()
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
                put("committed", route.queryCommitted); put("sort", route.querySort?.name); put("ascending", route.queryAscending)
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
                    if (isNull("filter")) null else MediaKind.valueOf(getString("filter")), getString("key"),
                    queryCommitted = optBoolean("committed", getString("text").isNotEmpty()),
                    querySort = optString("sort").let { name -> io.github.anastaci1a.localica.library.TrackSort.entries.firstOrNull { it.name == name } },
                    queryAscending = if (isNull("ascending")) null else getBoolean("ascending"))
            } }
        }
        val history = getJSONArray("history")
        NavigationState(MainTab.valueOf(getString("active")), stacks,
            (0 until history.length()).map { MainTab.valueOf(history.getString(it)) })
    }
}
