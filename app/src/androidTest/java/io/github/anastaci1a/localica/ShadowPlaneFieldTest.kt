package io.github.anastaci1a.localica

import androidx.compose.ui.graphics.Paint
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.GlassShadowGeometry
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.glassShadows
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.assertTrue
import org.junit.Test

/** U04.01a native green: the real renderer, fed the plane backend's per-plane contributions (one owner, D27), reproduces the single-owner field at every exclusion weight; equal-colour alpha ramps of those weights do not. */
class ShadowPlaneFieldTest {
    private val profile = motionProfileId(MotionProfile.Control)
    private val clock = UiClock(object : FrameSource { override suspend fun awaitFrame(): Long = error("No frames: shares are driven") }, checkOwner = {}).apply {
        configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), (uiSpec(InterfaceSettings(), geometry = ExpansionGeometryDomain(1000f, 3000f, 1f / 60)) as SpecResult.Accepted).spec) }
    private val probe = ShadowFieldProbe(listOf(probeRegion), 1f / 255)

    @Test fun ownedFieldMatchesTheSingleOwnerOracleAndAlphaRampsDoNot() {
        val a = ShadowPlane(clock, HostKey.next(Any())); val b = ShadowPlane(clock, HostKey.next(Any()))
        val node = SurfaceNode(clock, "trigger", PersistentShape(clock, ShapeTarget(listOf(probeBody), JoinPolicy.Union)), SurfaceTarget(Stratum.Floating, 1f, ShadowRole.Glass), a)
        node.transfer(b, profile); b.acknowledge(); val ownership = node.shadowOwnership as MotionFloat
        for (strength in listOf(.6f, 1f)) {
            val reference = shadowCapture { glassShadows(listOf(probeBody to 1f), strength, .5f, GlassShadowGeometry()) }
            var naiveWorst = 0f
            for (share in listOf(0f, .001f, .1f, .5f, .9f, .999f, 1f)) {
                val stamp = clock.stampNow; ownership.drive(share, stamp)
                val parts = listOf(a, b).map { it.contributionAt(node, stamp) }
                val planes = shadowCapture { parts.filter { it > 0f }.forEach { c -> glassShadows(listOf(probeBody to c), strength, .5f, GlassShadowGeometry()) } }
                probe.assertWithin(probe.compare(planes, reference), .008f, "strength=$strength w=$share")
                val weights = listOf(a, b).map { node.shareAt(it, stamp) }
                val ramps = shadowCapture { weights.filter { it > 0f }.forEach { c ->
                    drawContext.canvas.saveLayer(probeRegion.inflate(8f), Paint().apply { alpha = c })
                    glassShadows(listOf(probeBody to 1f), strength, .5f, GlassShadowGeometry()); drawContext.canvas.restore() } }
                naiveWorst = maxOf(naiveWorst, probe.compare(ramps, reference).maximumRgbaError)
            }
            if (strength == 1f) assertTrue("Alpha ramps must miss the oracle mid-transfer: $naiveWorst", naiveWorst > .008f)
        }
        node.dispose()
    }
}
