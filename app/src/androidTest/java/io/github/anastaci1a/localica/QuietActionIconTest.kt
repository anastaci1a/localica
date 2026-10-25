package io.github.anastaci1a.localica

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuietActionIconTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plainDotsReplaceArtworkCornersAcrossRepeatedPresentationChanges() {
        lateinit var anchor: GlassAnchor
        var gallery by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            anchor = rememberGlassAnchor()
            if (gallery) Box(Modifier.size(96.dp).contextMenuAnchor(anchor))
            else ActionIcon(R.drawable.ic_ellipsis, "Song actions", anchor = anchor, quiet = true) {}
        } }
        repeat(3) {
            compose.runOnIdle { assertEquals(16.dp, anchor.cornerRadius); gallery = false }
            compose.runOnIdle {
                assertNull("Dots must not inherit the previous artwork's square corners", anchor.cornerRadius)
                assertTrue(anchor.quiet)
                assertNotNull(anchor.face)
                gallery = true
            }
        }
    }

    @Test fun quietHoldHasItsOwnPointerAndAccessibleActionWithoutATapReplay() {
        var taps = 0
        var holds = 0
        var parentHolds = 0
        compose.setContent { LocalicaTheme {
            Box(Modifier.combinedClickable(onClick = {}, onLongClick = { parentHolds++ })) {
                ActionIcon(R.drawable.ic_ellipsis, "Song actions", quiet = true,
                    onLongClickLabel = "Select song", onLongClick = { holds++ }) { taps++ }
            }
        } }
        val dots = compose.onNodeWithContentDescription("Song actions")
        dots.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(0, taps); assertEquals(0, parentHolds) }
        dots.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        compose.runOnIdle { assertEquals(2, holds); assertEquals(0, taps) }
        dots.performClick()
        compose.runOnIdle { assertEquals(1, taps); assertEquals(2, holds) }
    }

    @Test fun quietDotsStillYieldFastSwipesToTheList() {
        var taps = 0
        var holds = 0
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Column(Modifier.height(400.dp).controlVerticalScroll(scroll)) {
                Spacer(Modifier.height(100.dp))
                ActionIcon(R.drawable.ic_ellipsis, "Song actions", quiet = true,
                    onLongClick = { holds++ }) { taps++ }
                Spacer(Modifier.height(800.dp))
            }
        } }
        compose.onNodeWithContentDescription("Song actions").performTouchInput {
            swipe(center, center + Offset(0f, -160f), 120)
        }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertEquals(0, taps); assertEquals(0, holds) }
    }
}
