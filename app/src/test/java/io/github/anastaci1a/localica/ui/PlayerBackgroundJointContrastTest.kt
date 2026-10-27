package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor
import kotlin.math.pow

/** Pure metadata/identity contracts. These do not acknowledge native drawing or image preparation. */
class PlayerBackgroundJointContrastTest {
    private fun candidate(raw: Int, tone: GlassTone): BackgroundRgb =
        BackgroundPixels(1, 1, intArrayOf(raw)).at(.5f, .5f, IntArray(256) { tone.byteChannel(it) })

    private fun choice(raw: Int, trim: Float = .5f, weight: Float = 2f): BackgroundJointContrast =
        requireNotNull(backgroundJointContrast(
            listOf(listOf(candidate(raw, GlassTone(trim, weight, false))) to 1f),
            listOf(listOf(candidate(raw, GlassTone(trim, weight, true))) to 1f), listOf(1f)))

    @Test fun hypotheticalJointToneMathIsDistinctFromComparingOnePaintedBacking() {
        val black = choice(0xff000000.toInt())
        val white = choice(-1)
        assertTrue(black.lightInk)
        assertFalse(white.lightInk)
        assertEquals(0.0, black.white.luminance, 0.0)
        assertEquals(1.0, white.black.luminance, 0.0)
        assertFalse("The old classifier sees lifted black and chooses black again",
            backgroundLightInk(listOf(listOf(candidate(0xff000000.toInt(), GlassTone(.5f, 2f, false))) to 1f), listOf(1f)))
        assertTrue(choice(0xff000000.toInt(), 0f).lightInk)
        assertFalse(choice(-1, 0f).lightInk)
    }

    @Test fun singleRenderedBackingChoosesTheSameInkRegardlessOfPriorPublication() {
        for (initial in listOf(false, true)) for (raw in listOf(0xff000000.toInt(), -1, 0xff808080.toInt())) {
            val state = PlayerBackgroundPolarityState()
            state.current(initial)
            val rendered = candidate(raw, GlassTone(.5f, 2f, false))
            val expected = requireNotNull(backgroundSingleContrast(listOf(listOf(rendered) to 1f), listOf(1f)))
            state.unavailable(PlayerBackgroundUnavailable.Preparing)
            assertEquals(initial, state.decision.lightInk)
            assertEquals(PlayerBackgroundConfidence.Stale, state.decision.confidence)
            state.current(expected.lightInk)
            assertEquals(expected.lightInk, state.decision.lightInk)
            assertEquals(PlayerBackgroundConfidence.Current, state.decision.confidence)
            state.unavailable(PlayerBackgroundUnavailable.Transitioning)
            assertEquals(expected.lightInk, state.decision.lightInk)
            assertEquals(PlayerBackgroundConfidence.Stale, state.decision.confidence)
        }
        assertFalse("A lifted black backing is compared as painted, not as a darkened alternative",
            requireNotNull(backgroundSingleContrast(listOf(listOf(candidate(0xff000000.toInt(),
                GlassTone(.5f, 2f, false))) to 1f), listOf(1f))).lightInk)
        assertTrue(requireNotNull(backgroundSingleContrast(listOf(listOf(BackgroundRgb.Black) to 1f), listOf(1f))).lightInk)
        assertNull(backgroundSingleContrast(emptyList(), listOf(1f)))
        assertNull(backgroundSingleContrast(listOf(listOf(BackgroundRgb.Black) to 1f), emptyList()))
    }

    @Test fun nonlinearToneMustBeQuantizedOnEachTexelBeforeBilinearFiltering() {
        val input = intArrayOf(0xff000000.toInt(), -1)
        val before = input.clone()
        val pixels = BackgroundPixels(2, 1, input)
        val tone = GlassTone(.5f, 2f, true)
        val actual = pixels.at(.5f, .5f, IntArray(256) { tone.byteChannel(it) })
        assertEquals(64f / 255f, actual.r, .000001f)
        assertEquals(actual.r, actual.g, 0f)
        assertEquals(actual.r, actual.b, 0f)
        val incorrectlyTonedAfterFiltering = tone.channel(pixels.at(.5f, .5f).r)
        assertEquals(.375f, incorrectlyTonedAfterFiltering, 0f)
        assertTrue(incorrectlyTonedAfterFiltering - actual.r > .12f)
        assertArrayEquals(before, input)
        assertEquals(0f, pixels.at(-2f, .5f, IntArray(256) { tone.byteChannel(it) }).r, 0f)
        assertEquals(128f / 255f, pixels.at(2f, .5f, IntArray(256) { tone.byteChannel(it) }).r, 0f)
    }

    @Test fun byteMappingMatchesIndependentRoundedEndpointEquationsAndPreservesUntonedInputs() {
        for (value in 0..255) {
            val white = floor(value - value.toDouble() * value / 510.0 + .5).toInt()
            val black = floor(value + (255.0 - value) * (255.0 - value) / 510.0 + .5).toInt()
            assertEquals(white, GlassTone(.5f, 2f, true).byteChannel(value))
            assertEquals(black, GlassTone(.5f, 2f, false).byteChannel(value))
            assertEquals(value, GlassTone(0f, 2f, false).byteChannel(value))
            assertEquals(value, GlassTone(0f, 2f, true).byteChannel(value))
        }
        val raw = BackgroundPixels(1, 1, intArrayOf(0xff204080.toInt()))
        assertEquals(BackgroundRgb(32f / 255f, 64f / 255f, 128f / 255f), raw.at(.5f, .5f))
        // Direct cover and grain use the original no-LUT path; candidate tone is opt-in per wash.
        assertNotEquals(raw.at(.5f, .5f), raw.at(.5f, .5f, IntArray(256) { GlassTone(.5f).byteChannel(it) }))
    }

    @Test fun candidateFilteringPreservesAlphaAndPremultipliesBeforeInterpolating() {
        val raw = BackgroundPixels(2, 1, intArrayOf(0x00ffffff, 0xff000000.toInt()))
        val white = raw.at(.5f, .5f, IntArray(256) { GlassTone(.5f, 2f, true).byteChannel(it) })
        assertEquals(.5f, white.a, 0f)
        assertEquals(0f, white.r, 0f)
        val black = raw.at(.5f, .5f, IntArray(256) { GlassTone(.5f, 2f, false).byteChannel(it) })
        assertEquals(.5f, black.a, 0f)
        assertEquals(128f / 255f, black.r, .000001f)
        val composed = black.over(BackgroundRgb(1f, 1f, 1f))
        assertEquals((128f / 255f + 1f) / 2f, composed.r, .000001f)
        assertEquals(1f, composed.a, 0f)
    }

    @Test fun bothCandidatesUseIdenticalRoleWeightsAndMeasuredAreasBeforeLuminance() {
        val dark = BackgroundRgb.Black
        val light = BackgroundRgb(1f, 1f, 1f)
        val result = requireNotNull(backgroundJointContrast(
            listOf(listOf(dark, light) to .25f, listOf(light, dark) to .75f),
            listOf(listOf(dark, dark) to .25f, listOf(dark, dark) to .75f), listOf(1f, 3f)))
        assertEquals(.375, result.black.red, 0.0)
        assertEquals(((.375 + .055) / 1.055).pow(2.4), result.black.luminance, .000000001)
        assertTrue(result.lightInk)
        val half = requireNotNull(backgroundJointContrast(listOf(listOf(dark, light) to 1f),
            listOf(listOf(dark, light) to 1f), listOf(1f, 1f)))
        assertEquals(.5, half.black.red, 0.0)
        assertEquals(((.5 + .055) / 1.055).pow(2.4), half.black.luminance, .000000001)
        assertNotEquals(.5, half.black.luminance, .01)
        // Normal Float weights may sum a few ulps above one; this is not excessive exposure.
        assertNotNull(backgroundJointContrast(listOf(listOf(light) to .2f, listOf(light) to .8f),
            listOf(listOf(light) to .2f, listOf(light) to .8f), listOf(1f)))
    }

    @Test fun exactContrastTieIsWhiteAndInvalidCandidateLuminanceCannotPublish() {
        assertEquals(true, jointBackgroundLightInk(.05, .475))
        assertEquals(false, jointBackgroundLightInk(.05, .475 + 1e-10))
        assertEquals(true, jointBackgroundLightInk(.05, .475 - 1e-10))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertNull(jointBackgroundLightInk(invalid, .1))
            assertNull(jointBackgroundLightInk(.1, invalid))
        }
    }

    @Test fun incompleteOrInvalidRoleMetadataFailsClosed() {
        val role = listOf(listOf(BackgroundRgb.Black) to 1f)
        assertNull(backgroundJointContrast(role, role, emptyList()))
        for (area in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(backgroundJointContrast(role, role, listOf(area)))
        assertNull(backgroundJointContrast(emptyList(), role, listOf(1f)))
        assertNull(backgroundJointContrast(role, listOf(emptyList<BackgroundRgb>() to 1f), listOf(1f)))
        for (weight in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, 1.1f))
            assertNull(backgroundJointContrast(listOf(role.single().first to weight), listOf(role.single().first to weight), listOf(1f)))
        assertNull(backgroundJointContrast(role, listOf(role.single().first to .5f), listOf(1f)))
        for (channel in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f))
            assertNull(backgroundJointContrast(listOf(listOf(BackgroundRgb(channel, 0f, 0f)) to 1f), role, listOf(1f)))
    }

    @Test fun readinessUsesExactSourceOutputAndToneReferencesNotValueOrDiagnosticIdentity() {
        data class EqualValue(val value: Int)
        val source = EqualValue(1)
        val output = EqualValue(2)
        val tone = GlassTone(.5f, 2f, true)
        val receipt = ToneSnapshotReceipt(source, output, tone)
        assertTrue(receipt.matches(source, output, tone.copy()))
        assertFalse(receipt.matches(EqualValue(1), output, tone))
        assertFalse(receipt.matches(source, EqualValue(2), tone))
        assertFalse(receipt.matches(source, output, tone.copy(lightInk = false)))
        assertFalse(receipt.matches(source, output, tone.copy(trim = .25f)))
        assertFalse(receipt.matches(source, output, tone.copy(weight = 1f)))
        assertFalse(ToneSnapshotReceipt(source, output, tone, custom = true).matches(source, output, tone))
        assertFalse(ToneSnapshotReceipt(source, source, null).matches(source, source, tone))
        assertTrue(ToneSnapshotReceipt(source, source, null).matches(source, source, tone.copy(trim = 0f)))
        assertFalse(ToneSnapshotReceipt(source, output, null).matches(source, output, tone.copy(trim = 0f)))
    }

    @Test fun obsoletePublicationCannotCertifyAReplacementEvenWhenItsToneChoiceIsEqual() {
        val publication = PlayerBackgroundPublication()
        val old = publication.begin(PlayerBackgroundUnavailable.Preparing)
        val current = publication.begin(PlayerBackgroundUnavailable.Preparing)
        val choice = choice(0xff000000.toInt()).lightInk
        assertTrue(publication.publish(current, choice))
        assertFalse(publication.publish(old, choice))
        publication.begin(PlayerBackgroundUnavailable.FrozenSampleUnavailable)
        assertFalse(publication.publish(current, choice))
        assertEquals(PlayerBackgroundConfidence.Stale, publication.decision.confidence)
    }
}
