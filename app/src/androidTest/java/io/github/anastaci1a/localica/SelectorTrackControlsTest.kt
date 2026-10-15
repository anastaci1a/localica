package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production controls; optical raster registration has its own acceptance gate. */
class SelectorTrackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navbarPullMovesTheShellLabelsAndSelectorTowardTheSameWall() {
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(280.dp, 64.dp)) {
                    SlidingTabs(listOf(MainTab.Home, MainTab.Library, MainTab.Songs), MainTab.Home, { 0f }) {}
                }
            }
        } }
        checkPull("tab-track-host", "tab-track-shell", "tab-selector", "tab-option-content-0", false)
    }

    @Test fun horizontalChoicesShareTheSameContactGeometry() {
        choices(horizontal = true)
        checkPull("choice-track-host", "choice-track-shell", "choice-lens", "choice-option-content-0", false)
    }

    @Test fun verticalChoicesStretchHeightAndMoveTheirTopContact() {
        choices(horizontal = false)
        checkPull("choice-track-host", "choice-track-shell", "choice-lens", "choice-option-content-0", true)
    }

    @Test fun rtlChoiceTargetsRemainInsideTheTrackInMirroredOrder() {
        var selected by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassChoices(listOf("First", "Second", "Third"), selected,
                        Modifier.width(280.dp), horizontal = true) { selected = it }
                }
            }
        } }
        val host = compose.onNodeWithTag("choice-track-host").getUnclippedBoundsInRoot()
        val targets = (0..2).map { compose.onNodeWithTag("choice-option-$it").getUnclippedBoundsInRoot() }
        targets.forEach { target ->
            assertTrue("Physical placement must not apply RTL alignment a second time", target.left >= host.left && target.right <= host.right)
        }
        assertTrue(targets[0].left > targets[1].left && targets[1].left > targets[2].left)
        compose.onNodeWithText("First").performTouchInput { swipe(center, Offset(-width * 1.5f, centerY), 350) }
        compose.onNodeWithText("Third").assertIsSelected()
        compose.onNodeWithText("Third").performTouchInput { swipe(center, Offset(width * 2.5f, centerY), 350) }
        compose.onNodeWithText("First").assertIsSelected()
    }

    private fun choices(horizontal: Boolean) {
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassChoices(listOf("First", "Second", "Third"), 0, Modifier.width(280.dp), horizontal = horizontal) {}
            }
        } }
    }

    // Unlike getUnclippedBoundsInRoot (position + untransformed layout size),
    // mapped corners include every ancestor graphics layer without clipping.
    private fun transformedBounds(tag: String): Rect {
        val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        val size = coordinates.size
        val corners = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f),
            Offset(0f, size.height.toFloat()), Offset(size.width.toFloat(), size.height.toFloat()))
            .map { coordinates.localToRoot(it) }
        return Rect(corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun checkPull(hostTag: String, shellTag: String, lensTag: String, contentTag: String, vertical: Boolean) {
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.waitForIdle()
        val host = compose.onNodeWithTag(hostTag, useUnmergedTree = true)
        val restingHost = bounds(hostTag)
        val shell = bounds(shellTag)
        val lens = bounds(lensTag)
        val content = transformedBounds(contentTag)
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            host.performTouchInput {
                down(if (vertical) Offset(centerX, height / 6f) else Offset(width / 6f, centerY))
                held = true
                moveBy(if (vertical) Offset(0f, -100f) else Offset(-100f, 0f), delayMillis = 100)
            }
            compose.mainClock.advanceTimeBy(800)
            val stretched = bounds(shellTag)
            val pressed = bounds(lensTag)
            val movedContent = transformedBounds(contentTag)
            assertEquals(restingHost, bounds(hostTag))
            val oldGap = if (vertical) lens.top.value - shell.top.value else lens.left.value - shell.left.value
            val newGap = if (vertical) pressed.top.value - stretched.top.value else pressed.left.value - stretched.left.value
            android.util.Log.i("SelectorTrack", "$hostTag shell=$shell lens=$lens stretched=$stretched pressed=$pressed content=$content movedContent=$movedContent")
            assertEquals("The measured resting lens includes the actual four-dp inset", 4f, oldGap, .1f)
            assertTrue("The contact gap must progressively close: $oldGap -> $newGap", newGap < oldGap - .1f)
            assertTrue("The outer track stretches along travel", if (vertical) stretched.height > shell.height else stretched.width > shell.width)
            assertTrue("Option content follows the moving wall", if (vertical) movedContent.top + movedContent.bottom < content.top + content.bottom else movedContent.left + movedContent.right < content.left + content.right)
            assertTrue("Option content deforms on the travel axis", if (vertical) movedContent.height > content.height else movedContent.width > content.width)
            host.performTouchInput { up(); held = false }
            compose.mainClock.advanceTimeBy(3000)
            assertEquals(restingHost, bounds(hostTag))
            val settled = bounds(shellTag)
            assertEquals(shell.width.value, settled.width.value, .1f)
            assertEquals(shell.height.value, settled.height.value, .1f)
        } finally {
            if (held) host.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }
}
