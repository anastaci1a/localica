package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class MaterialAverageCoverageTest {
    private fun plan(mapping: BackdropRegistration? = BackdropRegistration.Identity, corner: Float = 0f,
        width: Float = 100f, height: Float = 100f, extent: Size = Size(100f, 100f),
        material: Boolean = true, recorded: Boolean = true) =
        materialAverageCoverage(material, recorded, width, height, corner, extent, mapping)

    @Test fun fullCoverageHasExactly64RowMajorConsumerCenters() {
        val result = plan()
        assertEquals(MaterialAverageCoverageStatus.SAMPLED, result.status)
        assertEquals(64, result.validCount)
        assertEquals(64, result.samples.size)
        result.samples.forEachIndexed { index, sample ->
            assertEquals(Offset((index % 8 + .5f) * 12.5f, (index / 8 + .5f) * 12.5f), sample.sourcePosition)
            assertTrue(sample.valid)
        }
    }

    @Test fun partialAndRoundedCoverageExcludeOnlySamplesOutsideRecordedShape() {
        val translated = plan(BackdropRegistration.create(1f, 0f, 0f, 1f, 50f, 0f))
        assertEquals(32, translated.validCount)
        translated.samples.forEachIndexed { index, sample -> assertEquals(index % 8 < 4, sample.valid) }
        val rounded = plan(corner = 50f)
        assertEquals(64, rounded.samples.size)
        assertEquals(52, rounded.validCount)
        assertFalse(rounded.samples.first().valid)
        assertTrue(rounded.samples[3 * 8 + 3].valid)
        // Oversized finite radii use the same half-minimum clamp as the existing rounded bounds.
        assertEquals(rounded.samples, plan(corner = 100f).samples)
    }

    @Test fun scaleShearAndReversedBasisMapCurrentConsumerCoordinates() {
        val scaled = plan(BackdropRegistration.create(2f, 0f, 0f, 2f, 0f, 0f), extent = Size(50f, 50f))
        assertEquals(plan().samples, scaled.samples)
        val reversed = plan(BackdropRegistration.create(-1f, 0f, 0f, 1f, 100f, 0f))
        assertEquals(64, reversed.validCount)
        assertEquals(Offset(93.75f, 6.25f), reversed.samples.first().sourcePosition)
        val sheared = plan(BackdropRegistration.create(1f, .25f, .5f, 1f, 0f, 0f))
        assertTrue(sheared.validCount in 1..63)
        sheared.samples.forEachIndexed { index, sample ->
            val x = (index % 8 + .5f) * 12.5f
            val y = (index / 8 + .5f) * 12.5f
            val expected = Offset(x + .25f * y, .5f * x + y)
            assertEquals(expected, sample.sourcePosition)
            assertEquals(expected.x <= 100f && expected.y <= 100f, sample.valid)
        }
    }

    @Test fun invalidDimensionsRadiusAndExtentRejectWithoutPartialPlan() {
        val invalid = listOf(plan(width = 0f), plan(height = -1f), plan(width = Float.NaN),
            plan(height = Float.POSITIVE_INFINITY), plan(corner = -1f), plan(corner = Float.NaN),
            plan(extent = Size(0f, 10f)), plan(extent = Size(10f, Float.POSITIVE_INFINITY)),
            plan(width = Float.MAX_VALUE, height = Float.MAX_VALUE, corner = Float.MAX_VALUE))
        invalid.forEach {
            assertEquals(MaterialAverageCoverageStatus.INVALID_GEOMETRY, it.status)
            assertEquals(0, it.validCount)
            assertTrue(it.samples.isEmpty())
        }
    }

    @Test fun nullSingularAndOverflowMappingsRejectInsteadOfUsingIdentityOrPartialSamples() {
        assertNull(BackdropRegistration.create(1f, 1f, 1f, 1f, 0f, 0f))
        val invalid = listOf(plan(null), plan(BackdropRegistration.create(1f, 1f, 1f, 1f, 0f, 0f)),
            plan(BackdropRegistration.create(2f, 0f, 0f, 1f, 0f, 0f), extent = Size(Float.MAX_VALUE, 100f)))
        invalid.forEach {
            assertEquals(MaterialAverageCoverageStatus.INVALID_MAPPING, it.status)
            assertEquals(0, it.validCount)
            assertTrue(it.samples.isEmpty())
        }
    }

    @Test fun currentSourceAndProvenanceAreEvaluatedWithoutReusingEarlierEligibility() {
        assertEquals(64, plan().validCount)
        for (rejected in listOf(plan(material = false), plan(recorded = false), plan(material = false, recorded = false))) {
            assertEquals(MaterialAverageCoverageStatus.INVALID_PROVENANCE, rejected.status)
            assertTrue(rejected.samples.isEmpty())
        }
        assertEquals(32, plan(width = 50f).validCount)
        assertEquals(64, plan().validCount)
    }

    @Test fun tinyRealIntersectionCanRemainUnsampledWithoutClaimingNoOverlap() {
        val mapping = requireNotNull(BackdropRegistration.create(1f, 0f, 0f, 1f, 99.9f, 0f))
        // Consumer's left edge is inside the recorded rectangle; first grid center lies outside it.
        val overlappingEdge = mapping.map(Offset(0f, 50f))
        assertTrue(overlappingEdge.x < 100f && overlappingEdge.x > 0f)
        val result = plan(mapping)
        assertEquals(MaterialAverageCoverageStatus.UNSAMPLED, result.status)
        assertEquals(64, result.samples.size)
        assertEquals(0, result.validCount)
        assertTrue(result.samples.all { !it.valid })
    }

    @Test fun returnedSampleCollectionCannotBeMutatedOrAlterAnotherPlan() {
        val result = plan()
        val first = result.samples.first()
        try {
            (result.samples as MutableList<MaterialAverageSample>)[0] = MaterialAverageSample(Offset.Zero, false)
            fail("Sampling plan must not expose mutable storage")
        } catch (_: UnsupportedOperationException) { }
        assertEquals(first, result.samples.first())
        assertEquals(result.samples, plan().samples)
        assertEquals(64, result.validCount)
    }
}
