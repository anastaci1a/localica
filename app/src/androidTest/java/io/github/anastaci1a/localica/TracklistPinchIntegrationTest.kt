package io.github.anastaci1a.localica

import androidx.compose.runtime.*
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
