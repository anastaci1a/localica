package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** Counts committed root compositions, independently of animation frame count. */
@OptIn(ExperimentalTestApi::class)
class SheetCollapseCompositionTest {
    private class Scene(val observeClosed: Boolean = false, val compareControl: Boolean = false) {
        var sheet: SheetState? = null; var compositions = 0
        var closed = false; var closedCompositions = 0
        var presentedPhase = 1f; var controlPhase = 1f
    }
    @Composable private fun Fixture(open: Boolean, now: () -> Long, scene: Scene) {
        val clock = remember { UiClock(object : FrameSource {
            override suspend fun awaitFrame() = withFrameNanos { it }
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame)
        }, now) }
        LaunchedEffect(clock) { clock.run() }
        val spec = remember { ResolvedUiSpec((uiSpec(InterfaceSettings(), geometry =
            ExpansionGeometryDomain(1000f, 3000f, 1f / 60)) as SpecResult.Accepted).spec, Density(1f)) }
        CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock,
            LocalEngineContext provides EngineContext(1f / 60, 1000f, MotionAccess.Standard), LocalUiSpec provides spec) {
            // This is the same value-returning factory and effect as the screen root.
            val sheet = rememberSheetState("queue", true,
                ExpansionBasis(Rect(0f, 1000f, 100f, 1100f), Rect(0f, 0f, 100f, 1100f), 1f))
            LaunchedEffect(open) { if (open) sheet.expand() else sheet.collapse() }
            SideEffect { scene.sheet = sheet; scene.compositions++ }
            Canvas(Modifier.size(20.dp)) { scene.presentedPhase = sheet.expansion.phase.value() }
            if (scene.compareControl) {
                val control = remember { Animatable(1f) }
                // Control 7c9a1bd8 LocalicaScreen: queueOpen effect, handed-off popup, 400ms tween.
                LaunchedEffect(open) { control.animateTo(if (open) 1f else 0f, tween(400)) }
                Canvas(Modifier.size(20.dp)) { scene.controlPhase = control.value }
            }
            if (scene.observeClosed) ClosedHost(sheet.expansion, scene)
        }
    }

    @Composable private fun ClosedHost(view: ExpansionView, scene: Scene) {
        val closed by rememberExpansionClosed(view)
        SideEffect { scene.closed = closed; scene.closedCompositions++ }
        Box(Modifier.size(20.dp).testTag("underlay").then(if (closed)
            Modifier.semantics { contentDescription = "Underlying player" } else Modifier.clearAndSetSemantics {}))
    }

    @Test fun collapseIntentHasOneRootCompositionBeforeTheClosedEndpoint() = localicaRunComposeUiTest {
        val scene = Scene()
        var open by mutableStateOf(true)
        setContent { Fixture(open, { mainClock.currentTime * 1_000_000 }, scene) }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(64)
        var before = 0
        runOnIdle { before = scene.compositions; open = false }
        mainClock.advanceTimeBy(96)
        runOnIdle {
            assertFalse(scene.sheet!!.expansion.phase.settled)
            assertEquals("Only the user's closing intent may recompose the screen root", 1, scene.compositions - before)
        }
        mainClock.advanceTimeBy(2000)
        runOnIdle { assertTrue(scene.sheet!!.expansion.phase.settled); assertFalse(scene.sheet!!.expanded) }
    }

    @Test fun closedObserverRetainsSemanticsGateThroughMovingAndRetargeting() = localicaRunComposeUiTest {
        val scene = Scene(observeClosed = true)
        var open by mutableStateOf(true)
        setContent { Fixture(open, { mainClock.currentTime * 1_000_000 }, scene) }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(64)
        var before = 0
        runOnIdle { before = scene.closedCompositions; open = false }
        mainClock.advanceTimeBy(96)
        runOnIdle { assertFalse(scene.closed); assertEquals(before, scene.closedCompositions) }
        onNodeWithContentDescription("Underlying player").assertDoesNotExist()
        runOnIdle { open = true }
        mainClock.advanceTimeBy(2000)
        runOnIdle { assertFalse(scene.closed); assertEquals(before, scene.closedCompositions); open = false }
        mainClock.advanceTimeBy(2000)
        runOnIdle { assertTrue(scene.closed); assertEquals(before + 1, scene.closedCompositions) }
        onNodeWithContentDescription("Underlying player").assertExists()
    }

    @Test fun collapseDuringContactIsPendingAndCancellationAppliesIt() = localicaRunComposeUiTest {
        val scene = Scene()
        setContent { Fixture(true, { mainClock.currentTime * 1_000_000 }, scene) }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(64)
        runOnIdle {
            val sheet = scene.sheet!!
            assertTrue(sheet.contact.onDragStart())
            sheet.contact.onDrag(-200f)
            sheet.collapse()
        }
        mainClock.advanceTimeByFrame()
        runOnIdle {
            val sheet = scene.sheet!!
            val owner = sheet.expansion as ExpandCollapse
            assertEquals(false, owner.pendingTarget)
            assertTrue(owner.inContact)
            assertTrue(owner.expanded)
            sheet.contact.onDragCancel()
            assertNull(owner.pendingTarget)
            assertFalse(owner.inContact)
        }
        mainClock.advanceTimeBy(2000)
        runOnIdle { assertTrue(scene.sheet!!.expansion.phase.settled); assertFalse(scene.sheet!!.expansion.expanded) }
    }

    @Test fun firstPresentedCollapseFrameIsNoLaterThanControl() = localicaRunComposeUiTest {
        val scene = Scene(compareControl = true)
        var open by mutableStateOf(true)
        setContent {
            Fixture(open, { mainClock.currentTime * 1_000_000 }, scene)
            Box(Modifier.size(20.dp).testTag("collapse-intent").clickable { open = false })
        }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(64)
        runOnIdle { assertEquals(1f, scene.presentedPhase); assertEquals(1f, scene.controlPhase) }
        onNodeWithTag("collapse-intent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        val fixed = mutableListOf<Float>()
        val control = mutableListOf<Float>()
        repeat(6) {
            mainClock.advanceTimeByFrame()
            runOnIdle { fixed += scene.presentedPhase; control += scene.controlPhase }
        }
        val fixedFrame = fixed.indexOfFirst { it < 1f } + 1
        val controlFrame = control.indexOfFirst { it < 1f } + 1
        android.util.Log.i("SheetCollapseLatency", "fixedFirst=$fixedFrame controlFirst=$controlFrame fixed=$fixed control=$control")
        assertTrue("Control must start within six frames: $control", controlFrame > 0)
        assertTrue("Fixed must start within six frames: $fixed", fixedFrame > 0)
        assertTrue("Fixed=$fixedFrame must be no later than control=$controlFrame; fixed=$fixed control=$control", fixedFrame <= controlFrame)
    }

}
