package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class UiSpecFoundationTest {
    @Test fun rootFixtureResolvesOnceAndNativePopupInheritsTheAcceptedSnapshot() = localicaRunComposeUiTest {
        var scale by mutableFloatStateOf(1f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var resolves=0
        var root: ResolvedUiSpec?=null
        var popup: ResolvedUiSpec?=null
        val accepted=(uiSpec(InterfaceSettings()) as SpecResult.Accepted).spec
        setContent {
            val density=Density(scale,1.3f)
            val spec=remember(accepted,density) { resolves++; ResolvedUiSpec(accepted,density) }
            CompositionLocalProvider(LocalDensity provides density,LocalLayoutDirection provides direction,LocalUiSpec provides spec) {
                val current=LocalUiSpec.current
                SideEffect { root=current }
                Popup {
                    val inherited=LocalUiSpec.current
                    SideEffect { popup=inherited }
                }
            }
        }
        runOnIdle { assertNotNull(root); assertSame(root,popup); assertEquals(1,resolves) }
        mainClock.advanceTimeBy(1000)
        runOnIdle { assertEquals("Idle presentation performs no spec resolution",1,resolves); scale=2.625f }
        runOnIdle { assertEquals(2,resolves); assertSame(root,popup); assertEquals(12*2.625f,root!!.px(Space.Base),0f) }
        runOnIdle { direction=LayoutDirection.Rtl }
        runOnIdle { assertEquals("RTL reuses the same direction-independent resolution",2,resolves); assertSame(root,popup) }
    }
    @Test fun rejectedPayloadNeverBecomesTheInstalledFixtureSnapshot() = localicaRunComposeUiTest {
        val settings=InterfaceSettings(reducedMotion=true,fasterMotion=true)
        val invalid=uiSpec(settings,UiSpecData(expansion=StockUiSpec.expansion.copy(velocityKnee=Float.NaN)))
        assertTrue(invalid is SpecResult.Rejected)
        val fallback=(uiSpec(settings) as SpecResult.Accepted).spec
        var installed: ResolvedUiSpec?=null
        setContent {
            val accepted=remember(fallback) { ResolvedUiSpec(fallback,Density(1f)) }
            CompositionLocalProvider(LocalUiSpec provides accepted) {
                val current=LocalUiSpec.current
                SideEffect { installed=current }
            }
        }
        runOnIdle { assertEquals(MotionAccess.Reduced,installed!!.spec.access); assertTrue(installed!!.spec.expansion.velocityKnee.isFinite()) }
    }
}
