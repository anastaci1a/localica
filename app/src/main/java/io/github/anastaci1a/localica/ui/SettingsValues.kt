package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.library.SongListView
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.queue.QueueSettings

/** Values come from the live screen models, without writing or migrating preferences during search. */
internal fun settingsSearchValues(settings: InterfaceSettings, queues: QueueSettings, filters: LibraryFilters): Map<String, String> {
    fun Boolean.label() = if (this) "On" else "Off"
    return buildMap {
        put("Glass renderer", settings.glass.renderer.label)
        put("Selector style", settings.glass.tabStyle.label)
        put("Refract icons and labels", settings.glass.overlayForeground.label())
        put("Retrace tab history", settings.tabHistory.label())
        put("Confirm before exiting", settings.confirmExit.label())
        put("Main tabs", settings.tabs.joinToString { it.label })
        put("Expand player when a song starts", settings.expandOnPlay.label())
        put("Show Album Artist first", settings.albumArtistFirst.label())
        put("Lyrics timing correction", "${settings.lyricDelayMs} ms")
        put("Player artwork", settings.artworkLayout.label)
        put("Song-change transition", settings.artworkTransition.label)
        put("Swipe artwork to change songs", settings.artworkSwipe.label())
        put("Artwork tap", settings.artworkTap.label)
        put("Artwork hold", settings.artworkHold.label)
        put("Reduce motion", settings.reducedMotion.label())
        put("Faster animations", settings.fasterMotion.label())
        put("Long scroll animation", settings.longScroll.label)
        put("Preload next track", queues.preloadNext.label())
        put("Song tap", queues.songTap.label())
        put("Previous button", queues.previousBehavior.label())
        put("Restart threshold", "${queues.previousThresholdSeconds} seconds")
        put("Switch queue", queues.switching.label())
        put("Maximum queues", queues.maximum?.toString() ?: "Unlimited")
        SongListView.entries.forEach { put(it.label, queues.tapOverrides[it]?.label() ?: "Inherit app default") }
        MediaKind.entries.filterNot { it == MediaKind.Song || it == MediaKind.Queue }.forEach {
            put("Unfiltered ${it.label}", (it in filters.allCollections).label())
        }
    }
}
