package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test

class CollectionMotionPixelsTest {
    @Test fun measuredHeaderEdgesRemainMonotoneThroughExpansionReversalAndNegativeOffsets() {
        val cases = listOf(
            Rect(9f, -131f, 713f, 211f) to Rect(0f, -184f, 720f, 296f),
            Rect(0f, 11f, 720f, 350f) to Rect(9f, 21f, 713f, 319f),
            Rect(2.625f, -80.125f, 713.25f, 211.875f) to Rect(.25f, -91.75f, 719.75f, 300.5f))
        fun Rect.model() = CollectionHeaderPinch.Rect(left, top, right, bottom)
        fun edges(r: Rect) = listOf(r.left, r.top, r.right, r.bottom)
        for ((source, target) in cases) {
            val header = CollectionHeaderPinch()
            assertTrue(header.begin(1, source.model()))
            assertTrue(header.updateDestination(1, target.model()))
            val frames = (0..1000).map { step ->
                val f = requireNotNull(header.frame(1, step / 1000f, null)).presented
                val exact = Rect(f.left, f.top, f.right, f.bottom)
                val box = motionPixelRect(exact)
                val visible = Rect(box.left + box.translationX, box.top + box.translationY,
                    box.left + box.translationX + box.width * box.scaleX,
                    box.top + box.translationY + box.height * box.scaleY)
                edges(exact).zip(edges(visible)).forEach { (a, b) -> assertEquals(a, b, .005f) }
                visible
            }
            for (sequence in listOf(frames, frames.reversed())) for (edge in 0..3) {
                val rising = edges(sequence.last())[edge] >= edges(sequence.first())[edge]
                sequence.zipWithNext().forEach { (a, b) ->
                    assertTrue("edge=$edge $a -> $b", if (rising) edges(b)[edge] >= edges(a)[edge] - .005f
                        else edges(b)[edge] <= edges(a)[edge] + .005f)
                }
            }
            edges(motionSnap(source)).zip(edges(frames.first())).forEach { (a, b) -> assertEquals(a, b, .005f) }
            edges(motionSnap(target)).zip(edges(frames.last())).forEach { (a, b) -> assertEquals(a, b, .005f) }
            for (endpoint in listOf(source, target).filter { r -> edges(r).all { it == kotlin.math.round(it) } }) {
                val box = motionPixelRect(endpoint)
                assertEquals(0f, box.translationX, 0f); assertEquals(0f, box.translationY, 0f)
                assertEquals(1f, box.scaleX, 0f); assertEquals(1f, box.scaleY, 0f)
            }
        }
    }

    @Test fun fractionalCenteredArtworkUsesSymmetricEdgesRatherThanRoundedWidth() {
        val rect = Rect(159.6f, 40.25f, 560.4f, 440.65f)
        val box = motionPixelRect(rect)
        assertEquals(160, box.left); assertEquals(560, box.right)
        assertEquals(40, box.top); assertEquals(441, box.bottom)
        assertEquals("Both horizontal margins agree", box.left, 720 - box.right)
        val integer = motionPixelRect(Rect(160f, 40f, 560f, 440f))
        assertEquals(MotionPixelRect(160, 40, 560, 440, 0f, 0f, 1f, 1f), integer)
    }
}
