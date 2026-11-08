package io.github.anastaci1a.localica.ui

import java.text.Normalizer
import java.util.Locale

/** Stable row identities are independent of saved preference keys. Dynamic collections index their parent group. */
internal data class SettingSpec(val id: String, val page: String, val keywords: String = "",
    val values: List<String> = emptyList(), val revealedBy: String? = null) {
    val title: String get() = id
}

internal object SettingsCatalog {
    val parents: Map<String, String?> = linkedMapOf(
        "Interface" to null,
        "Appearance" to "Interface",
        "Icon packs" to "Interface",
        "Glass" to "Interface",
        "Glass appearance" to "Glass",
        "Glass advanced" to "Glass",
        "Navigation selector" to "Glass",
        "Navigation" to "Interface",
        "Player" to "Interface",
        "Motion" to "Interface",
        "Playback and queues" to null,
        "Song tap behavior" to "Playback and queues",
        "Per-list overrides" to "Song tap behavior",
        "Library and tags" to null,
        "Library filters" to "Library and tags",
        "Folders to scan" to "Library and tags",
        "Scanner" to "Library and tags",
        "Metadata read failures" to "Scanner",
        "Tag jobs and recovery" to "Library and tags",
        "About and licenses" to null
    )
    val entries = listOf(
        SettingSpec("Interface gray", "Appearance", "grey contrast secondary text"),
        SettingSpec("Reset gray tone", "Appearance", "default"),
        SettingSpec("Glass renderer", "Glass", "render normal none disable effects", values = listOf("Normal", "None")),
        SettingSpec("Blur", "Glass appearance", "frosted background blur"),
        SettingSpec("White tint", "Glass appearance", "opacity transparent"),
        SettingSpec("Shadow strength", "Glass appearance", "floating darkness"),
        SettingSpec("Shadow spread", "Glass appearance", "broad tight"),
        SettingSpec("Lift shadow strength", "Glass appearance", "selection glow"),
        SettingSpec("Brightness trim", "Glass advanced", "adaptive contrast"),
        SettingSpec("Brightness weighting", "Glass advanced", "highlights shadows"),
        SettingSpec("Shape damping", "Glass advanced", "squishy spring rebound"),
        SettingSpec("Resting depth", "Glass advanced", "refraction flatten"),
        SettingSpec("Control expansion", "Glass advanced", "sliders switches size"),
        SettingSpec("Reset glass appearance", "Glass advanced", "defaults"),
        SettingSpec("Selector style", "Navigation selector", "tabs lens", values = listOf("Adjustable glass", "Clear overlay lens", "Classic gray pill")),
        SettingSpec("Selector blur", "Navigation selector", "frosted", revealedBy = "Selector style"),
        SettingSpec("Selector white tint", "Navigation selector", "opacity", revealedBy = "Selector style"),
        SettingSpec("Gray pill opacity", "Navigation selector", "grey classic", revealedBy = "Selector style"),
        SettingSpec("Overlay darkening", "Navigation selector", "clear lens", revealedBy = "Selector style"),
        SettingSpec("Refract icons and labels", "Navigation selector", "foreground", revealedBy = "Selector style"),
        SettingSpec("Retrace tab history", "Navigation", "back previous"),
        SettingSpec("Confirm before exiting", "Navigation", "exit close"),
        SettingSpec("Main tabs", "Navigation", "bottom bar reorder remove"),
        SettingSpec("Add main tab", "Navigation", "tabs"),
        SettingSpec("Reset main tabs", "Navigation", "default"),
        SettingSpec("Customize player actions", "Player", "buttons controls reorder"),
        SettingSpec("Expand player when a song starts", "Player", "open playing"),
        SettingSpec("Show Album Artist first", "Player", "artist"),
        SettingSpec("Lyrics timing correction", "Player", "delay offset sync correction"),
        SettingSpec("Reset lyrics timing", "Player", "default"),
        SettingSpec("Player artwork", "Player", "background matte floating", values = listOf("Full-background artwork", "Floating artwork with matte background")),
        SettingSpec("Song-change transition", "Player", "carousel blur through", values = listOf("Blur through", "Carousel")),
        SettingSpec("Swipe artwork to change songs", "Player", "previous next"),
        SettingSpec("Artwork tap", "Player", "touch lyrics menu", values = listOf("Toggle lyrics", "Show artwork menu", "View artwork", "Change artwork", "Do nothing")),
        SettingSpec("Artwork hold", "Player", "long press", values = listOf("Toggle lyrics", "Show artwork menu", "View artwork", "Change artwork", "Do nothing")),
        SettingSpec("Reduce motion", "Motion", "animation accessibility disable animations"),
        SettingSpec("Faster animations", "Motion", "speed"),
        SettingSpec("Long scroll animation", "Motion", "fly through jump glide fade return top", values = listOf("Fly-through", "Jump, then glide", "Fade")),
        SettingSpec("Preload next track", "Playback and queues", "gapless buffering"),
        SettingSpec("Previous button", "Playback and queues", "back restart rewind", values = listOf("Restart after threshold", "Always previous song", "Always restart song")),
        SettingSpec("Restart threshold", "Playback and queues", "seconds delay", revealedBy = "Previous button"),
        SettingSpec("Switch queue", "Playback and queues", "browse restore resume paused", values = listOf("Browse without changing playback", "Restore paused", "Resume immediately", "Keep playing or paused")),
        SettingSpec("Maximum queues", "Playback and queues", "limit unlimited"),
        SettingSpec("Song tap", "Song tap behavior", "play now insert next append new queue", values = listOf("New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Songs", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Albums", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Artists", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Album Artists", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Folders", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Playlists", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Search", "Per-list overrides", "inherit default tap", values = listOf("Inherit app default", "New queue from displayed list", "Insert after current and play", "Add next", "Append to current")),
        SettingSpec("Reset all tap overrides", "Per-list overrides", "defaults"),
        SettingSpec("Visible filters", "Library filters", "order reorder remove"),
        SettingSpec("Add library filter", "Library filters", "types"),
        SettingSpec("Unfiltered Library collections", "Library filters", "all collections"),
        SettingSpec("Reset library filters", "Library filters", "default"),
        SettingSpec("Music folders", "Folders to scan", "choose add folder storage directory"),
        SettingSpec("Excluded subfolders", "Folders to scan", "exclude paths nested", revealedBy = "Music folders"),
        SettingSpec("Remove folder", "Folders to scan", "files kept", revealedBy = "Music folders"),
        SettingSpec("Full scan", "Scanner", "refresh tags missing"),
        SettingSpec("Quick Scan", "Scanner", "new files"),
        SettingSpec("Include subfolders", "Scanner", "recursive nested directories"),
        SettingSpec("Exclude short files", "Scanner", "minimum duration skip clips"),
        SettingSpec("Quick Scan on launch", "Scanner", "startup new files"),
        SettingSpec("Full scan on launch", "Scanner", "startup refresh"),
        SettingSpec("Tag editing jobs", "Tag jobs and recovery", "metadata background outcomes"),
        SettingSpec("Recover tag edit", "Tag jobs and recovery", "interrupted restore original", revealedBy = "Tag editing jobs"),
        SettingSpec("Export diagnostics", "About and licenses", "logs debug share"),
        SettingSpec("Frame capture", "About and licenses", "start stop timings performance"),
        SettingSpec("Open-source licenses", "About and licenses", "copyright notice"),
        SettingSpec("Active icon pack", "Icon packs", "built in switch select view delete"),
        SettingSpec("Import icon pack", "Icon packs", "zip"),
        SettingSpec("Export built-in icons as a template", "Icon packs", "zip template")
    )
    fun path(page: String): List<String> = parents[page]?.let { path(it) }.orEmpty() + page
    fun breadcrumb(page: String) = path(page).joinToString(" › ")
    fun entry(page: String, label: String): SettingSpec? {
        val name = label.substringBefore(" · ").substringBefore(":")
        val alias = when {
            page == "Player" && name == "Correction" -> "Lyrics timing correction"
            else -> name
        }
        return entries.firstOrNull { it.page == page && it.id == alias }
    }
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("grey", "gray")
        .split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }.joinToString(" ")

    data class Result(val title: String, val page: String, val setting: SettingSpec?, val score: Int)
    fun search(query: String): List<Result> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val tokens = normalized.split(' ')
        fun score(title: String, keywords: String, page: String): Int? {
            val name = normalize(title)
            val extra = normalize(keywords)
            val trail = normalize(breadcrumb(page))
            val scores = tokens.map { token -> when {
                name == token -> 100
                name.startsWith(token) -> 80
                name.split(' ').any { it.startsWith(token) } -> 60
                token in name -> 45
                token in extra -> 35
                token in trail -> 25
                else -> return null
            } }
            return scores.sum() + if (name == normalized) 100 else 0
        }
        val settings = entries.mapNotNull { spec ->
            score(spec.title, spec.keywords + " " + spec.values.joinToString(" "), spec.page)
                ?.let { Result(spec.title, spec.page, spec, it) }
        }
        val pages = parents.keys.mapNotNull { page ->
            val summary = entries.filter { it.page == page }.joinToString(" ") { it.title + " " + it.keywords }
            score(page, summary, page)?.let { Result(page, page, null, it - 1) }
        }
        return (settings + pages).sortedByDescending { it.score }.take(30)
    }
}
