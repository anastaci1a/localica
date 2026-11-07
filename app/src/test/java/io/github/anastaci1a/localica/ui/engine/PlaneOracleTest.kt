package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.glassShadowRingAlpha
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** U04.01a/D27 red witness: the multi-plane composite equals the single-owner oracle at every exclusion weight, because the backend never partitions a caster's field. */
class PlaneOracleTest {
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private val profile=motionProfileId(MotionProfile.Control)
    /** Independent Double oracle: one owner's cumulative ring coverage at depth m of n rings, strength s (GlassShadow's 1−exp(−k·s·u³) law). */
    private fun field(s: Double,m: Int,n: Int)=1-exp(-.34594453*s*((m+1.0)/n).pow(3))
    private fun composite(alphas: List<Double>)=1-alphas.fold(1.0) { p,a -> p*(1-a) }
    /** What one plane's renderer layer holds at depth m: its quantized rings composited SrcOver. */
    private fun drawn(s: Double,m: Int,n: Int)=composite((0..m).map { glassShadowRingAlpha(it,n,s.toFloat()).toDouble() })
    private val strengths=listOf(.15,.4,.8,1.0); private val rings=listOf(16,40,128)

    @Test fun rejectsNaiveAlphaPartition() = EngineHarness(spec=geometry).use { h ->
        // Analytic counterexample (U04 §3.2): equal-colour alpha ramps A(1−w), Aw composite to A−A²w(1−w); .19 for A=.2, w=.5, beyond the .008 budget.
        assertEquals(.19,composite(listOf(.2*.5,.2*.5)),1e-12); assertTrue(.2-composite(listOf(.1,.1))>.008)
        val a=ShadowPlane(h.clock,HostKey.next(Any())); val b=ShadowPlane(h.clock,HostKey.next(Any()))
        val node=SurfaceNode(h.clock,"trigger",PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(0f,0f,96f,40f,CornerRadius(20f))),JoinPolicy.Union)),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),a)
        node.transfer(b,profile); b.acknowledge(); val w=node.shadowOwnership as MotionFloat
        var naiveWorst=0.0; var strengthSplitWorst=0.0
        for(share in listOf(0f,.001f,.1f,.5f,.9f,.999f,1f)) {
            h.advance(.004); val stamp=h.clock.stampNow; w.drive(share,stamp)
            // D27: one plane draws the whole field at every exclusion weight; the composite is the single-owner field itself.
            val parts=listOf(a,b).map { it.contributionAt(node,stamp).toDouble() }; assertEquals("w=$share",listOf(0.0,1.0),parts)
            val weights=listOf(a,b).map { node.shareAt(it,stamp).toDouble() }; assertEquals("w=$share",1.0,weights.sum(),1e-6)
            for(s in strengths) for(n in rings) for(m in 0 until n) {
                val label="w=$share s=$s n=$n m=$m"; val oracle=drawn(s,m,n)
                assertEquals(label,oracle,composite(parts.filter { it>0 }.map { drawn(s*it,m,n) }),0.0)
                assertEquals(label,field(s,m,n),composite(parts.map { field(s*it,m,n) }),1e-12)
                // Rejected partitions of the same weights: alpha ramps, and a strength split (exact before quantization, not as drawn).
                naiveWorst=maxOf(naiveWorst,abs(oracle-composite(weights.map { oracle*it })))
                assertEquals(label,field(s,m,n),composite(weights.map { field(s*it,m,n) }),1e-6)
                strengthSplitWorst=maxOf(strengthSplitWorst,abs(oracle-composite(weights.map { drawn(s*it,m,n) })))
            }
        }
        assertTrue("A naive alpha partition must be rejected; worst=$naiveWorst",naiveWorst>.008)
        assertTrue("The quantized strength split misses the drawn field (why ownership is binary); worst=$strengthSplitWorst",strengthSplitWorst>.008)
    }

    @Test fun drainAndTransferSamplesMatchTheOracleAtTheirTotalContribution() = EngineHarness(spec=geometry).use { h ->
        val r=Random(60402); val planes=List(3) { ShadowPlane(h.clock,HostKey.next(Any())) }
        val node=SurfaceNode(h.clock,"drain",PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(0f,0f,96f,40f,CornerRadius(20f))),JoinPolicy.Union)),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),planes[0])
        repeat(400) {
            when(r.nextInt(4)) { 0 -> node.transfer(planes[r.nextInt(3)],profile); 1 -> planes[r.nextInt(3)].acknowledge(); 2 -> node.request(SurfaceTarget(Stratum.Floating,r.nextFloat(),ShadowRole.Glass),profile); else -> h.advance(r.nextDouble()*.05) }
            val stamp=h.clock.stampNow; val parts=planes.map { it.contributionAt(node,stamp).toDouble() }; val total=node.opacity.valueAt(stamp).toDouble()
            assertEquals(total,parts.sum(),1e-5)
            for(m in listOf(0,7,15)) assertEquals(field(total,m,16),composite(parts.map { field(it,m,16) }),1e-6)
        }
    }
}
