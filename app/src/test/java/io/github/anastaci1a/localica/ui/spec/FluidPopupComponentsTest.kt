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
}
