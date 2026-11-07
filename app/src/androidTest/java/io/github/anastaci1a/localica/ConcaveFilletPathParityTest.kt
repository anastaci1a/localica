package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.HoverPathCache
import io.github.anastaci1a.localica.ui.SelectionRegion
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min
import android.graphics.Region

/** U03.01b (D15) native proof: a ConcaveFillet frame path equals HoverPathCache.path for the same bodies, gap and strengths. */
class ConcaveFilletPathParityTest {
    private val profile=motionProfileId(MotionProfile.Selection)
    private val clock=UiClock(object: FrameSource { override suspend fun awaitFrame(): Long=error("No frames needed: unpresented shapes initialize") },checkOwner={}).apply {
        configure(EngineContext(1f/120,1000f,MotionAccess.Standard),(uiSpec(InterfaceSettings(),geometry=ExpansionGeometryDomain(1000f,3000f,1f/60)) as SpecResult.Accepted).spec) }
    private fun corpus(frame: Int,count: Int): List<RoundRect> { val p=frame/120f
        return List(12) { i -> val x=i%3*73f; val y=i/3*73f; val tx=x+(80f+min(i,2)*6f-x)*p; val ty=y+(70f+min(i,2)*6f-y)*p
            RoundRect(tx,ty,tx+74.25f,ty+74.25f,CornerRadius(12f)) }.filterIndexed { i,_ -> count==12 || i!=5 }.let { if(frame%7==0) it.reversed() else it } }
    /** Skia path ops on API 26 fail on the corpus's near-coincident body edges (Path.combine threw at frame 0); a Region at 1/16 px is exact and robust on every level. */
    private fun region(path: Path)=Region().apply { setPath(android.graphics.Path(path.asAndroidPath()).apply { transform(android.graphics.Matrix().apply { setScale(16f,16f) }) },Region(-16000,-16000,16000,16000)) }
    private fun near(e: Rect,a: Rect)=listOf(e.left-a.left,e.top-a.top,e.right-a.right,e.bottom-a.bottom).all { kotlin.math.abs(it)<=1e-4f }

    @Test fun frameContourMatchesHoverPathCacheAcrossTheIntegratedCorpus() {
        for(frame in 0..120 step 3) for(count in listOf(12,11)) for(gap in listOf(16f,42f)) {
            val p=frame/120f; val bodies=corpus(frame,count); val data=JoinData(gap,bodies.map { it.boundingRect }) { _,_ -> 1f-p }
            val shape=PersistentShape(clock,ShapeTarget(emptyList()),Triple(frame,count,gap)); shape.joinPolicyData(data); shape.retarget(ShapeTarget(bodies,JoinPolicy.ConcaveFillet),profile)
            val shown=shape.outline(); val engine=shown.path; assertSame("A frame path is built once",engine,shown.path)
            val regions=shown.bodies.map { SelectionRegion(it.boundingRect,it.topLeftCornerRadius.x.coerceAtMost(it.topLeftCornerRadius.y)) }
            val legacy=HoverPathCache().path(regions,regions,gap,data.strength)
            val label="frame=$frame count=$count gap=$gap"
            assertTrue(label,near(legacy.getBounds(),engine.getBounds()))
            assertFalse("$label: contours differ",region(legacy).op(region(engine),Region.Op.XOR))
            // The concave contour is not the plain union: at strength 1 the bridges add coverage beyond the bodies.
            if(p<.5f && gap==16f) assertTrue(label,region(engine).op(shown.bodies.fold(Region()) { u,b -> u.apply { op(region(Path().apply { addRoundRect(b) }),Region.Op.UNION) } },Region.Op.DIFFERENCE))
            shape.dispose()
        }
    }
}
