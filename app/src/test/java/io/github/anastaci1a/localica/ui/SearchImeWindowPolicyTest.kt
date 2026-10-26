package io.github.anastaci1a.localica.ui

import android.view.WindowManager.LayoutParams
import org.junit.Assert.*
import org.junit.Test

class SearchImeWindowPolicyTest {
    @Test fun focusPreservesVisibilityWithoutRequestingAShowOrHide() {
        for (state in 0..5) {
            val result = searchWindowSoftInputMode(state)
            assertEquals(LayoutParams.SOFT_INPUT_STATE_UNCHANGED, result)
            assertNotEquals(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, result)
            assertNotEquals(LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN, result)
        }
    }

    @Test fun everyAdjustmentAndForwardNavigationBitIsPreserved() {
        for (adjust in listOf(0, 16, 32, 48)) for (forward in listOf(0, 256)) for (state in 0..5) {
            val input = adjust or forward or state
            val output = searchWindowSoftInputMode(input)
            assertEquals(adjust or forward, output and LayoutParams.SOFT_INPUT_MASK_STATE.inv())
        }
    }

    @Test fun repeatedComposeAttributeUpdatesKeepTheSamePolicy() {
        for (mode in listOf(0, 16, 32, 48, 256, 304)) {
            val once = searchWindowSoftInputMode(mode)
            assertEquals(once, searchWindowSoftInputMode(once))
        }
    }

    @Test fun unrelatedHighBitsAreNotLost() {
        val input = Int.MIN_VALUE or 0x400 or LayoutParams.SOFT_INPUT_ADJUST_RESIZE or 5
        assertEquals(input and LayoutParams.SOFT_INPUT_MASK_STATE.inv(),
            searchWindowSoftInputMode(input) and LayoutParams.SOFT_INPUT_MASK_STATE.inv())
    }
}
