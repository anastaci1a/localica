package io.github.anastaci1a.localica.playback

import java.nio.ByteBuffer

/** A conservative description of one unfragmented, single-track MP4 edit, in media ticks. */
internal data class AacEditList(
    val movieTimescale: Long,
    val mediaTimescale: Long,
    val editDuration: Long,
    val mediaStart: Long,
    val mediaDuration: Long,
    val frameCount: Long,
    val regularDelta: Long,
    val finalDelta: Long,
) {
    /** Only repair a shortened final AAC access unit, never infer padding from silent audio. */
    fun padding(sampleRate: Int, frameSize: Int, encoderDelay: Int, encoderPadding: Int): Int? {
        if (mediaTimescale != sampleRate.toLong() || regularDelta != frameSize.toLong() ||
            finalDelta !in 1 until regularDelta || frameCount < 2 ||
            mediaStart != encoderDelay.toLong() || mediaStart !in 0 until 4L * frameSize ||
            encoderPadding != 0) return null
        // Require an exact integral audible length, rather than rounding a movie duration.
        val scaled = Math.multiplyExact(editDuration, mediaTimescale)
        if (scaled % movieTimescale != 0L) return null
        val audible = scaled / movieTimescale
        if (audible <= 0 || Math.addExact(mediaStart, audible) != mediaDuration) return null
        val decoded = Math.multiplyExact(frameCount, frameSize.toLong())
        val padding = decoded - mediaDuration
        return padding.takeIf { it in 1 until frameSize.toLong() }?.toInt()
    }

    companion object {
        /** Payload of a bounded moov box. Unsupported or inconsistent layouts keep native behavior. */
        fun parse(payload: ByteArray): AacEditList? = try {
            val root = Box(ByteBuffer.wrap(payload), 0, payload.size)
            require(root.children("mvex").isEmpty()) // Fragmented MP4 has different sample ownership.
            val movie = root.one("mvhd")
            val track = root.children("trak").single()
            val media = track.one("mdia")
            require(media.one("hdlr").int(8) == type("soun"))
            val header = media.one("mdhd")
            val edit = track.one("edts").one("elst")
            require(edit.uint(4) == 1L)
            val editVersion = edit.version()
            require(editVersion in 0..1)
            val duration = if (editVersion == 0) edit.uint(8) else edit.long(8)
            val start = if (editVersion == 0) edit.int(12).toLong() else edit.long(16)
            require(edit.int(if (editVersion == 0) 16 else 24) == 0x00010000)
            require(duration > 0 && start >= 0)
            val table = media.one("minf").one("stbl")
            require(table.children("ctts").isEmpty() && table.children("stss").isEmpty())
            val description = table.one("stsd")
            require(description.uint(4) == 1L && description.int(12) == type("mp4a"))
            require(description.uint(8) == (description.size - 8).toLong() && description.uint(8) >= 36)
            val chunks = table.one("stsc")
            val chunkRuns = chunks.uint(4)
            require(chunkRuns in 1..((chunks.size - 8) / 12).toLong())
            repeat(chunkRuns.toInt()) { require(chunks.uint(16 + it * 12) == 1L) }
            val timing = table.one("stts")
            val runs = timing.uint(4)
            require(runs in 2..((timing.size - 8) / 8).toLong())
            val regular = timing.uint(12)
            var count = 0L
            var ticks = 0L
            var last = 0L
            repeat(runs.toInt()) { i ->
                val n = timing.uint(8 + i * 8)
                val delta = timing.uint(12 + i * 8)
                require(n > 0 && delta > 0)
                if (i == runs.toInt() - 1) { require(n == 1L); last = delta }
                else require(delta == regular)
                count = Math.addExact(count, n)
                ticks = Math.addExact(ticks, Math.multiplyExact(n, delta))
            }
            require(table.one("stsz").uint(8) == count)
            val movieScale = movie.timescale()
            val mediaScale = header.timescale()
            require(movieScale > 0 && mediaScale > 0 && header.duration() == ticks)
            AacEditList(movieScale, mediaScale, duration, start, ticks, count, regular, last)
        } catch (_: RuntimeException) { null }

        private fun type(name: String): Int = name.fold(0) { value, c -> (value shl 8) or c.code }

        private class Box(val data: ByteBuffer, val start: Int, val end: Int) {
            val size get() = end - start
            fun int(offset: Int): Int { require(offset >= 0 && offset <= size - 4); return data.getInt(start + offset) }
            fun uint(offset: Int) = int(offset).toLong() and 0xffffffffL
            fun long(offset: Int): Long { require(offset >= 0 && offset <= size - 8); return data.getLong(start + offset) }
            fun version() = int(0) ushr 24
            fun timescale(): Long { require(version() in 0..1); return uint(if (version() == 0) 12 else 20) }
            fun duration() = if (version() == 0) uint(16) else long(24)
            fun one(name: String) = children(name).single()
            fun children(name: String): List<Box> {
                val result = mutableListOf<Box>()
                var p = 0
                while (p < size) {
                    require(p <= size - 8)
                    val length = uint(p)
                    val header = if (length == 1L) 16 else 8
                    val actual = if (length == 1L) long(p + 8) else length
                    require(actual >= header && actual <= size - p)
                    if (int(p + 4) == type(name)) result += Box(data, start + p + header, start + p + actual.toInt())
                    p += actual.toInt()
                }
                return result
            }
        }
    }
}
