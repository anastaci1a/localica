package io.github.anastaci1a.localica.library

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Bounded tag-only mirrors for readers that require a Path. Audio payloads are never copied.
 * MP4/WAV mirrors are sparse: original offsets are retained, with zero-filled audio holes.
 */
internal object TagFileView {
    private const val LIMIT = 16 * 1024 * 1024L
    fun <T> read(context: Context, input: FileChannel, extension: String, reader: (File) -> T): T {
        val directory = File(context.cacheDir, "tag-views").apply { mkdirs() }
        val file = File.createTempFile("metadata-", ".$extension", directory)
        try {
            RandomAccessFile(file, "rw").use { output -> copyMetadata(input, output, extension) }
            return reader(file)
        } finally { file.delete() }
    }
    internal fun copyMetadata(input: FileChannel, output: RandomAccessFile, extension: String) {
        val length = input.size()
        var copied = 0L
        fun bytes(offset: Long, size: Int): ByteArray {
            require(size >= 0 && offset >= 0 && offset <= length - size) { "Incomplete metadata container" }
            val buffer = ByteBuffer.allocate(size); input.position(offset)
            while (buffer.hasRemaining()) if (input.read(buffer) < 0) throw java.io.EOFException()
            return buffer.array()
        }
        fun copy(offset: Long, size: Long) {
            require(size >= 0 && size <= LIMIT - copied && offset <= length - size) { "Metadata exceeds 16 MB or is incomplete" }
            copied += size; input.position(offset); output.seek(offset)
            var remaining = size
            val buffer = ByteBuffer.allocate(8192)
            while (remaining > 0) {
                buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val count = input.read(buffer); if (count < 0) throw java.io.EOFException()
                output.write(buffer.array(), 0, count); remaining -= count
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            }
        }
        fun uint(data: ByteArray, offset: Int, little: Boolean = false) = ByteBuffer.wrap(data, offset, 4)
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        when (extension) {
            "flac" -> {
                var offset = 0L
                if (bytes(0, 3).toString(Charsets.US_ASCII) == "ID3") {
                    val header = bytes(0, 10)
                    require((6..9).all { header[it].toInt() and 0x80 == 0 })
                    val size = (6..9).fold(0L) { n, i -> (n shl 7) or (header[i].toLong() and 0x7f) } + 10
                    copy(0, size); offset = size
                }
                require(bytes(offset, 4).toString(Charsets.US_ASCII) == "fLaC") { "Invalid FLAC header" }
                copy(offset, 4); offset += 4
                do {
                    val header = bytes(offset, 4)
                    val size = (1..3).fold(0L) { n, i -> (n shl 8) or (header[i].toLong() and 0xff) }
                    copy(offset, size + 4); offset += size + 4
                } while (header[0].toInt() and 0x80 == 0)
            }
            "ogg", "oga" -> {
                var offset = 0L; var packets = 0
                while (packets < 2) {
                    val header = bytes(offset, 27)
                    require(header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS") { "Invalid Ogg header" }
                    val lacing = bytes(offset + 27, header[26].toInt() and 0xff)
                    val size = 27L + lacing.size + lacing.sumOf { it.toInt() and 0xff }
                    copy(offset, size); offset += size
                    packets += lacing.count { (it.toInt() and 0xff) < 255 }
                }
            }
            "wav" -> {
                val riff = bytes(0, 12)
                require(riff.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    riff.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "Unsupported WAV container" }
                copy(0, 12); var offset = 12L
                while (offset + 8 <= length) {
                    val header = bytes(offset, 8); val size = uint(header, 4, true)
                    require(size <= length - offset - 8) { "Incomplete WAV chunk" }
                    val name = header.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                    copy(offset, if (name in setOf("data", "JUNK", "PAD ")) 8 else size + 8)
                    offset += 8 + size + size % 2
                }
                output.setLength(length)
            }
            else -> {
                var offset = 0L
                while (offset + 8 <= length) {
                    val header = bytes(offset, 8); val declared = uint(header, 0)
                    val headerSize = if (declared == 1L) 16L else 8L
                    val size = when (declared) { 0L -> length - offset
                        1L -> ByteBuffer.wrap(bytes(offset + 8, 8)).long
                        else -> declared }
                    require(size >= headerSize && size <= length - offset) { "Invalid MP4 atom size" }
                    val name = header.copyOfRange(4, 8).toString(Charsets.US_ASCII)
                    copy(offset, if (name in setOf("moov", "ftyp", "meta")) size else headerSize)
                    offset += size
                }
                output.setLength(length)
            }
        }
    }
}
