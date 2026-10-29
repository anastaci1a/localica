package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.softwareGlassSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real hardware input and software Canvas: zero-effect conversion is not a blur/tint. */
class GlassSnapshotSoftwareTest {
    private fun pattern(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
            for (y in 0 until height) for (x in 0 until width) {
                image.setPixel(x, y, when {
                    x == 0 -> Color.TRANSPARENT
                    x == 1 -> Color.argb(128, 0, 0, 255)
                    (x + y) % 2 == 0 -> Color.RED
                    else -> Color.GREEN
                })
            }
        }

    @Test fun hardwareZeroEffectSnapshotIsSoftwareSafeSharpAndAlphaPreserving() = runBlocking {
        val original = pattern(35, 19)
        val hardware = requireNotNull(original.copy(Bitmap.Config.HARDWARE, false))
        var converted: Bitmap? = null
        val target = Bitmap.createBitmap(35, 19, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(Bitmap.Config.HARDWARE, hardware.config)
            val result = softwareGlassSnapshot(hardware.asImageBitmap(), IntSize(35, 19)).asAndroidBitmap()
            converted = result
            assertNotSame(hardware, result)
            assertEquals(Bitmap.Config.ARGB_8888, result.config)
            assertEquals(35, result.width)
            assertEquals(19, result.height)
            assertFalse("Normalization never retires its caller's native bitmap", hardware.isRecycled)
            // A hardware bitmap cannot be submitted to this Canvas. Drawing is part of the contract.
            Canvas(target).drawBitmap(result, 0f, 0f, null)
            for (y in 0 until 19) for (x in 0 until 35) {
                assertEquals("Unfiltered source pixel $x,$y", original.getPixel(x, y), result.getPixel(x, y))
                assertEquals("Software Canvas pixel $x,$y", original.getPixel(x, y), target.getPixel(x, y))
            }
        } finally {
            target.recycle(); converted?.recycle(); hardware.recycle(); original.recycle()
        }
    }

    @Test fun paddedHardwareCaptureCropsTheLogicalExtentWithoutScalingItsEdges() = runBlocking {
        val logical = IntSize(35, 19)
        val original = pattern(64, 64)
        // A distinct padding color makes squeezing the whole allocation into the logical size visible.
        for (y in 0 until 64) for (x in 0 until 64)
            if (x >= logical.width || y >= logical.height) original.setPixel(x, y, Color.MAGENTA)
        val hardware = requireNotNull(original.copy(Bitmap.Config.HARDWARE, false))
        var converted: Bitmap? = null
        try {
            val result = softwareGlassSnapshot(hardware.asImageBitmap(), logical).asAndroidBitmap()
            converted = result
            assertEquals(logical.width, result.width)
            assertEquals(logical.height, result.height)
            for (y in 0 until logical.height) for (x in 0 until logical.width)
                assertEquals("Crop preserves logical coordinate $x,$y", original.getPixel(x, y), result.getPixel(x, y))
            assertFalse(hardware.isRecycled)
        } finally {
            converted?.recycle(); hardware.recycle(); original.recycle()
        }
    }

    @Test fun softwareInputIsCopiedWithoutTakingOwnershipAndInvalidExtentsFailClosed() = runBlocking {
        val original = pattern(9, 7)
        var converted: Bitmap? = null
        try {
            val result = softwareGlassSnapshot(original.asImageBitmap(), IntSize(9, 7)).asAndroidBitmap()
            converted = result
            assertNotSame(original, result)
            val first = result.getPixel(3, 3)
            original.setPixel(3, 3, Color.BLUE)
            assertEquals("Published software pixels do not alias a mutable caller", first, result.getPixel(3, 3))
            for (size in listOf(IntSize.Zero, IntSize(0, 7), IntSize(9, 0), IntSize(10, 7), IntSize(9, 8))) {
                try {
                    softwareGlassSnapshot(original.asImageBitmap(), size)
                    fail("Invalid logical extent must fail before publication: $size")
                } catch (_: IllegalArgumentException) { }
                assertFalse("Rejected normalization does not recycle caller input", original.isRecycled)
            }
        } finally {
            converted?.recycle(); original.recycle()
        }
    }
}
