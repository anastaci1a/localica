package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerHarnessClockTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun harnessBorrowsThemeOwnerKeepsIdleIntentTimeAndRestoresOnDisposal() {
        lateinit var root: UiClock
        val owners = mutableListOf<UiClock>()
        var shown by mutableStateOf(true)
        val recorder = object : MotionEvents {
            override fun created(motion: MotionFloat) { owners += motion.clock }
            override fun joined(motion: MotionFloat, join: MotionJoin) = Unit
            override fun retired(motion: MotionFloat, stamp: FrameStamp) = Unit
        }
        compose.setContent { LocalicaTheme {
            root = LocalUiClock.current
            remember(root) { root.events = recorder; true }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val progress = remember { Animatable(0f) }
                if (shown) PlayerSurface(PlaybackState(), false, {}, InterfaceSettings(), progress,
                    {}, {}, {}, {}, {}, {})
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertTrue("Fixture created real Engine owners", owners.isNotEmpty())
            assertTrue("Every fixture owner borrows the theme root", owners.all { it === root })
            assertNotNull(root.intentNow)
            assertEquals(0, root.activeCount)
        }
        val before = compose.runOnIdle { root.presentedStamp.nanos }
        compose.mainClock.advanceTimeBy(512)
        compose.runOnIdle {
            assertEquals("Idle root need not present a frame", before, root.presentedStamp.nanos)
            assertEquals("First idle intent uses virtual now", compose.mainClock.currentTime * 1_000_000, root.stampNow.nanos)
            shown = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(root.intentNow); assertEquals(0, root.activeCount) }
        compose.mainClock.autoAdvance = true
    }
}
