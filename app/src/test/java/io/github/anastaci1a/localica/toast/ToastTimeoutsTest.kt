package io.github.anastaci1a.localica.toast

import org.junit.Assert.*
import org.junit.Test

class ToastTimeoutsTest {
    @Test fun onlyInfinityUsesFiniteAccessibleFallback() {
        assertEquals(60_000L, toastRecommendedDwell(3000, Long.MAX_VALUE))
        assertEquals(90_000L, toastRecommendedDwell(90_000, Long.MAX_VALUE))
        for (finite in listOf(0L, 2000L, 3000L, 45_000L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE - 1))
            assertEquals(finite, toastRecommendedDwell(3000, finite))
    }
}
