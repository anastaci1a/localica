package io.github.anastaci1a.localica

import org.junit.Assert.*
import org.junit.Test

class UncontestedAnchorsArgbTest {
    @Test fun bigEndianBytesAndHashMatchIndependentLegacyProducerWithoutChangingPixels() {
        val patterns = intArrayOf(0, Int.MIN_VALUE, Int.MAX_VALUE, -1, 0x01020304,
            0x00ff0080, 0x7f123456, 0x80123456.toInt(), 0xffabcdef.toInt())
        val fixtures = listOf(IntArray(0), intArrayOf(0), patterns,
            IntArray(8193) { patterns[it % patterns.size] xor (it * 0x01010101) })
        for (pixels in fixtures) {
            val immutable = pixels.copyOf()
            val bulk = UncontestedAnchorsArgb.encode(pixels)
            val proof = UncontestedAnchorsArgb.verifyLegacy(pixels, bulk)
            assertEquals(pixels.size * 4, bulk.size)
            assertArrayEquals("The captured integers stay unchanged", immutable, pixels)
            assertTrue(proof.endsWith("exact=true"))
            println(proof)
        }
        // Explicit bytes also catch endianness, sign and channel-order changes independently.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, -128, 0, 0, 0, -1, -85, -51, -17),
            UncontestedAnchorsArgb.encode(intArrayOf(0x01020304, Int.MIN_VALUE, 0xffabcdef.toInt())))
        val corrupted = UncontestedAnchorsArgb.encode(patterns)
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        try {
            UncontestedAnchorsArgb.verifyLegacy(patterns, corrupted)
            fail("An independently corrupted encoder result must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("differs"))
        }
    }
}
