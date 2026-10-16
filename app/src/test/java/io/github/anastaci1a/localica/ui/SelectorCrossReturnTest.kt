package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class SelectorCrossReturnTest {
    @Test fun releasePreservesThePresentedFrameAndContainmentWithoutCrossOvershoot() {
        val rest = SelectorCrossFrame(300f, 292f)
        for (start in listOf(SelectorCrossFrame(288f, 286f), SelectorCrossFrame(300f, 296f), rest))
            for (velocity in listOf(-500f, 0f, 500f)) {
                val release = SelectorCrossReturn(start, rest, velocity, -velocity)
                assertEquals(start, release.frame(0f))
                var previous = 1f
                for (frame in 1..240) {
                    val progress = release.progress(frame / 120f)
                    assertTrue(progress <= previous)
                    val shape = release.frame(frame / 120f)
                    assertTrue(shape.lens <= shape.shell)
                    assertTrue(shape.shell in minOf(start.shell, rest.shell)..maxOf(start.shell, rest.shell))
                    assertTrue(shape.lens in minOf(start.lens, rest.lens)..maxOf(start.lens, rest.lens))
                    previous = progress
                }
                assertEquals(rest.shell, release.frame(2f).shell, .0001f)
                assertEquals(rest.lens, release.frame(2f).lens, .0001f)
            }
    }

    @Test fun outwardVelocityIsExplicitlyDissipatedWithoutChangingInitialPosition() {
        val start = SelectorCrossFrame(290f, 286f)
        val rest = SelectorCrossFrame(300f, 292f)
        val release = SelectorCrossReturn(start, rest, -100f, -100f)
        assertEquals(0f, release.progressVelocity, 0f)
        assertEquals(start, release.frame(0f))
    }

    @Test fun physicalDensityScalingPreservesTheReturnTrajectory() {
        val normal = SelectorCrossReturn(SelectorCrossFrame(60f, 58f), SelectorCrossFrame(64f, 56f), 3f, -2f)
        val doubled = SelectorCrossReturn(SelectorCrossFrame(120f, 116f), SelectorCrossFrame(128f, 112f), 6f, -4f)
        for (frame in 0..60) {
            val a = normal.frame(frame / 60f)
            val b = doubled.frame(frame / 60f)
            assertEquals(a.shell * 2f, b.shell, .0001f)
            assertEquals(a.lens * 2f, b.lens, .0001f)
        }
    }
    @Test fun fastInwardVelocityProjectsToTheCriticalCone() {
        val release = SelectorCrossReturn(SelectorCrossFrame(290f, 286f), SelectorCrossFrame(300f, 292f), 1000f, 1000f)
        assertEquals(-kotlin.math.sqrt(380f), release.progressVelocity, .0001f)
        val derivative = (release.progress(.0001f) - release.progress(0f)) / .0001f
        assertEquals(release.progressVelocity, derivative, .03f)
    }

    @Test fun regrabBlendStartsAtTheDisplayedContourAndKeepsContainment() {
        val visible = SelectorCrossFrame(294f, 290f, 21f, 24f)
        val rawHeld = SelectorCrossFrame(288f, 286f, 23f, 24f)
        val handoff = SelectorCrossReturn(visible, rawHeld)
        assertEquals(visible, handoff.frame(0f))
        for (frame in 0..120) {
            val value = rawHeld.toward(visible, handoff.progress(frame / 60f))
            assertTrue(value.lens <= value.shell)
        }
        assertEquals(rawHeld, handoff.frame(2f))
    }

    @Test fun contourOnlyReturnStartsAtTheCapturedCorner() {
        val start = SelectorCrossFrame(300f, 292f, 22f, 24f)
        val rest = SelectorCrossFrame(300f, 292f, 20f, 24f)
        val release = SelectorCrossReturn(start, rest)
        assertEquals(start, release.frame(0f))
        assertTrue(release.frame(.1f).corner in rest.corner..start.corner)
    }

}
