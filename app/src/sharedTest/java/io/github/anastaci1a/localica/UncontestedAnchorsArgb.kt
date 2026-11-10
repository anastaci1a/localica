package io.github.anastaci1a.localica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Proof-only encoder. Preserves the legacy signed writeInt bit pattern and pixel order. */
internal object UncontestedAnchorsArgb {
    fun encode(pixels: IntArray): ByteArray {
        val bytes = ByteArray(Math.multiplyExact(pixels.size, Int.SIZE_BYTES))
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().put(pixels)
        return bytes
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Independent RAM-based producer, never a decode of the new encoder's own output. */
    fun verifyLegacy(pixels: IntArray, bulk: ByteArray): String {
        val memory = ByteArrayOutputStream(Math.multiplyExact(pixels.size, Int.SIZE_BYTES))
        DataOutputStream(memory).use { data -> pixels.forEach(data::writeInt) }
        val legacy = memory.toByteArray()
        check(legacy.contentEquals(bulk)) { "Bulk ARGB differs from legacy writeInt bytes" }
        val legacyHash = sha256(legacy)
        val bulkHash = sha256(bulk)
        check(legacyHash == bulkHash) { "Bulk ARGB hash differs from legacy writeInt hash" }
        return "pixels=${pixels.size} bytes=${bulk.size} legacySha256=$legacyHash bulkSha256=$bulkHash exact=true"
    }
}
