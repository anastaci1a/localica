package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ShadowUnionTest {
    private fun individual(ring: Int, strength: Float, opacity: Float): Double {
        var result = 0.0
        for (index in 0..ring) result += (1 - result) * shadowRingAlpha(index, strength, opacity)
        return result
    }
    private fun union(bands: List<ShadowBand>, coveredRings: List<Int>): Double {
        var result = 0.0
        for (band in bands) if (band.ringIndices.indices.any { body ->
            band.ringIndices[body] >= 0 && coveredRings[body] >= band.ringIndices[body]
        }) result += (1 - result) * band.alpha
        return result
    }

    @Test fun weightedOverlapEqualsMaximumIndividualFieldAtEveryRingPair() {
        for (strength in listOf(.1f, .6f, 1f)) for (a in listOf(0f, .001f, .05f, .5f, 1f))
            for (b in listOf(.05f, .3f, 1f)) {
                val bands = weightedShadowBands(listOf(a, b), strength)
                for (i in -1..15) for (j in -1..15) assertEquals(
                    maxOf(individual(i, strength, a), individual(j, strength, b)),
                    union(bands, listOf(i, j)), 1e-7)
            }
    }

    @Test fun equalOpacityAndCoincidentBodiesDoNotStack() {
        for (opacity in listOf(.05f, .5f, 1f)) for (count in 1..4) {
            val bands = weightedShadowBands(List(count) { opacity }, 1f)
            assertEquals(15, bands.size)
            for (ring in -1..15) assertEquals(individual(ring, 1f, opacity),
                union(bands, List(count) { ring }), 1e-7)
        }
    }

    @Test fun independentFadeIsContinuousIncludingOpacityOrderCrossing() {
        var previous = -1.0
        for (step in 0..1000) {
            val opacity = step / 1000f
            val actual = union(weightedShadowBands(listOf(opacity, .5f, .2f), 1f), listOf(15, 8, 12))
            assertTrue(actual >= previous - 1e-7)
            if (previous >= 0) assertTrue(abs(actual - previous) < .001)
            previous = actual
        }
        assertTrue(weightedShadowBands(listOf(0f, 0f), 1f).isEmpty())
        assertTrue(weightedShadowBands(listOf(.5f, 1f), 0f).isEmpty())
    }

    @Test fun nearlyEqualAndRemovalThresholdWeightsRemainFiniteAndExact() {
        for (weights in listOf(listOf(1f, .99999994f), listOf(.5f, .50000006f),
            listOf(.000999f, .001001f), listOf(0f, 1f))) {
            val bands = weightedShadowBands(weights, 1f)
            for (i in -1..15) for (j in -1..15) assertEquals(
                maxOf(individual(i, 1f, weights[0]), individual(j, 1f, weights[1])),
                union(bands, listOf(i, j)), 1e-7)
            assertTrue(bands.all { it.alpha.isFinite() && it.alpha > 0 && it.alpha < 1 })
        }
    }

    @Test fun mixedGroupWorkIsBoundedByBodyAndRingCounts() {
        for (count in 2..4) {
            val bands = weightedShadowBands(List(count) { (it + 1f) / count }, 1f)
            assertTrue(bands.size <= 15 * count)
            assertTrue(bands.all { it.alpha.isFinite() && it.alpha > 0 && it.alpha < 1 })
            assertTrue(bands.all { it.ringIndices.size == count })
        }
    }

    @Test fun absoluteLevelsReplaceIncrementalFieldsWithoutChangingTheirValue() {
        val weights = listOf(listOf(1f, .5f, .25f), listOf(1f, .75f, .5f, .25f),
            listOf(1f, .18126415f, .43256444f, .21986524f), listOf(1f, .18f, .43f, .22f),
            listOf(.5f, .50000006f, .000999f, .001001f))
        for (values in weights) for (strength in listOf(.1f, .6f, 1f)) {
            val bands = weightedShadowBands(values, strength)
            for (first in -1..15) for (second in -1..15) for (third in -1..15) {
                val rings = listOf(first, second, third, (first + second + third + 3) % 17 - 1).take(values.size)
                val opacity = bands.lastOrNull { band -> band.ringIndices.indices.any { body ->
                    band.ringIndices[body] >= 0 && rings[body] >= band.ringIndices[body]
                } }?.opacity?.toDouble() ?: 0.0
                assertEquals(values.indices.maxOf { individual(rings[it], strength, values[it]) }, opacity, 1e-7)
                assertEquals(union(bands, rings), opacity, 1e-7)
            }
            assertTrue(bands.zipWithNext().all { (a, b) -> a.opacity <= b.opacity })
        }
    }

}
