package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseResult
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.MediaRef

/** All collection actions consume the same currently displayed ordered occurrence snapshot. */
internal fun collectionActionTarget(ref: MediaRef, title: String, displayed: BrowseResult,
    coverUri: String? = null): BrowserItem = BrowserItem(ref, title, null, displayed.rows, coverUri = coverUri)

/** Empty explicit IDs is the All mode, distinct from explicitly selecting every album. */
internal object ArtistAlbumSelection {
    fun only(id: String): List<String> = listOf(id)
    fun shownSelected(explicit: List<String>, id: String): Boolean = explicit.isEmpty() || id in explicit
    fun toggle(explicit: List<String>, id: String, albums: List<String>): List<String> {
        val valid = explicit.filter { it in albums }.distinct()
        if (id !in albums) return valid
        // All covers are selected in All mode: toggle just this one out of that effective set.
        val effective = if (valid.isEmpty()) albums.distinct() else valid
        return if (id in effective) effective - id else effective + id
        // Empty result means All again, never an empty song scope.
    }
}
