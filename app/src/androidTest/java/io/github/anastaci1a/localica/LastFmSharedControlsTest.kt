package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.layout.settingsLinkMarker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmSharedControlsTest {
    @get:Rule val compose = localicaComposeRule()
    private fun pixels(): List<Color> {
        val map = compose.onNodeWithTag("face").captureToImage().toPixelMap()
        return (0 until map.height).flatMap { y -> (0 until map.width).map { x -> map[x, y] } }
    }

    @Test fun originalPositionalButtonAndExplicitNormalFaceKeepTheSameGeometryAndPixels() {
        var explicit by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("face")) {
                if (explicit) GlassActionButtonFace(false, AppIcon.ActionPlus, "Add")
                else GlassActionButtonFace(AppIcon.ActionPlus, "Add")
            }
        } }
        val before = pixels()
        val bounds = compose.onNodeWithTag("face").getUnclippedBoundsInRoot()
        compose.runOnIdle { explicit = true }
        assertEquals(bounds, compose.onNodeWithTag("face").getUnclippedBoundsInRoot())
        assertEquals(before, pixels())
    }

    @Test fun iconOnlyConfirmHasOneAccessibleActionNoVisibleLabelAndHonorsDisabledState() {
        var enabled by mutableStateOf(false); var calls = 0
        compose.setContent { LocalicaTheme {
            GlassActionButton(true, AppIcon.ActionCheck, "Use selection", enabled = enabled) { calls++ }
        } }
        compose.onAllNodes(hasContentDescription("Use selection") and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Use selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled().performClick()
        assertEquals(0, calls)
        compose.runOnIdle { enabled = true }
        compose.onNodeWithContentDescription("Use selection").assertIsEnabled().performClick()
        assertEquals(1, calls)
    }

    @Test fun markedLinkRetainsTheIdenticalForegroundDescriptorWithoutChangingTheTextGrid() {
        var showLive by mutableStateOf(true); var retainedFace by mutableStateOf(false)
        var descriptor by mutableStateOf<(@Composable () -> Unit)?>(null)
        val color = Color(0xFF71C9A2)
        compose.setContent { LocalicaTheme {
            val anchor = rememberGlassAnchor()
            if (showLive) {
                // Original positional callback and the required marker overload both remain callable.
                SettingsLink(color, "Matched", "12 songs", null, true, anchor, Modifier, {})
                SideEffect { if (descriptor == null) descriptor = anchor.face }
            } else Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("face")) {
                if (retainedFace) descriptor?.invoke()
                else SettingsLinkFace("Matched", "12 songs", null, Modifier.settingsLinkMarker(color))
            }
        } }
        compose.waitUntil(5000) { descriptor != null }
        compose.runOnIdle { showLive = false }
        val before = pixels()
        val bounds = compose.onNodeWithTag("face").getUnclippedBoundsInRoot()
        compose.runOnIdle { retainedFace = true }
        assertEquals(bounds, compose.onNodeWithTag("face").getUnclippedBoundsInRoot())
        assertEquals(before, pixels())
    }
}
