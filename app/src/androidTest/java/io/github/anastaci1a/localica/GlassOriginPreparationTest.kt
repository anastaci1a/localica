package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassOriginPreparationTest {
    @get:Rule val compose = createComposeRule()
    private var open by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private lateinit var preparation: GlassOriginPreparation
    private lateinit var anchor: GlassAnchor

    private fun fixture(reduced: Boolean = false) {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                preparation = rememberGlassOriginPreparation()
                anchor = rememberGlassAnchor()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp).size(48.dp).glassAnchor(anchor))
                    if (mounted) GlassMorphPopup(open, anchor, { open = false },
                        originPreparation = preparation) { Box(Modifier.size(180.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun preparationPrecedesModalOwnershipAndRejoinsAfterReturn() {
        fixture()
        try {
            compose.runOnIdle { open = true }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertTrue(preparation.progress > 0f && preparation.progress < 1f)
                assertFalse(anchor.modalPresent)
                assertFalse(anchor.hidden)
            }
            compose.mainClock.advanceTimeBy(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); assertEquals(1f, preparation.progress, 0f) }
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertEquals("Origin stays prepared throughout modal return", 1f, preparation.progress, 0f)
            }
            compose.mainClock.advanceTimeBy(4000)
            compose.runOnIdle {
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
                assertEquals(0f, preparation.progress, 0f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cancelledPreparationAndReopenNeverCreateAnEarlyModal() {
        fixture()
        try {
            compose.runOnIdle { open = true }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { assertFalse(anchor.modalPresent); open = true }
            compose.mainClock.advanceTimeBy(4000)
            compose.runOnIdle { assertTrue(anchor.modalPresent); assertEquals(1f, preparation.progress, 0f) }
            compose.runOnIdle { mounted = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(0f, preparation.progress, 0f)
                assertFalse(anchor.modalPresent); assertFalse(anchor.hidden)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionRetainsStagingEndpointsWithoutAnimatedDelay() {
        fixture(reduced = true)
        try {
            compose.runOnIdle { open = true }
            repeat(5) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle { assertEquals(1f, preparation.progress, 0f); assertTrue(anchor.modalPresent) }
            compose.runOnIdle { open = false }
            repeat(10) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle { assertEquals(0f, preparation.progress, 0f); assertFalse(anchor.modalPresent) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
