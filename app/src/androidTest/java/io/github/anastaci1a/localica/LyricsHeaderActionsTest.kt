package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LyricsHeaderActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realHeaderSplitsBeforeOwnershipAndRejoinsAfterReturn() {
        var sources by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var preparation: GlassOriginPreparation
        lateinit var backing: FrostedBackdrop
        compose.setContent { ClearGlassTheme {
            anchor = rememberGlassAnchor()
            preparation = rememberGlassOriginPreparation()
            backing = rememberFrostedBackdrop(Unit)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing)) {
                    val tile = 16.dp.toPx()
                    val colors = listOf(Color(0xFF8BC7BD), Color(0xFFF0C06F), Color(0xFF7D91C9), Color(0xFFCD83A7))
                    for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt()) {
                        drawRect(colors[(x + 3 * y) % colors.size], Offset(x * tile, y * tile), Size(tile, tile))
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides backing) { Box(Modifier.padding(32.dp)) {
                LyricsHeaderActions(true, 1f, anchor, preparation, {}, { sources = true }, {})
                GlassMenu(sources, anchor, { sources = false }, originPreparation = preparation) {
                    ActionMenuItem("Embedded lyrics") { sources = false }
                }
                } }
            }
        } }
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing.lensSnapshot != null }
        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("player-lyrics-pill").captureToImage().asAndroidBitmap()
            val folder = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            java.io.File(folder, "lyrics-header-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            capture("joined")
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            capture("held")
            compose.onNodeWithContentDescription("Lyrics sources").performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(4000)
            compose.onNodeWithContentDescription("Lyrics sources").performClick()
            compose.mainClock.advanceTimeBy(100)
            capture("preparing")
            compose.runOnIdle {
                assertTrue(preparation.progress > 0f)
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
            }
            compose.onNodeWithContentDescription("Hide lyrics").assertExists()
            compose.mainClock.advanceTimeBy(4000)
            capture("modal-owned")
            compose.runOnIdle {
                assertTrue(anchor.modalPresent)
                assertTrue(anchor.hidden)
                assertEquals(1f, preparation.progress, 0f)
            }
            compose.runOnIdle { sources = false }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertEquals(1f, preparation.progress, 0f) }
            compose.mainClock.advanceTimeBy(4000)
            capture("rejoined")
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
                assertEquals(0f, preparation.progress, 0f)
            }
            compose.onNodeWithContentDescription("Lyrics sources").assertExists()
            compose.onNodeWithContentDescription("Hide lyrics").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun interruptedPreparationDoesNotLeaveTheTriggerHidden() {
        var open by mutableStateOf(true)
        var sources by mutableStateOf(false)
        lateinit var preparation: GlassOriginPreparation
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme {
            preparation = rememberGlassOriginPreparation()
            anchor = rememberGlassAnchor()
            Box(Modifier.fillMaxSize().padding(32.dp)) {
                LyricsHeaderActions(open, if (open) 1f else 0f, anchor, preparation,
                    { open = true }, { sources = true }, { open = false; sources = false })
                GlassMenu(sources, anchor, { sources = false }, originPreparation = preparation) {
                    ActionMenuItem("Embedded lyrics") { sources = false }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Lyrics sources").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithContentDescription("Hide lyrics").performClick()
            compose.mainClock.advanceTimeBy(4000)
            compose.runOnIdle {
                assertFalse(anchor.hidden)
                assertFalse(anchor.modalPresent)
                assertEquals(0f, preparation.progress, 0f)
            }
            compose.onNodeWithContentDescription("Show lyrics").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Lyrics sources").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }
}
