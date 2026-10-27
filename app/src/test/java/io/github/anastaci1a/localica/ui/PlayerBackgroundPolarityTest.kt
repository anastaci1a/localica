package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class PlayerBackgroundPolarityTest {
    @Test fun coldUnavailableIsDeterministicAndNeverPretendsToBeCurrent() {
        val publication = PlayerBackgroundPublication()
        assertEquals(PlayerBackgroundDecision(), publication.decision)
        publication.begin(PlayerBackgroundUnavailable.FrozenSampleUnavailable)
        assertTrue(publication.decision.lightInk)
        assertEquals(PlayerBackgroundConfidence.Cold, publication.decision.confidence)
        assertEquals(PlayerBackgroundUnavailable.FrozenSampleUnavailable, publication.decision.unavailable)
    }

    @Test fun frozenRetainsLastVerifiedPolarityAsStaleThenLiveRecoveryPublishes() {
        val publication = PlayerBackgroundPublication()
        val first = publication.begin(PlayerBackgroundUnavailable.Preparing)
        assertTrue(publication.publish(first, false))
        publication.begin(PlayerBackgroundUnavailable.FrozenSampleUnavailable)
        assertFalse(publication.decision.lightInk)
        assertEquals(PlayerBackgroundConfidence.Stale, publication.decision.confidence)
        assertEquals(PlayerBackgroundUnavailable.FrozenSampleUnavailable, publication.decision.unavailable)
        val recovery = publication.begin(PlayerBackgroundUnavailable.Preparing)
        assertTrue(publication.publish(recovery, true))
        assertTrue(publication.decision.lightInk)
        assertEquals(PlayerBackgroundConfidence.Current, publication.decision.confidence)
        assertNull(publication.decision.unavailable)
    }

    @Test fun staleCompletionCannotOverwriteSuccessorOrUnavailableGeneration() {
        val publication = PlayerBackgroundPublication()
        val old = publication.begin(PlayerBackgroundUnavailable.Preparing)
        val current = publication.begin(PlayerBackgroundUnavailable.Preparing)
        assertTrue(publication.publish(current, false))
        assertFalse(publication.publish(old, true))
        publication.begin(PlayerBackgroundUnavailable.FrozenSampleUnavailable)
        assertFalse(publication.publish(current, true))
        assertFalse(publication.decision.lightInk)
        assertEquals(PlayerBackgroundConfidence.Stale, publication.decision.confidence)
    }

    @Test fun outgoingIncomingUseActualRgbWeightsNotTargetOrLuminanceAverage() {
        val black = listOf(BackgroundRgb.Black)
        val white = listOf(BackgroundRgb(1f, 1f, 1f))
        assertTrue(backgroundLightInk(listOf(black to .9f, white to .1f), listOf(1f)))
        assertFalse(backgroundLightInk(listOf(black to .1f, white to .9f), listOf(1f)))
        assertTrue("RGB is mixed before evaluating contrast, not endpoint luminances",
            backgroundLightInk(listOf(black to .55f, white to .45f), listOf(1f)))
        for (weight in listOf(0f, .2f, .5f, .8f, 1f)) {
            assertFalse("Same-cover fades keep their full weight", backgroundLightInk(listOf(white to weight, white to 1f - weight), listOf(1f)))
        }
    }

    @Test fun equalContrastHasADeterministicWhiteTieBreak() {
        val threshold = sqrt(.0525) - .05
        assertTrue(backgroundContrastLightInk(threshold))
        assertTrue(backgroundContrastLightInk(java.lang.Math.nextDown(threshold)))
        assertFalse(backgroundContrastLightInk(java.lang.Math.nextUp(threshold)))
    }

    @Test fun alreadyTonedHalfWhiteChoosesTheHigherContrastBlackInk() {
        assertFalse(backgroundLightInk(listOf(listOf(BackgroundRgb(.5f, .5f, .5f)) to 1f), listOf(1f)))
        assertTrue(backgroundLightInk(listOf(listOf(BackgroundRgb(.3f, .3f, .3f)) to 1f), listOf(1f)))
    }

    @Test fun polarityToneFeedbackStabilizesForBothStartingPolaritiesAndMaximumTrim() {
        for (raw in listOf(0f, .1f, .25f, .5f, .75f, 1f)) for (trim in listOf(0f, .25f, .5f))
            for (weight in listOf(1f, 2f)) for (initial in listOf(false, true)) {
                var ink = initial
                var flips = 0
                repeat(8) {
                    // The existing ordered tone endpoints; this test does not change GlassTone.
                    val displayed = if (ink) raw - trim * raw.pow(weight) else raw + trim * (1f - raw).pow(weight)
                    val next = backgroundLightInk(listOf(listOf(BackgroundRgb(displayed, displayed, displayed)) to 1f), listOf(1f))
                    if (next != ink) flips++
                    ink = next
                }
                assertTrue("Background/ink feedback must not oscillate: $raw/$trim/$weight/$initial", flips <= 1)
            }
    }

    @Test fun nativeBlurIsDistinctFromFrozenAndAlsoRequiresLiveRecovery() {
        val publication = PlayerBackgroundPublication()
        val token = publication.begin(PlayerBackgroundUnavailable.Preparing)
        publication.publish(token, false)
        publication.begin(PlayerBackgroundUnavailable.NativeBlurSampleUnavailable)
        assertFalse(publication.decision.lightInk)
        assertEquals(PlayerBackgroundConfidence.Stale, publication.decision.confidence)
        assertEquals(PlayerBackgroundUnavailable.NativeBlurSampleUnavailable, publication.decision.unavailable)
    }

    @Test fun sampleAreaComesFromClippedMeasuredControlsNotTheirEnclosingBottomBand() {
        val background = Rect(0f, 0f, 300f, 900f)
        val viewport = Rect(0f, 100f, 300f, 700f)
        val controls = listOf(Rect(30f, 80f, 270f, 130f), Rect(50f, 600f, 250f, 750f))
        assertEquals(listOf(Rect(30f, 100f, 270f, 130f), Rect(50f, 600f, 250f, 700f)),
            playerBackgroundFootprints(background, viewport, controls))
        val translated = controls.map { Rect(it.left + 8, it.top + 20, it.right + 8, it.bottom + 20) }
        assertEquals(Rect(38f, 100f, 278f, 150f), playerBackgroundFootprints(background, viewport, translated).first())
        assertTrue(playerBackgroundFootprints(null, viewport, controls).isEmpty())
    }

    @Test fun overlappingFootprintsCountTheirUnionAndRejectNonfiniteExtents() {
        val bounds = Rect(0f, 0f, 200f, 200f)
        val footprints = playerBackgroundFootprints(bounds, bounds, listOf(Rect(0f, 0f, 100f, 100f),
            Rect(50f, 50f, 150f, 150f), Rect(Float.NaN, 0f, 1f, 1f)))
        assertEquals(17500f, footprints.sumOf { (it.width * it.height).toDouble() }.toFloat(), 0f)
        for (a in footprints.indices) for (b in a + 1 until footprints.size) {
            val overlap = footprints[a].intersect(footprints[b])
            assertTrue(overlap.width <= 0f || overlap.height <= 0f)
        }
    }

    @Test fun boundedSoftwareMetadataPreservesBorderAndTextureClamp() {
        val pixels = BackgroundPixels(2, 4, intArrayOf(-1, -1, -1, -1, -1, -1, 0xff000000.toInt(), 0xff000000.toInt()))
        assertEquals(1f, pixels.at(.5f, .2f).r, 0f)
        assertEquals(0f, pixels.at(.5f, .95f).r, 0f)
        assertEquals(0f, pixels.at(5f, 2f).r, 0f)
        assertEquals(.5f, pixels.at(.5f, .75f).r, .0001f)
    }
}
