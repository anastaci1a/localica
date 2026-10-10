package io.github.anastaci1a.localica.library

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TagFileViewTest {
    private fun mirror(bytes: ByteArray, extension: String, verify: (ByteArray) -> Unit) {
        val source = File.createTempFile("tags-source", ".$extension")
        val target = File.createTempFile("tags-view", ".$extension")
        try {
            source.writeBytes(bytes)
            RandomAccessFile(source, "r").use { input -> RandomAccessFile(target, "rw").use { output ->
                TagFileView.copyMetadata(input.channel, output, extension)
            } }
            verify(target.readBytes())
        } finally { source.delete(); target.delete() }
    }
    private fun atom(name: String, payload: ByteArray, little: Boolean = false): ByteArray {
        val size = ByteBuffer.allocate(4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .putInt(payload.size + if (little) 0 else 8).array()
        return (if (little) name.toByteArray() + size else size + name.toByteArray()) + payload
    }
    @Test fun mp4KeepsTrailingTagsAtOriginalOffsetsWithoutCopyingAudio() {
        val audio = atom("mdat", ByteArray(8192) { 42 })
        val tags = atom("moov", byteArrayOf(1, 2, 3, 4))
        mirror(audio + tags, "m4a") { view ->
            assertEquals(audio.size + tags.size, view.size)
            assertTrue(view.copyOfRange(8, audio.size).all { it == 0.toByte() })
            assertArrayEquals(tags, view.copyOfRange(audio.size, view.size))
        }
    }
    @Test fun wavKeepsTagsAfterOddPaddedAudioChunk() {
        val chunks = atom("data", byteArrayOf(5, 6, 7), true) + byteArrayOf(0) + atom("LIST", byteArrayOf(1, 2), true)
        val source = "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks.size + 4).array() + "WAVE".toByteArray() + chunks
        mirror(source, "wav") { view ->
            assertEquals(source.size, view.size)
            assertArrayEquals(byteArrayOf(0, 0, 0), view.copyOfRange(20, 23))
            assertArrayEquals(source.copyOfRange(24, source.size), view.copyOfRange(24, view.size))
        }
    }
    @Test fun flacStopsAtLastMetadataBlock() {
        val tags = "fLaC".toByteArray() + byteArrayOf(0x84.toByte(), 0, 0, 3, 1, 2, 3)
        mirror(tags + ByteArray(4096) { 42 }, "flac") { assertArrayEquals(tags, it) }
    }
    @Test fun corruptAtomCannotSeekPastEnd() {
        assertThrows(IllegalArgumentException::class.java) {
            mirror(byteArrayOf(0, 0, 0, 100) + "moov".toByteArray(), "m4a") { fail("Corrupt atom accepted") }
        }
    }
}
