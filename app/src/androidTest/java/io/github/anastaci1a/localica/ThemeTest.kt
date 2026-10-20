package io.github.anastaci1a.localica

import android.content.res.Configuration
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsingRetainsLightSurfacesAndReadableInheritedTextInSystemNightMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        var night by mutableStateOf(false)
        var foreground = Color.Unspecified
        var background = Color.Unspecified
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                LocalicaTheme {
                    foreground = LocalContentColor.current
                    background = MaterialTheme.colorScheme.background
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(), loading = false), PlaybackState(connected = true),
                        {}, {}, {}, {}, {}, {},
                        playlist = PlaylistState(loading = false))
                }
            }
        }
        for (systemNight in listOf(false, true)) {
            compose.runOnIdle { night = systemNight }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.runOnIdle {
                assertEquals(Color(0xFFF9F9FB), background)
                assertEquals(Color(0xFF202124), foreground)
            }
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val middle = pixels[1, pixels.height / 2]
            assertTrue("Library background stays light in system night mode=$systemNight", middle.red > 0.9f && middle.green > 0.9f && middle.blue > 0.9f)
            compose.onAllNodesWithText("Library", substring = false).onFirst().assertIsDisplayed()
        }
    }
}
