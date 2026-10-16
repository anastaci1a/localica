package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.ArtworkLayout
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real Player settings destination and shared popup, not a replacement choice.
 * Pointer injection targets the resolved Compose root; Android window routing is not covered.
 */
class PlayerArtworkChoiceInputTest {
    @get:Rule val compose = createComposeRule()
    private var settings = InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground)
    private var saves = 0
    private var slop = 0f

    private fun fixture(fontScale: Float = 1f) {
        compose.setContent {
            var current by remember { mutableStateOf(settings) }
            val density = LocalDensity.current.density
            slop = LocalViewConfiguration.current.touchSlop
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                LocalicaTheme { GlassDialogHost {
                    Column(Modifier.width(280.dp).height(440.dp).verticalScroll(rememberScrollState())) {
                        InterfaceSettingsControls(current, "Player") {
                            saves++; current = it; settings = it
                        }
                    }
                } }
            }
        }
    }
    private fun option(value: ArtworkLayout) = compose.onNode(hasText(value.label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun phase(label: String, action: () -> Unit) {
        android.util.Log.i("ArtworkChoiceInput", "phase=$label saves=$saves value=${settings.artworkLayout} slop=$slop")
        try { action() } catch (failure: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { index, root ->
                    val view = (root.root as androidx.compose.ui.platform.ViewRootForTest).view
                    val native = compose.runOnUiThread { "attached=${view.isAttachedToWindow} focus=${view.hasWindowFocus()}" }
                    android.util.Log.e("ArtworkChoiceInput", "root=$index bounds=${root.boundsInRoot} $native")
                }
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("ArtworkChoiceInput")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun open() = phase("open actual artwork choice") {
        compose.onNodeWithText("Player artwork").performScrollTo().performTouchInput { click(center) }
        // Standard Compose synchronization only: this covers settled input, not opening-frame readiness.
        option(settings.artworkLayout).assertIsSelected()
    }
    private fun confirm(expected: ArtworkLayout) = phase("confirm $expected") {
        compose.onNodeWithText("Select", substring = false).performTouchInput { click(center) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select", substring = false).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(expected, settings.artworkLayout) }
    }

    @Test fun singlePointerTapOnEachUnselectedArtworkChoiceSurvivesReopening() {
        fixture()
        for (target in listOf(ArtworkLayout.Floating, ArtworkLayout.FullBackground, ArtworkLayout.Floating)) {
            open()
            phase("single pointer tap $target") {
                val node = option(target)
                android.util.Log.i("ArtworkChoiceInput", "tap target=${node.fetchSemanticsNode().boundsInRoot}")
                node.performTouchInput { click(center) }
                node.assertIsSelected()
            }
            confirm(target)
        }
        compose.runOnIdle { assertEquals("One saved settings change per confirmation", 3, saves) }
    }

    @Test fun subSlopMovementStillSelectsOneUnselectedOptionAtLargeFont() {
        fixture(fontScale = 1.5f)
        open()
        val target = option(ArtworkLayout.Floating).performScrollTo()
        phase("one sub-slop tap") {
            android.util.Log.i("ArtworkChoiceInput", "subslop target=${target.fetchSemanticsNode().boundsInRoot} delta=${slop * .25f}")
            target.performTouchInput {
                down(center); moveBy(Offset(0f, slop * .25f)); up()
            }
            target.assertIsSelected()
        }
        confirm(ArtworkLayout.Floating)
        compose.runOnIdle { assertEquals(1, saves) }
    }

    /** Characterize the existing button contract; this is not a new drag-anywhere requirement. */
    @Test fun unselectedOriginOverSlopCancelsActivationRatherThanChangingDraft() {
        fixture()
        open()
        val first = option(ArtworkLayout.FullBackground)
        val second = option(ArtworkLayout.Floating)
        phase("unselected-origin diagnostic drag") {
            val bounds = second.fetchSemanticsNode().boundsInRoot
            android.util.Log.i("ArtworkChoiceInput", "unselected target=$bounds movement=${slop * 2f}")
            second.performTouchInput {
                down(center); moveBy(Offset(0f, -slop * 2f), delayMillis = 120); up()
            }
            first.assertIsSelected(); second.assertIsNotSelected()
            compose.runOnIdle { assertEquals(0, saves) }
        }
    }

    @Test fun selectedOriginDragCancelsWithoutChangingDraftAndCommitsOnlyOnRelease() {
        fixture()
        open()
        val first = option(ArtworkLayout.FullBackground)
        val second = option(ArtworkLayout.Floating)
        val distance = second.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        phase("selected-origin cancel") {
            first.performTouchInput {
                down(center); moveBy(Offset(0f, distance), delayMillis = 120); cancel()
            }
            first.assertIsSelected(); second.assertIsNotSelected()
            compose.runOnIdle { assertEquals(0, saves) }
        }
        phase("selected-origin release") {
            val startNode = first.fetchSemanticsNode()
            val targetNode = second.fetchSemanticsNode()
            val root = startNode.root
            val view = (root as androidx.compose.ui.platform.ViewRootForTest).view
            val start = startNode.boundsInRoot.center
            var point = start
            var held = false
            var downTime = 0L
            var eventTime = 0L
            fun send(action: Int, position: Offset = point) {
                point = position
                eventTime = maxOf(android.os.SystemClock.uptimeMillis(), eventTime + 16)
                if (action == android.view.MotionEvent.ACTION_DOWN) { downTime = eventTime; held = true }
                compose.runOnUiThread {
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val properties = android.view.MotionEvent.PointerProperties().apply {
                        id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                    }
                    val coordinates = android.view.MotionEvent.PointerCoords().apply {
                        x = point.x + location[0]; y = point.y + location[1]; pressure = 1f; size = 1f
                    }
                    val event = android.view.MotionEvent.obtain(downTime, eventTime, action, 1,
                        arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                    try {
                        val delivered = view.dispatchTouchEvent(event)
                        if (action != android.view.MotionEvent.ACTION_CANCEL) assertTrue(delivered)
                    } finally { event.recycle() }
                }
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) held = false
            }
            try {
                send(android.view.MotionEvent.ACTION_DOWN, start)
                send(android.view.MotionEvent.ACTION_MOVE, start + Offset(0f, distance))
                compose.mainClock.advanceTimeByFrame()
                // Fresh semantics on UI, without Espresso idle or cached config while held.
                compose.runOnUiThread {
                    val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    assertTrue(nodes.single { it.id == startNode.id }.config[SemanticsProperties.Selected])
                    assertFalse(nodes.single { it.id == targetNode.id }.config[SemanticsProperties.Selected])
                }
                send(android.view.MotionEvent.ACTION_UP)
            } finally {
                if (held) send(android.view.MotionEvent.ACTION_CANCEL)
            }
            second.assertIsSelected(); first.assertIsNotSelected()
            compose.runOnIdle { assertEquals("Draft must not save before Select", 0, saves) }
        }
        confirm(ArtworkLayout.Floating)
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
