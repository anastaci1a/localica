package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogChromeTest {
    @Test fun regularPreservesTheExistingGoodDialogViewportMetrics() {
        assertEquals(24, DialogChrome.Regular.perimeterDp)
        assertEquals(48, DialogChrome.Regular.totalPerimeterDp)
        assertEquals(16, DialogChrome.Regular.gapDp)
    }

    @Test fun compactStillReservesTheSameInsetOnEveryEdge() {
        assertEquals(16, DialogChrome.Compact.perimeterDp)
        assertEquals(32, DialogChrome.Compact.totalPerimeterDp)
        assertEquals(8, DialogChrome.Compact.gapDp)
    }

    @Test fun completeRegularLayoutIsTriedBeforeCompact() {
        assertEquals(listOf(DialogChrome.Regular, DialogChrome.Compact), DialogChrome.entries.toList())
    }

    @Test fun missingSlotsDoNotLeavePhantomChromeGaps() {
        DialogChrome.entries.forEach { profile ->
            assertEquals(0, profile.gapCount(hasHeader = false, hasBody = false))
            assertEquals(1, profile.gapCount(hasHeader = true, hasBody = false))
            assertEquals(1, profile.gapCount(hasHeader = false, hasBody = true))
            assertEquals(2, profile.gapCount(hasHeader = true, hasBody = true))
        }
    }

    @Test fun completeFramesReservePerimeterAndBothChromeGaps() {
        fun overhead(profile: DialogChrome) = profile.totalPerimeterDp + profile.gapDp * profile.gapCount(true, true)
        assertEquals(80, overhead(DialogChrome.Regular))
        assertEquals(48, overhead(DialogChrome.Compact))
    }
}
