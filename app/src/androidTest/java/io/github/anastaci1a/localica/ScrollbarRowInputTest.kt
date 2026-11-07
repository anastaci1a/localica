package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A wider invisible edge target must not take ordinary taps away from row actions. */
class ScrollbarRowInputTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun overlappingRowActionReceivesTapButScrollbarStillOwnsVerticalDrag() {
        var taps = 0
        val transit = ArtworkTransit()
        val state = LazyGridState()
        val items = List(60) { BrowserItem(MediaRef(MediaKind.Album, "$it"), "Album $it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                Box(Modifier.width(300.dp).height(500.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                        items(61) { Text("Row $it", Modifier.height(100.dp)) }
                    }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                        ActionIcon(AppIcon.ActionEllipsis, "Row overflow", onClick = { taps++ })
                    }
                    BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(Alignment.CenterEnd))
                }
            }
        } }
        compose.onNodeWithContentDescription("Row overflow").performTouchInput { click() }
        compose.runOnIdle { assertEquals("Scrollbar must pass taps to the row action", 1, taps) }
        val bar = compose.onNodeWithTag("browse-scrollbar")
        bar.performTouchInput { down(Offset(centerX, 20f)); moveBy(Offset(0f, 130f), delayMillis = 150) }
        compose.runOnIdle { assertTrue("Scrollbar keeps a real drag", transit.active) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertFalse(transit.active); assertEquals(1, taps) }
    }
}
