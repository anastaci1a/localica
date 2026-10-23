package io.github.anastaci1a.localica

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.playback.PlaybackExtractors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AacEditListExtractionTest {
    @Test fun repairsOnlyPaddingAndPreservesPacketTimestamps() {
        val native = extract("boundary-edit-list.m4a", repair = false)
        val repaired = extract("boundary-edit-list.m4a", repair = true)
        assertEquals(0, native.format!!.encoderPadding)
        assertEquals(956, repaired.format!!.encoderPadding)
        assertEquals(native.format!!.buildUpon().setEncoderPadding(956).build(), repaired.format)
        assertEquals(native.timestamps, repaired.timestamps)
        assertEquals(45, repaired.timestamps.size)
        assertEquals(1_000_000L, repaired.durationUs)
    }

    @Test fun fullFrameTimingAlreadyTrimsCorrectlyAndIsUnchanged() {
        val native = extract("boundary.m4a", repair = false)
        val repaired = extract("boundary.m4a", repair = true)
        assertEquals(956, native.format!!.encoderPadding)
        assertEquals(native.format, repaired.format)
        assertEquals(native.timestamps, repaired.timestamps)
    }

    @Test fun unknownSourceLengthKeepsNativeExtraction() {
        val native = extract("boundary-edit-list.m4a", repair = false)
        val unknown = extract("boundary-edit-list.m4a", repair = true, knownLength = false)
        assertEquals(native.format, unknown.format)
        assertEquals(native.timestamps, unknown.timestamps)
    }

    private class Output {
        var format: Format? = null
        var durationUs = C.TIME_UNSET
        val timestamps = mutableListOf<Long>()
    }

    private fun extract(fixture: String, repair: Boolean, knownLength: Boolean = true): Output {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(fixture).use { it.readBytes() }
        val factory = if (repair) PlaybackExtractors() else DefaultExtractorsFactory()
        val extractor = factory.createExtractors().first { it.underlyingImplementation is Mp4Extractor }
        val output = Output()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DummyTrackOutput() {
                override fun format(format: Format) { output.format = format }
                override fun durationUs(durationUs: Long) { output.durationUs = durationUs }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    output.timestamps += timeUs
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        fun input(position: Int): DefaultExtractorInput {
            val stream = ByteArrayInputStream(bytes, position, bytes.size - position)
            return DefaultExtractorInput({ b, o, n -> stream.read(b, o, n) }, position.toLong(),
                if (knownLength) bytes.size.toLong() else C.LENGTH_UNSET.toLong())
        }
        val seek = PositionHolder()
        var source = input(0)
        try {
            repeat(1000) {
                when (extractor.read(source, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> return output
                    Extractor.RESULT_SEEK -> source = input(seek.position.toInt())
                }
            }
            throw AssertionError("Extractor did not finish")
        } finally { extractor.release() }
    }
}
