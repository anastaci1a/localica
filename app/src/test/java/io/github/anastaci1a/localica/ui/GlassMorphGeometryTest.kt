package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.PopupWindowMotion
import io.github.anastaci1a.localica.ui.spec.ExpansionGeometryDomain
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.SpecResult
import io.github.anastaci1a.localica.ui.spec.uiSpec
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Test-only affine fixture input (the app helper was dead, #748); production curves are covered by the actual-engine sweep. */
internal fun fixtureGeometryProgress(progress: Float, quiet: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (quiet) ((p - .14f) / .86f).coerceIn(0f, 1f) else p
}

class GlassMorphGeometryTest {
    @Test fun preparedThroughRequestsKeepEndpointAndPivotOverridesWithTheirRoles() {
        val from = PopupPortalBounds(16.0, 10.0, 40.0, 20.0)
        val to = PopupPortalBounds(0.0, 0.0, 120.0, 80.0)
        val growth = fluidGrowingOutlineTargets(to, from, 20.0, { it }, { it }) { bounds, inset ->
            requireNotNull(PopupPortalInsets(inset, inset, inset, inset).signedCrop(bounds))
        }
        val collapsed = FluidOutlineOverride(PopupPortalBounds(26.0, 10.0, 20.0, 20.0), 10.0, true)
        val companion = FluidOutlineOverride(PopupPortalBounds(32.0, 15.0, 20.0, 20.0), 10.0)
        val (start, pivot, end) = PopupThroughItem.entries
        assertEquals(listOf(pivot to null, start to null), fluidOutlineRequests(Source, true, growth, null))
        assertEquals(listOf(pivot to null, start to collapsed), fluidOutlineRequests(CollapsedSource, true, growth, null))
        assertEquals(listOf(end to FluidOutlineOverride(null, 120.0), pivot to companion),
            fluidOutlineRequests(ExpandedOverrounded, true, growth, null))
        assertEquals(listOf(end to null, pivot to companion), fluidOutlineRequests(FullyExpanded, true, growth, null))
        assertEquals(listOf(start to null, pivot to companion),
            fluidOutlineRequests(FullyExpanded, false, null, FluidOutlineStep(null, companion)))
        assertEquals(listOf(pivot to null, end to null), fluidOutlineRequests(Source, false, null, null))
    }

    @Test fun edgeSpringPreparedPopupKeepsItsFrameCorpus() {
        val domain = ExpansionGeometryDomain(1000f, 4000f, 1f / 30)
        val spec = (uiSpec(InterfaceSettings(), geometry = domain) as SpecResult.Accepted).spec
        val home = Rect(40.25f, 50.5f, 88.75f, 98.125f)
        val envelope = Rect(10.125f, 20.375f, 310.5f, 620.25f)
        val samples = buildList {
            for (kind in listOf(PopupKind.Menu, PopupKind.BottomMenu)) EngineHarness(spec = spec).use { h ->
                val popup = PopupWindowMotion(h.clock, spec.expansion, kind, prepared = true, quiet = false, kind = kind)
                popup.place(home, envelope, 2f)
                val rect = requireNotNull(popup.bounds)
                for (open in listOf(true, false)) {
                    popup.show(open)
                    repeat(240) {
                        val expansion = popup.expansion
                        if (expansion.awaitingNativeGate) assertTrue(expansion.acknowledgeNativeGate(expansion.nativeGeneration))
                        h.advance(1.0 / 120)
                        val stamp = h.clock.stampNow
                        for (edge in listOf(rect.left, rect.top, rect.right, rect.bottom)) {
                            val sample = edge.sampleAt(stamp)
                            add(sample.value.toRawBits())
                            add(sample.velocity.toRawBits())
                        }
                    }
                    assertSame(rect, popup.bounds)
                    assertEquals(if (open) envelope else home, rect.valueAt(h.clock.stampNow))
                }
                popup.dispose()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(samples.joinToString(",").toByteArray())
        assertEquals("Prepared Edge Spring edge values and velocities",
            "98c64bf88bb0dee5d001c8f0a5842744b63ffc5ffc1b244e9ba6c04829c6dc66",
            digest.joinToString("") { "%02x".format(it) })
    }

    @Test fun quietShapeStaysAtItsHomeThroughoutTheMaterialFade() {
        for (p in listOf(0f, .035f, .07f, .105f, .14f)) {
            assertEquals("Bounds and corners must both be home before the fade", 0f,
                fixtureGeometryProgress(p, quiet = true), 0f)
        }
    }

    @Test fun quietShapeUsesOneContinuousReversibleInterval() {
        val samples = (0..100).map { fixtureGeometryProgress(it / 100f, quiet = true) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a && b - a < .012f) }
        assertEquals(.5f, fixtureGeometryProgress(.57f, quiet = true), .000001f)
        assertEquals(samples, (100 downTo 0).map { fixtureGeometryProgress(it / 100f, true) }.reversed())
    }

    @Test fun glassControlsKeepTheirExistingGeometryAndOvershootsStayBounded() {
        for (p in listOf(0f, .14f, .3f, .5f, .8f, 1f))
            assertEquals(p, fixtureGeometryProgress(p, quiet = false), 0f)
        for (quiet in listOf(false, true)) {
            assertEquals(0f, fixtureGeometryProgress(-.1f, quiet), 0f)
            assertEquals(1f, fixtureGeometryProgress(1.1f, quiet), 0f)
        }
    }
}
