package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class AacEditListTest {
    private val timing = AacEditList(1000, 44100, 1000, 1024, 45124, 45, 1024, 68)

    @Test fun onlyExplicitSbrAbsenceQualifiesForCorrection() {
        assertFalse(normalAacLcConfig(byteArrayOf(0x12, 0x08)))
        assertTrue(normalAacLcConfig(byteArrayOf(0x12, 0x08, 0x56, 0xe5.toByte(), 0)))
        for (config in listOf(byteArrayOf(), byteArrayOf(0x12), byteArrayOf(0x12, 0x0c),
            byteArrayOf(0x12, 0x08, 0), byteArrayOf(0x12, 0x08, 0x56, 0xe5.toByte()),
            byteArrayOf(0x12, 0x0a), byteArrayOf(0x12, 0x09), byteArrayOf(0x2b, 0x12),
            byteArrayOf(0x12, 0x08, 0x56, 0xe5.toByte(), 0x80.toByte()))) {
            assertFalse(normalAacLcConfig(config))
        }
    }

    @Test fun derivesPaddingFromAnExactEditAndDecodedFrameExtent() {
        assertEquals(956, timing.padding(44100, 1024, 1024, 0))
        val otherRate = timing.copy(mediaTimescale = 48000, mediaDuration = 49024,
            frameCount = 48, finalDelta = 896)
        assertEquals(128, otherRate.padding(48000, 1024, 1024, 0))
    }

    @Test fun leavesFullFinalFramesAndExistingPaddingAlone() {
        assertNull(timing.copy(finalDelta = 1024).padding(44100, 1024, 1024, 0))
        assertNull(timing.padding(44100, 1024, 1024, 956))
    }

    @Test fun rejectsInconsistentClockFrameOrDelay() {
        assertNull(timing.padding(48000, 1024, 1024, 0))
        assertNull(timing.padding(44100, 960, 1024, 0))
        assertNull(timing.padding(44100, 1024, 0, 0))
        assertNull(timing.copy(mediaDuration = 45125).padding(44100, 1024, 1024, 0))
        assertNull(timing.copy(frameCount = 46).padding(44100, 1024, 1024, 0))
    }

    @Test fun doesNotRoundAnEditToForceAnIntegralSampleCount() {
        assertNull(timing.copy(editDuration = 999).padding(44100, 1024, 1024, 0))
        assertNull(timing.copy(mediaStart = 4096).padding(44100, 1024, 4096, 0))
    }

    @Test fun fixtureMetadataIndependentlyDeclaresOneAudibleSecond() {
        for (name in listOf("boundary.m4a", "boundary-edit-list.m4a")) {
            val bytes = File("src/androidTest/assets/$name").readBytes()
            var position = 0
            while (String(bytes, position + 4, 4, Charsets.US_ASCII) != "moov")
                position += ByteBuffer.wrap(bytes).getInt(position)
            val size = ByteBuffer.wrap(bytes).getInt(position)
            val parsed = AacEditList.parse(bytes.copyOfRange(position + 8, position + size))!!
            assertEquals(1000, parsed.movieTimescale)
            assertEquals(1000, parsed.editDuration)
            assertEquals(44100, parsed.mediaTimescale)
            assertEquals(1024, parsed.mediaStart)
            assertEquals(if (name == "boundary.m4a") 1024 else 68, parsed.finalDelta)
            assertEquals(if (name == "boundary.m4a") null else 956, parsed.padding(44100, 1024, 1024, 0))
        }
    }

    @Test fun rejectsComplexEditsFragmentationAndMultipleTracks() {
        assertNull(AacEditList.parse(moov(edits = 2)))
        assertNull(AacEditList.parse(moov(rate = 0x00008000)))
        assertNull(AacEditList.parse(moov(fragmented = true)))
        assertNull(AacEditList.parse(moov(secondTrack = true)))
    }

    @Test fun rejectsUnsupportedDescriptionsCompositionOffsetsAndInconsistentCounts() {
        assertNull(AacEditList.parse(moov(codec = "alac")))
        assertNull(AacEditList.parse(moov(codec = "enca")))
        assertNull(AacEditList.parse(moov(compositionOffsets = true)))
        assertNull(AacEditList.parse(moov(sampleCount = 44)))
        assertNull(AacEditList.parse(moov(mediaDuration = 45125)))
    }

    @Test fun truncatedAndOversizedBoxesCannotProduceARepair() {
        val valid = moov()
        assertNotNull(AacEditList.parse(valid))
        for (length in 0 until valid.size) assertNull("prefix $length", AacEditList.parse(valid.copyOf(length)))
        val bad = valid.copyOf()
        ByteBuffer.wrap(bad).putInt(Int.MAX_VALUE)
        assertNull(AacEditList.parse(bad))
    }

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private fun box(type: String, bytes: ByteArray) = ints(bytes.size + 8) + type.toByteArray() + bytes
    private fun container(type: String, vararg children: ByteArray) = box(type, children.fold(byteArrayOf()) { a, b -> a + b })
    private fun moov(edits: Int = 1, rate: Int = 0x00010000, fragmented: Boolean = false,
        secondTrack: Boolean = false, codec: String = "mp4a", compositionOffsets: Boolean = false,
        sampleCount: Int = 45, mediaDuration: Int = 45124): ByteArray {
        val table = container("stbl",
            box("stsd", ints(0, 1, 36) + codec.toByteArray() + ByteArray(28)),
            box("stsc", ints(0, 1, 1, 45, 1)),
            box("stts", ints(0, 2, 44, 1024, 1, 68)),
            box("stsz", ints(0, 0, sampleCount)),
            if (compositionOffsets) box("ctts", ints(0, 0)) else byteArrayOf())
        val track = container("trak",
            container("edts", box("elst", ints(0, edits, 1000, 1024, rate))),
            container("mdia", box("hdlr", ints(0, 0) + "soun".toByteArray()),
                box("mdhd", ints(0, 0, 0, 44100, mediaDuration)), container("minf", table)))
        return box("mvhd", ints(0, 0, 0, 1000, 1000)) + track +
            (if (secondTrack) track else byteArrayOf()) + (if (fragmented) box("mvex", byteArrayOf()) else byteArrayOf())
    }
}
