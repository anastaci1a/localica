package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class BrowseOptionsDeclarationTest {
    @Test fun catalogAndSongRoutesKeepTheirActualSelectableDomains() {
        val catalog = browseOptionsDeclaration(BrowseRoute(MainTab.Library))
        assertFalse(catalog.quickSearch)
        assertFalse(catalog.footerScope)
        assertEquals(TrackSort.entries - setOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder), catalog.sortFields)
        assertEquals(ListSize.densityOrder - ListSize.Tracklist, catalog.sizes)
        assertEquals(ExtraInfo.entries.toSet(), catalog.information.toSet())
        for (tab in listOf(MainTab.Songs, MainTab.MostPlayed)) {
            val songs = browseOptionsDeclaration(BrowseRoute(tab))
            assertEquals(TrackSort.entries - setOf(TrackSort.Manual, TrackSort.Count), songs.sortFields)
            assertTrue(songs.quickSearch)
        }
        val filtered = browseOptionsDeclaration(BrowseRoute(MainTab.Library, filter = MediaKind.Song))
        assertEquals(TrackSort.entries - setOf(TrackSort.Manual, TrackSort.Count), filtered.sortFields)
        assertFalse(filtered.information.contains(ExtraInfo.Inherited))
    }

    @Test fun collectionAndSearchVisibilityStaysIndependentOfTheDraft() {
        for (kind in MediaKind.entries) {
            val route = BrowseRoute(MainTab.Library, MediaRef(kind, "collection"))
            val declaration = browseOptionsDeclaration(route, mutable = true, randomize = true, overridden = true, reverse = true)
            assertEquals(kind in setOf(MediaKind.Queue, MediaKind.Playlist), TrackSort.Manual in declaration.sortFields)
            assertFalse(TrackSort.Count in declaration.sortFields)
            assertEquals(kind in setOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Queue), ListSize.Tracklist in declaration.sizes)
            assertEquals(kind != MediaKind.Album, declaration.quickSearch)
            assertTrue(declaration.mutable && declaration.randomize && declaration.reverse && declaration.reserveError && declaration.overridden)
            assertFalse(declaration.footerScope)
            assertTrue(browseOptionsDeclaration(route).footerScope)
            assertFalse(browseOptionsDeclaration(route).reverse)
            assertFalse(browseOptionsDeclaration(route.copy(search = true)).quickSearch)
        }
        assertTrue(browseOptionsDeclaration(BrowseRoute(MainTab.Library, search = true)).footerScope)
    }

    @Test fun everyOldDraftMapsToOneOfFourMeasuredHostStructuresWithoutCoercion() {
        val base = BrowseStyle()
        val drafts = ListSize.densityOrder.map { base.copy(listSize = it) } +
            (1..5).map { base.copy(presentation = Presentation.Standard, itemsPerRow = it) } +
            ExtraInfo.entries.map { base.copy(extraInfo = it) } +
            TrackSort.entries.map { base.copy(sort = it, ascending = false) }
        val declaration = browseOptionsDeclaration(BrowseRoute(MainTab.Library))
        val structures = setOf(BrowseOptionsTopology(false, false), BrowseOptionsTopology(false, true),
            BrowseOptionsTopology(true, false), BrowseOptionsTopology(true, true))
        for (draft in drafts) assertTrue(declaration.topology(draft) in structures)
        for (presentation in Presentation.entries) for (sort in TrackSort.entries) {
            val style = base.copy(presentation = presentation, sort = sort)
            assertEquals(presentation != Presentation.List, declaration.topology(style).gallery)
            assertEquals(sort == TrackSort.Manual, declaration.topology(style).manual)
        }
        // Legacy/fallback summaries remain displayable, never added to selectable domains.
        assertTrue(ListSize.Tracklist in declaration.sizeSummaries && ListSize.Tracklist !in declaration.sizes)
        assertTrue(declaration.sortSummaries.containsAll(listOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder)))
        assertTrue(declaration.informationSummaries.contains(ExtraInfo.Inherited))
        assertEquals((1..5).map { "Items per row: $it" }, declaration.sliderTitles)
    }
}
