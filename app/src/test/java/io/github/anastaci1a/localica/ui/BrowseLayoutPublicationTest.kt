package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class BrowseLayoutPublicationTest {
    private val source = BrowseSourceRevision(LibrarySnapshot(), emptyList(), emptyList(), LibraryFilters())
    private val route = BrowseRoute(MainTab.Albums)
    private val style = BrowseStyle()
    private val sorts = BrowseSortSnapshot.Default
    private val current = PresentedBrowse(route, style, BrowseResult(), source, sorts)

    @Test fun layoutOnlyPreviewAndRollbackPublishWithoutWaitingForAnotherComposition() {
        val gallery = style.copy(presentation = Presentation.Standard, itemsPerRow = 3)
        val displayed = browseLayoutPresentation(current, source, route, gallery, sorts)
        assertEquals(gallery, displayed?.style)
        assertSame("Layout changes reuse the already matching result", current.result, displayed?.result)
        assertEquals(style, browseLayoutPresentation(displayed, source, route, style, sorts)?.style)
    }

    @Test fun sortFilterSourceAndRouteChangesStillWaitForTheirMatchingResult() {
        val gallery = style.copy(presentation = Presentation.Standard, itemsPerRow = 3)
        assertSame(current, browseLayoutPresentation(current, source, route, gallery.copy(ascending = !style.ascending), sorts))
        assertSame(current, browseLayoutPresentation(current, source, route.copy(filter = MediaKind.Artist), gallery, sorts))
        assertSame(current, browseLayoutPresentation(current, source, route.copy(key = "new-route"), gallery, sorts))
        val changedSource = BrowseSourceRevision(LibrarySnapshot(), emptyList(), emptyList(), LibraryFilters())
        assertSame(current, browseLayoutPresentation(current, changedSource, route, gallery, sorts))
        val changedSorts = BrowseSortSnapshot.fromValues(mapOf("contents.Album.sort" to "Title"))
        assertSame(current, browseLayoutPresentation(current, source, route, gallery, changedSorts))
        assertNull(browseLayoutPresentation(null, source, route, gallery, sorts))
    }
}
