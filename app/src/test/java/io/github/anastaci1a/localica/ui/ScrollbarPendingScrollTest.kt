package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1062: a scroll request is pending until the next measure, while every phase may still read the scrollbar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScrollbarPendingScrollTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aPendingNegativeScrollRequestDoesNotEnterTheModel() {
        val grid = LazyGridState()
        rule.setContent {
            LazyVerticalGrid(GridCells.Fixed(1), Modifier.height(1000.dp), grid) { items(12) { Box(Modifier.height(100.dp)) } }
        }
        val model = ScrollbarModel()
        rule.runOnIdle {
            val measured = model.observe(grid.scrollbarFrame())
            // The queue opening's inset request (BrowserScreen initial positioning): the state shows it before layout does.
            val inset = model.remaining.toInt() + 16 // position + remaining = -16 px, as in the #1062 report
            grid.requestScrollToItem(5, -inset)
            assertEquals(-inset, grid.firstVisibleItemScrollOffset)
            assertEquals(measured, model.observe(grid.scrollbarFrame()), 0f)
        }
        rule.runOnIdle { assertEquals(grid.firstVisibleItemScrollOffset.toFloat() + 100f * grid.firstVisibleItemIndex * rule.density.density, model.observe(grid.scrollbarFrame()) * model.scrollable, .5f) }
    }
}
