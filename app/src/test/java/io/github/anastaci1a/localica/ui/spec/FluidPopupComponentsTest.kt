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
        val body = FluidScalarSpringBody(40.0, 0.0)
        val start = FluidScalarSpringEntry(body, FluidKickSpec.unkickTarget, body)
        val returned = FluidMainEjectDefaults.advanceUnsmoothed(start, 0.35)
        assertTrue(returned.body.value < 0.0)
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
        val fresh = controls.immediateEntry(-0.001, 5.0, null, null, spring, null)
        assertEquals(FluidScalarSpringBody(-0.001, 0.0), fresh.body)
        assertEquals(fresh.body, fresh.intermediate)
        assertEquals(5.0.toRawBits(), fresh.target.toRawBits())
        assertTrue(spring.advanceUnsmoothed(fresh, 0.0) === fresh)
        assertTrue(spring.advanceUnsmoothed(fresh, -0.03) === fresh)
        val sampled = spring.advanceUnsmoothed(fresh, 0.03)
        assertEquals(FluidScalarSpringBody(5.0, 0.0), sampled.intermediate)
        val expected = SpringKernel.fromInstalled(1.0, 2.0).advanceBody(fresh.body, 5.0, 0.03)
        assertEquals(expected, sampled.body)
        val stored = FluidScalarSpringBody(1.25, 7.0)
        val retained = FluidScalarSpringEntry(stored, 3.0, FluidScalarSpringBody(2.0, -0.25))
        fun retarget(previous: Double?, settings: FluidPopupSpringSettings?) =
            controls.immediateEntry(20.0, 5.0, retained, previous, settings, null)
        val composed = retarget(3.0, spring)
        assertEquals(FluidScalarSpringBody(1.25, 8.0), composed.body)
        assertEquals(retained.intermediate, composed.intermediate)
        assertEquals(5.0.toRawBits(), composed.target.toRawBits())
        assertEquals(stored, retarget(null, spring).body)
        val noImpulse = FluidPopupSpringSettings(1.0, 2 * Math.PI)
        assertEquals(stored, retarget(3.0, noImpulse).body)
        assertEquals(stored, retarget(3.0, null).body)
        val nonpositiveDamping = FluidPopupSpringSettings(0.0, 0.2, retargetImpulse = 0.5)
        assertEquals(stored, retarget(3.0, nonpositiveDamping).body)
    }

    @Test fun pendingPropertyVelocityTakesPrecedenceOverStartingVelocityAndImpulse() {
        val controls = FluidPropertySpringControls(0.0, 0.5, interactive = false, additive = false)
        val spring = FluidPopupSpringSettings(1.0, 2 * Math.PI, retargetImpulse = 0.5)
        val stored = FluidScalarSpringEntry(FluidScalarSpringBody(1.25, 7.0), 3.0, FluidScalarSpringBody(3.0, 0.0))
        assertEquals(FluidScalarSpringBody(1.25, 2.5), controls.immediateEntry(20.0, 25.0, stored, 3.0, spring, null).body)
        assertEquals(FluidScalarSpringBody(1.25, -0.0), controls.immediateEntry(20.0, 25.0, stored, 3.0, spring, -0.0).body)
        val fresh = controls.immediateEntry(20.0, 25.0, null, null, spring, null)
        assertEquals(FluidScalarSpringBody(20.0, 2.5), fresh.body)
        assertEquals(FluidScalarSpringBody(20.0, 0.0), fresh.intermediate)
    }

    @Test fun freshCentreSpringsStartAtZeroAndPreserveRunningVelocityOnHandover() {
        val controls = FluidPropertySpringControls(0.0, null, interactive = false, additive = false)
        val content = FluidContentDefaults.copy(lensingEnabled = false)
        val queue = fluidThroughQueue(true, FluidMainEjectDefaults, content) { error("Lensing disabled") }
        val carried = FluidScalarSpringBody(31.5, -7.25)
        val retained = FluidScalarSpringEntry(carried, 20.0, FluidScalarSpringBody(20.0, 0.0))
        for (action in listOf(CollapsedSource, ExpandedOverrounded, FullyExpanded, Kick, Unkick)) {
            val spring = requireNotNull(queue.first { it.action == action }.timing.spring)
            assertEquals(0.0.toRawBits(), spring.retargetImpulse.toRawBits())
            assertEquals(FluidScalarSpringBody(8.0, 0.0), controls.immediateEntry(8.0, 40.0, null, null, spring, null).body)
            assertEquals(carried, controls.immediateEntry(8.0, 40.0, retained, 20.0, spring, null).body)
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
        val due = FluidScalarSpringEntry(dueBody, -7.0, FluidScalarSpringBody(0.0, 4.0))
        val next = spring.advanceUnsmoothed(due, 0.019).body
        assertEquals(1.3359182287897973, next.value, 1e-12 * 1.3359182287897973)
        assertEquals(-92.34064472028794, next.velocity, 1e-12 * 92.34064472028794)
        val rest = FluidScalarSpringBody(-7.0, -0.0)
        val settled = spring.advanceUnsmoothed(due.copy(body = rest), 0.019)
        assertTrue(settled.body === rest)
        assertEquals(FluidScalarSpringBody(-7.0, 0.0), settled.intermediate)
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

    @Test fun gaussianTileDecimationUsesLiveAdmissionAndTheNamedThreadDivisors() {
        val factors = FluidGaussianSpec.Decimation.entries
        val capabilities = FluidGaussianSpec.Capabilities(true, 0, true, true, 0)
        for (size in factors) {
            fun admits(shape: Boolean = false, flags: Int = 0, mode: Int = 0,
                allowed: Boolean = true, current: Boolean = true, level: Int = 0xf0 or size.factor,
            ) = FluidGaussianSpec.admitsTileDecimation(size, shape, flags, mode, capabilities.copy(tileShaders = allowed), current, level)
            assertTrue(admits())
            assertTrue(admits(flags = 1, mode = 2))
            assertFalse(admits(shape = true))
            assertFalse(admits(flags = 2))
            assertFalse(admits(flags = 1, mode = 1))
            assertFalse(admits(allowed = false))
            assertFalse(admits(current = false))
            assertFalse(admits(level = 0xf0 or (size.factor - 1)))
        }
        fun tile(size: FluidGaussianSpec.Decimation, encoder: Boolean = true,
            destinationFlags: Int = 0x40, sourceFlags: Int = 8, tileSize: Pair<UInt, UInt> = 15u to 11u,
        ) = FluidGaussianSpec.tileDecimation(
            size, encoder, destinationFlags, sourceFlags, tileSize, -7 to 17, 1 to 3, 1f, 4f, 2f,
        )
        val passes = factors.map { tile(it)!! }
        assertEquals(listOf("tile_downsample_2", "tile_downsample_4", "tile_downsample_8"), passes.map { it.function })
        assertEquals(listOf(7u to 5u, 7u to 5u, 3u to 2u), passes.map { it.threads })
        assertEquals(listOf(0x0005fffc, 0x0001fffe, 0xffffffff.toInt()), passes.map { it.offsetLanes })
        assertEquals(0.25f to 0.5f, passes.last().gainInputs)
        assertEquals(null, tile(factors.first(), encoder = false))
        assertEquals(null, tile(factors.first(), destinationFlags = 8))
        assertEquals(null, tile(factors.first(), sourceFlags = 0x40))
        assertEquals(null, tile(factors.last(), tileSize = 3u to 8u))
    }

    @Test fun gaussianCapabilitiesUseDistinctLiveComputeAndTilePreferences() {
        val caps = FluidGaussianSpec.Capabilities(
            familySupportsCompute = true, computeDisableByte = 0,
            tileShaders = true, simdShuffleAndFill = true, tilePreferenceByte = 0,
        )
        assertTrue(caps.promotesCompute)
        assertTrue(caps.copy(computeDisableByte = 2).promotesCompute)
        assertFalse(caps.copy(computeDisableByte = 3).promotesCompute)
        assertFalse(caps.copy(familySupportsCompute = false).promotesCompute)
        assertTrue(caps.copy(tilePreferenceByte = 3).jointTiles)
        assertFalse(caps.copy(tilePreferenceByte = 1).jointTiles)
        assertFalse(caps.copy(tileShaders = false).jointTiles)
        assertTrue(caps.copy(simdShuffleAndFill = false).jointTiles)
        assertTrue(caps.admitsSimd(0, hasShape = false, reinterpretedSource = false))
        assertTrue(caps.admitsSimd(2, hasShape = false, reinterpretedSource = false))
        assertFalse(caps.admitsSimd(1, hasShape = false, reinterpretedSource = false))
        assertFalse(caps.admitsSimd(0, hasShape = true, reinterpretedSource = false))
        assertFalse(caps.admitsSimd(0, hasShape = false, reinterpretedSource = true))
        assertFalse(caps.copy(simdShuffleAndFill = false).admitsSimd(0, hasShape = false, reinterpretedSource = false))
    }

    @Test fun gaussianBoxFallbackKeepsAxisScalesAndTheComputeAndDrawInputsSeparate() {
        val factors = FluidGaussianSpec.Decimation.entries
        fun box(size: FluidGaussianSpec.Decimation, compute: Boolean, supported: Boolean = true) =
            FluidGaussianSpec.boxDecimation(size, compute, supported, 2f to 1f, 0.25f to 0.5f)

        val compute = factors.map { box(it, true, supported = false)!! }
        assertEquals(listOf(0x25, 0x0d, 0x0f), compute.map { it.computeFunction })
        assertEquals(listOf(1, 0x1b, 0x1d), compute.map { it.textureFunction })
        assertEquals(listOf(2f to 1f, 4f to 4f, 8f to 8f), compute.map { it.scale })
        assertTrue(compute.first().offsets.isEmpty())
        assertEquals(listOf(-1f to 1f, 1f to 1f, -1f to -1f, 1f to -1f), compute[1].offsets)
        assertEquals(List(4) { 0.25f }, compute[1].weights)
        assertEquals(listOf(1f to 1f, 1f to 3f, 3f to 1f, 3f to 3f), compute.last().offsets)
        assertEquals(null, compute.last().weights)
        assertEquals(null, box(factors[1], false, supported = false))
        val draw = factors.map { box(it, false)!! }
        assertTrue(draw.all { it.computeFunction == null })
        assertEquals(compute[1].offsets, draw[1].offsets)
        assertEquals(listOf(0.25f to 0.5f, 0.25f to 1.5f, 0.75f to 0.5f, 0.75f to 1.5f), draw.last().offsets)
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
        val automaticRadius = Float.fromBits(0x7fe00000)
        assertEquals(0f.toRawBits(), FluidCornerSpec.matchedRadius(-5f, 10.0, 18.0).toRawBits())
        assertEquals((-0f).toRawBits(), FluidCornerSpec.matchedRadius(-0f, 10.0, 18.0).toRawBits())
        assertEquals(0f.toRawBits(), FluidCornerSpec.matchedRadius(null, 10.0, 18.0).toRawBits())
        assertEquals(0f.toRawBits(), FluidCornerSpec.matchedRadius(Float.NaN, 10.0, 18.0).toRawBits())
        assertEquals(5f.toRawBits(), FluidCornerSpec.matchedRadius(automaticRadius, 18.0, 10.0).toRawBits())
        assertEquals(5f.toRawBits(), FluidCornerSpec.matchedRadius(automaticRadius, Double.NaN, 10.0).toRawBits())
        assertTrue(FluidCornerSpec.matchedRadius(automaticRadius, 10.0, Double.NaN).isNaN())
        val source = doubleArrayOf(3.0, 9.0, -5.0, 10.0, 7.0, 11.0, 8.0, 12.0)
        val vector = FluidCornerSpec.matchedRadii(source, capacity = 8)
        assertEquals(null, FluidCornerSpec.matchedRadii(source, capacity = 7))
        assertEquals(null, FluidCornerSpec.matchedRadii(source.copyOf(9), capacity = 9))
        assertEquals(null, FluidCornerSpec.matchedRadii(null, capacity = 8))
        val lanes = FluidCornerSpec.rawRadii(null, false, vector, Float.NaN, 10.0, 18.0)
        assertEquals(listOf(3f, 0f, 7f, 8f), lanes?.toList())
        val automatic = FluidCornerSpec.rawRadii(null, false, null, automaticRadius, 10.0, 18.0)
        assertEquals(List(4) { 5f }, automatic?.toList())
        assertEquals(List(4) { 0f }, FluidCornerSpec.rawRadii(null, false, null, Float.NaN, 10.0, 18.0)?.toList())
        assertEquals(null, FluidCornerSpec.rawRadii(null, true, vector, 3f, 10.0, 18.0))
    }

    @Test fun trackedCornerBoundsChooseUniformAndSignedUnevenPayloads() {
        val transform = FluidCornerSpec.fieldTransform(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0, 2.0), Math::fma)
        val uniform = FluidCornerSpec.boundsEncoding(floatArrayOf(-1f, 0f, 0.009f, 0.01f), transform, 0, 6.0, 8.0, 0f, inline = true)
        assertEquals(21, uniform.imageFunction)
        assertEquals(4f, uniform.family, 0f)
        assertEquals(listOf(1f, 1f, 0.02f, 0f), uniform.radii.toList())
        val rounded = FluidCornerSpec.boundsEncoding(FloatArray(4) { 5f }, transform, 1, 0.0, 10.0, 0f, inline = true)
        assertEquals(listOf(1f, 0f, 10f, 0f), rounded.radii.toList())
        val unordered = FluidCornerSpec.boundsEncoding(FloatArray(4) { 5f }, transform, 1, Double.NaN, 10.0, 0f, inline = true)
        assertTrue(unordered.radii[0].isNaN())
        assertEquals(0f.toRawBits(), unordered.radii[1].toRawBits())
        val uneven = FluidCornerSpec.boundsEncoding(floatArrayOf(1f, 2f, 3f, 4f), transform, 1, 60.0, 80.0, 0f, inline = true)
        assertEquals(22, uneven.imageFunction)
        assertEquals(5f, uneven.family, 0f)
        assertEquals(listOf(1f, -0f, -0f, 1f).map(Float::toRawBits), uneven.normalMatrix.map(Float::toRawBits))
        assertEquals(listOf(-2f, -4f, -6f, -8f), uneven.radii.toList())
        val cut = Double.fromBits(0x402875696e58a32fL).toFloat() / 2f
        assertEquals(cut to cut, uneven.gridCut)
    }

    @Test fun trackedCornerExtentPadsBeforeScalingAndSplitsOnlyPastTheOrderedThreshold() {
        val identity = FluidCornerSpec.fieldTransform(null, Math::fma)
        fun encode(width: Double, height: Double, pad: Float, inline: Boolean) =
            FluidCornerSpec.boundsEncoding(FloatArray(4) { 2f }, identity, 0, width, height, pad, inline)

        val direct = encode(8.0, 0.0, -1f, inline = false)
        assertEquals(3f to -1f, direct.fieldHalfSize)
        assertEquals(3f to 0f, direct.gridCut)
        val inline = encode(8.0, 0.0, -1f, inline = true)
        assertEquals(5f to 1f, inline.fieldHalfSize)
        assertEquals(1f to 0f, inline.gridCut)
        val padded = encode(8.0, 2.0, -1f, inline = false)
        assertEquals(4f to 1f, padded.fieldHalfSize)
        assertEquals(3f to 2f, padded.gridCut)
        assertEquals(null, encode(4.0, 2.0, 1f, inline = false).gridCut)
        assertEquals(null, encode(8.0, 2.0, -1f, inline = true).gridCut)
        assertEquals(-4096f to -4096f, encode(0.0, 0.0, 8192f, inline = true).fieldHalfSize)
        assertEquals(3f to 3f, encode(4.0, 2.0, -3f, inline = true).fieldHalfSize)
        assertTrue(encode(4.0, Double.NaN, 0f, inline = true).gridCut?.second?.isNaN() == true)
        assertTrue(encode(4.0, 2.0, Float.NaN, inline = false).gridCut?.first?.isNaN() == true)

        assertEquals(6f, FluidCornerSpec.parentPadding(2f, 8f, 0f, 1f, Math::fma), 0f)
        assertEquals(7f, FluidCornerSpec.parentPadding(2f, 8f, 0f, -1f, Math::fma), 0f)
        assertEquals(0x40333333, FluidCornerSpec.parentPadding(0f, 0f, 1f, 0f, Math::fma).toRawBits())
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
        assertEquals(20, FluidCornerSpec.rectImageFunction)
        assertEquals(66, FluidCornerSpec.inlineEffectTexture)
        assertEquals(58, FluidCornerSpec.groupTexture(subtract = false, roiDirect = false, roiOverlap = true))
        assertEquals(1, FluidCornerSpec.groupTexture(subtract = false, roiDirect = true, roiOverlap = true))
        assertEquals(59, FluidCornerSpec.groupTexture(subtract = true, roiDirect = true, roiOverlap = true))
    }

    @Test fun trackedInlineScanSkipsExcludedSubtreesAndRequiresOneBoundsUnion() {
        data class Node(val flags: Long, val boundsUnion: Boolean = true, val children: List<Node> = emptyList())
        val candidate = Node(1L shl 39)
        val ineligible = candidate.copy(boundsUnion = false)
        val skipped = Node((1L shl 40) or (1L shl 39), children = listOf(ineligible))
        val branch = Node(0, children = listOf(candidate))
        fun inline(nodes: List<Node>, debug: Boolean = false, capability: Boolean = false, requiresCheck: Boolean = false,
            surface: Boolean = false, checkParent: () -> Boolean = { error("No parent check required") },
        ) = FluidCornerSpec.inlineBounds(
            nodes, debug, capability, requiresCheck, surface, checkParent, Node::flags, Node::boundsUnion, Node::children,
        )

        val roots = listOf(skipped, branch)
        assertTrue(inline(roots) === candidate)
        assertTrue(inline(roots, requiresCheck = true) { true } === candidate)
        assertEquals(null, inline(roots, requiresCheck = true) { false })
        assertEquals(null, inline(roots, debug = true))
        assertEquals(null, inline(roots, capability = true))
        assertEquals(null, inline(roots, surface = true))
        assertEquals(null, inline(listOf(skipped)))
        assertEquals(null, inline(listOf(branch, candidate)))
        assertEquals(null, inline(listOf(ineligible)))
        assertEquals(null, inline(listOf(skipped.copy(flags = skipped.flags or (1L shl 41)))))
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

    @Test fun propertyDisplayTimePrimesFromTheDriverAndKeepsNonpositiveTicksAtZero() {
        val unprimed = FluidPropertyFrameTime(0.0, 0.0, 0.0)
        val driver = 0.016
        val primed = unprimed.primed(12.0) { driver }
        val expectedInterval = driver * Double.fromBits(0x3ff004189374bc6aL)
        assertEquals(expectedInterval.toRawBits(), primed.refreshInterval.toRawBits())
        assertEquals((12.0 + expectedInterval * -0.4).toRawBits(), primed.previousTime.toRawBits())
        assertEquals(0.0.toRawBits(), primed.delta.toRawBits())
        assertEquals(0x3f91156f8c384071L, unprimed.primed(12.0) { 0.0 }.refreshInterval.toRawBits())
        val reprime = primed.primed(13.0) { error("Use the stored refresh interval") }
        assertEquals(primed.refreshInterval.toRawBits(), reprime.refreshInterval.toRawBits())
        val tick = primed.advance(12.01)
        assertEquals(12.01.toRawBits(), tick.previousTime.toRawBits())
        assertEquals((12.01 - primed.previousTime).toRawBits(), tick.delta.toRawBits())
        assertEquals(((0.02 + tick.delta) / 2.0).toRawBits(), tick.entryDelta(0.02, 2.0).toRawBits())
        for (time in listOf(0.0, Double.NaN, 12.0, 12.01)) {
            val stale = tick.advance(time)
            assertEquals(tick.previousTime.toRawBits(), stale.previousTime.toRawBits())
            assertEquals(0.0.toRawBits(), stale.delta.toRawBits())
        }
    }

    @Test fun propertyFrameDeliversTheFinalSampleBeforeCompletionAndGroupFlush() {
        val publication = FluidScalarPublication()
        val callbacks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        runFluidPropertyFrame(
            targetTimestamp = 1.02,
            preparationFlags = 2,
            solve = {
                assertTrue(events.isEmpty())
                events += "solve"
                publication.queueTick(FluidScalarSpringBody(0.99, 7.0), 1.0, true, Double::equals)
                callbacks += {
                    events += "complete true"
                    callbacks += { events += "cleanup" }
                }
                1
            },
            consumeSamples = {
                publication.consume {
                    assertEquals(FluidScalarSpringBody(1.0, 0.0), it)
                    events += "sample"
                }
            },
            postTick = callbacks,
            normalizedChange = { 1.0 },
            flush = { stamp, transaction, changed ->
                assertEquals(1.02.toRawBits(), stamp.toRawBits())
                assertTrue(transaction)
                assertTrue(changed)
                events += "flush"
            },
        )
        assertEquals(listOf("solve", "sample", "complete true", "cleanup", "flush"), events)
        assertTrue(callbacks.isEmpty())
    }

    @Test fun groupFlushUsesTheLowFlagBitAndAnOrderedEligibilityThreshold() {
        val cases = listOf(
            Triple(0, Double.NaN, false to false),
            Triple(2, Math.nextDown(1.0), false to false),
            Triple(2, 1.0, false to true),
            Triple(3, 1.0, true to true),
        )
        for ((preparation, change, expected) in cases) {
            runFluidPropertyFrame(1.02, preparation, { 4 }, {}, mutableListOf(), { change }) { time, transaction, eligible ->
                assertEquals(1.02.toRawBits(), time.toRawBits())
                assertEquals(expected, transaction to eligible)
            }
        }
    }

    @Test fun liveLensModifiersWriteHeightAndOnlyTheFirstNamedAmountInRenderOrder() {
        val filters = listOf("gaussianBlur", "displacementMap", null, "displacementMap")
        val amounts = mutableListOf<Pair<Int, Long>>()
        val heights = mutableListOf<Int>()
        fun apply(channel: FluidPopupChannel, sample: Double?, kind: Int = 7, names: List<String?> = filters) =
            FluidLensingSpec.applyPresentation(channel, sample, kind, names, { heights += it.toRawBits() }) { slot, value ->
                amounts += slot to value.toRawBits()
            }

        val value = Math.nextUp(3.0)
        apply(FluidPopupChannel.LensHeight, value, kind = 6)
        apply(FluidPopupChannel.LensHeight, null)
        apply(FluidPopupChannel.LensHeight, value)
        apply(FluidPopupChannel.LensAmount, value, names = listOf("gaussianBlur", null))
        apply(FluidPopupChannel.LensAmount, null)
        apply(FluidPopupChannel.LensAmount, value)
        assertEquals(listOf(3f.toRawBits()), heights)
        assertEquals(listOf(1 to value.toRawBits()), amounts)
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

    @Test fun unsmoothedRadiusResetsItsIntermediateBeforeAdvancementAndPublication() {
        val body = FluidScalarSpringBody(-2.0, -1.3)
        val entry = FluidScalarSpringEntry(body, -12.0, FluidScalarSpringBody(-5.0, 2.0))
        val cases = listOf(
            doubleArrayOf(100.0, 7.0, 0.023, -2.277141109672307, -22.1331732458615),
            doubleArrayOf(144.0, 24.0, 0.019, -2.2432583584385632, -22.580950056268875),
            doubleArrayOf(81.0, 24.0, 0.031, -2.334434614188172, -18.071557887997244),
        )
        for ((stiffness, damping, dt, value, velocity) in cases) {
            val advanced = entry.advanceUnsmoothed(stiffness, damping, dt)
            assertEquals(FluidScalarSpringBody(-12.0, 0.0), advanced.intermediate)
            val next = advanced.body
            assertEquals(value, next.value, 1e-12 * maxOf(1.0, kotlin.math.abs(value)))
            assertEquals(velocity, next.velocity, 1e-12 * maxOf(1.0, kotlin.math.abs(velocity)))
            assertTrue(entry.advanceUnsmoothed(stiffness, damping, 0.0) === entry)
            val publication = FluidScalarPublication()
            publication.queueTick(next, 0.0, false, Double::equals)
            FluidSharedBlurSpec.publishRadius(publication) { radius, publishedVelocity, _ ->
                assertEquals(0.0.toRawBits(), radius.toRawBits())
                assertEquals(next.velocity.toRawBits(), publishedVelocity.toRawBits())
            }
            assertTrue(next.value < 0.0)
        }
    }

    @Test fun sharedBlurOriginsAndPublishedRadiusPreserveTheTypedBody() {
        val identity = FluidSharedBlurSpec.zoomOrigin(null)
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), identity.toList())
        val zoom = identity.copyOf().also { it[0] = 1.2 }
        assertTrue(FluidSharedBlurSpec.zoomOrigin(zoom) === zoom)
        assertEquals(0.0.toRawBits(), FluidSharedBlurSpec.radiusOrigin(null).toRawBits())
        assertEquals((-0.0).toRawBits(), FluidSharedBlurSpec.radiusOrigin(-0.0).toRawBits())
        assertFalse(FluidSharedBlurSpec.additive)

        val nan = Double.fromBits(0x7ff8000000000042L)
        val values = listOf(-7.0 to 0.0, -0.0 to 0.0, nan to nan, 3.25 to 3.25)
        for ((input, expected) in values) {
            val body = FluidScalarSpringBody(input, -2.25)
            val publication = FluidScalarPublication()
            publication.queueTick(body, 4.0, false) { _, _ -> error("No previous value") }
            FluidSharedBlurSpec.publishRadius(publication) { radius, velocity, additive ->
                assertEquals(expected.toRawBits(), radius.toRawBits())
                assertEquals((-2.25).toRawBits(), velocity.toRawBits())
                assertFalse(additive)
            }
            assertEquals(input.toRawBits(), body.value.toRawBits())
        }
        val destinationWrites = mutableListOf<Double>()
        FluidDestinationBlend(0.5, -1.0).present { _, value -> destinationWrites += value }
        assertEquals(listOf(0.5, -1.0), destinationWrites)
    }

    @Test fun lensModifierReusesOnlyACompatibleOrdinaryAssociation() {
        val channel = FluidPopupChannel.LensHeight
        val format = FluidModifierFormat(1, additive = false)
        val store = mutableMapOf(channel.key to format)
        val events = mutableListOf<String>()
        fun write(ordinary: Boolean) {
            store.writeFluidPresentationModifier(
                channel, ordinary, format, { it }, { existing ->
                    assertTrue(existing === format)
                    events += "update"
                },
                { key ->
                    assertEquals(null, store[key])
                    events += "cancel $key"
                },
                { key -> events += "create $key" },
            )
        }
        write(ordinary = true)
        assertEquals(listOf("update"), events)
        for (old in listOf(FluidModifierFormat(2, false), FluidModifierFormat(1, true))) {
            store[channel.key] = old
            events.clear()
            write(ordinary = true)
            assertEquals(listOf("cancel effect.height", "create effect.height"), events)
        }
        store[channel.key] = format
        events.clear()
        write(ordinary = false)
        assertEquals(listOf("cancel effect.height", "create effect.height"), events)
    }

    @Test fun oldLensCompletionClearsTheKeyAndCapturesOnlyTheRemovedModifier() {
        val height = FluidPopupChannel.LensHeight
        val amount = FluidPopupChannel.LensAmount
        val old = Any()
        val replacement = Any()
        val amountModifier = Any()
        val store = mutableMapOf(height.key to old, amount.key to amountModifier)
        val queuedCreate = mutableSetOf(height.key, amount.key)
        val deferred = mutableListOf<Any>()
        val oldCompletion: (Boolean) -> Unit = {
            store.completeFluidPresentationModifier(
                height,
                { key ->
                    assertEquals(null, store[key])
                    queuedCreate.remove(key)
                },
                { false }, { error("This removal is queued") }, { modifier -> deferred += modifier },
            )
        }
        oldCompletion(false)
        store[height.key] = replacement
        assertEquals(listOf(old), deferred)
        assertTrue(store[height.key] === replacement)
        assertTrue(store[amount.key] === amountModifier)
        assertEquals(setOf(amount.key), queuedCreate)
        store.completeFluidPresentationModifier(
            amount, { queuedCreate.remove(it) }, { true },
            { assertTrue(it === amountModifier) }, { error("This removal is immediate") },
        )
        assertEquals(null, store[amount.key])
        assertTrue(queuedCreate.isEmpty())
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
