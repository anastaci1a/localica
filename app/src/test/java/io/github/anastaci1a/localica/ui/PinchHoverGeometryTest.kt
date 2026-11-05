package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.browse.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.compose.runtime.BroadcastFrameClock

class PinchHoverGeometryTest {
    @Test fun everyAdjacentStyleKeepsCapturedLiftAtRecognitionAndOrdinaryLiftAtHandoff() {
        val styles = ListSize.entries.map { BrowseStyle(listSize = it) } +
            (1..5).map { BrowseStyle(presentation = Presentation.Standard, itemsPerRow = it) }
        for (widthDp in listOf(320, 360, 411)) for (density in listOf(1f, 1.75f, 2.75f, 3f)) {
            for (rtl in listOf(false, true)) for (style in styles) for (outward in listOf(false, true)) {
                val target = style.zoomStep(outward, widthDp.toFloat(), true)
                if (target == style) continue
                val a = outline(style, widthDp, density, rtl)
                val b = outline(target, widthDp, density, rtl)
                for (phase in listOf(0f, .2f, 1f)) {
                    val captured = hoverGeometry(a, Rect(4f * density, 3f * density, 4f * density, 3f * density), phase)
                    // Reflow can temporarily report no room even though the source was visibly lifted.
                    fun frame(p: Float): HoverGeometry {
                        val rest = mediaItemOutlineLerp(a, b, p)
                        return interpolateHoverGeometry(captured, hoverGeometry(rest, Rect.Zero, phase), p)
                    }
                    val first = frame(0f)
                    assertEquals("$style -> $target/$density/$rtl/$phase source", captured.lifted, first.lifted)
                    assertEquals(captured.scale, first.scale, .000001f)
                    assertEquals(hoverGeometry(b, Rect.Zero, phase), frame(1f))
                    val forward = (0..100).map { frame(it / 100f) }
                    for (step in 100 downTo 0) {
                        val current = frame(step / 100f)
                        assertEquals("Reversal retraces the same shared geometry", forward[step], current)
                        assertTrue(current.scale.isFinite() && current.scale >= 1f)
                        assertEquals(current.rest.bounds.center, current.pivot)
                        assertEquals(current.rest.corner * current.scale, current.lifted.corner, .0001f)
                        if (phase == 0f) {
                            assertEquals(current.rest.bounds.left, current.lifted.bounds.left, .0001f)
                            assertEquals(current.rest.bounds.top, current.lifted.bounds.top, .0001f)
                            assertEquals(current.rest.bounds.right, current.lifted.bounds.right, .0001f)
                            assertEquals(current.rest.bounds.bottom, current.lifted.bounds.bottom, .0001f)
                        }
                    }
                }
            }
        }
    }

    @Test fun independentSelectionAnimationKeepsItsLivePhaseDuringPinch() {
        val rest = MediaItemOutline(Rect(20f, 40f, 320f, 140f), 12f)
        val room = Rect(8f, 4f, 8f, 4f)
        for (capturedPhase in listOf(0f, .1f, .5f, 1f)) {
            val source = hoverGeometry(rest, room, capturedPhase)
            for (phase in (0..20).map { it / 20f }) {
                val current = hoverGeometry(rest, Rect.Zero, phase)
                val actual = interpolateHoverGeometry(source, current, 0f)
                val expected = hoverGeometry(rest, room, phase)
                assertEquals(expected.scale, actual.scale, .00001f)
                assertEquals(phase, actual.progress, 0f)
                if (phase == 0f) for (p in listOf(0f, .3f, .8f, 1f)) {
                    assertEquals("Deselecting cannot leave an orphan lift", rest,
                        interpolateHoverGeometry(source, current, p).lifted)
                }
            }
        }
    }

    @Test fun liveViewportAndRetainedLocalCoordinatesResolveTheSameTransform() {
        val a = MediaItemOutline(Rect(0f, 0f, 96f, 140f), 12f)
        val b = MediaItemOutline(Rect(8f, 0f, 180f, 220f), 18f)
        val source = hoverGeometry(a, Rect(4f, 4f, 4f, 4f), 1f)
        for (offset in listOf(Offset(33.5f, -41.75f), Offset(-100f, 700f))) {
            for (step in 0..100) {
                val p = step / 100f
                val current = hoverGeometry(mediaItemOutlineLerp(a, b, p), Rect(2f, 3f, 2f, 3f), 1f)
                val local = interpolateHoverGeometry(source, current, p).translate(offset)
                val viewport = interpolateHoverGeometry(source, current.translate(offset), p)
                assertEquals(local.scale, viewport.scale, .000001f)
                assertEquals(local.lifted.bounds.left, viewport.lifted.bounds.left, .0001f)
                assertEquals(local.lifted.bounds.top, viewport.lifted.bounds.top, .0001f)
                assertEquals(local.lifted.bounds.right, viewport.lifted.bounds.right, .0001f)
                assertEquals(local.lifted.bounds.bottom, viewport.lifted.bounds.bottom, .0001f)
            }
        }
    }

    @Test fun retiredGeometryClocksKeepTheirTerminalValueUntilOldLayoutRetires() = runBlocking {
        for (terminal in listOf(0f, 1f)) {
            val motion = BrowsePinchMotion()
            motion.progress.snapTo(terminal)
            val retired = motion.progress
            motion.beginGeometry()
            assertNotSame(retired, motion.progress)
            assertEquals(0f, motion.progress.value, 0f)
            motion.progress.snapTo(.3f)
            assertEquals("A new transaction cannot rewind the preceding layout", terminal, retired.value, 0f)
        }
    }

    @Test fun caughtUpReversalPublishesBeforeTheNextLayoutFrame() = runBlocking {
        val clock = BroadcastFrameClock()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined + clock)
        val motion = BrowsePinchMotion()
        try {
            motion.beginGeometry()
            motion.held = true
            motion.destinationReady = true
            motion.fingerProgress = .086f
            motion.followFingers(scope)
            repeat(9) { clock.sendFrame(1_000_000_000L + it * 16_666_667L) }
            assertEquals(.086f, motion.progress.value, .000001f)
            motion.fingerProgress = 0f
            motion.followFingers(scope)
            assertEquals("Layout and drawing must enter the next frame with the same reversal value",
                0f, motion.progress.value, .000001f)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun fingerCatchUpUsesEveryLatestTargetAndKeepsItsOwnDuration() = runBlocking {
        for (scale in listOf(0f, .5f, 2f)) {
            val clock = BroadcastFrameClock()
            val owner = Job()
            val duration = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = scale }
            val scope = CoroutineScope(owner + Dispatchers.Unconfined + clock + duration)
            val motion = BrowsePinchMotion()
            try {
                motion.beginGeometry()
                motion.held = true; motion.destinationReady = true; motion.fingerProgress = .05f
                motion.followFingers(scope)
                clock.sendFrame(1_000_000_000L)
                assertEquals(0f, motion.progress.value, 0f)
                motion.fingerProgress = .15f
                motion.followFingers(scope)
                assertEquals("A changing target cannot skip the presented source", 0f, motion.progress.value, 0f)
                clock.sendFrame(1_016_666_667L)
                assertTrue(motion.progress.value in 0f..(.15f * .15f))
                repeat(7) { clock.sendFrame(1_000_000_000L + (it + 2) * 16_666_667L) }
                assertEquals("The latest finger position is reached over 100ms at scale $scale",
                    .15f, motion.progress.value, .000001f)
                motion.fingerProgress = 0f
                motion.followFingers(scope)
                assertEquals(0f, motion.progress.value, 0f)
            } finally { owner.cancelAndJoin() }
        }
    }

    @Test fun cancellingCatchUpKeepsRetiredClockAtItsPresentedValue() = runBlocking {
        val clock = BroadcastFrameClock()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined + clock)
        val motion = BrowsePinchMotion()
        try {
            motion.beginGeometry()
            motion.held = true; motion.destinationReady = true; motion.fingerProgress = .6f
            motion.followFingers(scope)
            clock.sendFrame(1_000_000_000L)
            clock.sendFrame(1_032_000_000L)
            val retired = motion.progress
            val shown = retired.value
            assertTrue(shown > 0f)
            motion.beginGeometry()
            motion.fingerProgress = 1f
            motion.followFingers(scope)
            clock.sendFrame(1_064_000_000L)
            clock.sendFrame(1_096_000_000L)
            assertEquals("A new finger target cannot change a retired clock", shown, retired.value, 0f)
            assertTrue(motion.progress.value > 0f)
        } finally { owner.cancelAndJoin() }
    }

    private fun outline(style: BrowseStyle, widthDp: Int, density: Float, rtl: Boolean): MediaItemOutline {
        val columns = galleryColumns(style.presentation, widthDp.toFloat(), style.itemsPerRow)
        val geometry = BrowseCellGeometry(columns)
        val cellWidth = geometry.cellWidth((widthDp * density).roundToInt(), density)
        val width = geometry.cardWidth(cellWidth, density)
        val metrics = BrowseItemMetrics(style, columns, geometry.contentWidth(cellWidth, density), density, false)
        val titleHeight = (metrics.titleLine * density * metrics.titleLines).roundToInt()
        val infoWidth = minOf(metrics.informationLimit, (40 * density).roundToInt())
        val infoHeight = (12 * density).roundToInt()
        val placement = metrics.place(titleHeight, infoWidth, infoHeight)
        return metrics.outline(placement, width, metrics.titleWidth(infoWidth), titleHeight,
            infoWidth, infoHeight, metrics.art > 0, rtl, density)
    }
}
