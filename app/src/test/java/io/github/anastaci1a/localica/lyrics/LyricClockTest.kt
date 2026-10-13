package io.github.anastaci1a.localica.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricClockTest {
    @Test fun interpolationUsesPlaybackRateAndStopsWhenPausedOrStale() {
        assertEquals(1250L, lyricPosition(1000, 2000, 2250, true, 1f, 0))
        assertEquals(1500L, lyricPosition(1000, 2000, 2250, true, 2f, 0))
        assertEquals(1000L, lyricPosition(1000, 2000, 2250, false, 1f, 0))
        assertEquals(1500L, lyricPosition(1000, 2000, 9000, true, 1f, 0))
        assertEquals(1000L, lyricPosition(1000, 0, 9000, true, 1f, 0))
    }
    @Test fun positiveCorrectionDelaysLyricsWithoutMovingTheMediaClock() {
        assertEquals(800L, lyricPosition(1000, 2000, 2100, true, 1f, 300))
        assertEquals(1400L, lyricPosition(1000, 2000, 2100, true, 1f, -300))
        assertEquals(0L, lyricPosition(50, 2000, 2100, false, 1f, 300))
        assertEquals(1150L, lyricPosition(1000, 2000, 2300, true, 1f, 0, 1150))
    }
}
