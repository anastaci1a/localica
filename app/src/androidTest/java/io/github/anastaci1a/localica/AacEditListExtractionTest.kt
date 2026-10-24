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
import io.github.anastaci1a.localica.playback.MediaEditTrim
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

    @Test fun shortLcConfigDoesNotProveAbsenceOfInBandSbr() {
        val native = extract("boundary-short-config.m4a", repair = false)
        val wrapped = extract("boundary-short-config.m4a", repair = true)
        assertEquals(2, native.format!!.initializationData.single().size)
        assertEquals(0, native.format!!.encoderPadding)
        assertEquals("Ambiguous configuration must retain native padding", native.format, wrapped.format)
        assertEquals(native.timestamps, wrapped.timestamps)
    }

    @Test fun unknownSourceLengthKeepsNativeExtraction() {
        val native = extract("boundary-edit-list.m4a", repair = false)
        val unknown = extract("boundary-edit-list.m4a", repair = true, knownLength = false)
        assertEquals(native.format, unknown.format)
        assertEquals(native.timestamps, unknown.timestamps)
    }

    @Test fun roundedClockRestoresPrerollPacketsAndCarriesMediaUnitsToTheSink() {
        for (fixture in listOf("boundary-rounded-edit.m4a", "boundary-rounded-short-config.m4a", "boundary-rounded-streamable.m4a")) {
            val native = extract(fixture, repair = false)
            val corrected = extract(fixture, repair = true, rounded = true)
            assertEquals(44, native.timestamps.size)
            assertTrue(native.timestamps.first() < 0)
            assertTrue(native.format!!.hasPrerollSamples)
            assertEquals(0, native.format!!.encoderDelay)
            assertEquals(45, corrected.timestamps.size)
            assertEquals(0L, corrected.timestamps.first())
            assertFalse(corrected.format!!.hasPrerollSamples)
            assertEquals(1600, corrected.format!!.encoderDelay)
            assertEquals(0, corrected.format!!.encoderPadding)
            val edit = corrected.format!!.customData as MediaEditTrim
            assertEquals(44100L, edit.timescale)
            assertEquals(1600L, edit.start)
            assertEquals(3200, edit.delayAt(88200))
            assertEquals(44480L * 1_000_000 / 44100, corrected.durationUs)
        }
    }

    @Test fun roundedClockActivationRequiresThePairedDecodedSinkOptIn() {
        val native = extract("boundary-rounded-short-config.m4a", repair = false)
        val notPaired = extract("boundary-rounded-short-config.m4a", repair = true)
        assertEquals(native.format, notPaired.format)
        assertEquals(native.timestamps, notPaired.timestamps)
    }

    private class Output {
        var format: Format? = null
        var durationUs = C.TIME_UNSET
        val timestamps = mutableListOf<Long>()
    }

    private fun extract(fixture: String, repair: Boolean, knownLength: Boolean = true, rounded: Boolean = false): Output {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(fixture).use { it.readBytes() }
        val factory = if (repair) PlaybackExtractors(decodedEditBinding = rounded) else DefaultExtractorsFactory()
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
