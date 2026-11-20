package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class GlassShadowModelTest {
    @Test fun capsuleMassKeepsTheFixedDownshiftWhenSpreadDoubles() {
        fun field(spread: Float, shift: Float = 8f) =
            io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMassReference(
                496, 448, 144f, 176f, 352f, 272f, 48f, 2f, spread, shift)
        val fixed = field(1f)
        val wide = field(2f)
        assertEquals(3.40, fixed.expected.ratio, .05)
        assertEquals(1.81, wide.expected.ratio, .05)
        assertTrue(fixed.accepts(fixed.expected))
        assertFalse("A zero-shift field must fail the same acceptance", fixed.accepts(field(1f, 0f).expected))
        assertTrue("Wider support reduces directionality without cancelling the shift",
            fixed.expected.ratio > wide.expected.ratio && wide.expected.below > wide.expected.above)
        for (background in listOf(1.0, .5)) {
            fun near(shift: Float) = io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.shadowMassReference(
                208, 448, 0f, 176f, 208f, 272f, 48f, 2f, shiftDp = shift,
                code = 1.0 / (255 * background), bandDp = 16f)
            val reference = near(8f)
            assertEquals(2.792, reference.expected.ratio, .005)
            assertTrue("One-code envelope must reject a symmetric field on backing $background", reference.minimum > 1.5)
            assertFalse(reference.accepts(near(0f).expected))
        }
    }
    @Test fun geometryUsesOneDensityAndModelMultipliers() {
        // Independent physical pins for the reviewed 24dp radius, 48dp support and fixed 8dp shift.
        val pins = listOf(
            floatArrayOf(2f, 0f, 0f, 0f, 16f, 16f, 16f),
            floatArrayOf(2f, 0.5f, 24f, 48f, 16f, 64f, 64f),
            floatArrayOf(2f, 0.6000000238418579f, 28.80000114440918f, 57.60000228881836f, 16f, 73.60000610351562f, 74f),
            floatArrayOf(2f, 1f, 48f, 96f, 16f, 112f, 112f),
            floatArrayOf(2f, 2f, 96f, 192f, 16f, 208f, 128f),
            floatArrayOf(2.481250047683716f, 0f, 0f, 0f, 19.850000381469727f, 19.850000381469727f, 20f),
            floatArrayOf(2.481250047683716f, 0.5f, 29.775001525878906f, 59.55000305175781f, 19.850000381469727f, 79.4000015258789f, 80f),
            floatArrayOf(2.481250047683716f, 0.6000000238418579f, 35.730003356933594f, 71.46000671386719f, 19.850000381469727f, 91.31000518798828f, 92f),
            floatArrayOf(2.481250047683716f, 1f, 59.55000305175781f, 119.10000610351562f, 19.850000381469727f, 138.95001220703125f, 128f),
            floatArrayOf(2.481250047683716f, 2f, 119.10000610351562f, 238.20001220703125f, 19.850000381469727f, 258.0500183105469f, 128f),
            floatArrayOf(3f, 0f, 0f, 0f, 24f, 24f, 24f),
            floatArrayOf(3f, 0.5f, 36f, 72f, 24f, 96f, 96f),
            floatArrayOf(3f, 0.6000000238418579f, 43.20000076293945f, 86.4000015258789f, 24f, 110.4000015258789f, 111f),
            floatArrayOf(3f, 1f, 72f, 144f, 24f, 168f, 128f),
            floatArrayOf(3f, 2f, 144f, 288f, 24f, 312f, 128f)
        )
        for (pin in pins) {
            val model = GlassShadowModel(pin[0], pin[1])
            assertEquals(pin[2], model.radius, 0f)
            assertEquals(pin[3], model.reach, 0f)
            assertEquals(pin[4], model.shift, 0f)
            assertEquals(pin[5], model.inset, 0f)
            assertEquals(pin[6].toInt(), model.count)
            assertTrue(model.expansion(0) < pin[3])
            assertTrue(model.expansion(model.count - 1) > -pin[4])
            assertEquals(0, model.coverage(0, 1f))
        }
        assertEquals(.20f, GlassShadowFill, 0f)
    }
    @Test fun profilePinsHalfArithmeticAndSettingsScaleOnlyTheModel() {
        // Conditional Half polynomial samples: distance -8, 0, 24 and 48dp, fill .20, opacity .5.
        // These literal physical coordinates pass through the same sampler used by every drawn ring.
        val samples = listOf(
            floatArrayOf(2f, -16f, 0f, 48f, 96f),
            floatArrayOf(2.48125f, -19.85f, 0f, 59.55000305175781f, 119.10000610351562f),
            floatArrayOf(3f, -24f, 0f, 72f, 144f))
        val alphas = listOf(.06805419921875f, .04998779296875f, .0076141357421875f, 0f)
        for (sample in samples) for (index in alphas.indices) {
            val model = GlassShadowModel(sample[0], 1f)
            for (strength in listOf(.5f, 1f, 2f))
                assertEquals(alphas[index] * strength, model.alpha(sample[index + 1], strength), 0f)
        }
        for (density in listOf(2f, 2.48125f, 3f)) {
            for ((spread, alpha) in listOf(.5f to .082763671875f, .6f to .078369140625f,
                1f to .06805419921875f, 2f to .05926513671875f)) {
                val model = GlassShadowModel(density, spread)
                assertEquals(alpha, model.alpha(-model.shift, 1f), 0f)
            }
            val zero = GlassShadowModel(density, 0f)
            assertEquals(.0999755859375f, zero.alpha(-1f, 1f), 0f)
            assertEquals(0f, zero.alpha(0f, 1f), 0f)
            assertEquals(0f, zero.alpha(1f, 1f), 0f)
        }
    }
    @Test fun cappedRingCountDisclosesItsPhysicalWidth() {
        for ((density, count) in listOf(2f to 112, 2.48125f to 128, 3f to 128)) {
            val model = GlassShadowModel(density, 1f)
            assertEquals(count, model.count)
            assertTrue(model.expansion(0) < model.reach)
            assertTrue(model.expansion(count - 1) > -model.shift)
        }
    }
}
