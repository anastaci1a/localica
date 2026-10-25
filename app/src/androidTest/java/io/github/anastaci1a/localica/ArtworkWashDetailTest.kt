package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import io.github.anastaci1a.localica.ui.ArtworkWash
import io.github.anastaci1a.localica.ui.playerPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

class ArtworkWashDetailTest {
    @Test fun visibleWashKeepsCoverDetailInsteadOfPaletteThumbnailAliasing() {
        val cover = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        for (y in 0 until cover.height) for (x in 0 until cover.width) {
            val shade = (128 + 110 * cos((x + .5) * 2 * PI * 64 / cover.width)).roundToInt()
            cover.setPixel(x, y, Color.rgb(shade, shade, shade))
        }
        val palette = runBlocking { playerPalette(cover) }
        val wash = requireNotNull(palette.wash)
        assertEquals("Soft transition endpoint retains its bounded old width", 32, requireNotNull(palette.softWash).width)
        assertEquals(80, requireNotNull(palette.softWash).height)
        val reduced = Bitmap.createScaledBitmap(cover, 96, 96, true)
        val lowDetail = ArtworkWash.create(reduced)
        val expected = Bitmap.createScaledBitmap(cover, ArtworkWash.WIDTH, ArtworkWash.WIDTH, true)
        val row = ((.35f - ArtworkWash.TOP) * ArtworkWash.WIDTH).toInt()
        var actualError = 0L
        var thumbnailError = 0L
        for (x in 0 until wash.width) {
            val reference = Color.red(expected.getPixel(x, 0))
            val actual = Color.red(wash.getPixel(x, row)) - reference
            val thumbnail = Color.red(lowDetail.getPixel(x, row)) - reference
            actualError += actual * actual
            thumbnailError += thumbnail * thumbnail
        }
        assertTrue("The old thumbnail path must actually lose this cover's detail", thumbnailError > 100_000)
        assertTrue("Visible continuation comes from full cover detail, not its color sample",
            actualError * 10 < thumbnailError)
        assertFalse("Preparing washes must not recycle the shared decoded cover", cover.isRecycled)
    }

    @Test fun extremeAspectRatiosStayBoundedAndPreserveUniformColor() {
        for ((width, height) in listOf(1 to 768, 768 to 1, 256 to 256)) {
            val cover = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(40, 120, 210))
            }
            val wash = ArtworkWash.create(cover)
            assertEquals(256, wash.width)
            assertEquals(640, wash.height)
            assertEquals(655_360, wash.allocationByteCount)
            for (x in listOf(0, 128, 255)) for (y in listOf(0, 160, 639))
                assertEquals(Color.rgb(40, 120, 210), wash.getPixel(x, y))
            assertFalse(cover.isRecycled)
        }
    }

    @Test fun transparentCoverContinuesTheSameNeutralOpaqueBase() {
        val cover = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val wash = ArtworkWash.create(cover)
        assertEquals(Color.rgb(36, 43, 52), wash.getPixel(128, 160))
        assertEquals(255, Color.alpha(wash.getPixel(0, 0)))
        assertEquals(255, Color.alpha(wash.getPixel(255, 639)))
    }
}
