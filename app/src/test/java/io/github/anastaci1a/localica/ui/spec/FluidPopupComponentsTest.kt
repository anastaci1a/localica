package io.github.anastaci1a.localica.ui.spec

import io.github.anastaci1a.localica.ui.spec.FluidTimelineAction.*
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidPopupComponentsTest {
    // Conditional inputs and results from independent native component checks.
    @Test fun previewMorphsOnlyForCompactOverlap() {
        assertFalse(fluidPreviewMorphs(false) { error("Overlap must be queried only in compact menus") })
        assertFalse(fluidPreviewMorphs(true) { false })
        assertTrue(fluidPreviewMorphs(true) { true })
    }

    @Test fun intermediateTargetLimitsSignedTravelBeforeAddingToTheSource() {
        val cases = listOf(
            doubleArrayOf(0.0, 100.0, 0.8, 200.0, 80.0),
            doubleArrayOf(100.0, 0.0, 0.8, 200.0, 20.0),
            doubleArrayOf(0.0, 1000.0, 0.8, 200.0, 200.0),
            doubleArrayOf(1000.0, 0.0, 0.8, 200.0, 800.0),
            doubleArrayOf(0.0, 100.0, 0.0, 200.0, 0.0),
            doubleArrayOf(20.0, 20.0, 0.8, 200.0, 20.0),
            doubleArrayOf(0.0, 100.0, 0.8, 0.0, 0.0),
            doubleArrayOf(0.0, 100.0, 0.2, 200.0, 20.0),
        )
        for ((source, target, ratio, cap, expected) in cases) {
            assertEquals(expected.toRawBits(), fluidIntermediateY(source, target, ratio, cap).toRawBits())
        }
    }

    @Test fun storedDelayMultipliesSpeedBeforeAmplification() {
        assertEquals(0.06999999999999999.toRawBits(), fluidStoredDelay(0.1, 0.7, false).toRawBits())
        assertEquals(0.7.toRawBits(), fluidStoredDelay(0.1, 0.7, true).toRawBits())
        assertEquals(0.08399999999999999.toRawBits(), fluidStoredDelay(0.12, 0.7, false).toRawBits())
        assertEquals(0.21.toRawBits(), fluidStoredDelay(0.3, 0.7, false).toRawBits())
        assertEquals(0.0.toRawBits(), fluidStoredDelay(0.0, 0.7, true).toRawBits())
        assertEquals(0.125.toRawBits(), fluidStoredDelay(0.1, 1.25, false).toRawBits())
        assertEquals(0.0.toRawBits(), fluidStoredDelay(0.1, 0.0, false).toRawBits())
    }

    @Test fun growingQueueKeepsAllThreeBatchesAndTheLiveMainSpring() {
        val eject = FluidPopupSpringSettings(0.72, 0.42)
        val absorb = FluidPopupSpringSettings(0.84, 0.65)
        val lens = FluidLensingSpec.requests(true, true, 25.0, 90.0, 0.5, 120.0, eject, absorb)
        val queue = fluidThroughQueue(true, eject, FluidContentDefaults) { lens }
        assertEquals(
            listOf(
                Source, AddSdfElements, CollapsedSource, ExpandedOverrounded, Kick, SmoothOn, LensOn, ChangeLensDimension,
                BlurOut, FullyExpanded, Unkick, LensOff, BlurIn, SmoothOff,
            ),
            queue.map { it.action },
        )
        assertEquals(List(9) { 0.0 } + listOf(0.1, 0.1, 0.12, 0.12, 0.3), queue.map { it.timing.storedDelay })
        val growingGeometry = listOf(CollapsedSource, ExpandedOverrounded, FullyExpanded, Kick, Unkick).map { action ->
            queue.first { it.action == action }.timing
        }
        val springs = growingGeometry.map { requireNotNull(it.spring) }
        assertEquals(listOf(1.0, 0.72, 0.72, 1.0, 0.72), springs.map { it.dampingRatio })
        assertEquals(listOf(0.3, 0.42, 0.42, 0.2, 0.42), springs.map { it.response })
        for (index in listOf(1, 2, 4)) assertTrue(springs[index] === eject)
        assertTrue(fluidThroughMainSpring(true, eject, absorb) === eject)
        assertTrue(fluidThroughMainSpring(false, eject, absorb) === absorb)
        assertEquals(0.06999999999999999.toRawBits(), growingGeometry[2].dueThreshold(0.7, false, 1.0).toRawBits())
        assertEquals(0.0.toRawBits(), growingGeometry[3].dueThreshold(0.7, false, 1.0).toRawBits())
        assertTrue(queue[7].lens === lens[1])
        assertEquals(0.1, requireNotNull(queue[5].timing.spring).response, 0.0)
        assertEquals(0.6, requireNotNull(queue.last().timing.spring).response, 0.0)
        assertEquals(1.0, requireNotNull(queue.last().timing.spring).dampingRatio, 0.0)
    }

    @Test fun nonGrowingQueueKeepsDelayedTiesAndCanOmitTheLensBatch() {
        val eject = FluidMainEjectDefaults
        val absorb = FluidPopupSpringSettings(0.8, 0.7)
        val lens = FluidLensingSpec.requests(true, false, 25.0, 90.0, 0.5, 120.0, eject, absorb)
        val queue = fluidThroughQueue(false, eject, FluidContentDefaults) { lens }
        assertEquals(
            listOf(
                FullyExpanded, AddSdfElements, Source, KickEjectee, KickSource, SmoothOn, LensOn, ChangeLensDimension, BlurOut,
                UnkickEjectee, SmoothOff, LensOff, BlurIn, UnkickSource,
            ),
            queue.map { it.action },
        )
        assertEquals(List(9) { 0.0 } + List(4) { 0.1 }, queue.dropLast(1).map { it.timing.storedDelay })
        assertEquals(0x3fc3333333333334L, queue.last().timing.storedDelay.toRawBits())
        assertTrue(queue[7].timing.spring === absorb)
        val disabled = FluidContentDefaults.copy(lensingEnabled = false)
        val nonGrowingNoLens = fluidThroughQueue(false, eject, disabled) { error("Lensing must stay disabled") }
        assertEquals(listOf(FullyExpanded, AddSdfElements, Source, KickEjectee, KickSource, SmoothOn, UnkickEjectee, SmoothOff, UnkickSource), nonGrowingNoLens.map { it.action })
        val growingNoLens = fluidThroughQueue(true, eject, disabled) { error("Lensing must stay disabled") }
        assertEquals(listOf(Source, AddSdfElements, CollapsedSource, ExpandedOverrounded, Kick, SmoothOn, FullyExpanded, Unkick, SmoothOff), growingNoLens.map { it.action })
    }

    @Test fun queueResetAndStartAdmissionReuseTheOrderedThreshold() {
        val eject = FluidMainEjectDefaults
        val lens = FluidLensingSpec.requests(true, true, 25.0, 90.0, 0.5, 120.0, eject, eject)
        val route = fluidThroughQueue(true, eject, FluidContentDefaults) { lens }
        val queue = ArrayDeque<FluidTimelineStep>()
        val ran = mutableListOf<FluidTimelineAction>()
        var nextAction = AddSdfElements
        fun execute(step: FluidTimelineStep, speed: Double) {
            assertEquals(nextAction, queue.first().action)
            assertEquals(0.7, speed, 0.0)
            ran += step.action
        }
        queue.add(route.last())
        queue.replaceFluidTimeline(route)
        assertEquals(route, queue.toList())
        queue.drainFluidTimeline(0.0, 0.7, false, { error("A holding start takes the first action directly") }, true, ::execute)
        assertEquals(listOf(Source), ran)
        assertEquals(AddSdfElements, queue.first().action)
        nextAction = FullyExpanded
        queue.drainFluidTimeline(0.0, 0.7, false, { 1.0 }, false, ::execute)
        assertEquals(route.take(9).map { it.action }, ran)
        nextAction = LensOff
        queue.drainFluidTimeline(0.07, 0.7, false, { 1.0 }, false, ::execute)
        assertEquals(listOf(FullyExpanded, Unkick), ran.takeLast(2))
        assertEquals(LensOff, queue.first().action)
        queue.replaceFluidTimeline(route)
        val disabled = mutableListOf<FluidTimelineStep>()
        queue.drainFluidTimeline(0.0, 0.0, true, { 1.0 }, false) { step, speed ->
            assertEquals(0.0, speed, 0.0)
            assertTrue(queue.isEmpty())
            disabled += step
        }
        assertEquals(route, disabled)
        assertTrue(queue.isEmpty())
    }

    @Test fun growingKickUsesTheExistingPropertySpringAndSuppliedPresentationScale() {
        assertEquals(0, FluidKickSpec.growingSlot)
        assertEquals(0.0.toRawBits(), FluidKickSpec.unkickTarget.toRawBits())
        assertEquals("MagicMorphAnimation.kickAmount", FluidPopupChannel.KickAmount.key)
        assertEquals("MagicMorphAnimation.kickScale", FluidPopupChannel.KickScale.key)
        assertEquals(FluidPopupChannel.Value.StandardScalar, FluidPopupChannel.KickAmount.value)
        assertEquals(FluidPopupChannel.Value.StandardScalar, FluidPopupChannel.KickScale.value)
        assertEquals(-80.0, FluidKickSpec.translationY(-40.0, 2.0), 0.0)
        val start = FluidScalarSpringBody(40.0, 0.0)
        val returned = FluidMainEjectDefaults.advance(start, FluidKickSpec.unkickTarget, 0.35)
        assertTrue(returned.value < 0.0)
    }

    @Test fun convertsSuppliedSpringSettingsWithUnitMass() {
        assertEquals(0.75.toRawBits(), FluidMainEjectDefaults.dampingRatio.toRawBits())
        assertEquals(0.5.toRawBits(), FluidMainEjectDefaults.response.toRawBits())
        val cases = listOf(
            FluidMainEjectDefaults to doubleArrayOf(1.0, 157.91367041742973, 18.84955592153876),
            FluidPopupSpringSettings(0.8, 0.7) to doubleArrayOf(1.0, 80.56819919256618, 14.361566416410483),
            FluidPopupSpringSettings(1.0, 0.3) to doubleArrayOf(1.0, 438.6490844928604, 41.88790204786391),
            FluidPopupSpringSettings(0.4, 0.22847946571562133) to doubleArrayOf(1.0, 756.2499999999998, 22.0),
            FluidPopupSpringSettings(0.75, 0.35) to doubleArrayOf(1.0, 322.27279677026473, 26.927937030769655),
        )
        for ((spring, expected) in cases) {
            assertEquals(expected[0].toRawBits(), spring.mass.toRawBits())
            assertEquals(expected[1].toRawBits(), spring.stiffness.toRawBits())
            assertEquals(expected[2].toRawBits(), spring.damping.toRawBits())
        }
    }

    @Test fun throughBlurUsesTheOppositeNamedSpringSlots() {
        val defaults = FluidContentDefaults
        assertEquals(1.2.toRawBits(), defaults.minScale.toRawBits())
        assertEquals(1.65.toRawBits(), defaults.maxScale.toRawBits())
        assertEquals(0.3.toRawBits(), defaults.blurInSpring.response.toRawBits())
        assertEquals(0.45.toRawBits(), defaults.blurOutSpring.response.toRawBits())
        assertEquals(1.0.toRawBits(), defaults.blurInSpring.dampingRatio.toRawBits())
        assertEquals(0.8.toRawBits(), defaults.blurOutSpring.dampingRatio.toRawBits())

        val supplied = defaults.copy(
            blurInSpring = FluidPopupSpringSettings(dampingRatio = 0.9, response = 0.2),
            blurOutSpring = FluidPopupSpringSettings(dampingRatio = 0.6, response = 0.4),
            speed = 1.25, amplifyDelays = true,
        )
        val grows = requireNotNull(supplied.blurSequence(growing = true))
        val shrinks = requireNotNull(supplied.blurSequence(growing = false))
        assertEquals(supplied.blurInSpring, grows.blurOut.spring)
        assertEquals(supplied.blurOutSpring, grows.blurIn.spring)
        assertEquals(supplied.blurInSpring, shrinks.blurOut.spring)
        assertEquals(supplied.blurOutSpring, shrinks.blurIn.spring)
        assertEquals(0.0.toRawBits(), grows.blurOut.storedDelay.toRawBits())
        assertEquals(0.12.toRawBits(), grows.blurIn.storedDelay.toRawBits())
        assertEquals(0.1.toRawBits(), shrinks.blurIn.storedDelay.toRawBits())
        assertEquals(1.5.toRawBits(), grows.blurIn.dueThreshold(supplied.speed, supplied.amplifyDelays, 1.0).toRawBits())
        assertEquals(1.25.toRawBits(), shrinks.blurIn.dueThreshold(supplied.speed, supplied.amplifyDelays, 1.0).toRawBits())
        assertEquals(null, supplied.copy(lensingEnabled = false).blurSequence(growing = true))
    }

    @Test fun blurRequestsUseTheCapturedHeightAndKeepBothRecipients() {
        val reads = mutableListOf<String>()
        val cases = listOf(
            100.0 to 1.65,
            215.0 to 1.65,
            367.5 to 1.4249999999999998,
            520.0 to 1.2,
            600.0 to 1.2,
            Double.NaN to 1.2,
        )
        for ((height, scale) in cases) {
            reads.clear()
            val request = fluidContentBlurOut(
                height, maxScale = { reads += "max"; 1.65 }, minScale = { reads += "min"; 1.2 },
            )
            assertEquals(listOf("max", "min"), reads)
            assertEquals(scale.toRawBits(), request.portalScale.toRawBits())
            assertEquals(4.0.toRawBits(), request.gaussianRadius.toRawBits())
        }
        assertEquals(FluidContentBlurRequest(1.0, 0.0), FluidContentBlurIn)
    }

    @Test fun destinationCrossBlurUsesExplicitThenIdentifierThenMaterialPolicy() {
        assertTrue(fluidDestinationCrossBlur(true, "same", "same", previous = false, incomingGlass = false))
        assertFalse(fluidDestinationCrossBlur(false, "old", "new", previous = true, incomingGlass = true))
        assertFalse(fluidDestinationCrossBlur(null, "same", "same", previous = true, incomingGlass = true))
        assertTrue(fluidDestinationCrossBlur(null, null, null, previous = true, incomingGlass = false))
        assertTrue(fluidDestinationCrossBlur(null, "old", "new", previous = false, incomingGlass = true))
        assertFalse(fluidDestinationCrossBlur(null, "old", "new", previous = false, incomingGlass = false))
    }

    @Test fun destinationProgressKeepsSeparateOpacityAndRadiusWrites() {
        assertEquals(FluidDestinationBlend(0.25, 3.0), fluidDestinationBlend(0.25, true, false, true))
        assertEquals(FluidDestinationBlend(0.0, 4.0), fluidDestinationBlend(0.0, true, false, true))
        assertEquals(FluidDestinationBlend(1.0, 0.0), fluidDestinationBlend(1.0, true, false, true))
        assertTrue(fluidDestinationBlend(Double.POSITIVE_INFINITY, true, false, true).gaussianRadius.isNaN())
        assertEquals(FluidDestinationBlend(0.5, 0.0), fluidDestinationBlend(0.325, false, true, true))
        assertEquals(FluidDestinationBlend(1.0, 0.0), fluidDestinationBlend(0.325, false, true, false))
        assertEquals(FluidDestinationBlend(0.0, 0.0), fluidDestinationBlend(-0.25, false, false, false))
        assertEquals(FluidDestinationBlend(1.0, 0.0), fluidDestinationBlend(0.9, false, false, false))
        assertEquals(FluidDestinationBlend(1.0, 0.0), fluidDestinationBlend(Double.NaN, false, false, false))
        assertEquals(0.1f.toRawBits(), fluidDestinationBlend(0.1, true, false, true).layerOpacity.toRawBits())
    }

    @Test fun lensingRequestsSwapCapturedDimensionsAndReuseTheGrowthDelay() {
        val eject = FluidPopupSpringSettings(0.9, 0.55)
        val absorb = FluidPopupSpringSettings(0.8, 0.42)
        val growing = FluidLensingSpec.requests(true, true, 25.0, 90.0, 0.5, 120.0, eject, absorb)
        val shrinking = FluidLensingSpec.requests(true, false, 25.0, 90.0, 0.6, 120.0, eject, absorb)
        assertEquals(listOf(120.0, 90.0, null), growing.map { it.dimension })
        assertEquals(listOf(90.0, 25.0, null), shrinking.map { it.dimension })
        assertEquals(listOf(1.0, 1.0, 0.0), growing.map { it.gain })
        assertEquals(listOf(0.0, 0.0, 0.12), growing.map { it.timing.storedDelay })
        assertEquals(listOf(0.0, 0.0, 0.1), shrinking.map { it.timing.storedDelay })
        for (request in growing + shrinking) {
            val controls = request.propertyControls
            assertEquals(0.0.toRawBits(), controls.delay.toRawBits())
            assertEquals(null, controls.startingVelocity)
            assertFalse(controls.interactive)
            assertFalse(controls.additive)
        }
        assertEquals(listOf(0.2, 0.55, 0.3), growing.map { it.timing.spring.response })
        assertEquals(listOf(0.2, 0.42, 0.4), shrinking.map { it.timing.spring.response })
        assertEquals(listOf(1.0, 0.9, 1.0), growing.map { it.timing.spring.dampingRatio })
        assertTrue(growing[1].timing.spring === eject)
        assertTrue(shrinking[1].timing.spring === absorb)
        val content = requireNotNull(FluidContentDefaults.blurSequence(growing = true))
        assertEquals(content.blurIn.dueThreshold(0.7, false, 1.0).toRawBits(), growing[2].timing.dueThreshold(0.7, false, 1.0).toRawBits())
        assertTrue(FluidLensingSpec.requests(false, true, 25.0, 90.0, 0.5, 120.0, eject, absorb).isEmpty())
        assertEquals(15.0, FluidLensingSpec.shortDimension(1.5, 40.0, 10.0), 0.0)
        assertEquals(60.0, FluidLensingSpec.shortDimension(1.5, 40.0, Double.NaN), 0.0)
        assertEquals((-0.0).toRawBits(), FluidLensingSpec.shortDimension(1.0, -0.0, 0.0).toRawBits())
    }

    @Test fun immediatePropertySpringStartsFreshFromPreWriteAndRetainsALiveBody() {
        val controls = FluidPropertySpringControls(0.0, null, interactive = false, additive = false)
        val spring = FluidPopupSpringSettings(1.0, 2 * Math.PI, retargetImpulse = 0.5)
        val fresh = controls.immediateBody(-0.001, 5.0, null, null, spring, null)
        assertEquals(FluidScalarSpringBody(-0.001, 0.0), fresh)
        val stored = FluidScalarSpringBody(1.25, 7.0)
        assertEquals(FluidScalarSpringBody(1.25, 8.0), controls.immediateBody(20.0, 5.0, stored, 3.0, spring, null))
        assertEquals(stored, controls.immediateBody(20.0, 5.0, stored, null, spring, null))
        val noImpulse = FluidPopupSpringSettings(1.0, 2 * Math.PI)
        assertEquals(stored, controls.immediateBody(20.0, 5.0, stored, 3.0, noImpulse, null))
        assertEquals(stored, controls.immediateBody(20.0, 5.0, stored, 3.0, null, null))
        val nonpositiveDamping = FluidPopupSpringSettings(0.0, 0.2, retargetImpulse = 0.5)
        assertEquals(stored, controls.immediateBody(20.0, 5.0, stored, 3.0, nonpositiveDamping, null))
    }

    @Test fun pendingPropertyVelocityTakesPrecedenceOverStartingVelocityAndImpulse() {
        val controls = FluidPropertySpringControls(0.0, 0.5, interactive = false, additive = false)
        val spring = FluidPopupSpringSettings(1.0, 2 * Math.PI, retargetImpulse = 0.5)
        val stored = FluidScalarSpringBody(1.25, 7.0)
        assertEquals(FluidScalarSpringBody(1.25, 2.5), controls.immediateBody(20.0, 25.0, stored, 3.0, spring, null))
        assertEquals(FluidScalarSpringBody(1.25, -0.0), controls.immediateBody(20.0, 25.0, stored, 3.0, spring, -0.0))
        assertEquals(FluidScalarSpringBody(20.0, 2.5), controls.immediateBody(20.0, 25.0, null, null, spring, null))
    }

    @Test fun freshCentreSpringsStartAtZeroAndPreserveRunningVelocityOnHandover() {
        val controls = FluidPropertySpringControls(0.0, null, interactive = false, additive = false)
        val content = FluidContentDefaults.copy(lensingEnabled = false)
        val queue = fluidThroughQueue(true, FluidMainEjectDefaults, content) { error("Lensing disabled") }
        val carried = FluidScalarSpringBody(31.5, -7.25)
        for (action in listOf(CollapsedSource, ExpandedOverrounded, FullyExpanded, Kick, Unkick)) {
            val spring = requireNotNull(queue.first { it.action == action }.timing.spring)
            assertEquals(0.0.toRawBits(), spring.retargetImpulse.toRawBits())
            assertEquals(FluidScalarSpringBody(8.0, 0.0), controls.immediateBody(8.0, 40.0, null, null, spring, null))
            assertEquals(carried, controls.immediateBody(8.0, 40.0, carried, 20.0, spring, null))
        }
    }

    @Test fun installedSpringUsesTheRecoveredThreeBodyBranches() {
        val body = FluidScalarSpringBody(2.0, 1.3)
        val cases = listOf(
            doubleArrayOf(10.0, 7.0, 0.023, 2.277141109672307, 22.1331732458615),
            doubleArrayOf(12.0, 24.0, 0.019, 2.2432583584385632, 22.580950056268875),
            doubleArrayOf(9.0, 24.0, 0.031, 2.334434614188172, 18.071557887997244),
        )
        for ((omega, damping, dt, value, velocity) in cases) {
            val kernel = SpringKernel(omega, damping / (omega + omega), installedDamping = damping)
            val next = kernel.advanceBody(body, 12.0, dt)
            assertEquals(value, next.value, 1e-12 * maxOf(1.0, kotlin.math.abs(value)))
            assertEquals(velocity, next.velocity, 1e-12 * maxOf(1.0, kotlin.math.abs(velocity)))
            assertTrue(kernel.advanceBody(body, 12.0, 0.0) === body)
        }
    }

    @Test fun installedPropertyScalesFinishVelocityWithItsPhysicalDamping() {
        val spring = FluidPopupSpringSettings(0.68, 0.37)
        assertEquals(0.08008676736384175.toRawBits(), spring.velocityScalingFactor.toRawBits())
        val dueBody = FluidScalarSpringBody(3.0, -80.43776178513674)
        val next = spring.advance(dueBody, -7.0, 0.019)
        assertEquals(1.3359182287897973, next.value, 1e-12 * 1.3359182287897973)
        assertEquals(-92.34064472028794, next.velocity, 1e-12 * 92.34064472028794)
        val rest = FluidScalarSpringBody(-7.0, -0.0)
        assertTrue(spring.advance(rest, -7.0, 0.019) === rest)
        val cancel = FluidScalarSpringBody(2.0, 4.0)
        assertTrue(cancel.finished(1.0, 0.25, 0.0, hasPending = false))
        assertFalse(cancel.finished(1.0, 0.25, 0.0, hasPending = true))
        assertFalse(cancel.finished(1.001, 0.25, 0.0001, hasPending = false))
        assertTrue(FluidScalarSpringBody(1.0, Double.POSITIVE_INFINITY).finished(1.0, 0.25, 0.0, hasPending = false))
    }

    @Test fun actionThresholdAppliesDragAfterSpeedAndAmplification() {
        val step = FluidPopupStep(0.1, FluidMainEjectDefaults)
        assertEquals(0.21.toRawBits(), step.dueThreshold(0.7, true, 0.3).toRawBits())
        assertEquals(0.0.toRawBits(), step.dueThreshold(0.0, true, 0.3).toRawBits())
    }

    @Test fun actionBridgeScalesBothCapturedResponsesWithoutMutatingTheSpring() {
        val spring = FluidPopupSpringSettings(0.9, 0.55, retargetImpulse = 0.125, trackingResponse = 0.4)
        val scaled = spring.scaledResponses(1.25)
        assertEquals(0.6875.toRawBits(), scaled.response.toRawBits())
        assertEquals(0.5.toRawBits(), scaled.trackingResponse.toRawBits())
        assertEquals(0.55.toRawBits(), spring.response.toRawBits())
        assertEquals(0.4.toRawBits(), spring.trackingResponse.toRawBits())
        assertEquals(0.9.toRawBits(), spring.dampingRatio.toRawBits())
        assertEquals(spring.dampingRatio.toRawBits(), scaled.dampingRatio.toRawBits())
        assertEquals(spring.retargetImpulse.toRawBits(), scaled.retargetImpulse.toRawBits())
    }

    @Test fun geometryStepUsesTheSharedScaledSpringAndOnlyFallsBackForAbsentPositionSpring() {
        val content = FluidContentDefaults.copy(lensingEnabled = false)
        val route = fluidThroughQueue(true, FluidMainEjectDefaults, content) { error("Lensing disabled") }
        val expected = listOf(CollapsedSource to 0.21, ExpandedOverrounded to 0.35, FullyExpanded to 0.35)
        for ((action, response) in expected) {
            val binding = route.first { it.action == action }.geometrySpringBinding(0.7)
            val shared = requireNotNull(binding.sharedSpring)
            assertTrue(binding.tracksPresentationLayer)
            assertTrue(fluidPositionSpring(shared) === shared)
            assertEquals(response.toRawBits(), shared.response.toRawBits())
            assertEquals(0.105.toRawBits(), shared.trackingResponse.toRawBits())
            assertEquals(0.0.toRawBits(), shared.retargetImpulse.toRawBits())
        }
        val unresolved = route.first().geometrySpringBinding(0.7)
        assertEquals(null, unresolved.sharedSpring)
        assertTrue(unresolved.tracksPresentationLayer)
        val fallback = fluidPositionSpring(null)
        assertEquals(1.0, fallback.dampingRatio, 0.0)
        assertEquals(0.4, fallback.response, 0.0)
    }

    @Test fun lensingSetupKeepsFilterOrderAndTrackedProperties() {
        assertEquals(listOf(FluidLensingSpec.Filter.GaussianBlur, FluidLensingSpec.Filter.DisplacementMap), FluidLensingSpec.filterOrder)
        assertEquals(listOf("bounds", "position", "cornerRadius", "cornerRadii", "cornerCurve", "hidden", "meshTransform"), FluidLensingSpec.trackedProperties)
        assertEquals(0.0.toRawBits(), FluidLensingSpec.trackStoredDelay.toRawBits())
        assertTrue(FluidLensingSpec.trackedHitTestsAsFill)
    }

    @Test fun displacementKeepsNamedMapInTheSecondTextureSlot() {
        assertEquals(44, FluidLensingSpec.displacementFunction)
        assertEquals(1, FluidLensingSpec.namedSourceTexture)
    }

    @Test fun displacementUniformsPackTheOrderedLanesAndOptionalOffset() {
        val transform = doubleArrayOf(2.0, 3.0, 99.0, 99.0, 5.0, 7.0)
        val p = FluidLensingSpec.amountUniform(transform, 16, 8, 0.5f, -2.0)
        assertEquals(listOf(-0.125f, -0.3125f, -0.375f, -0.875f).map(Float::toRawBits), p.map(Float::toRawBits))
        assertEquals(0.0625f.toRawBits(), FluidLensingSpec.amountUniform(transform, 16, 8, 0.5f)[0].toRawBits())
        transform[0] = 0.0
        assertEquals(0f.toRawBits(), FluidLensingSpec.amountUniform(transform, 16, 8, 0.5f, -2.0)[0].toRawBits())
        transform[1] = Double.POSITIVE_INFINITY
        assertTrue(FluidLensingSpec.amountUniform(transform, 16, 8, 0.5f, -2.0)[0].isNaN())
        assertEquals(0.78125f to 0.8125f, FluidLensingSpec.usedExtent(13, 7, 16, 8))
        assertEquals(0f to 0f, FluidLensingSpec.offset(null))
        assertEquals(0f to 0f, FluidLensingSpec.offset(doubleArrayOf()))
        assertEquals(-0.0f to -0.0f, FluidLensingSpec.offset(doubleArrayOf(-0.0)))
        assertEquals(2f to 3f, FluidLensingSpec.offset(doubleArrayOf(2.0, 3.0, 4.0)))
    }

    @Test fun guardedThroughSkipsBothImmediateUnhideSites() {
        assertEquals(false to false, fluidImmediateUnhideSites(true, true, true, true))
        assertEquals(true to false, fluidImmediateUnhideSites(false, true, true, false))
        assertEquals(false to true, fluidImmediateUnhideSites(false, true, false, true))
        assertEquals(false to false, fluidImmediateUnhideSites(false, false, true, false))
    }

    @Test fun namedMapQuadKeepsCoordinatePairsAndFusedRounding() {
        val quad = FluidLensingSpec.namedMapQuad(10, -4, 8, 6, 4, 3, 0.5f, 1f, Math::fma)
        assertEquals(listOf(
            FluidLensingSpec.Vertex(5f, -2f, 0f, 0f, 16f, -11f),
            FluidLensingSpec.Vertex(9f, -2f, 8f, 0f, 32f, -11f),
            FluidLensingSpec.Vertex(9f, 1f, 8f, 6f, 32f, 1f),
            FluidLensingSpec.Vertex(5f, 1f, 0f, 6f, 16f, 1f),
        ), quad)
        val cancellation = FluidLensingSpec.namedMapQuad(
            8_388_609, 0, 1, 1, 8_388_610, 0, 1f, Math.nextUp(1f), Math::fma,
        )
        assertEquals(0x34000000, cancellation[0].mapX.toRawBits())
        val wrapped = FluidLensingSpec.namedMapQuad(Int.MAX_VALUE - 3, 0, 8, 1, 0, 0, 1f, 1f, Math::fma)
        assertEquals(Int.MIN_VALUE.toFloat().toRawBits(), wrapped[1].mapX.toRawBits())
        assertEquals(Int.MIN_VALUE.toFloat().toRawBits(), wrapped[1].x.toRawBits())
    }

    @Test fun gaussianEdgesKeepModeAndOptionalBoundsSeparate() {
        assertEquals(null, FluidGaussianSpec.decodeBounds(null))
        assertEquals(null, FluidGaussianSpec.decodeBounds(doubleArrayOf(1.0, 2.0, 3.0)))
        val decoded = FluidGaussianSpec.decodeBounds(doubleArrayOf(-5.0, 6.0, 15.0, 17.0, 100.0))
        assertEquals(listOf(-5.0, 6.0, 20.0, 11.0), decoded?.toList())
        assertEquals(0, FluidGaussianSpec.edgeMode(0, 0, explicitBounds = false))
        assertEquals(1, FluidGaussianSpec.edgeMode(0, -1, explicitBounds = false))
        assertEquals(1, FluidGaussianSpec.edgeMode(0, 0, explicitBounds = decoded != null))
        assertEquals(2, FluidGaussianSpec.edgeMode(1, 1, explicitBounds = decoded != null))
        assertEquals(2, FluidGaussianSpec.edgeMode(1, 0, explicitBounds = false))
    }

    @Test fun narrowGaussianUsesTheFinalProgramOnlyForAnAdmittedDestination() {
        val widths = FluidGaussianSpec.NarrowWidth.entries
        val ordinary = widths.map { FluidGaussianSpec.narrowCompute(it, false, 0x80000000.toInt()) }
        val final = widths.map { FluidGaussianSpec.narrowCompute(it, true, 0x80000000.toInt()) }
        assertEquals((0x12..0x17).toList(), ordinary.map { it.function })
        assertEquals((0x18..0x1d).toList(), final.map { it.function })
        assertEquals((0x21..0x26).toList(), ordinary.map { it.textureFunction })
        assertEquals(ordinary.map { it.textureFunction }, final.map { it.textureFunction })
        for (flags in listOf(0, 0x20000000, 0xa0000000.toInt())) {
            assertEquals(0x12, FluidGaussianSpec.narrowCompute(widths.first(), true, flags).function)
        }
        assertEquals(2, ordinary.first().writeOptions)
        assertEquals(3, final.first().writeOptions)
        assertEquals(FluidGaussianSpec.NarrowTexel.Zero, ordinary.last().texel(inside = false, edgePolicy = 0))
        assertEquals(FluidGaussianSpec.NarrowTexel.ClampedSource, ordinary.last().texel(inside = false, edgePolicy = 1))
        assertEquals(FluidGaussianSpec.NarrowTexel.ExistingDestination, final.last().texel(inside = false, edgePolicy = 0))
        assertEquals(FluidGaussianSpec.NarrowTexel.ExistingDestination, final.last().texel(inside = false, edgePolicy = 1))
        assertEquals(FluidGaussianSpec.NarrowTexel.GaussianColor, final.last().texel(inside = true, edgePolicy = 0))
        assertEquals(FluidGaussianSpec.NarrowTexel.GaussianColor, ordinary.last().texel(inside = true, edgePolicy = 0))
    }

    @Test fun gaussianIdentityAndPaddingUseTheSameOrderedRadius() {
        assertTrue(FluidGaussianSpec.isIdentity(FluidContentBlurIn.gaussianRadius))
        assertTrue(FluidGaussianSpec.isIdentity(Double.NaN))
        assertTrue(FluidGaussianSpec.isIdentity(2.0, Double.NaN))
        assertFalse(FluidGaussianSpec.isIdentity(0.0, 2.0))
        assertEquals(4.0, FluidGaussianSpec.effectiveRadius(Double.NaN, 4.0), 0.0)
        val padding = FluidGaussianSpec.edgePadding(1.0, 2.0, 0.5, 2.0)
        assertEquals(0x40333333, padding.first.toRawBits())
        assertEquals(0x41333333, padding.second.toRawBits())
    }

    @Test fun trackedCornerDefaultAndMatchesPreserveTheEncodedCurve() {
        assertEquals(0, FluidCornerSpec.freshCurve(0x07e7ffff))
        assertEquals(1, FluidCornerSpec.freshCurve(0x07e80000))
        assertEquals(0f.toRawBits(), FluidCornerSpec.matchedRadius(-5.0).toRawBits())
        assertEquals((-0f).toRawBits(), FluidCornerSpec.matchedRadius(-0.0).toRawBits())
        val vector = FluidCornerSpec.matchedRadii(doubleArrayOf(3.0, 9.0, -5.0, 10.0, 7.0, 11.0, 8.0, 12.0))
        val lanes = FluidCornerSpec.rawRadii(null, false, vector, Float.NaN, 10.0)
        assertEquals(listOf(3f, 0f, 7f, 8f), lanes?.toList())
        val automatic = FluidCornerSpec.rawRadii(null, false, null, Float.fromBits(0x7fe00000), 10.0)
        assertEquals(List(4) { 5f }, automatic?.toList())
        assertEquals(List(4) { 0f }, FluidCornerSpec.rawRadii(null, false, null, Float.NaN, 10.0)?.toList())
        assertEquals(null, FluidCornerSpec.rawRadii(null, true, vector, 3f, 10.0))
    }

    @Test fun trackedCornerBoundsChooseUniformAndSignedUnevenPayloads() {
        val transform = FluidCornerSpec.fieldTransform(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0, 2.0), Math::fma)
        val uniform = FluidCornerSpec.boundsEncoding(floatArrayOf(-1f, 0f, 0.009f, 0.01f), transform, 0, 6f, 8f)
        assertEquals(21, uniform.imageFunction)
        assertEquals(4f, uniform.family, 0f)
        assertEquals(listOf(1f, 1f, 0.02f, 0f), uniform.radii.toList())
        val rounded = FluidCornerSpec.boundsEncoding(FloatArray(4) { 5f }, transform, 1, 0f, 10f)
        assertEquals(listOf(1f, 0f, 10f, 0f), rounded.radii.toList())
        val uneven = FluidCornerSpec.boundsEncoding(floatArrayOf(1f, 2f, 3f, 4f), transform, 1, 6f, 8f)
        assertEquals(22, uneven.imageFunction)
        assertEquals(5f, uneven.family, 0f)
        assertEquals(listOf(1f, -0f, -0f, 1f).map(Float::toRawBits), uneven.normalMatrix.map(Float::toRawBits))
        assertEquals(listOf(-2f, -4f, -6f, -8f), uneven.radii.toList())
        assertEquals(0x402875696e58a32fL, uneven.expansion.toRawBits())
        assertFalse(uneven.splitsIntoRect(0.0, uneven.expansion))
        assertTrue(uneven.splitsIntoRect(0.0, Math.nextUp(uneven.expansion)))
        assertTrue(uneven.splitsIntoRect(Double.NaN, uneven.expansion))
        assertTrue(uneven.splitsIntoRect(0.0, Double.NaN))
    }

    @Test fun trackedCornerScaleAndMatrixShareTheFusedAffineDeterminant() {
        val identity = FluidCornerSpec.fieldTransform(null) { _, _, _ -> error("Absent affine uses literal identity") }
        assertEquals(1f, identity.scale, 0f)
        assertEquals(listOf(1f, 0f, 0f, 1f).map(Float::toRawBits), identity.normalMatrix.map(Float::toRawBits))
        val reflected = FluidCornerSpec.fieldTransform(doubleArrayOf(-3.0, 0.0, 0.0, 0.0, 0.0, 4.0), Math::fma)
        assertEquals(3f, reflected.scale, 0f)
        assertEquals(listOf(-1f, 0f, 0f, 0.75f).map(Float::toRawBits), reflected.normalMatrix.map(Float::toRawBits))
        val cancellation = FluidCornerSpec.fieldTransform(
            doubleArrayOf(Math.nextUp(1f).toDouble(), 1.0, 0.0, 0.0, 8_388_610.0, 8_388_609.0), Math::fma,
        )
        assertEquals(0x287ffffc, cancellation.scale.toRawBits())
    }

    @Test fun trackedCornerDispatchKeepsImageAndCompositionSelectionsSeparate() {
        assertTrue(FluidCornerSpec.scansOverlap(parentAdmitted = true, disableOption = false))
        assertFalse(FluidCornerSpec.scansOverlap(parentAdmitted = true, disableOption = true))
        assertTrue(FluidCornerSpec.inlinesBounds(parentAllowsInline = true, eligibleBoundsUnionChildren = 1))
        assertFalse(FluidCornerSpec.inlinesBounds(parentAllowsInline = true, eligibleBoundsUnionChildren = 2))
        assertEquals(20, FluidCornerSpec.rectImageFunction)
        assertEquals(66, FluidCornerSpec.inlineEffectTexture)
        assertEquals(58, FluidCornerSpec.groupTexture(subtract = false, roiDirect = false, roiOverlap = true))
        assertEquals(1, FluidCornerSpec.groupTexture(subtract = false, roiDirect = true, roiOverlap = true))
        assertEquals(59, FluidCornerSpec.groupTexture(subtract = true, roiDirect = true, roiOverlap = true))
    }

    @Test fun destinationPresentationKeepsFullKeysAndUnroundedValues() {
        val submitted = mutableListOf<Pair<String, Long>>()
        val value = fluidDestinationBlend(0.37, crossBlur = true, allOpaque = false, lastDestination = true)
        value.present { key, sample -> submitted += key.key to sample.toRawBits() }
        assertEquals(listOf("opacity" to 0.37.toRawBits(), "filters.gaussianBlur.inputRadius" to 2.52.toRawBits()), submitted)
        assertEquals("sublayerTransform", FluidPopupChannel.ContentZoom.key)
        assertEquals(FluidPopupChannel.Value.Transform, FluidPopupChannel.ContentZoom.value)
        assertEquals(FluidPopupChannel.Value.ScaledScalar, FluidPopupChannel.GaussianRadius.value)
        value.present(null)
    }

    @Test fun lensPublicationPreservesPendingOnEqualityAndClearsBeforeTheCallback() {
        val height = FluidScalarPublication()
        val amount = FluidScalarPublication()
        val equal = { a: Double, b: Double -> a == b }
        height.consume { error("A fresh entry has no pending sample") }
        height.queueTick(FluidScalarSpringBody(21.25, -0.0), 28.0, false) { _, _ ->
            error("The first value has no previous value to compare")
        }
        height.queueTick(FluidScalarSpringBody(21.25, 9.0), 28.0, false, equal)
        amount.queueTick(FluidScalarSpringBody(-0.005, 0.25), -0.01, false, equal)
        FluidLensingSpec.publishSample(height) { value, velocity, cap, immediate, additive ->
            assertEquals(21.25.toRawBits(), value.toRawBits())
            assertEquals((-0.0).toRawBits(), velocity.toRawBits())
            assertEquals(0, cap)
            assertFalse(immediate)
            assertFalse(additive)
            height.consume { error("The pending sample was cleared before the callback") }
            height.queueTick(FluidScalarSpringBody(22.0, 3.0), 28.0, false, equal)
        }
        val published = mutableListOf<FluidScalarSpringBody>()
        height.consume { published += it }
        amount.consume { published += it }
        assertEquals(listOf(FluidScalarSpringBody(22.0, 3.0), FluidScalarSpringBody(-0.005, 0.25)), published)
    }

    @Test fun lensPublicationUsesTheProposedPreviousValueAndSnapsFiniteFinish() {
        val publication = FluidScalarPublication()
        val equal = { a: Double, b: Double -> a == b }
        publication.queueTick(FluidScalarSpringBody(0.0, 2.0), 1.0, false, equal)
        publication.queueTick(FluidScalarSpringBody(-0.0, 4.0), 1.0, false, equal)
        publication.queueTick(FluidScalarSpringBody(0.999, 5.0), 1.0, true) { proposed, previous ->
            assertEquals(1.0.toRawBits(), proposed.toRawBits())
            assertEquals((-0.0).toRawBits(), previous.toRawBits())
            false
        }
        val published = mutableListOf<FluidScalarSpringBody>()
        publication.consume { published += it }
        publication.consume { error("Existing publications are consumed only once") }
        assertEquals(listOf(FluidScalarSpringBody(1.0, 0.0)), published)
        publication.queueTick(FluidScalarSpringBody(1.0, 8.0), 2.0, false, equal)
        publication.consume { error("A duplicate value does not enqueue velocity by itself") }
    }

    @Test fun lensEntriesAppendIndependentlyAndReuseTheExistingDrain() {
        val pending = mutableListOf<FluidPopupChannel>()
        val events = mutableListOf<String>()
        pending.submitFluidPropertyPublication(
            FluidPopupChannel.LensHeight, { false }, { -1 },
            {
                assertEquals(listOf(FluidPopupChannel.LensHeight), pending)
                events += "first commit"
            },
            { error("The idle phase uses the first commit hook") },
        )
        pending.submitFluidPropertyPublication(
            FluidPopupChannel.LensAmount, { true }, { error("The drain is already registered") },
            { error("The drain is already registered") }, { error("The drain is already registered") },
        )
        assertEquals(listOf(FluidPopupChannel.LensHeight, FluidPopupChannel.LensAmount), pending)
        val registrationOrder = mutableListOf<String>()
        pending.map { channel ->
            val factory = {
                registrationOrder += "factory ${channel.key}"
                channel
            }
            factory to { coordinator: FluidPopupChannel -> registrationOrder += "register ${coordinator.key}" }
        }.registerFluidPropertyPublications()
        assertEquals(
            listOf(
                "factory effect.height", "register effect.height",
                "factory filters.displacementMap.inputAmount", "register filters.displacementMap.inputAmount",
            ),
            registrationOrder,
        )
        pending.clear()
        pending.submitFluidPropertyPublication(
            FluidPopupChannel.LensAmount, { false }, { 1 },
            { error("The active phase uses the platform hook") },
            {
                assertEquals(listOf(FluidPopupChannel.LensAmount), pending)
                events += "after commit"
            },
        )
        assertEquals(listOf("first commit", "after commit"), events)
    }

    @Test fun contentPointScaleUsesTheLiveOriginalLayerContext() {
        assertEquals(1.0, fluidLayerPointScale(false) { error("The layer context was not resolved") }, 0.0)
        assertEquals(1.0, fluidLayerPointScale(true) { null }, 0.0)
        assertEquals((-0.0).toRawBits(), fluidLayerPointScale(true) { -0.0 }.toRawBits())
        var live = 2.0
        val windowScale = { live }
        assertEquals(0.25, fluidRadiusEpsilonSquared(fluidLayerPointScale(true, windowScale)), 0.0)
        live = 4.0
        val pointScale = fluidLayerPointScale(true, windowScale)
        val thresholds = fluidZoomEpsilonSquared(pointScale, 6.0 to 8.0, false, 0.5, 0.5)
        assertEquals(listOf(0.0025000000000000005, 0.0025000000000000005, 0.0625, 0.0025000000000000005, 0.0025000000000000005), thresholds.toList())
    }

    @Test fun typedThresholdsKeepThePointScaleAndGeometryOverloadsDistinct() {
        assertEquals(0.1111111111111111.toRawBits(), fluidRadiusEpsilonSquared(3.0).toRawBits())
        val fixed = fluidZoomEpsilonSquared(2.0, null, false, 0.0, 0.0)
        assertEquals(listOf(0.0001, 0.000001, 0.25, 0.000001, 0.000001), fixed.toList())
        val empty = fluidZoomEpsilonSquared(2.0, 0.0 to 1.0, true, 0.5, 0.5)
        assertEquals(fixed.toList(), empty.toList())
        val spatial = fluidZoomEpsilonSquared(25.0, 6.0 to 8.0, false, 0.5, 0.5)
        assertEquals(listOf(0.000064, 0.000064, 0.0016, 0.000064, 0.000064), spatial.toList())
        assertTrue(fluidLensSuppressesAction(0.0, 0.05, null, bypass = false))
        assertTrue(fluidLensSuppressesAction(0.0, 0.05, 0.0, bypass = false))
        assertFalse(fluidLensSuppressesAction(0.0, 0.05, 1.0, bypass = false))
        assertFalse(fluidLensSuppressesAction(0.0, 0.05, null, bypass = true))
        assertFalse(fluidLensSuppressesAction(0.0, Double.NaN, null, bypass = false))
        assertEquals("effect.height", FluidPopupChannel.LensHeight.key)
        assertEquals("filters.displacementMap.inputAmount", FluidPopupChannel.LensAmount.key)
        assertEquals(FluidPopupChannel.Value.CoarseScalar, FluidPopupChannel.LensHeight.value)
        assertEquals(FluidPopupChannel.LensHeight.value, FluidPopupChannel.LensAmount.value)
    }

    @Test fun registrationRepeatsPreparationAndKeepsTheFirstOpacityMatchPerIdentity() {
        data class Layer(val label: Int)
        val from = Layer(0)
        val to = Layer(0)
        val first = Layer(1)
        val duplicate = Layer(2)
        val third = Layer(3)
        val masks = mutableListOf<Boolean>()
        val tracked = mutableListOf<Layer>()
        val portals = IdentityHashMap<Layer, Layer>()
        FluidLensingSpec.registerPortals(listOf(first, duplicate, third), true, portals,
            { if (it === third) to else from }, { it }, { _, mask -> masks += mask }, { tracked += it }, { _, outer -> outer },
        )
        assertEquals(listOf(false, false, false), masks)
        assertEquals(listOf(first, duplicate, third), tracked)
        assertEquals(2, portals.size)
        assertTrue(portals[from] === first)
        assertTrue(portals[to] === third)
    }

    @Test fun endpointAndTerminalOperationsPreserveTheThroughOrder() {
        val calls = mutableListOf<String>()
        val hide = { item: String -> calls += item; item.takeUnless { it == "to" } }
        assertEquals(listOf("from"), fluidHoldEndpoints("from", "to", hide))
        fluidCompleteGroup(
            { fluidCompleteTransition(true, { calls += "teardown" }, { calls += "remove" }, { calls += "alongside" }) },
            { calls += "portals" }, { calls += "morphables" }, { calls += "animations" }, { calls += "hiding" },
        )
        assertEquals(listOf("from", "to", "teardown", "remove", "alongside", "portals", "morphables", "animations", "hiding"), calls)
    }

    @Test fun displacementUsesNamedFreshInputsAndIndependentSurfaceCoordinates() {
        val lens = FluidLensingSpec
        assertEquals(6, lens.displacementVertexType)
        assertEquals(20.0, lens.freshHeight, 0.0)
        assertEquals(-0.001, lens.freshAmount, 0.0)
        assertEquals(0f to 0f, lens.freshOffset)
        assertEquals(0.125f to 0.75f, lens.surfaceCoordinates(4f, 4f, 32, 16, true, 0, true))
        assertEquals(0.5f to 0.125f, lens.surfaceCoordinates(4f, 4f, 8, 32, true, 9, true))
        assertTrue(lens.skipsDisplacementSample(Float.fromBits(0x3b808080)))
        assertFalse(lens.skipsDisplacementSample(Float.fromBits(0x3b808081)))
        assertEquals("DisplacementMap(GaussianBlur(color))", lens.colorChain("color") { filter, input -> "$filter($input)" })
        assertEquals("DisplacementMap(color)", lens.colorChain("color") { filter, input ->
            if (filter == FluidLensingSpec.Filter.GaussianBlur) null else "$filter($input)"
        })
    }
}
