package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #501: the Android 8 emulator's gralloc overruns RGBA buffers of 4084/4088 bytes modulo 4096. */
class GrallocReadbackSizeTest {
    private fun residue(width: Int, rows: Int) = width.toLong() * rows * 4 % 4096

    @Test fun overrunningSizesGainOnlyTheRowsNeededToLeaveTheResidue() {
        assertEquals(3, grallocSafeReadbackHeight(511, 2))   // 4088 -> 6132 % 4096 = 2036
        assertEquals(2, grallocSafeReadbackHeight(1021, 1))  // 4084 -> 8168 % 4096 = 4072
        assertEquals(15, grallocSafeReadbackHeight(73, 14))  // 4088 -> 4380 % 4096 = 284
        assertEquals(1, grallocSafeReadbackHeight(1, 1))
        assertEquals(1919, grallocSafeReadbackHeight(1080, 1919))
    }

    @Test fun everyReturnedHeightAvoidsTheOverrunAndNeverShrinks() {
        for (width in 1..1200) for (height in intArrayOf(1, 2, 3, 14, 64, 255, 1022, 1023, 1919)) {
            val rows = grallocSafeReadbackHeight(width, height)
            assertTrue("$width x $height", rows >= height && rows <= height + 2)
            val left = residue(width, rows)
            assertTrue("$width x $rows leaves $left", left != 4084L && left != 4088L)
        }
    }
}
