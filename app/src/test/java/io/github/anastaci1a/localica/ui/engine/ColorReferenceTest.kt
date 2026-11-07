package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.graphics.Color
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.*

class ColorReferenceTest {
    // Independent row-vector reference transcribed from Ottosson's 2021 published matrices,
    // https://bottosson.github.io/posts/oklab/ . Compare BEFORE Compose half-float packing.
    @Test fun unclampedInverseMatchesIndependentMatrixReference() {
        val inverseCone=arrayOf(doubleArrayOf(1.0,.3963377774,.2158037573),doubleArrayOf(1.0,-.1055613458,-.0638541728),doubleArrayOf(1.0,-.0894841775,-1.291485548))
        val inverseRgb=arrayOf(doubleArrayOf(4.0767416621,-3.3077115913,.2309699292),doubleArrayOf(-1.2684380046,2.6097574011,-.3413193965),doubleArrayOf(-.0041960863,-.7034186147,1.707614701))
        val r=Random(600105)
        repeat(10000) {
            val lab=doubleArrayOf(r.nextDouble()*2-.5,r.nextDouble()*2-1,r.nextDouble()*2-1)
            val cones=inverseCone.map { row -> row.indices.sumOf { row[it]*lab[it] }.pow(3) }
            for(i in 0..2) assertEquals(inverseRgb[i].indices.sumOf { inverseRgb[i][it]*cones[it] },oklabToLinear(lab[0],lab[1],lab[2],i),1e-6)
        }
        assertTrue(oklabToLinear(1.2,0.0,0.0,0)>1)
    }
    @Test fun declaredSpansContainNumericallyDerivedGamutExtrema() {
        // Primary forward matrix extrema on linear RGB cube, from independent projected
        // face/edge optimisation (private oklab-extents.mjs), not a clipped [-.5,.5] box.
        val minA=-.23388757418790818; val maxA=.27621677052305565
        val minB=-.3115281476783751; val maxB=.19856975465179516
        assertTrue(.51010436f.toDouble()>=maxA-minA); assertTrue(.5100979f.toDouble()>=maxB-minB)
        fun a(r: Double,g: Double,b: Double): Double { val l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b); val m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b); val s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b); return 1.9779984951*l-2.428592205*m+.4505937099*s }
        assertEquals(minA,a(0.0,1.0,0.0),1e-12); assertEquals(maxA,a(1.0,0.0,.7419195206912128),1e-12)
        val random=Random(600106)
        repeat(100000) { val value=a(random.nextDouble(),random.nextDouble(),random.nextDouble()); assertTrue(value>=minA-1e-12 && value<=maxA+1e-12) }
    }
    @Test fun tenThousandRectAndColorInterruptionsPreservePersistentChannels() {
        EngineHarness().use { h ->
            val rect=MotionRect(h.clock,androidx.compose.ui.geometry.Rect.Zero,EdgeRanges(1000f,1000f,1000f,1000f))
            val ids=rect.channels.map { it.id }; val color=MotionColor(h.clock,Color.Black)
            val random=Random(600107); val profile=motionProfileId(MotionProfile.Control)
            repeat(10000) {
                h.now+=1_000_000; val stamp=h.clock.stampNow; val before=rect.valueAt(stamp); val c=color.valueAt(stamp)
                val a=random.nextFloat()*500; val b=random.nextFloat()*500
                rect.retarget(androidx.compose.ui.geometry.Rect(a,b,a+100,b+100),profile)
                color.retarget(Color(random.nextFloat(),random.nextFloat(),random.nextFloat()),profile)
                assertEquals(before,rect.valueAt(stamp)); assertEquals(c,color.valueAt(stamp)); assertEquals(ids,rect.channels.map { it.id })
                if(it%100==0) h.advance(.001)
            }
            rect.dispose(); color.dispose()
        }
    }
    @Test fun stageOneHasNoProductionOwnerFactoryCallers() {
        val root=File("src/main/java")
        assertTrue(root.isDirectory)
        val names=Regex("\\b(rememberMotionFloat|rememberNormalizedMotionFloat|rememberMotionRect|rememberMotionColor|rememberExpandCollapse|rememberPersistentShape|rememberSurfaceNode|rememberPopupMotion|rememberPopupState)\\s*\\(")
        // The components factory declaration (U07.00: rememberPopupState → rememberPopupMotion) is not a caller.
        val declaration=Regex("^@Composable fun rememberPopupState\\(")
        val callers=root.walkTopDown().filter { it.extension=="kt" && "/ui/engine/" !in it.invariantSeparatorsPath }.filter { f -> f.readLines().any { names.containsMatchIn(it) && !declaration.containsMatchIn(it) } }.toList()
        assertEquals(emptyList<File>(),callers)
    }
    @Test fun transparentAlphaNarrowRectAndMirroredPhysicalEdgesStayFinite() {
        EngineHarness().use { h ->
            val color=MotionColor(h.clock,Color(1f,0f,0f,0f)); val profile=motionProfileId(MotionProfile.Control)
            color.retarget(Color(0f,1f,0f,1f),profile)
            val rect=MotionRect(h.clock,androidx.compose.ui.geometry.Rect(1f,0f,1.00001f,10f),EdgeRanges(100f,100f,100f,100f))
            val mirrored=MotionRect(h.clock,androidx.compose.ui.geometry.Rect(98.99999f,0f,99f,10f),EdgeRanges(100f,100f,100f,100f))
            rect.retarget(androidx.compose.ui.geometry.Rect(20f,30f,20.00001f,40f),profile)
            mirrored.retarget(androidx.compose.ui.geometry.Rect(79.99999f,30f,80f,40f),profile)
            repeat(30) {
                val stamp=FrameStamp(h.now+it*10_000_000L,it.toLong()); val a=rect.valueAt(stamp); val b=mirrored.valueAt(stamp); val c=color.valueAt(stamp)
                assertEquals(100-a.right,b.left,1e-5f); assertEquals(100-a.left,b.right,1e-5f)
                assertTrue(c.alpha in 0f..1f); assertTrue(listOf(c.red,c.green,c.blue,a.left,a.right).all { it.isFinite() })
            }
            val extreme=MotionFloat(h.clock,Float.MAX_VALUE,Float.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) { extreme.retarget(-Float.MAX_VALUE,profile) }; assertEquals(Float.MAX_VALUE,extreme.target,0f)
            color.dispose(); rect.dispose(); mirrored.dispose(); extreme.dispose()
        }
    }

}
