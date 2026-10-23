package io.github.anastaci1a.localica.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import java.io.EOFException
import java.nio.ByteBuffer

/** Keep Media3's extractors, adding only the bounded AAC edit-list correction for #28. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackExtractors : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = wrap(defaults.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(defaults.createExtractors(uri, responseHeaders))
    private fun wrap(extractors: Array<Extractor>) = extractors.map {
        if (it is Mp4Extractor) EditListAacExtractor(it) else it
    }.toTypedArray()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class EditListAacExtractor(private val delegate: Mp4Extractor) : Extractor by delegate {
    private var inspected = false
    private var boxesRead = 0
    private var edit: AacEditList? = null

    override fun getUnderlyingImplementation(): Extractor = delegate
    override fun getSniffFailureDetails() = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput by output {
            private val outputs = mutableMapOf<Int, TrackOutput>()
            override fun track(id: Int, type: Int): TrackOutput = outputs.getOrPut(id) {
                val track = output.track(id, type)
                object : TrackOutput by track {
                    override fun durationUs(durationUs: Long) = track.durationUs(durationUs)
                    override fun format(format: Format) {
                        val timing = if (id == 0 && type == C.TRACK_TYPE_AUDIO) edit else null
                        val padding = if (timing != null && isNormalAacLc(format)) try {
                            timing.padding(format.sampleRate, 1024, format.encoderDelay, format.encoderPadding)
                        } catch (_: ArithmeticException) { null } else null
                        track.format(if (padding == null) format else format.buildUpon().setEncoderPadding(padding).build())
                    }
                }
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (inspected) return delegate.read(input, seekPosition)
        // The normal MP4 extractor already seeks between moov and mdat. Inspect bounded
        // metadata through the same reader, never a second unregistered provider handle.
        // Unknown lengths, large/fragmented/ambiguous metadata keep Media3's native behavior.
        if (input.length == C.LENGTH_UNSET.toLong() || ++boxesRead > 128) return finish(seekPosition)
        try {
            val start = input.position
            val header = ByteArray(16)
            if (!input.readFully(header, 0, 8, true)) return finish(seekPosition)
            val data = ByteBuffer.wrap(header)
            var size = data.int.toLong() and 0xffffffffL
            val type = data.int
            var headerSize = 8
            if (size == 1L) {
                input.readFully(header, 8, 8)
                size = data.getLong(8)
                headerSize = 16
            }
            if (size < headerSize || size > input.length - start) return finish(seekPosition)
            if (type == 0x6d6f6f76) { // moov
                val payloadSize = size - headerSize
                if (payloadSize <= 2 * 1024 * 1024) {
                    val payload = ByteArray(payloadSize.toInt())
                    input.readFully(payload, 0, payload.size)
                    edit = AacEditList.parse(payload)
                }
                return finish(seekPosition)
            }
            if (type == 0x6d6f6f66) return finish(seekPosition) // moof
            seekPosition.position = start + size
            return Extractor.RESULT_SEEK
        } catch (_: EOFException) {
            return finish(seekPosition)
        }
    }

    private fun finish(position: PositionHolder): Int {
        inspected = true
        position.position = 0
        return Extractor.RESULT_SEEK
    }

    private fun isNormalAacLc(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_AAC && format.codecs == "mp4a.40.2" &&
            format.drmInitData == null && format.initializationData.size == 1 &&
            normalAacLcConfig(format.initializationData.single())
}

/** True only for simple 1,024-sample AAC-LC without explicit or implicit SBR/PS. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun normalAacLcConfig(config: ByteArray): Boolean = try {
    val bits = ParsableBitArray(config)
    if (bits.readBits(5) != 2) false
    else {
        if (bits.readBits(4) == 15) bits.skipBits(24)
        val channels = bits.readBits(4)
        // GASpecificConfig: reject 960-frame mode, core dependency and extension flags.
        if (channels !in 1..7 || bits.readBit() || bits.readBit() || bits.readBit()) false
        else {
            val extensionAbsent = if (bits.bitsLeft() >= 11) {
                bits.readBits(11) == 0x2b7 && bits.readBits(5) == 5 && !bits.readBit()
            } else true
            extensionAbsent && bits.bitsLeft() <= 7 &&
                (bits.bitsLeft() == 0 || bits.readBits(bits.bitsLeft()) == 0)
        }
    }
} catch (_: RuntimeException) { false }
