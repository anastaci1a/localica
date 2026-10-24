package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class RoundedAudioEditTest {
    private data class Fixture(val all: ByteArray, val offset: Int, val payload: ByteArray)
    private fun fixture(name: String = "boundary-rounded-edit.m4a"): Fixture {
        val all = File("src/androidTest/assets/$name").readBytes()
        var at = 0
        while (String(all, at + 4, 4, Charsets.US_ASCII) != "moov") at += ByteBuffer.wrap(all).getInt(at)
        val end = at + ByteBuffer.wrap(all).getInt(at)
        return Fixture(all, at + 8, all.copyOfRange(at + 8, end))
    }
    private fun parse(f: Fixture): RoundedAudioEdit? {
        val payloads = mutableListOf<LongRange>()
        var at = 0
        while (at < f.all.size) {
            val size = ByteBuffer.wrap(f.all).getInt(at)
            if (String(f.all, at + 4, 4, Charsets.US_ASCII) == "mdat") payloads += (at + 8).toLong() until (at + size).toLong()
            at += size
        }
        return roundedAudioEdit(f.payload, f.offset.toLong(), f.all.size.toLong())?.takeIf { it.isInside(payloads) }
    }
    private fun tag(bytes: ByteArray, tag: String): Int = bytes.indices.first { i ->
        i + 4 <= bytes.size && tag.indices.all { j -> bytes[i + j] == tag[j].code.toByte() }
    }
    @Test fun clockCorrectionCarriesAuthoritativeUnitsWithoutClaimingSbrAbsence() {
        for (name in listOf("boundary-rounded-edit.m4a", "boundary-rounded-short-config.m4a", "boundary-rounded-streamable.m4a")) {
            val f = fixture(name)
            val correction = parse(f)!!
            assertEquals(4, correction.patches.size)
            assertEquals(1600, correction.trim.delayAt(44100))
            assertEquals(3200, correction.trim.delayAt(88200))
            val changed = f.all.copyOf()
            correction.patches.forEach { it.bytes.copyInto(changed, it.offset.toInt()) }
            val movie = tag(changed, "mvhd") + 4
            val edit = tag(changed, "elst") + 4
            val track = tag(changed, "tkhd") + 4
            assertEquals(44100, ByteBuffer.wrap(changed).getInt(movie + 12))
            assertEquals(44480, ByteBuffer.wrap(changed).getInt(movie + 16))
            assertEquals(44480, ByteBuffer.wrap(changed).getInt(track + 20))
            assertEquals(44480, ByteBuffer.wrap(changed).getInt(edit + 8))
            assertEquals(1600, ByteBuffer.wrap(changed).getInt(edit + 12))
            assertArrayEquals("Every media byte is unchanged", f.all.copyOfRange(0, f.offset - 8), changed.copyOfRange(0, f.offset - 8))
            assertNull("The exact new clock needs no further correction", roundedAudioEdit(changed.copyOfRange(f.offset, f.offset + f.payload.size), f.offset.toLong(), changed.size.toLong()))
        }
    }
    @Test fun doesNotActivateOnExistingSupportedOrAmbiguousShortFinalSampleFixtures() {
        for (name in listOf("boundary.m4a", "boundary-edit-list.m4a", "boundary-short-config.m4a", "boundary-silent-tail.m4a")) assertNull(name, parse(fixture(name)))
    }
    @Test fun requiresCoherentHeadersAndOnlyHalfMovieTickOverrun() {
        val f = fixture()
        for (duration in listOf(1008, 1010, 0, Int.MAX_VALUE)) {
            val p = f.payload.copyOf()
            for ((name, offset) in listOf("mvhd" to 16, "tkhd" to 20, "elst" to 8)) ByteBuffer.wrap(p).putInt(tag(p, name) + 4 + offset, duration)
            assertNull("duration $duration", parse(f.copy(payload = p)))
        }
        val bad = f.payload.copyOf()
        ByteBuffer.wrap(bad).putInt(tag(bad, "tkhd") + 4 + 20, 1008)
        assertNull(parse(f.copy(payload = bad)))
    }
    @Test fun negativeEditAndForeignCodecCannotActivate() {
        val f = fixture()
        val negative = f.payload.copyOf()
        ByteBuffer.wrap(negative).putInt(tag(negative, "elst") + 4 + 12, -1)
        assertNull(parse(f.copy(payload = negative)))
        val codec = f.payload.copyOf()
        // Fixture provenance identifies DecoderConfigDescriptor's MPEG-4 Audio type byte.
        codec[tag(codec, "esds") + 21] = 0x6b // MPEG audio, not AAC.
        assertNull(parse(f.copy(payload = codec)))
    }
    @Test fun rejectsExistingGaplessMetadataAndMalformedPacketOffsets() {
        val f = fixture()
        assertNull(parse(f.copy(payload = f.payload + "iTunSMPB".toByteArray())))
        val bad = f.payload.copyOf()
        val offset = tag(bad, "stco") + 4
        ByteBuffer.wrap(bad).putInt(offset + 8, Int.MAX_VALUE)
        assertNull(parse(f.copy(payload = bad)))
    }

    @Test fun inFileChunksOutsideActualMediaPayloadCannotActivate() {
        val f = fixture()
        val bad = f.payload.copyOf()
        ByteBuffer.wrap(bad).putInt(tag(bad, "stco") + 4 + 8, 16)
        // The file bounds alone permit this range. The ftyp/header bytes are not mdat.
        assertNotNull(roundedAudioEdit(bad, f.offset.toLong(), f.all.size.toLong()))
        assertNull(parse(f.copy(payload = bad)))
    }

    @Test fun unknownMediaDurationNeverBecomesAnAuthoritativeEndpoint() {
        val f = fixture()
        val bad = f.payload.copyOf()
        ByteBuffer.wrap(bad).putInt(tag(bad, "mdhd") + 4 + 16, -1)
        assertNull(parse(f.copy(payload = bad)))
    }
    @Test fun zeroNativeDelayCannotActivateAClockRewriteThatStillNeedsPreroll() {
        val f = fixture("boundary-rounded-short-config.m4a")
        val p = f.payload.copyOf()
        for ((name, offset) in listOf("mvhd" to 16, "tkhd" to 20, "elst" to 8))
            ByteBuffer.wrap(p).putInt(tag(p, name) + 4 + offset, 1045)
        ByteBuffer.wrap(p).putInt(tag(p, "elst") + 4 + 12, 1)
        // This fixture's two-byte descriptor starts 39 bytes after its esds type.
        assertEquals(0x12, p[tag(p, "esds") + 39].toInt() and 255)
        p[tag(p, "esds") + 39] = 0x16 // Standard 7350-Hz AAC LC.
        assertNull(parse(f.copy(payload = p)))
    }

    @Test fun truncationInsideAnyDeclaredBoxCannotReturnClockPatches() {
        val f = fixture()
        val boundaries = mutableSetOf<Int>()
        var at = 0
        while (at < f.payload.size) { at += ByteBuffer.wrap(f.payload).getInt(at); boundaries += at }
        // Removing whole optional trailing boxes can leave a valid complete movie payload.
        // Cutting inside any declared box must fail closed.
        for (size in f.payload.indices) if (size !in boundaries)
            assertNull("prefix $size", parse(f.copy(payload = f.payload.copyOf(size))))
    }
}
