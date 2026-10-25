package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The live and frozen pinch passes must use the new Tracklist contract, not old album Normal. */
class TracklistPinchIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryModeChangeRetainsTheNumberUntilItsExitFadeCompletes() {
        var style by mutableStateOf(BrowseStyle(listSize = ListSize.Tracklist))
        val item = BrowserItem(MediaRef(MediaKind.Song, "fading-number"), "Song", "Artist", emptyList())
        compose.setContent { LocalicaTheme {
            LookaheadScope {
                Box(Modifier.width(320.dp)) {
                    BrowseItemLayout(item, style, BrowseCellGeometry(1), this@LookaheadScope, false, {}, itemIndex = 2)
                }
            }
        } }
        compose.onNodeWithText("3").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { style = style.copy(listSize = ListSize.Normal) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("3").assertExists()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithText("3").assertExists()
            // Reversal keeps the same label rather than remounting an empty glyph.
            compose.runOnIdle { style = style.copy(listSize = ListSize.Tracklist) }
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithText("3").assertExists()
            compose.runOnIdle { style = style.copy(listSize = ListSize.Normal) }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText("3").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun liveAndCapturedEndpointsKeepNumbersOnlyInTheNumberedMode() {
        var index by mutableIntStateOf(2)
        var albumNumber by mutableStateOf<String?>(null)
        lateinit var measure: (BrowseStyle, Int, Int) -> BrowsePinchEndpoint
        val item = BrowserItem(MediaRef(MediaKind.Song, "numbered"), "Song", "Artist", emptyList())
        compose.setContent { LocalicaTheme {
            measure = rememberBrowseItemMeasure(item, albumNumber, true, emptyMap(), index)
        } }
        compose.runOnIdle {
            val numbered = measure(BrowseStyle(listSize = ListSize.Tracklist), 1, 320)
            val ordinary = measure(BrowseStyle(listSize = ListSize.Normal), 1, 320)
            assertEquals("3", numbered.number?.layoutInput?.text?.text)
            assertFalse(numbered.artwork)
            assertNull(ordinary.number)
            assertTrue(ordinary.artwork)
        }
        // A captured endpoint closure belongs to its source occurrence even if live indices change.
        val captured = measure
        compose.runOnIdle { index = 4 }
        compose.runOnIdle {
            assertEquals("3", captured(BrowseStyle(listSize = ListSize.Tracklist), 1, 320).number?.layoutInput?.text?.text)
            assertEquals("5", measure(BrowseStyle(listSize = ListSize.Tracklist), 1, 320).number?.layoutInput?.text?.text)
            albumNumber = "12"
        }
        compose.runOnIdle {
            assertEquals("12", measure(BrowseStyle(listSize = ListSize.Tracklist), 1, 320).number?.layoutInput?.text?.text)
            assertNull(measure(BrowseStyle(), 1, 320).number)
            val gallery = measure(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3), 3, 100)
            assertNull(gallery.number)
            assertTrue(gallery.artwork)
        }
    }
}
