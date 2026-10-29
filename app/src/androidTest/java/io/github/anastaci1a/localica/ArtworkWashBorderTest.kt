package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import io.github.anastaci1a.localica.ui.ArtworkWash
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ArtworkWashBorderTest {
    @Test fun syntheticBlueBorderHasNoHorizontalStepWhereTopBlurResolves() {
        // Generated fixture reproduces the high-contrast border, not the user's cover image.
        val cover = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            cover.setPixel(x, y, if (x < 6 || x >= 250 || y < 6 || y >= 250) Color.BLUE else Color.WHITE)
        }
        val wash = ArtworkWash.create(cover)
        try {
            val first = ((.15f - ArtworkWash.TOP) * ArtworkWash.WIDTH).toInt()
            val last = ((.4f - ArtworkWash.TOP) * ArtworkWash.WIDTH).toInt()
            for (y in first + 1..last) for (x in 0 until wash.width) {
                val before = wash.getPixel(x, y - 1)
                val after = wash.getPixel(x, y)
                val jump = maxOf(abs(Color.red(before) - Color.red(after)),
                    abs(Color.green(before) - Color.green(after)), abs(Color.blue(before) - Color.blue(after)))
                assertTrue("Border must resolve smoothly, not jump at ($x,$y): $jump", jump <= 8)
            }
        } finally { wash.recycle(); cover.recycle() }
    }
}
