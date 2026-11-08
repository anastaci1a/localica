package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.AppIcon
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seek arrowheads sit on the ring's top endpoint and point along its turning direction (Revert: [RevertIconTest]). */
class RotationIconTest {
    private fun check(icon: AppIcon, toward: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (size in listOf(24, 48, 96)) {
            val drawable = requireNotNull(context.getDrawable(icon.builtin))
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                fun alpha(x: Float, y: Float): Int = Color.alpha(bitmap.getPixel(
                    (x * size / 24).toInt(), (y * size / 24).toInt()))
                // The head at 12 o'clock points toward `toward` (-1 left, +1 right): its wings trail behind it.
                val wing = 12f - toward * 1.5f
                for ((x, y) in listOf(12f to 4f, wing to 2.5f, wing to 5.5f, 20f to 12f, 12f to 20f, 4f to 12f)) {
                    assertTrue("${icon.id}: missing head/ring ink at ($x, $y), size=$size", alpha(x, y) > 128)
                }
                val ahead = 12f + toward * 2f
                assertTrue("${icon.id}: arrowhead must not trail ahead of its tip, size=$size",
                    alpha(ahead, 2.2f) < 32 && alpha(ahead, 6f) < 32)
                assertTrue("${icon.id}: ring center must remain hollow, size=$size", alpha(12f, 12f) < 32)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun seekBackTurnsCounterclockwise() = check(AppIcon.ActionSeekBack, toward = -1f)

    @Test fun seekForwardTurnsClockwise() = check(AppIcon.ActionSeekForward, toward = 1f)
}
