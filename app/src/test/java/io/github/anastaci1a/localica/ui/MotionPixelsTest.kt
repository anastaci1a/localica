package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.engine.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MotionPixelsTest {
    private fun MotionPixelRect.visible() = Rect(left + translationX, top + translationY,
        left + translationX + width * scaleX, top + translationY + height * scaleY)

    private fun assertMonotone(label: String, values: List<Float>) {
        val rising = values.last() >= values.first()
        values.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue("$label reverses by ${if (rising) a - b else b - a} px at sample $index: $a -> $b",
                if (rising) b >= a - .01f else b <= a + .01f)
        }
    }

    @Test fun wholePixelRectanglesAreUntransformed() {
        for (rect in listOf(Rect(0f, 0f, 64f, 64f), Rect(-12f, 7f, 300f, 41f), Rect(3f, 3f, 3f, 3f),
            Rect(63.99998f, 1.00001f, 128.00002f, 9f))) {
            val pixels = motionPixelRect(rect)
            assertEquals(0f, pixels.translationX, 0f); assertEquals(0f, pixels.translationY, 0f)
            assertEquals(1f, pixels.scaleX, 0f); assertEquals(1f, pixels.scaleY, 0f)
            assertEquals(Math.round(rect.left), pixels.left); assertEquals(Math.round(rect.right), pixels.right)
        }
    }

    @Test fun pinchPartSweepIsMonotoneAndExactAtBothEndpoints() {
        // Five-per-row cover toward a large list row and back, on fractional-density sizes.
        val cases = listOf(intArrayOf(9, 7, 63, 63, 16, 8, 56, 56), intArrayOf(9, 7, 63, 63, 21, 18, 140, 140),
            intArrayOf(16, 8, 56, 56, 6, 5, 191, 191), intArrayOf(80, 30, 210, 41, 12, 140, 97, 38))
        for (c in cases) {
            val frames = (0..1000).map { p ->
                motionPixelRect(browsePinchPartRect(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], p / 1000f))
            }
            for ((index, frame) in frames.withIndex()) {
                val exact = browsePinchPartRect(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], index / 1000f)
                val visible = frame.visible()
                for ((a, b) in listOf(exact.left to visible.left, exact.top to visible.top,
                    exact.right to visible.right, exact.bottom to visible.bottom))
                    assertEquals("Drawn edge follows the single float source", a, b, .005f)
            }
            val visible = frames.map { it.visible() }
            assertMonotone("left", visible.map { it.left }); assertMonotone("right", visible.map { it.right })
            assertMonotone("top", visible.map { it.top }); assertMonotone("bottom", visible.map { it.bottom })
            assertMonotone("width", visible.map { it.width }); assertMonotone("height", visible.map { it.height })
            // Endpoints land on the endpoint layout with no transform: rest pixels are unchanged.
            assertEquals(MotionPixelRect(c[0], c[1], c[0] + c[2], c[1] + c[3], 0f, 0f, 1f, 1f), frames.first())
            assertEquals(MotionPixelRect(c[4], c[5], c[4] + c[6], c[5] + c[7], 0f, 0f, 1f, 1f), frames.last())
        }
    }

    @Test fun mirroredPartsKeepTheRelativePlacementAtEndpoints() {
        val rect = browsePinchPartRect(9, 7, 63, 63, 16, 8, 56, 56, 1f).mirrored(400f)
        assertEquals(MotionPixelRect(400 - 16 - 56, 8, 400 - 16, 64, 0f, 0f, 1f, 1f), motionPixelRect(rect))
    }

    @Test fun playerSurfaceEdgesGrowMonotonicallyAndLandOnTheWindow() {
        for (density in listOf(2.625f, 2.75f, 3f, 3.5f)) for (pixels in listOf(1080 to 2400, 1440 to 3120, 720 to 1600)) {
            val d = Density(density)
            val width = (pixels.first / density).dp; val height = (pixels.second / density).dp
            val frames = (0..4000).map { step ->
                playerSurfaceFrame(width, height, 24.dp, 80.dp, step / 4000f, DeviceCorners()).pixels(d)
            }
            assertMonotone("player left $density", frames.map { it.left.toFloat() })
            assertMonotone("player right $density", frames.map { it.right.toFloat() })
            assertMonotone("player top $density", frames.map { it.top.toFloat() })
            assertMonotone("player bottom $density", frames.map { it.bottom.toFloat() })
            val full = frames.last()
            val window = with(d) { motionPixelRect(Rect(0f, 0f, width.toPx(), height.toPx())) }
            assertEquals("Full player covers the window exactly", window, full)
            // Near full size both horizontal edges arrive together (no one-pixel side gap).
            frames.filter { it.left == 0 }.forEach { assertEquals(window.right, it.right) }
        }
    }
}
