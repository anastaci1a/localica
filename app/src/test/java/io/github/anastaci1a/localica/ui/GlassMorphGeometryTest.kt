package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.engine.placeDestination
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.boundingRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.EngineHarness
import io.github.anastaci1a.localica.ui.engine.EngineContext
import io.github.anastaci1a.localica.ui.engine.FluidPropertyMotion
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.engine.PopupWindowMotion
import io.github.anastaci1a.localica.ui.spec.ExpansionGeometryDomain
import io.github.anastaci1a.localica.ui.spec.FluidKickSpec
import io.github.anastaci1a.localica.ui.spec.FluidMainEject
import io.github.anastaci1a.localica.ui.spec.FluidPopupSpringSettings
import io.github.anastaci1a.localica.ui.spec.FluidPropertySpringControls
import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.PopupMotionStyle
import io.github.anastaci1a.localica.ui.spec.PopupOutlinePose
import io.github.anastaci1a.localica.ui.spec.fluidOutlineSpring
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
            assertEquals(Rect(39.5f, 22.5f, 44.5f, 27.5f), opened[1].boundingRect)
            assertEquals(2.5f, opened[1].topLeftCornerRadius.x, 0f)
            val edges = listOf(aperture.left, aperture.top, aperture.right, aperture.bottom)
            val before = edges.map { it.sampleAt(h.clock.stampNow) }
            popup.show(false)
            popup.place(home, envelope, 1f, preview = input)
            assertEquals(before, edges.map { it.sampleAt(h.clock.stampNow) })
            assertEquals(opened, popup.outlineBodies())
            frames(240)
            assertTrue(popup.expansion.awaitingNativeGate)
            assertSame(aperture, popup.bounds)
            assertEquals(home, aperture.valueAt(h.clock.stampNow))
            assertEquals(6f, requireNotNull(popup.expansion.outlineRadius()), 0f)
            val returned = requireNotNull(popup.outlineBodies())
            assertEquals(Rect(31f, 15f, 41f, 25f), returned[0].boundingRect)
            assertEquals(5f, returned[0].topLeftCornerRadius.x, 0f)
            assertEquals(home, returned[1].boundingRect)
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

    @Test fun reducedMotionRunsBothPreparedSlotsToTheirEndpoints() = fluidWindow(MotionAccess.Reduced, 24f) { h, popup, input ->
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
            assertTrue("Both reduced-motion slots continue beyond the admission frame", changed.all { it > 0 })
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

    @Test fun reopeningDuringCloseRetainsBothPhysicalSlots() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, input ->
        val menu=Rect(0f, 0f, 120f, 80f)
        popup.show(true)
        h.frames(popup, 480)
        popup.show(false)
        popup.place(input.pivot, menu, 1f, preview=input)
        h.frames(popup, 3)
        val before=requireNotNull(popup.outlineBodies())
        val aperture=requireNotNull(popup.bounds)
        val stamp=h.clock.stampNow
        assertNotEquals(0f, aperture.top.velocityAt(stamp), 0f)
        val closingMenu = requireNotNull(popup.fluidDestinationProgress(popup = true))
        val closingButton = requireNotNull(popup.fluidDestinationProgress(popup = false))
        assertTrue(closingMenu in 0.0..<1.0 && closingButton in 0.0..<1.0)
        assertEquals(1.0, closingMenu + closingButton, 1e-14)
        popup.show(true)
        popup.place(input.pivot, menu, 1f, preview=input)
        assertEquals(before, popup.outlineBodies())
        assertEquals(closingMenu, requireNotNull(popup.fluidDestinationProgress(popup = true)), 0.0)
        assertEquals(closingButton, requireNotNull(popup.fluidDestinationProgress(popup = false)), 0.0)
        assertNotEquals(0f, aperture.top.velocityAt(stamp), 0f)
        h.frames(popup, 480)
        assertEquals(menu, aperture.value())
        assertEquals(1.0, requireNotNull(popup.fluidDestinationProgress(popup = true)), 0.0)
        assertEquals(0.0, requireNotNull(popup.fluidDestinationProgress(popup = false)), 0.0)
        assertTrue(popup.expansion.retirementReady)
    }

    @Test fun preparedThroughRequestsKeepEndpointAndPivotOverridesWithTheirRoles() {
        val home = Rect(35f, 40f, 123f, 84f)
        val menu = Rect(35f, 40f, 285f, 400f)
        val input = FluidPopupPreview(home, Offset.Zero, 22f, 24f, 0.5, 1.0)
        val opening = input.prepare(home, menu, 1f, opening = true)
        val closing = input.prepare(home, menu, 1f, opening = false)
        val source = PopupOutlinePose(home, 22f, 1f)
        val pivot = PopupOutlinePose(Rect(-46f, -63f, 204f, 187f), 125f, 22f / 360f)
        val collapsed = PopupOutlinePose(Rect(57f, 40f, 101f, 84f), 22f, 1f)
        val companion = PopupOutlinePose(Rect(77f, 80f, 121f, 124f), 22f, 0.25f)
        val expanded = PopupOutlinePose(menu, 24f, 1f)
        assertEquals(listOf(pivot, source), opening.outline(Source))
        assertEquals(listOf(pivot, collapsed), opening.outline(CollapsedSource))
        val overrounded = requireNotNull(opening.outline(ExpandedOverrounded))
        assertEquals(listOf(expanded.copy(radius = 250f), companion), overrounded)
        assertEquals(listOf(overrounded[0].copy(radius = 24f), overrounded[1]), opening.outline(FullyExpanded))
        assertEquals(listOf(expanded, companion), closing.outline(FullyExpanded))
        assertEquals(listOf(pivot, source), closing.outline(Source))
        assertEquals(0 to 70.22222f, opening.kick(Kick))
        assertEquals(0 to 70.22222f, closing.kick(KickEjectee))
        assertEquals(1 to -35.11111f, closing.kick(KickSource))
        assertEquals(0 to 0f, opening.kick(Unkick))
        assertEquals(0 to 0f, closing.kick(UnkickEjectee))
        assertEquals(1 to 0f, closing.kick(UnkickSource))
    }

    @Test fun dismissalPreviewCarriesItsMaterialAndUsesTheExistingClip() {
        val home = Rect(35f, 40f, 123f, 84f)
        val menu = Rect(35f, 40f, 285f, 400f)
        val preview = FluidPopupPreview(home, Offset.Zero, 22f, 24f, 0.5, 1.0)
        assertFalse(preview.prepare(home, menu, 1f, opening = true).contentInputs.home.isGlass)
        val items = preview.copy(sourceIsGlass = true).prepare(home, menu, 1f, opening = false).contentInputs
        for (item in listOf(items.home, items.popup)) {
            assertTrue(item.isGlass)
            assertNull(item.matchedViewIdentifier)
            assertEquals(0, item.contentScaleAxis)
            assertFalse(item.prefersUnclippedContents)
        }
    }

    @Test fun freshClipDisplaysTheDrainTargetsBeforeTheSourceSeedAdvances() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, input ->
        val home = Rect(35f, 40f, 123f, 84f)
        val menu = Rect(35f, 40f, 285f, 400f)
        popup.show(true)
        popup.place(home, menu, 1f, preview = input.copy(pivot = home, homeRadius = 22f, hostScaleY = 1.0))
        repeat(240) { h.advance(1.0 / 120) }
        assertTrue(popup.expansion.awaitingNativeGate)
        popup.acknowledgeNativeGate(popup.expansion.nativeGeneration)
        assertTrue(popup.isFirstOutlineClipCommit)
        val initial = requireNotNull(popup.outlineBodies())
        assertEquals(160f, initial[0].boundingRect.center.x, 0f)
        assertEquals(220f, initial[0].boundingRect.center.y, 0f)
        assertEquals(15.277778f, initial[0].width, 0.00002f)
        assertEquals(22f, initial[0].height, 0.00002f)
        assertEquals(7.638889f, initial[0].topLeftCornerRadius.x, 0.00002f)
        assertEquals(Rect(77f, 80f, 121f, 124f), initial[1].boundingRect)
        assertEquals(22f, initial[1].topLeftCornerRadius.x, 0f)
        assertEquals(initial[0].boundingRect, requireNotNull(popup.bounds).value())
        assertEquals(0.0, requireNotNull(popup.fluidDestinationProgress(popup = true)), 0.0)
        assertEquals(1.0, requireNotNull(popup.fluidDestinationProgress(popup = false)), 0.0)
        val (menuSlot, buttonSlot) = requireNotNull(popup.fluidContentSlots())
        assertEquals(initial[0], menuSlot.aperture)
        assertEquals(initial[1], buttonSlot.aperture)
        assertEquals(1.0, menuSlot.blend.opacity, 0.0)
        assertEquals(0.0, buttonSlot.blend.opacity, 0.0)
        val menuPlacement = requireNotNull(menuSlot.placeDestination(Size(250f, 360f), Offset.Zero, leftToRight = true))
        assertEquals(menuSlot.aperture.boundingRect, menuPlacement.extent)
        assertEquals(menuSlot.aperture.width / 250f, menuPlacement.scaleX, 0f)
        assertEquals(menuSlot.aperture.height / 360f, menuPlacement.scaleY, 0f)
        assertEquals(menuSlot.aperture.topLeftCornerRadius.x,
            requireNotNull(menuPlacement.clip).topLeftCornerRadius.x * menuPlacement.scaleX, 1e-6f)
        h.advance(1.0 / 120)
        assertFalse(popup.isFirstOutlineClipCommit)
        val menuProgress = requireNotNull(popup.fluidDestinationProgress(popup = true))
        val buttonProgress = requireNotNull(popup.fluidDestinationProgress(popup = false))
        assertTrue(menuProgress > 0.0 && buttonProgress < 1.0)
        assertEquals(1.0, menuProgress + buttonProgress, 1e-14)
        assertEquals(menuProgress, requireNotNull(popup.fluidContentBlend(popup = true)).opacity, 0.0)
        assertEquals((1.0 - menuProgress) * 4.0, requireNotNull(popup.fluidContentBlend(popup = true)).gaussianRadius, 0.0)
        val first = requireNotNull(popup.outlineBodies())[0]
        assertEquals(19.912427f, first.width, 0.00002f)
        assertEquals(20.003361f, first.height, 0.00002f)
        assertEquals(9.956213f, first.topLeftCornerRadius.x, 0.00002f)
        assertEquals(first.boundingRect, requireNotNull(popup.bounds).value())
    }

    @Test fun aFreshHeldSourceKeepsItsInstalledContent() = fluidWindow(MotionAccess.Standard, 24f) { _, popup, _ ->
        val bounds = Rect(0f, 0f, 250f, 360f)
        val pose = PopupOutlinePose(bounds, 24f, 0.25f)
        val poses = listOf(pose, pose)
        val expansion = popup.expansion
        expansion.hold()
        expansion.replaceOutline(poses, 0, 1.0, FluidMainEject)
        val shaped = requireNotNull(popup.outlineBodies())
        assertTrue(shaped.all { it.width == 62.5f && it.height == 90f && it.topLeftCornerRadius.x == 6f })
        expansion.release()
        assertEquals(shaped, popup.outlineBodies())
    }

    @Test fun kickDisplacementUsesTheInstantaneousSlotScale() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, _ ->
        val bounds = Rect(0f, 0f, 250f, 360f)
        val pose = PopupOutlinePose(bounds, 24f, 0.25f)
        val spring = FluidMainEject.scaledResponses(0.7)
        val expansion = popup.expansion
        expansion.replaceOutline(listOf(pose, pose), 0, 2.0, spring)
        expansion.replaceOutline(listOf(pose.copy(scale = 1f), pose), 0, 2.0, spring)
        expansion.kickOutline(0, 40f, FluidKickSpec.ejecteeSpring)
        h.advance(1.0 / 120)
        val slots = requireNotNull(popup.outlineBodies())
        val propertyTime = 0.008333333 + 0.4 * 1.001 * (1f / 120).toDouble()
        val frequency = 2.0 * Math.PI / 0.35
        val decay = 0.75 * frequency
        val oscillation = frequency * kotlin.math.sqrt(1.0 - 0.75 * 0.75)
        val kickScale = 2.0 * (1.0 - 0.75 * kotlin.math.exp(-decay * propertyTime) *
            (kotlin.math.cos(oscillation * propertyTime) + decay / oscillation * kotlin.math.sin(oscillation * propertyTime)))
        val kt = 2.0 * Math.PI / 0.2 * propertyTime
        val kick = 40.0 * (1.0 - (1.0 + kt) * kotlin.math.exp(-kt))
        assertEquals((250.0 * kickScale / 2.0).toFloat(), slots[0].width, 0.00002f)
        assertEquals((180.0 + kickScale * kick).toFloat(), slots[0].boundingRect.center.y, 0.00002f)
        assertEquals(180f, slots[1].boundingRect.center.y, 0f)
    }

    @Test fun invertedSourceUsesTheRetainedSlotCenter() = fluidWindow(MotionAccess.Reduced, 24f) { h, popup, _ ->
        val expansion = popup.expansion
        val spring = fluidOutlineSpring(null)
        val before = PopupOutlinePose(Rect(50f, 20f, 90f, 60f), 20f, 1f)
        expansion.replaceOutline(listOf(before, before), 0, 1.0, spring)
        val inverted = PopupOutlinePose(Rect(0f, 0f, 250f, 250f), 125f, 0.25f, preservesCenter = true)
        expansion.replaceOutline(listOf(inverted, before), 0, 1.0, spring)
        h.frames(popup, 480)
        val after = requireNotNull(popup.outlineBodies()).first()
        assertEquals(Offset(70f, 40f), after.boundingRect.center)
        assertEquals(62.5f, after.width, 0f)
        assertEquals(31.25f, after.topLeftCornerRadius.x, 0f)
    }

    @Test fun reducedSourceSeparatesGeometryAndTransformImpulses() = fluidWindow(MotionAccess.Reduced, 24f) { h, popup, _ ->
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced, durationScale = 0f), h.clock.spec)
        val expansion = popup.expansion
        val spring = fluidOutlineSpring(null)
        val pose = PopupOutlinePose(Rect(0f, 0f, 250f, 360f), 24f, 1f)
        expansion.replaceOutline(listOf(pose, pose), 0, 1.0, spring)
        val next = pose.copy(bounds = pose.bounds.translate(Offset(12f, 0f)), scale = 2f)
        expansion.replaceOutline(listOf(next, pose), 0, 1.0, spring)
        val aperture = requireNotNull(popup.bounds)
        val stamp = h.clock.stampNow
        val left = aperture.left.velocityAt(stamp)
        val right = aperture.right.velocityAt(stamp)
        val geometryImpulse = 0.0001 * (2 * Math.PI / 0.15) * (2 * Math.PI / 0.15)
        val transformImpulse = 0.016 * (2 * Math.PI / 0.3) * (2 * Math.PI / 0.3)
        assertEquals((12 * geometryImpulse).toFloat(), (left + right) * 0.5f, 0.0001f)
        assertEquals((250 * transformImpulse).toFloat(), right - left, 0.001f)
        assertEquals(pose.bounds, requireNotNull(popup.rectangle).valueAt(stamp))
        assertEquals(next.bounds, aperture.valueAt(stamp))
    }

    @Test fun kickPropertiesFinishIndependentlyOnTheirProjectedError() {
        EngineHarness().use { h ->
            val controls = FluidPropertySpringControls(0.0, null, interactive = false, additive = false)
            val small = FluidPropertyMotion(h.clock, Double::equals)
            val large = FluidPropertyMotion(h.clock, Double::equals)
            val spring = FluidKickSpec.ejecteeSpring
            small.animate(0.0, 0.5e-6, controls, spring, FluidKickSpec.epsilonSquared)
            large.animate(0.0, 2e-6, controls, spring, FluidKickSpec.epsilonSquared)
            h.advance(1.0 / 120)
            assertTrue(small.settled)
            assertEquals(0.5e-6, small.sample().value, 0.0)
            assertEquals(0.0, small.sample().velocity, 0.0)
            assertFalse(large.settled)
            assertTrue(large.sample().velocity > 0.0)
            val running = large.sample()
            large.animate(running.value, 0.0, controls, spring, FluidKickSpec.epsilonSquared)
            assertEquals(running, large.sample())
        }
    }

    @Test fun changingDragKeepsElapsedOutlineSpringTime() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, _ ->
        val expansion = popup.expansion
        val spring = FluidPopupSpringSettings(1.0, 0.4)
        val start = Rect(0f, 0f, 250f, 360f)
        val pose = PopupOutlinePose(start, 24f, 1f)
        expansion.replaceOutline(listOf(pose, pose), 0, 1.0, spring)
        expansion.replaceOutline(listOf(pose.copy(bounds = start.translate(Offset(100f, 0f))), pose), 0, 1.0, spring)
        h.advance(0.016)
        val before = requireNotNull(popup.outlineBodies()).first().boundingRect.center.x
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, durationScale = 2f), h.clock.spec)
        assertEquals(before, requireNotNull(popup.outlineBodies()).first().boundingRect.center.x, 0f)
        h.advance(0.016)
        val elapsed = 0.016 + 0.016 / 2
        val t = 2 * Math.PI / 0.4 * elapsed
        val expected = 125.0 + 100.0 * (1.0 - (1.0 + t) * kotlin.math.exp(-t))
        val after = requireNotNull(popup.outlineBodies()).first().boundingRect.center.x
        assertEquals(expected.toFloat(), after, 0.00002f)
    }

    @Test fun unscaledSourceAppliesOnlyTheRecoveredSpringImpulse() = fluidWindow(MotionAccess.Standard, 24f) { h, popup, _ ->
        val bounds = Rect(0f, 0f, 250f, 360f)
        val pose = PopupOutlinePose(bounds, 24f, 1f)
        val expansion = popup.expansion
        val spring = fluidOutlineSpring(null)
        val aperture = requireNotNull(popup.bounds)
        fun centreVelocity() = with(h.clock.stampNow) { (aperture.left.velocityAt(this) + aperture.right.velocityAt(this)) * 0.5f }
        fun request(dx: Float) {
            val moved = pose.copy(bounds = bounds.translate(Offset(dx, 0f)))
            expansion.replaceOutline(listOf(moved, pose), 0, 1.0, spring)
        }
        request(0f)
        assertEquals(0f, centreVelocity(), 0f)
        request(12f)
        val impulsePerPoint = 0.016 * (2 * Math.PI / 0.3) * (2 * Math.PI / 0.3)
        assertEquals((12 * impulsePerPoint).toFloat(), centreVelocity(), 0.002f)
        request(17f)
        assertEquals((17 * impulsePerPoint).toFloat(), centreVelocity(), 0.002f)
        val stamp = h.clock.stampNow
        assertEquals(125f, requireNotNull(popup.rectangle).valueAt(stamp).center.x, 0f)
        assertEquals(142f, aperture.valueAt(stamp).center.x, 0f)
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
