package io.github.anastaci1a.localica.ui

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class SelectorCrossReturnTest {
    private val rest = SelectorReturnFrame(360f, 64f, 82f, 56f, 4f, 28f, 32f, 0f)
    private val held = SelectorReturnFrame(364f, 56f, 70f, 56f, 0f, 28f, 28f, 0f)
    private val limits = SelectorReturnLimits(4f, 4f, 1f)
    private fun near(a: Float, b: Float, tolerance: Float = .001f) = assertEquals(a, b, tolerance)
    private fun SelectorReturnFrame.scaled(scale: Float) = SelectorReturnFrame.from(values().map { it * scale }.toFloatArray())

    @Test fun releaseCapturesAllDisplayedDimensionsGapAndUniformCorners() {
        val release = SelectorCrossReturn(held, rest, limits)
        assertEquals(held, release.frame)
        assertEquals(held, release.advance(0f))
        val next = release.advance(.00001f)
        held.values().zip(next.values()).forEach { (a, b) -> near(a, b, .0001f) }
    }

    @Test fun shellAndLensShareOneSignedOppositePhaseModeWithoutAxisPlateaus() {
        val release = SelectorCrossReturn(held, rest, limits)
        var negative = false; var positiveAfterNegative = false
        repeat(600) { index ->
            val frame = release.advance(1f / 120f, 8f)
            near(1f, release.physicalScale)
            assertTrue((frame.shellTravel - rest.shellTravel) * (frame.shellCross - rest.shellCross) <= .0001f)
            if (frame.shellTravel < rest.shellTravel - .01f) negative = true
            if (negative && frame.shellTravel > rest.shellTravel + .01f) positiveAfterNegative = true
            // The captured contour residual has disappeared; the same signed scalar
            // must now drive both physical footprints, with no separate axis clock.
            if (index > 120 && !release.settled) {
                assertTrue((frame.lensTravel - rest.lensTravel) * (frame.lensCross - rest.lensCross) <= .001f)
                near(frame.shellTravel - rest.shellTravel, frame.lensTravel - rest.lensTravel, .001f)
                near(-2f * (frame.shellTravel - rest.shellTravel), frame.shellCross - rest.shellCross, .001f)
            }
            assertTrue(frame.lensCross <= frame.shellCross)
            near((frame.shellCross - frame.lensCross) / 2f, frame.gap)
            near(frame.outerCorner - frame.corner, frame.gap)
        }
        assertTrue(negative); assertTrue(positiveAfterNegative)
        assertEquals(rest, release.frame)
    }

    @Test fun dampingExtremesRemainBoundedAndEventuallyReturnExactly() {
        for (damping in listOf(2f, 8f, 40f)) {
            val release = SelectorCrossReturn(held, rest, limits)
            var rebounds = false
            repeat(1800) {
                val frame = release.advance(1f / 120f, damping)
                rebounds = rebounds || frame.shellTravel < rest.shellTravel - .01f
                assertTrue(abs(frame.shellTravel - rest.shellTravel) <= 4.001f)
                assertTrue(abs(frame.shellCross - rest.shellCross) <= 8.001f)
                assertTrue(abs(frame.lensCross - rest.lensCross) <= 6.001f)
                assertTrue(frame.lensCross <= frame.shellCross)
                assertTrue(frame.corner * 2f <= minOf(frame.lensTravel, frame.lensCross) + .001f)
                near((frame.shellCross - frame.lensCross) / 2f, frame.gap)
            }
            assertEquals(damping < 40f, rebounds)
            assertTrue(release.settled)
            assertEquals(rest, release.frame)
        }
    }

    @Test fun densityAndTrackLengthDoNotChangeThePhysicalModeOrTiming() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f)) for (travel in listOf(180f, 360f, 1200f)) {
            val a = SelectorCrossReturn(held, rest, limits)
            val b = SelectorCrossReturn(held.copy(shellTravel = travel + 4f).scaled(density),
                rest.copy(shellTravel = travel).scaled(density), SelectorReturnLimits(4f * density, 4f * density, 1f))
            repeat(720) {
                val x = a.advance(1f / 120f, 2f); val y = b.advance(1f / 120f, 2f)
                near(x.shellTravel - rest.shellTravel, y.shellTravel / density - travel)
                near(x.shellCross, y.shellCross / density)
                near(x.lensTravel, y.lensTravel / density)
                near(x.lensCross, y.lensCross / density)
                near(x.gap, y.gap / density)
            }
        }
    }

    @Test fun regrabAndReversalBridgeFromTheShownFrameToTheLiveHeldTarget() {
        val release = SelectorCrossReturn(held, rest, limits)
        val displayed = release.advance(.14f)
        var raw = held
        assertEquals(displayed, displayed.toward(raw, 1f - selectorCriticalResidual(0f)))
        for (index in 1..120) {
            if (index == 20) raw = held.copy(shellStart = -4f)
            val value = displayed.toward(raw, 1f - selectorCriticalResidual(index / 120f))
            assertTrue(value.lensCross <= value.shellCross)
            assertTrue(value.shellStart in -4f..0f)
        }
        assertEquals(raw, displayed.toward(raw, 1f))
        assertEquals(raw, SelectorCrossReturn(raw, rest, limits.copy(wall = -1f)).frame)
    }

    @Test fun roundedChoiceInsetFitsTheFirstReleaseFrameAtFractionalDensity() {
        val density = 2.625f
        val inset = 11f // Existing choice layout rounds 4 dp before summing dimensions.
        val base = SelectorReturnFrame(400f, 160f, 137f, 160f - 2f * inset, inset, 52f, 63f, 0f)
        val shown = base.copy(shellTravel = 400f + 4f * density, shellCross = base.lensCross,
            lensTravel = 120f, gap = 0f, outerCorner = 52f)
        val physical = SelectorReturnLimits(4f * density, inset, 1f)
        near(inset, physical.crossClearance)
        val release = SelectorCrossReturn(shown, base, physical)
        assertEquals(shown, release.frame)
        val first = release.advance(.00001f)
        shown.values().zip(first.values()).forEach { (a, b) -> near(a, b, .0001f) }
        repeat(1200) {
            val frame = release.advance(1f / 120f, 2f)
            assertTrue(abs(frame.shellTravel - base.shellTravel) <= 4f * density + .001f)
            assertTrue(abs(frame.shellCross - base.shellCross) <= 2f * physical.crossClearance + .001f)
            assertTrue(abs(frame.lensCross - base.lensCross) <= 6f * density + .001f)
        }
    }

    @Test fun higherDampingFirstLobeRemainsResolvedAtFasterSpeedAndFractionalDensity() {
        for (density in listOf(1.75f, 2.625f)) for (vertical in listOf(false, true)) {
            val inset = if (density == 1.75f) 7f else 11f
            val cell = if (density == 1.75f) 91f else 137f
            val width = if (density == 1.75f) 455f else 682f
            val travel = if (vertical) 3f * cell + 2f * inset else width
            val cross = if (vertical) width else cell + 2f * inset
            val physical = SelectorReturnLimits(4f * density, inset, 1f)
            val geometry = SelectorTrackGeometry(travel, cross, .04f, 1f,
                maximumExtension = physical.extension, heldCrossInset = inset)
            val insets = SelectorTrackInsets(inset, 0f, inset)
            val raw = geometry.lens(2f, 3, insets, vertical, false,
                GlassShape(-.22f, .24f * kotlin.math.tanh((-.24f - .22f * .3f) / .24f)),
                24f * density - inset)
            val base = geometry.restFrame(3, insets, vertical, 24f * density)
            val shown = raw.returnFrame(geometry, vertical)
            assertTrue(shown.lensTravel < base.lensTravel)
            for (startupLagMs in listOf(0, 16, 32)) for (sampleMs in listOf(96, 112, 128, 144)) {
                val release = SelectorCrossReturn(shown, base, physical)
                val elapsed = (sampleMs - startupLagMs) / 1000f * (4f / 3f)
                val frame = release.advance(elapsed, damping = 20f)
                val crossDelta = frame.lensCross - base.lensCross
                val travelDelta = frame.lensTravel - base.lensTravel
                // Same first-lobe interval as the native 32 ms sampler. Both axes
                // must be visible above a whole pixel despite two startup frames.
                assertTrue("Cross lobe resolves at density $density, vertical=$vertical, lag=$startupLagMs, sample=$sampleMs", crossDelta > 1f)
                assertTrue("Travel is in the opposite phase", travelDelta < -1f)
                assertTrue(abs(crossDelta) <= 6f * density)
                assertTrue(abs(frame.shellTravel - base.shellTravel) <= physical.extension + .001f)
                assertTrue(abs(frame.shellCross - base.shellCross) <= 2f * physical.crossClearance + .001f)
                assertTrue(selectorReturnRoundedContains(frame))
            }
        }
    }

    @Test fun exactModeStepIsFrameRateIndependentAndLiveDampingKeepsState() {
        val whole = SelectorDampedMode(1f).advance(.2f, 8f)
        var split = SelectorDampedMode(1f)
        repeat(24) { split = split.advance(1f / 120f, 8f) }
        near(whole.value, split.value, .00001f); near(whole.velocity, split.velocity, .0001f)
        assertEquals(split, split.advance(0f, 40f))
        val tuned = split.advance(.000001f, 40f)
        near(split.value, tuned.value, .0001f)
        assertTrue(tuned.value.isFinite() && tuned.velocity.isFinite())
    }

    @Test fun elapsedTimeIsScaledOnlyByTheCaller() {
        val normal = SelectorCrossReturn(held, rest, limits)
        val faster = SelectorCrossReturn(held, rest, limits)
        repeat(60) {
            normal.advance(1f / 60f, 8f)
            val expected = normal.advance(1f / 60f, 8f)
            val actual = faster.advance(2f / 60f, 8f)
            expected.values().zip(actual.values()).forEach { (a, b) -> near(a, b) }
        }
    }

    @Test fun horizontalFullContactRetainsTheAuthoritativeExtentDespiteRectangleRounding() {
        val geometry = SelectorTrackGeometry(497f, 105f, .04f, -1f, maximumExtension = 497f * .04f)
        val lens = geometry.lens(0f, 3, SelectorTrackInsets(7f, 0f, 7f),
            vertical = false, rtl = false, GlassShape(), 42f)
        // This is the exact API36 regression: the two translated edges round independently.
        assertTrue(lens.bounds.height > geometry.crossExtent)
        assertEquals(Math.ulp(geometry.crossExtent), lens.bounds.height - geometry.crossExtent, 0f)
        val frame = lens.crossFrame(geometry)
        assertEquals(geometry.crossExtent, frame.lens, 0f)
        assertEquals(lens.bounds, lens.withCrossFrame(frame, geometry.cross, false).bounds)
    }

    @Test fun authoritativeCrossExtentMatchesDrawnBoundsAcrossAxesAndDensities() {
        for (density in listOf(1f, 1.75f, 2.625f, 3f))
            for (vertical in listOf(false, true)) for (rtl in listOf(false, true))
                for (stretch in listOf(0f, .013f, .04f)) for (wall in listOf(-1f, 1f)) {
                    val geometry = SelectorTrackGeometry(284f * density, 60f * density, stretch, wall)
                    val lens = geometry.lens(if (wall < 0f) 0f else 2f, 3,
                        SelectorTrackInsets(4f * density, 0f, 4f * density), vertical, rtl,
                        GlassShape(), 24f * density)
                    val frame = lens.crossFrame(geometry)
                    assertTrue(frame.lens <= frame.shell)
                    val drawn = lens.withCrossFrame(frame, geometry.cross, vertical)
                    val tolerance = 2f * Math.ulp(maxOf(geometry.travel, geometry.cross))
                    assertEquals(lens.bounds.left, drawn.bounds.left, tolerance)
                    assertEquals(lens.bounds.top, drawn.bounds.top, tolerance)
                    assertEquals(lens.bounds.right, drawn.bounds.right, tolerance)
                    assertEquals(lens.bounds.bottom, drawn.bounds.bottom, tolerance)
                    assertEquals(frame.lens, drawn.crossExtent, 0f)
                    val measured = if (vertical) drawn.bounds.width else drawn.bounds.height
                    assertEquals(frame.lens, measured, 2f * Math.ulp(frame.shell))
                    val start = if (vertical) drawn.bounds.left else drawn.bounds.top
                    val end = if (vertical) drawn.bounds.right else drawn.bounds.bottom
                    assertTrue(start >= geometry.crossStart - Math.ulp(frame.shell))
                    assertTrue(end <= geometry.crossStart + frame.shell + Math.ulp(frame.shell))
                }
    }

}
