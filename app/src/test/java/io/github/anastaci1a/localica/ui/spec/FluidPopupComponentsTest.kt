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
}
