package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.boundingRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.engine.PopupWindowMotion
import io.github.anastaci1a.localica.ui.spec.ExpansionGeometryDomain
import io.github.anastaci1a.localica.ui.spec.FluidKickSpec
import io.github.anastaci1a.localica.ui.spec.FluidPopupSpringSettings
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.PopupMotionStyle
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
    @Test fun preparedWindowPublishesBothOutlinesAndReturnsThroughTheSameAperture() {
        val domain = ExpansionGeometryDomain(1000f, 4000f, 1f / 30)
        val spec = (uiSpec(InterfaceSettings(popupMotion = PopupMotionStyle.Fluid), geometry = domain) as SpecResult.Accepted).spec
        val home = Rect(16f, 10f, 56f, 30f)
        val envelope = Rect(0f, 0f, 120f, 80f)
        val input = FluidPopupPreview(home, Offset.Zero, 6f, 24f, 0.5, 2.0)
        EngineHarness(spec = spec).use { h ->
            val popup = PopupWindowMotion(h.clock, spec.expansion, "fluid", prepared = true, quiet = false, kind = PopupKind.Menu)
            popup.place(home, envelope, 1f, preview = input)
            val aperture = requireNotNull(popup.bounds)
            fun frames(count: Int) { repeat(count) { h.advance(1.0 / 120) } }
            popup.show(true)
            frames(240)
            assertTrue(popup.expansion.awaitingNativeGate)
            assertNull(popup.outlineBodies())
            popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
            frames(6)
            val moving = requireNotNull(popup.outlineBodies())
            assertEquals(2, moving.size)
            assertEquals(aperture.valueAt(h.clock.stampNow), moving.first().boundingRect)
            assertEquals(aperture.valueAt(h.clock.stampNow).bottom, aperture.bottom.valueAt(h.clock.stampNow), 0f)
            assertEquals(moving.first().topLeftCornerRadius.x, requireNotNull(popup.expansion.outlineRadius()), 0f)
            moving.forEach { assertTrue(it.topLeftCornerRadius.x <= minOf(it.width, it.height) / 2f) }
            frames(240)
            val opened = requireNotNull(popup.outlineBodies())
            assertEquals(envelope, opened[0].boundingRect)
            assertEquals(24f, opened[0].topLeftCornerRadius.x, 0f)
            assertEquals(Rect(32f, 15f, 52f, 35f), opened[1].boundingRect)
            assertEquals(10f, opened[1].topLeftCornerRadius.x, 0f)
            val edges = listOf(aperture.left, aperture.top, aperture.right, aperture.bottom)
            val before = edges.map { it.sampleAt(h.clock.stampNow) }
            popup.show(false)
            popup.place(home, envelope, 1f, preview = input)
            assertEquals(before, edges.map { it.sampleAt(h.clock.stampNow) })
            frames(240)
            assertTrue(popup.expansion.awaitingNativeGate)
            assertSame(aperture, popup.bounds)
            assertEquals(home, aperture.valueAt(h.clock.stampNow))
            assertEquals(6f, requireNotNull(popup.expansion.outlineRadius()), 0f)
            popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
            frames(240)
            assertTrue(popup.expansion.retirementReady)
            popup.dispose()
        }
    }

    @Test fun shrinkingOpeningKeepsTheReceivingEndpointInTheNativeAperture() {
        val domain = ExpansionGeometryDomain(1000f, 4000f, 1f / 30)
        val spec = (uiSpec(InterfaceSettings(popupMotion = PopupMotionStyle.Fluid), geometry = domain) as SpecResult.Accepted).spec
        val home = Rect(16f, 10f, 56f, 30f)
        val envelope = Rect(0f, 0f, 20f, 20f)
        val input = FluidPopupPreview(home, Offset.Zero, 6f, 8f, 0.5, 2.0)
        EngineHarness(spec = spec).use { h ->
            val popup = PopupWindowMotion(h.clock, spec.expansion, "fluid", prepared = true, quiet = false, kind = PopupKind.Menu)
            popup.place(home, envelope, 1f, preview = input)
            val aperture = requireNotNull(popup.bounds)
            for (opening in listOf(true, false)) {
                popup.show(opening)
                popup.place(home, envelope, 1f, preview = input)
                repeat(480) {
                    if (popup.expansion.awaitingNativeGate) popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
                    h.advance(1.0 / 120)
                }
                val bodies = requireNotNull(popup.outlineBodies())
                assertEquals(2, bodies.size)
                val endpoint = if (opening) envelope else home
                val radius = if (opening) 8f else 6f
                val receiving = bodies[if (opening) 1 else 0]
                assertSame(aperture, popup.bounds)
                assertEquals(endpoint, aperture.valueAt(h.clock.stampNow))
                assertEquals(endpoint, receiving.boundingRect)
                assertEquals(radius, receiving.topLeftCornerRadius.x, 0f)
                assertEquals(radius, requireNotNull(popup.expansion.outlineRadius()), 0f)
                assertTrue(popup.expansion.retirementReady)
            }
            popup.dispose()
        }
    }

    private fun fluidWindow(access: MotionAccess, popupRadius: Float, body: (EngineHarness, PopupWindowMotion, FluidPopupPreview) -> Unit) {
        val domain = ExpansionGeometryDomain(1000f, 4000f, 1f / 30)
        val spec = (uiSpec(InterfaceSettings(popupMotion = PopupMotionStyle.Fluid), geometry = domain) as SpecResult.Accepted).spec
        val input = FluidPopupPreview(Rect(16f, 10f, 56f, 30f), Offset.Zero, 6f, popupRadius, 0.5, 2.0)
        EngineHarness(access, spec).use { h ->
            val popup = PopupWindowMotion(h.clock, spec.expansion, "fluid", prepared = true, quiet = false, kind = PopupKind.Menu)
            popup.place(input.pivot, Rect(0f, 0f, 120f, 80f), 1f, preview = input)
            body(h, popup, input)
            popup.dispose()
        }
    }
    private fun EngineHarness.frames(popup: PopupWindowMotion, count: Int, each: (Int) -> Unit = {}) = repeat(count) {
        if (popup.expansion.awaitingNativeGate) popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
        advance(1.0 / 120)
        each(it)
    }

    @Test fun apertureRadiusClampsAndAKickInFlightSurvivesReversal() = fluidWindow(MotionAccess.Standard, 120f) { h, popup, input ->
        val aperture = requireNotNull(popup.bounds)
        popup.show(true)
        h.frames(popup, 480)
        // Radius 120 on the 120x80 slot publishes 40 per lane, on the aperture and in the field.
        assertEquals(40f, requireNotNull(popup.expansion.outlineRadius()), 0f)
        assertEquals(40f, requireNotNull(popup.outlineBodies())[0].topLeftCornerRadius.x, 0f)
        popup.expansion.kickOutline(0, -40f, FluidPopupSpringSettings(1.0, 0.2))
        h.frames(popup, 3)
        val edges = listOf(aperture.top, aperture.bottom)
        val kicked = edges.map { it.sampleAt(h.clock.stampNow) }
        assertTrue(kicked.all { it.velocity != 0f })
        popup.show(false)
        popup.place(input.pivot, Rect(0f, 0f, 120f, 80f), 1f, preview = input)
        assertEquals(kicked, edges.map { it.sampleAt(h.clock.stampNow) })
        h.frames(popup, 480)
        assertTrue(popup.expansion.retirementReady)
    }

    @Test fun reducedMotionFinishesBothSlotsOnTheSameFrame() = fluidWindow(MotionAccess.Reduced, 24f) { h, popup, input ->
        for (opening in listOf(true, false)) {
            popup.show(opening)
            popup.place(input.pivot, Rect(0f, 0f, 120f, 80f), 1f, preview = input)
            var previous = popup.outlineBodies()
            val changed = IntArray(2) { -1 }
            h.frames(popup, 480) { frame ->
                val bodies = popup.outlineBodies()
                bodies?.forEachIndexed { i, body -> if (previous?.get(i) != body) changed[i] = frame }
                previous = bodies
            }
            // Reduced requests commit Rest to both slots atomically, so neither slot settles on a later frame than the other.
            assertEquals(changed[0], changed[1])
            val endpoint = if (opening) Rect(0f, 0f, 120f, 80f) else input.pivot
            assertEquals(endpoint, requireNotNull(popup.bounds).value())
            assertTrue(requireNotNull(previous).any { it.boundingRect == endpoint })
            assertTrue(popup.expansion.retirementReady)
        }
    }

    @Test fun aReversalRequestedWhileHeldLandsItsKicksAtRelease() {
        // Oracle: a hold that covers the reversal stamp leaves exactly the targets of the same reversal without a hold.
        fun reversal(held: Boolean): List<Float> {
            var targets = emptyList<Float>()
            fluidWindow(MotionAccess.Standard, 24f) { h, popup, input ->
                popup.show(true)
                h.frames(popup, 480)
                popup.expansion.kickOutline(0, -40f, FluidKickSpec.ejecteeSpring)
                h.frames(popup, 3)
                if (held) popup.hold()
                popup.show(false)
                popup.place(input.pivot, Rect(0f, 0f, 120f, 80f), 1f, preview = input)
                if (held) popup.release()
                val aperture = requireNotNull(popup.bounds)
                targets = listOf(aperture.top.target, aperture.bottom.target, requireNotNull(popup.rectangle).top.target)
            }
            return targets
        }
        val held = reversal(held = true)
        assertEquals(reversal(held = false), held)
        // Absolute: the manual -40 kick (translation -80 at kick scale 2) does not survive the reversal.
        assertNotEquals(-80f, held[0] - held[2], 0f)
    }

    @Test fun aReturnWithoutALayoutReachesHomeBeforeItsGate() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, input ->
        popup.show(true)
        h.frames(popup, 480)
        popup.show(false)
        var gated = false
        repeat(480) {
            if (!gated && popup.expansion.awaitingNativeGate) {
                gated = true
                assertEquals(input.pivot, requireNotNull(popup.bounds).valueAt(h.clock.stampNow))
                popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
            }
            h.advance(1.0 / 120)
        }
        assertTrue(gated)
        assertTrue(popup.expansion.retirementReady)
    }

    @Test fun preparedThroughRequestsKeepEndpointAndPivotOverridesWithTheirRoles() {
        val from = PopupPortalBounds(16.0, 10.0, 40.0, 20.0)
        val to = PopupPortalBounds(0.0, 0.0, 120.0, 80.0)
        val growth = fluidGrowingOutlineTargets(to, from, 20.0, { it }, { it }) { bounds, inset ->
            requireNotNull(PopupPortalInsets(inset, inset, inset, inset).signedCrop(bounds))
        }
        val collapsed = FluidOutlineOverride(PopupPortalBounds(26.0, 10.0, 20.0, 20.0), 10.0, true)
        val companion = FluidOutlineOverride(PopupPortalBounds(32.0, 15.0, 20.0, 20.0), 10.0)
        val (start, pivot, end) = PopupThroughItem.entries
        assertEquals(listOf(pivot to null, start to null), fluidOutlineRequests(Source, true, growth))
        assertEquals(listOf(pivot to null, start to collapsed), fluidOutlineRequests(CollapsedSource, true, growth))
        assertEquals(listOf(end to FluidOutlineOverride(null, 120.0), pivot to companion),
            fluidOutlineRequests(ExpandedOverrounded, true, growth))
        assertEquals(listOf(end to null, pivot to companion), fluidOutlineRequests(FullyExpanded, true, growth))
        assertEquals(listOf(start to null, pivot to companion),
            fluidOutlineRequests(FullyExpanded, false, growth))
        // Return Source keeps Pivot A then To with no override even when the prepared targets are supplied.
        assertEquals(listOf(pivot to null, end to null), fluidOutlineRequests(Source, false, growth))
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
