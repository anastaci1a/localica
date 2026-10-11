package io.github.anastaci1a.localica

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.ReturnToPositionButton
import org.junit.Rule
import org.junit.Test

class PlayerRefinementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningToCurrentPositionKeepsDirectionThroughoutExit() {
        var visible by mutableStateOf(true)
        var up by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            AnimatedVisibility(visible, exit = fadeOut(tween(300))) {
                ReturnToPositionButton(up, "Current song", visible) {}
            }
        } }
        val button = compose.onNodeWithContentDescription("Current song")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll up"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false; up = false }
        compose.mainClock.advanceTimeBy(150)
        button.assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll up"))
        compose.mainClock.advanceTimeBy(350)
        button.assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(500)
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scroll down"))
    }
}
