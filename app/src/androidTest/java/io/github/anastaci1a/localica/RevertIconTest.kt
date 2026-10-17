package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class RevertIconTest {
    @Test fun arrowheadPointsAlongTheCounterclockwiseRingAtActionIconSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (size in listOf(24, 48, 96)) {
            val icon = requireNotNull(context.getDrawable(R.drawable.ic_revert))
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                icon.setBounds(0, 0, size, size)
                icon.draw(Canvas(bitmap))
                fun alpha(x: Float, y: Float): Int = Color.alpha(bitmap.getPixel(
                    (x * size / 24).toInt(), (y * size / 24).toInt()))
                // The upper-left ring endpoint points down-left: its wings extend up and right.
                for ((x, y) in listOf(6.343f to 3.5f, 6.343f to 6.343f, 9f to 6.343f,
                    12f to 4f, 20f to 12f, 12f to 20f, 4f to 12f)) {
                    assertTrue("Missing head/ring ink at ($x, $y), size=$size", alpha(x, y) > 128)
                }
                // A leftward wing would turn the arrowhead 90 degrees away from the tangent.
                assertTrue("Arrowhead must not extend left, size=$size", alpha(3.5f, 6.343f) < 32)
                assertTrue("Ring center must remain hollow, size=$size", alpha(12f, 12f) < 32)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
