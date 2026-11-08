package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
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
    private class Scene { var sheet: SheetState? = null; var compositions = 0 }
    @Composable private fun Fixture(open: Boolean, now: () -> Long, scene: Scene) {
        val clock = remember { UiClock(object : FrameSource {
            override suspend fun awaitFrame() = withFrameNanos { it }
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
            Canvas(Modifier.size(20.dp)) { sheet.expansion.phase.value() }
        }
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
}
