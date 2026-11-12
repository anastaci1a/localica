package io.github.anastaci1a.localica.ui.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

class SurfacePresentationTest {
    private fun exact(expected: Float, actual: Float) = assertEquals(expected.toRawBits(), actual.toRawBits())

    @Test fun queueRevealKeepsLinearRampAndExactBoundaryBits() {
        val values = listOf(-.05f, 0f, Math.nextDown(.4f), .4f, Math.nextUp(.4f), .5f,
            Math.nextDown(1f), 1f, Math.nextUp(1f), 1.05f, Float.NaN)
        values.forEach { p -> exact(((p - .4f) / .6f).coerceIn(0f, 1f), queuePlayerAlpha(p)) }
    }

    @Test fun chromeRetainsMaximumThenRoundingInBothDirections() {
        val random = Random(893)
        repeat(2000) {
            val p = random.nextFloat() * 1.1f - .05f
            val visibility = random.nextFloat()
            val travel = random.nextFloat() * 4000f
            assertEquals(round(travel * maxOf(p, 1f - visibility)).toInt(),
                navigationChromeOffset(p, visibility, travel))
        }
    }

    @Test fun coverTransformsAreBitIdenticalForMainAndNeighborAndInactiveSwipe() {
        val random = Random(89311)
        repeat(2000) {
            val width = random.nextFloat() * 4000f + .001f
            val offset = (random.nextFloat() * 4f - 2f) * width
            val swipe = (random.nextFloat() * 4f - 2f) * width
            val alpha = random.nextFloat()
            for (neighbor in listOf(false, true)) for (active in listOf(false, true)) {
                val remaining = if (neighbor) abs(swipe) else width - abs(swipe)
                val expected = alpha * if (active) (remaining / (width * .08f)).coerceIn(0f, 1f) else 1f
                exact(expected, playerCoverAlpha(swipe, width, active, alpha, neighbor))
            }
            exact((offset / width).coerceIn(-1f, 1f) * -27f, playerCoverRotation(offset, width))
            val depth = abs(offset / width).coerceIn(0f, 1f)
            exact(1f - .035f * depth, playerCoverScale(offset, width))
            exact((abs(swipe) / width).coerceIn(0f, 1f), playerSwipeFraction(swipe, width))
        }
    }

    @Test fun playerOrderPreservesEndpointsOvershootAndLyricsParticipation() {
        val phases = listOf(-.05f, 0f, Math.nextUp(0f), .5f, Math.nextDown(1f), 1f, 1.05f)
        for (p in phases) for (mode in phases) {
            val moving = p > 0f && p < 1f
            exact(if (moving) -.5f else 0f, playerMetadataOrder(p))
            val lyrics = mode.coerceIn(0f, 1f) * p.coerceIn(0f, 1f)
            exact(if (moving || lyrics > 0f) 0f else -1f, playerArtworkOrder(p, mode))
        }
    }
    @Test fun titleInputBandPreservesRoundingInputsAndZeroHeightBounds() {
        val random = Random(893)
        repeat(2000) {
            val height = random.nextInt(0, 4000)
            val status = random.nextInt(-2000, 6000)
            val origin = random.nextInt(-2000, 6000)
            val seek = random.nextInt(-2000, 6000)
            val padding = random.nextInt(0, 100)
            val top = (status - origin).coerceIn(0, height)
            val bottom = minOf(height, seek - padding - origin).coerceAtLeast(top)
            val actual = playerTitleInputBand(status, origin, height, seek, padding)
            assertEquals(top, actual.top)
            assertEquals(bottom, actual.bottom)
            assertEquals(bottom - top, actual.height)
        }
    }

}
