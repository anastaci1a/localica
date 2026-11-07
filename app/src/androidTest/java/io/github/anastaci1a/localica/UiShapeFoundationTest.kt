package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** U03.01a native proof: the real factory initializes before presentation, draws the Union path, and keeps published frames immutable. */
@OptIn(ExperimentalTestApi::class)
class UiShapeFoundationTest {
    private class Scene { var clock: UiClock?=null; var shape: PersistentShape?=null; var drawn: ShapeFrame?=null }
    private val a=RoundRect(0f,0f,100f,40f,CornerRadius(20f)); private val b=RoundRect(80f,10f,180f,50f,CornerRadius(20f)); private val c=RoundRect(20f,60f,120f,100f,CornerRadius(20f))
    private val profile=motionProfileId(MotionProfile.Selection)
    @Composable private fun Fixture(now: ()->Long,scene: Scene) {
        val clock=remember { UiClock(object: FrameSource { override suspend fun awaitFrame()=withFrameNanos { it } },now) }; LaunchedEffect(clock) { clock.run() }
        val snapshot=remember { ResolvedUiSpec((uiSpec(InterfaceSettings(),geometry=ExpansionGeometryDomain(1000f,3000f,1f/60)) as SpecResult.Accepted).spec,Density(1f)) }
        CompositionLocalProvider(LocalClockOwner provides clock,LocalUiClock provides clock,LocalEngineContext provides EngineContext(1f/120,1000f,MotionAccess.Standard),LocalUiSpec provides snapshot) {
            val shape=rememberPersistentShape("native-shape")
            // The owner sets its first target before the first draw, so it initializes rather than morphing from empty.
            SideEffect { if(scene.shape==null) { shape.retarget(ShapeTarget(listOf(a,b),JoinPolicy.Union),profile); scene.shape=shape; scene.clock=clock } }
            Canvas(Modifier.size(200.dp)) { val frame=shape.outline(); scene.drawn=frame; drawPath(frame.path,Color.Black) }
        }
    }
    private fun covers(path: Path,body: RoundRect)=Path.combine(PathOperation.Difference,Path().apply { addRoundRect(body) },path).isEmpty
    private fun assertRect(expected: Rect,actual: Rect) { for((e,v) in listOf(expected.left to actual.left,expected.top to actual.top,expected.right to actual.right,expected.bottom to actual.bottom)) assertEquals(e,v,1e-3f) }

    @Test fun factoryShapeDrawsUnionPathAndOldFramesStayImmutable() = localicaRunComposeUiTest {
        val scene=Scene(); setContent { Fixture({ mainClock.currentTime*1_000_000 },scene) }
        mainClock.autoAdvance=false
        var first: ShapeFrame?=null
        runOnIdle {
            val frame=scene.drawn!!; first=frame
            assertEquals(listOf(a,b),frame.bodies); assertEquals(0,scene.clock!!.activeCount)
            val path=frame.path; assertSame("A frame path is built once",path,frame.path)
            assertRect(Rect(0f,0f,180f,50f),path.getBounds()); assertRect(frame.bounds,path.getBounds()); assertTrue(covers(path,a) && covers(path,b))
            scene.shape!!.retarget(ShapeTarget(listOf(c),JoinPolicy.Union),profile)
        }
        mainClock.advanceTimeBy(100)
        runOnIdle {
            val middle=scene.drawn!!; assertNotSame(first,middle); assertRect(middle.bounds,middle.path.getBounds())
            assertTrue(middle.bodies.size in 1..2)
        }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            val last=scene.drawn!!; assertEquals(listOf(c),last.bodies); assertRect(Rect(20f,60f,120f,100f),last.path.getBounds()); assertTrue(covers(last.path,c))
            // The first published frame is untouched by every later intent and frame.
            val old=first!!; assertEquals(listOf(a,b),old.bodies); assertRect(Rect(0f,0f,180f,50f),old.path.getBounds()); assertTrue(covers(old.path,a) && covers(old.path,b))
        }
    }
}
