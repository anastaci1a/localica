package io.github.anastaci1a.localica.playback

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.nio.ByteBuffer

/** A same-width, read-only metadata substitution. Media bytes and provider handles stay unchanged. */
internal data class MovieClockPatch(val offset: Long, val bytes: ByteArray)

/**
 * A rounded movie-clock edit can extend fractionally beyond the media end. Preserve its leading
 * edit and express the bounded end in media ticks so native extraction retains audio preroll.
 * This does not infer an AAC frame size, SBR absence, decoder padding or silence from audio bytes.
 */
internal data class RoundedAudioEdit(val patches: List<MovieClockPatch>, val trim: MediaEditTrim,
    val mediaChunks: List<LongRange>) {
    fun isInside(mediaPayloads: List<LongRange>) = mediaChunks.isNotEmpty() && mediaChunks.all { chunk ->
        mediaPayloads.any { chunk.first >= it.first && chunk.last <= it.last }
    }
}

internal fun roundedAudioEdit(payload: ByteArray, fileOffset: Long, fileLength: Long): RoundedAudioEdit? = try {
    require(!payload.toString(Charsets.ISO_8859_1).contains("iTunSMPB"))
    val root = EditBox(ByteBuffer.wrap(payload), 0, payload.size)
    require(root.children("mvex").isEmpty() && root.children("pssh").isEmpty())
    val movie = root.one("mvhd")
    val track = root.children("trak").single()
    val media = track.one("mdia")
    require(media.one("hdlr").uint(8) == editType("soun").toLong())
    val header = media.one("mdhd")
    val edit = track.one("edts").one("elst")
    require(edit.uint(4) == 1L)
    val editVersion = edit.version()
    require(movie.version() == 0 && header.version() == 0 && editVersion == 0)
    val durationOffset = 8
    val startOffset = if (editVersion == 0) 12 else 16
    val start = if (editVersion == 0) edit.uint(startOffset).toInt().toLong() else edit.long(startOffset)
    val editDuration = edit.duration(durationOffset)
    require(start >= 0 && edit.uint(if (editVersion == 0) 16 else 24) == 0x00010000L)
    val movieScale = movie.scale()
    val mediaScale = header.scale()
    val mediaDuration = header.headerDuration()
    require(mediaDuration in 1 until 0xffffffffL && editDuration in 1 until 0xffffffffL)
    require(movieScale in 1..0xffffffffL && mediaScale in 1..0xffffffffL && movieScale != mediaScale)
    val table = media.one("minf").one("stbl")
    require(table.children("ctts").isEmpty() && table.children("stss").isEmpty())
    val description = table.one("stsd")
    require(description.uint(4) == 1L && description.uint(12) == editType("mp4a").toLong())
    require(description.uint(8) >= 36 && description.uint(8) == (description.size - 8).toLong())
    require(description.uint(24) == 0L) // Only version-zero ISO audio sample descriptions.
    val sampleEntry = EditBox(description.data, description.start + 44, description.end)
    require(sampleEntry.children(null).size == listOf("esds", "btrt", "free").sumOf { sampleEntry.children(it).size })
    require(sampleEntry.children("btrt").size <= 1)
    val inputRate = requireAacDescription(sampleEntry.one("esds"))
    // Constant packet timing: the media end is declared extent, not decoded sample inference.
    val timing = table.one("stts")
    val runs = timing.uint(4)
    require(runs in 1..((timing.size - 8) / 8).toLong() && timing.size.toLong() == 8 + runs * 8)
    val delta = timing.uint(12)
    var count = 0L
    repeat(runs.toInt()) {
        val n = timing.uint(8 + it * 8)
        require(n > 0 && timing.uint(12 + it * 8) == delta)
        count = Math.addExact(count, n)
    }
    require(count in 2..Int.MAX_VALUE.toLong() && delta in 1..Int.MAX_VALUE.toLong() &&
        Math.multiplyExact(count, delta) == mediaDuration)
    val sizes = table.one("stsz")
    require(sizes.uint(8) == count)
    require(sizes.uint(4) == 0L && sizes.size.toLong() == 12 + count * 4)
    require(start in 1 until Math.multiplyExact(minOf(4L, count - 1), delta) && start < mediaDuration)
    // Native gapless selection requires at least one representable leading frame.
    require(Math.multiplyExact(start, inputRate.toLong()) / mediaScale in 1..Int.MAX_VALUE.toLong())
    val chunks = table.one("stsc")
    val chunkRuns = chunks.uint(4)
    require(chunkRuns in 1..((chunks.size - 8) / 12).toLong())
    require(chunks.size.toLong() == 8 + chunkRuns * 12)
    val offsets = (table.children("stco") + table.children("co64")).single()
    val wideOffsets = table.children("co64").isNotEmpty()
    val chunkCount = offsets.uint(4)
    require(chunkCount in 1..Int.MAX_VALUE.toLong() && offsets.size.toLong() == 8 + chunkCount * (if (wideOffsets) 8 else 4))
    var sample = 0L
    var previousRun = 0L
    var previousChunkEnd = 0L
    val mediaChunks = mutableListOf<LongRange>()
    repeat(chunkRuns.toInt()) { run ->
        val first = chunks.uint(8 + run * 12)
        val perChunk = chunks.uint(12 + run * 12)
        val next = if (run + 1 < chunkRuns) chunks.uint(8 + (run + 1) * 12) else chunkCount + 1
        require(first > previousRun && (run != 0 || first == 1L) && next > first && next <= chunkCount + 1 && perChunk > 0)
        require(chunks.uint(16 + run * 12) == 1L)
        for (chunk in first until next) {
            val offset = if (wideOffsets) offsets.long(8 + (chunk.toInt() - 1) * 8) else offsets.uint(8 + (chunk.toInt() - 1) * 4)
            var length = 0L
            repeat(perChunk.toInt().also { require(perChunk <= count && perChunk <= Int.MAX_VALUE) }) {
                require(sample < count)
                val packetSize = sizes.uint(12 + sample.toInt() * 4)
                require(packetSize > 0)
                length = Math.addExact(length, packetSize)
                sample++
            }
            require(offset >= maxOf(8, previousChunkEnd) && length > 0 && offset <= fileLength - length)
            previousChunkEnd = offset + length
            require(offset + length <= fileOffset - 8 || offset >= fileOffset + payload.size)
            if (mediaChunks.lastOrNull()?.last?.plus(1) == offset)
                mediaChunks[mediaChunks.lastIndex] = mediaChunks.last().first until offset + length
            else mediaChunks += offset until offset + length
        }
        previousRun = first
    }
    require(sample == count)
    val excess = Math.subtractExact(
        Math.addExact(Math.multiplyExact(start, movieScale), Math.multiplyExact(editDuration, mediaScale)),
        Math.multiplyExact(mediaDuration, movieScale))
    // Round-to-nearest at the coarser movie clock; never accept a whole movie tick of overrun.
    require(excess / movieScale > 2 && Math.multiplyExact(2, excess) <= mediaScale)
    val trackHeader = track.one("tkhd")
    require(trackHeader.version() == 0)
    fun patch(box: EditBox, offset: Int, value: Long, wide: Boolean): MovieClockPatch {
        require(value >= 0 && (wide || value <= 0xffffffffL))
        val bytes = ByteBuffer.allocate(if (wide) 8 else 4)
        if (wide) bytes.putLong(value) else bytes.putInt(value.toInt())
        return MovieClockPatch(Math.addExact(fileOffset, (box.start + offset).toLong()), bytes.array())
    }
    require(movie.headerDuration() == editDuration && trackHeader.duration(if (trackHeader.version() == 0) 20 else 28) == editDuration)
    val audible = mediaDuration - start
    RoundedAudioEdit(listOf(
        patch(movie, if (movie.version() == 0) 12 else 20, mediaScale, false),
        patch(movie, if (movie.version() == 0) 16 else 24, audible, movie.version() == 1),
        patch(trackHeader, if (trackHeader.version() == 0) 20 else 28, audible, trackHeader.version() == 1),
        patch(edit, durationOffset, audible, editVersion == 1),
    ), MediaEditTrim(mediaScale, start), mediaChunks)
} catch (_: RuntimeException) { null }

/** Bounded MPEG-4 descriptor parsing; mp4a alone does not establish AAC. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun requireAacDescription(esds: EditBox): Int {
    val bytes = ByteArray(esds.size).also { esds.data.duplicate().apply { position(esds.start) }.get(it) }
    var pos = 4
    fun byte(): Int { require(pos < bytes.size); return bytes[pos++].toInt() and 255 }
    fun descriptor(tag: Int): Int {
        require(byte() == tag)
        var length = 0
        var done = false
        repeat(4) { if (!done) { val next = byte(); length = (length shl 7) or (next and 127); done = next and 128 == 0 } }
        require(done && length <= bytes.size - pos)
        return pos + length
    }
    val esEnd = descriptor(3)
    byte(); byte()
    require(byte() == 0) // No dependent streams, URLs or OCR streams.
    val decoderEnd = descriptor(4)
    require(decoderEnd <= esEnd && byte() == 0x40 && byte() ushr 2 == 5)
    repeat(11) { byte() }
    val configEnd = descriptor(5)
    require(configEnd <= decoderEnd && configEnd - pos >= 2)
    val config = bytes.copyOfRange(pos, configEnd)
    val type = (config[0].toInt() and 255) ushr 3
    require(type in listOf(2, 5, 29)) // AAC LC/HE; this is not an SBR-absence assertion.
    val parsed = runCatching { androidx.media3.extractor.AacUtil.parseAudioSpecificConfig(config) }.getOrNull()
    require(parsed != null && parsed.sampleRateHz in 1..1_000_000 && parsed.channelCount > 0)
    return parsed.sampleRateHz
}

private fun editType(name: String): Int = name.fold(0) { value, c -> (value shl 8) or c.code }

private class EditBox(val data: ByteBuffer, val start: Int, val end: Int) {
    val size get() = end - start
    fun uint(offset: Int): Long { require(offset >= 0 && offset <= size - 4); return data.getInt(start + offset).toLong() and 0xffffffffL }
    fun long(offset: Int): Long { require(offset >= 0 && offset <= size - 8); return data.getLong(start + offset) }
    fun version(): Int = (uint(0) ushr 24).toInt().also { require(it in 0..1) }
    fun duration(offset: Int) = if (version() == 0) uint(offset) else long(offset)
    fun scale() = uint(if (version() == 0) 12 else 20)
    fun headerDuration() = duration(if (version() == 0) 16 else 24)
    fun one(name: String) = children(name).single()
    fun children(name: String?): List<EditBox> {
        val result = mutableListOf<EditBox>()
        var p = 0
        while (p < size) {
            require(p <= size - 8)
            val shortSize = uint(p)
            val header = if (shortSize == 1L) 16 else 8
            val length = if (shortSize == 1L) long(p + 8) else shortSize
            require(length >= header && length <= size - p)
            if (name == null || uint(p + 4) == editType(name).toLong()) result += EditBox(data, start + p + header, start + p + length.toInt())
            p += length.toInt()
        }
        return result
    }
}

/** Overlay metadata for both consumed and peeked reads, including reads crossing field boundaries. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MovieClockInput(input: ExtractorInput, private val patches: List<MovieClockPatch>) : ForwardingExtractorInput(input) {
    private fun apply(target: ByteArray, offset: Int, length: Int, start: Long) {
        if (length <= 0) return
        patches.forEach { patch ->
            val lo = maxOf(start, patch.offset)
            val hi = minOf(start + length, patch.offset + patch.bytes.size)
            if (hi > lo) patch.bytes.copyInto(target, offset + (lo - start).toInt(), (lo - patch.offset).toInt(), (hi - patch.offset).toInt())
        }
    }
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val start = position
        return super.read(target, offset, length).also { apply(target, offset, it, start) }
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        val start = position; super.readFully(target, offset, length); apply(target, offset, length, start)
    }
    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val start = position
        return super.readFully(target, offset, length, allowEndOfInput).also { if (it) apply(target, offset, length, start) }
    }
    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val start = peekPosition
        return super.peek(target, offset, length).also { apply(target, offset, it, start) }
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        val start = peekPosition; super.peekFully(target, offset, length); apply(target, offset, length, start)
    }
    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val start = peekPosition
        return super.peekFully(target, offset, length, allowEndOfInput).also { if (it) apply(target, offset, length, start) }
    }
}
