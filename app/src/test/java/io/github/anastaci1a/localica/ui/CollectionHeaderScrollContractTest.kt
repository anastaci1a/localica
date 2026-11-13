package io.github.anastaci1a.localica.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CollectionHeaderScrollContractTest {
    @Test fun carouselSuppliesLiveHorizontalDirectionsToItsSharedTiles() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/CollectionHeader.kt").readText()
            .substringAfter("internal fun ArtistAlbumChoices(").substringBefore("internal fun CollectionBackground(")
        assertTrue(source.contains("LazyRow(Modifier.controlScrollParent(scroll, ControlScrollAxis.Horizontal)"))
        assertTrue(source.contains("outline = albumOutline).ownButtonDrag().toggleable("))
        assertTrue(source.contains("outline = albumOutline).ownButtonDrag().combinedClickable("))
        var backward = false
        val context = ControlScrollContext().apply {
            node = ControlScrollNode { controlScrollDirections(false, true, backward, false) }
        }
        assertEquals(ControlScrollDirection.Left, context.directions())
        backward = true
        assertEquals(ControlScrollDirection.Left or ControlScrollDirection.Right, context.directions())
    }
}
