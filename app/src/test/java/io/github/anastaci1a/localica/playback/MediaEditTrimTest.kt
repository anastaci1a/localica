package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test

class MediaEditTrimTest {
    @Test fun bindsMediaTicksToActualDecoderOutputRateWithoutAssumingSbrAbsence() {
        val edit = MediaEditTrim(44100, 1600)
        assertEquals(1600, edit.delayAt(44100))
        assertEquals(3200, edit.delayAt(88200))
        assertEquals(800, edit.delayAt(22050))
        assertEquals(1741, edit.delayAt(48000)) // Floor, less than one output frame.
    }
    @Test fun ptsAfterTheLeadingEditNeverRetrimsEvenAfterRateChanges() {
        val edit = MediaEditTrim(44100, 1600)
        for (rate in listOf(22050, 44100, 48000, 88200)) {
            assertEquals(0, edit.delayAt(rate, 1_000_000))
            assertEquals(0, edit.delayAt(rate, Long.MAX_VALUE))
        }
    }
    @Test fun truncatedMicrosecondPtsRetainsOnlyTheRemainingLeadingFrames() {
        val edit = MediaEditTrim(44100, 1600)
        assertEquals(576, edit.delayAt(44100, 23_219))
        assertEquals(1152, edit.delayAt(88200, 23_219))
        assertEquals(288, edit.delayAt(22050, 23_219))
        assertEquals(0, edit.delayAt(44100, 46_439))
        assertEquals(1600, edit.delayAt(44100, -1))
    }
    @Test fun streamIdentityDoesNotCollapseEqualEditsAcrossIdenticalFiles() {
        assertNotEquals(MediaEditTrim(44100, 1600), MediaEditTrim(44100, 1600))
    }
    @Test fun malformedAndOverflowingUnitsCannotBecomeUntrimmedFallbacks() {
        for (edit in listOf(MediaEditTrim(0, 1), MediaEditTrim(-1, 1), MediaEditTrim(44100, -1),
            MediaEditTrim(1, Long.MAX_VALUE), MediaEditTrim(1, Int.MAX_VALUE.toLong()))) {
            try { edit.delayAt(44100); fail("Invalid trim accepted") } catch (_: IllegalArgumentException) { } catch (_: ArithmeticException) { }
        }
        try { MediaEditTrim(44100, 1600).delayAt(0); fail("Invalid rate accepted") } catch (_: IllegalArgumentException) { }
    }
}
