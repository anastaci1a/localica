package io.github.anastaci1a.localica.navigation

import io.github.anastaci1a.localica.library.enumValue
import io.github.anastaci1a.localica.library.enumNameOrNull
import android.content.Context
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

class InterfacePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    fun read(): InterfaceSettings {
        preferences.edit().apply {
            remove("glass-renderer")
            remove("glass-live-scroll")
            remove("glass-blur")
            remove("glass-white-tint")
            remove("glass-shadow-strength-multiplier")
            remove("glass-shadow-strength")
            remove("glass-shadow-spread-multiplier")
            remove("glass-shadow-spread")
            remove("glass-contrast-trim")
            remove("glass-contrast")
            remove("tab-lens-style")
            remove("tab-lens-blur")
            remove("tab-lens-tint")
            remove("glass-shape-damping")
            remove("glass-depth")
            remove("glass-contrast-weight")
            remove("tab-classic-opacity")
            remove("glass-control-expansion")
            remove("tab-overlay-darkening")
            remove("tab-overlay-foreground")
            remove("glass-lift-shadow-strength")
        }.apply()
        val tabs = preferences.getString("tabs", null)?.split(',')?.mapNotNull { name -> enumNameOrNull<MainTab>(name)?.takeIf { it in MainTab.available } }
            ?.distinct()?.takeIf { it.isNotEmpty() } ?: InterfaceSettings().tabs
        return InterfaceSettings(tabs, preferences.getBoolean("history", true), preferences.getBoolean("confirm-exit", false),
            preferences.getBoolean("expand-on-play", true), preferences.getBoolean("album-artist", true),
            preferences.getBoolean("reduced-motion", false),
            preferences.enumValue<ArtworkAction>("artwork-tap") ?: ArtworkAction.Lyrics,
            preferences.enumValue<ArtworkAction>("artwork-hold") ?: ArtworkAction.Menu,
            animationSpeedTenths = (if (preferences.contains("animation-speed-tenths")) preferences.getInt("animation-speed-tenths", 10)
                else if (preferences.getBoolean("faster-motion", false)) FasterAnimationSpeedTenths else 10)
                .coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths),
            respectSystemAnimationScale = preferences.getBoolean("respect-system-animation-scale", true),
            artworkLayout = preferences.enumValue<ArtworkLayout>("artwork-layout") ?: ArtworkLayout.FullBackground,
            artworkTransition = preferences.enumValue<ArtworkTransition>("artwork-transition") ?: ArtworkTransition.BlurThrough,
            artworkSwipe = preferences.getBoolean("artwork-swipe", true),
            grayTone = preferences.getFloat("gray-tone", .4f).normalizedGrayTone(),
            lyricDelayMs = preferences.getInt("lyric-delay-ms", 0).coerceIn(-2000, 2000),
            longScroll = preferences.enumValue<LongScrollStyle>("long-scroll") ?: LongScrollStyle.FlyThrough)
    }
    fun write(value: InterfaceSettings) {
        require(value.tabs.isNotEmpty() && value.tabs.distinct() == value.tabs)
        preferences.edit().putString("tabs", value.tabs.joinToString(",") { it.name }).putBoolean("history", value.tabHistory)
            .putBoolean("confirm-exit", value.confirmExit).putBoolean("expand-on-play", value.expandOnPlay)
            .putBoolean("album-artist", value.albumArtistFirst).putBoolean("reduced-motion", value.reducedMotion)
            .putInt("animation-speed-tenths", value.animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths))
            // A downgrade keeps the closest old state.
            .putBoolean("faster-motion", value.animationSpeedTenths >= FasterAnimationSpeedTenths)
            .putBoolean("respect-system-animation-scale", value.respectSystemAnimationScale)
            .putString("artwork-layout", value.artworkLayout.name)
            .putString("artwork-transition", value.artworkTransition.name).putBoolean("artwork-swipe", value.artworkSwipe)
            .putFloat("gray-tone", value.grayTone.normalizedGrayTone())
            .putInt("lyric-delay-ms", value.lyricDelayMs.coerceIn(-2000, 2000))
            .putString("long-scroll", value.longScroll.name)
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
    /** Reject an unusable saved-instance snapshot; the owning Saver supplies its fresh state. */
    fun decodeOrNull(text: String): NavigationState? = try {
        decode(text).takeIf { state ->
            state.active in state.stacks && state.stacks.values.all { it.isNotEmpty() } &&
                state.history.all { it in state.stacks }
        }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

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
                    querySort = enumNameOrNull<io.github.anastaci1a.localica.library.TrackSort>(optString("sort")),
                    queryAscending = if (isNull("ascending")) null else getBoolean("ascending"))
            } }
        }
        val history = getJSONArray("history")
        NavigationState(MainTab.valueOf(getString("active")), stacks,
            (0 until history.length()).map { MainTab.valueOf(history.getString(it)) })
    }
}
