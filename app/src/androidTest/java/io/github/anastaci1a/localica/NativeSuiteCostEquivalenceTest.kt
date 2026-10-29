package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

/** Native bitmap equivalence precheck; excluded from measured product trials. */
class NativeSuiteCostEquivalenceTest {
    @Test fun opaqueArtworkBulkWriteMatchesEveryOriginalPixel() {
        val original = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        val bulk = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until original.width) for (y in 0 until original.height)
                original.setPixel(x, y, Color.rgb(x * 255 / 600, y * 255 / 300, 80))
            val expected = IntArray(bulk.width * bulk.height) { index ->
                val x = index % bulk.width
                val y = index / bulk.width
                Color.rgb(x * 255 / 600, y * 255 / 300, 80)
            }
            bulk.setPixels(expected, 0, bulk.width, 0, 0, bulk.width, bulk.height)
            assertEquals(original.config, bulk.config)
            assertTrue("Every native bitmap pixel is identical", original.sameAs(bulk))
            val originalPixels = IntArray(expected.size)
            val bulkPixels = IntArray(expected.size)
            original.getPixels(originalPixels, 0, original.width, 0, 0, original.width, original.height)
            bulk.getPixels(bulkPixels, 0, bulk.width, 0, 0, bulk.width, bulk.height)
            assertArrayEquals(expected, originalPixels)
            assertArrayEquals(originalPixels, bulkPixels)
        } finally {
            original.recycle()
            bulk.recycle()
        }
    }
}
