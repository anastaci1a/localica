package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.PlayerSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerPausedSkipTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(PlaybackState(connected = true, title = "Paused", durationMs = 60000))
    private var next = 0
    private var previous = 0
    private var toggles = 0

    private fun mount(expanded: Boolean) {
        compose.setContent { LocalicaTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerSurface(state, expanded, {}, InterfaceSettings(),
                    remember { Animatable(if (expanded) 1f else 0f) },
                    { toggles++ }, {}, { previous++ }, { next++ }, {}, {})
            }
        } }
    }

    private fun pausedBuffering(expanded: Boolean) {
        mount(expanded)
        repeat(4) { visit ->
            compose.onNodeWithContentDescription("Next").performClick()
            compose.runOnIdle { state = state.copy(trackId = "next-$visit", title = "Next $visit", isBuffering = true) }
            compose.onNodeWithContentDescription("Play").assertExists()
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(isBuffering = false) }
            compose.onNodeWithContentDescription("Previous").performClick()
            compose.runOnIdle { state = state.copy(trackId = "previous-$visit", title = "Previous $visit", isBuffering = true) }
            compose.onNodeWithContentDescription("Play").assertExists()
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(isBuffering = false) }
        }
        compose.runOnIdle {
            assertEquals(4, next); assertEquals(4, previous); assertEquals(0, toggles)
            assertEquals(false, state.playWhenReady)
        }
        // A genuinely requested buffering play must still offer Pause.
        compose.runOnIdle { state = state.copy(isBuffering = true, playWhenReady = true) }
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isBuffering = false, isPlaying = true) }
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test fun fullPlayerPausedSkipsKeepPlayGlyphAndEveryCommand() = pausedBuffering(true)
    @Test fun miniPlayerPausedSkipsKeepPlayGlyphAndEveryCommand() = pausedBuffering(false)
}
