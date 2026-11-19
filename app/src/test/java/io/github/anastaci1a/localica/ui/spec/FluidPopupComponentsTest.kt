package io.github.anastaci1a.localica.ui.spec

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
        assertEquals(1.5.toRawBits(), grows.blurIn.scaledDelay(supplied).toRawBits())
        assertEquals(1.25.toRawBits(), shrinks.blurIn.scaledDelay(supplied).toRawBits())
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
        val middle = FluidPopupSpringSettings(0.9, 0.55)
        val growing = FluidLensingSpec.requests(true, true, 25.0, 90.0, 0.5, 120.0, middle)
        val shrinking = FluidLensingSpec.requests(true, false, 25.0, 90.0, 0.6, 120.0, middle)
        assertEquals(listOf(120.0, 90.0, null), growing.map { it.dimension })
        assertEquals(listOf(90.0, 25.0, null), shrinking.map { it.dimension })
        assertEquals(listOf(1.0, 1.0, 0.0), growing.map { it.gain })
        assertEquals(listOf(0.0, 0.0, 0.12), growing.map { it.timing.storedDelay })
        assertEquals(listOf(0.0, 0.0, 0.1), shrinking.map { it.timing.storedDelay })
        assertEquals(listOf(0.2, 0.55, 0.3), growing.map { it.timing.spring.response })
        assertEquals(listOf(0.2, 0.55, 0.4), shrinking.map { it.timing.spring.response })
        assertEquals(listOf(1.0, 0.9, 1.0), growing.map { it.timing.spring.dampingRatio })
        assertTrue(growing[1].timing.spring === middle)
        val content = requireNotNull(FluidContentDefaults.blurSequence(growing = true))
        assertEquals(content.blurIn.scaledDelay(FluidContentDefaults).toRawBits(), growing[2].timing.scaledDelay(FluidContentDefaults).toRawBits())
        assertTrue(FluidLensingSpec.requests(false, true, 25.0, 90.0, 0.5, 120.0, middle).isEmpty())
        assertEquals(15.0, FluidLensingSpec.shortDimension(1.5, 40.0, 10.0), 0.0)
        assertEquals(60.0, FluidLensingSpec.shortDimension(1.5, 40.0, Double.NaN), 0.0)
        assertEquals((-0.0).toRawBits(), FluidLensingSpec.shortDimension(1.0, -0.0, 0.0).toRawBits())
    }

    @Test fun lensingSetupKeepsFilterOrderAndTracksByIdentity() {
        assertEquals(listOf(FluidLensingSpec.Filter.GaussianBlur, FluidLensingSpec.Filter.DisplacementMap), FluidLensingSpec.filterOrder)
        assertEquals(listOf("bounds", "position", "cornerRadius", "cornerRadii", "cornerCurve", "hidden", "meshTransform"), FluidLensingSpec.trackedProperties)
        assertEquals(0.0.toRawBits(), FluidLensingSpec.trackStoredDelay.toRawBits())
        assertTrue(FluidLensingSpec.trackedHitTestsAsFill)

        data class Layer(val name: String)
        val existing = Layer("same")
        val new = Layer("same")
        val other = Layer("other")
        val additions = FluidLensingSpec.newTrackedSources(listOf(existing, new, new, other), listOf(existing))
        assertEquals(2, additions.size)
        assertTrue(additions[0] === new)
        assertTrue(additions[1] === other)
    }

    @Test fun displacementSelectsTheFirstExactNameAndPrefersExplicitImages() {
        val names = listOf(byteArrayOf(97), byteArrayOf(97, 0), byteArrayOf(97, 0))
        val first = FluidLensingSpec.namedSource(names, byteArrayOf(97, 0)) { it }
        assertTrue(first === names[1])
        assertEquals(null, FluidLensingSpec.namedSource(names, null) { error("Name lookup without a key") })
        assertEquals(null, FluidLensingSpec.displacementSource(false, "image") { error("No primary surface") })
        assertEquals("image", FluidLensingSpec.displacementSource(true, "image") { error("Image takes precedence") })
        assertEquals("child", FluidLensingSpec.displacementSource(true, null) { "child" })
        assertEquals(44, FluidLensingSpec.displacementFunction)
        assertEquals(1, FluidLensingSpec.namedSourceTexture)
    }

    @Test fun displacementUniformsPackTheOrderedLanesAndOptionalOffset() {
        val transform = doubleArrayOf(2.0, 3.0, 99.0, 99.0, 5.0, 7.0)
        val p = FluidLensingSpec.amountUniform(transform, 16, 8, 0.5f, -2.0)
        assertEquals(listOf(-0.125f, -0.625f, -0.1875f, -0.875f).map(Float::toRawBits), p.map(Float::toRawBits))
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

    @Test fun throughCompletionRunsDismissalCallbackBeforeClearingRetainedContent() {
        val release = listOf(
            FluidTerminalSpec.Request.ReleaseSharedPortals,
            FluidTerminalSpec.Request.ClearMorphables,
            FluidTerminalSpec.Request.ClearMorphAnimations,
            FluidTerminalSpec.Request.ClearHidingAssertions,
        )
        assertEquals(listOf(
            FluidTerminalSpec.Request.TearDownPlatter,
            FluidTerminalSpec.Request.RemovePlatterContainer,
            FluidTerminalSpec.Request.CompleteAlongside,
        ) + release, FluidTerminalSpec.completeGroup(dismissing = true, hasCallback = true, hasAlongside = true))
        assertEquals(listOf(FluidTerminalSpec.Request.CompleteAlongside) + release,
            FluidTerminalSpec.completeGroup(dismissing = false, hasCallback = true, hasAlongside = true))
        assertEquals(release, FluidTerminalSpec.completeGroup(dismissing = true, hasCallback = false, hasAlongside = true))
        assertEquals(listOf(FluidTerminalSpec.Request.TearDownPlatter, FluidTerminalSpec.Request.RemovePlatterContainer) + release,
            FluidTerminalSpec.completeGroup(dismissing = true, hasCallback = true, hasAlongside = false))
    }

    @Test fun successfulAppearanceRestoresClippingAfterFocusedPortalsAreReleased() {
        assertEquals(listOf(
            FluidTerminalSpec.Request.FlashScrollIndicators, FluidTerminalSpec.Request.ClearFocusedPortaling,
            FluidTerminalSpec.Request.SuspendClipping, FluidTerminalSpec.Request.ReleaseFocusedPortals,
            FluidTerminalSpec.Request.RestoreClipping,
        ), FluidTerminalSpec.completeAppearance(success = true, dismissing = false, menuPresent = true, focusedPortaling = true))
        assertEquals(listOf(FluidTerminalSpec.Request.FlashScrollIndicators, FluidTerminalSpec.Request.RestoreClipping),
            FluidTerminalSpec.completeAppearance(success = true, dismissing = false, menuPresent = true, focusedPortaling = false))
        assertEquals(listOf(FluidTerminalSpec.Request.FlashScrollIndicators),
            FluidTerminalSpec.completeAppearance(success = true, dismissing = false, menuPresent = true, focusedPortaling = null))
        assertTrue(FluidTerminalSpec.completeAppearance(false, false, true, true).isEmpty())
        assertTrue(FluidTerminalSpec.completeAppearance(true, true, true, true).isEmpty())
        assertTrue(FluidTerminalSpec.completeAppearance(true, false, false, true).isEmpty())
    }

    @Test fun guardedThroughSkipsBothImmediateUnhideSites() {
        assertEquals(false to false, FluidTerminalSpec.immediateUnhideSites(true, true, true, true))
        assertEquals(true to false, FluidTerminalSpec.immediateUnhideSites(false, true, true, false))
        assertEquals(false to true, FluidTerminalSpec.immediateUnhideSites(false, true, false, true))
        assertEquals(false to false, FluidTerminalSpec.immediateUnhideSites(false, false, true, false))
    }

    @Test fun displacementConsumesGaussianColorOnlyWhenThatNodeEmits() {
        fun color(gaussianEmits: Boolean) = FluidLensingSpec.colorChain("content") { filter, input ->
            when (filter) {
                FluidLensingSpec.Filter.GaussianBlur -> if (gaussianEmits) "blur(" + input + ")" else null
                FluidLensingSpec.Filter.DisplacementMap -> "warp(" + input + ")"
            }
        }
        assertEquals("warp(blur(content))", color(gaussianEmits = true))
        assertEquals("warp(content)", color(gaussianEmits = false))
    }

    @Test fun namedMapQuadKeepsCoordinatePairsAndFusedRounding() {
        val quad = FluidLensingSpec.namedMapQuad(10, -4, 8, 6, 4, 3, 0.5f, 1f, Math::fma)
        assertEquals(listOf(
            FluidLensingSpec.Vertex(0f, 0f, 16f, -11f),
            FluidLensingSpec.Vertex(8f, 0f, 32f, -11f),
            FluidLensingSpec.Vertex(8f, 6f, 32f, 1f),
            FluidLensingSpec.Vertex(0f, 6f, 16f, 1f),
        ), quad)
        val cancellation = FluidLensingSpec.namedMapQuad(
            8_388_609, 0, 1, 1, 8_388_610, 0, 1f, Math.nextUp(1f), Math::fma,
        )
        assertEquals(0x34000000, cancellation[0].mapX.toRawBits())
        val wrapped = FluidLensingSpec.namedMapQuad(Int.MAX_VALUE - 3, 0, 8, 1, 0, 0, 1f, 1f, Math::fma)
        assertEquals(Int.MIN_VALUE.toFloat().toRawBits(), wrapped[1].mapX.toRawBits())
    }
}
