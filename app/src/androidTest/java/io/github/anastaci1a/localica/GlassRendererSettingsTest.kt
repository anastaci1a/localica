package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassRendererSettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun choice(mode: GlassRendererMode) =
        compose.onNode(hasText(mode.label) and isSelectable())

    @Test fun supportedChoicesStageUntilSelectAndUpdateTheSharedRuntime() {
        var mode by mutableStateOf(GlassRendererMode.Automatic)
        var changes = 0
        val runtime = GlassRendererRuntime(mode)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalGlassRendererRuntime provides runtime) {
                SideEffect { runtime.request(mode) }
                Column { GlassRendererPreference(mode) { mode = it; changes++ } }
            }
        } }
        val supported = if (Build.VERSION.SDK_INT >= 33)
            listOf(GlassRendererMode.Legacy, GlassRendererMode.Modern, GlassRendererMode.Automatic)
        else listOf(GlassRendererMode.Legacy, GlassRendererMode.Automatic)
        for ((index, next) in supported.withIndex()) {
            val before = mode
            compose.onNodeWithText("Glass renderer").performClick()
            if (Build.VERSION.SDK_INT < 33) choice(GlassRendererMode.Modern).assertDoesNotExist()
            choice(next).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(before, mode); assertEquals(index, changes) }
            compose.onNodeWithText("Select", substring = false).performClick()
            compose.runOnIdle {
                assertEquals(next, mode)
                assertEquals(index + 1, changes)
                assertEquals(next, runtime.requested)
                assertEquals(effectiveGlassBackend(next, Build.VERSION.SDK_INT), runtime.generation.backend)
            }
            val message = if (next == GlassRendererMode.Automatic)
                "Automatic uses ${runtime.generation.backend.name} on this device."
            else "Using ${next.label} on this device."
            compose.onNodeWithText(message).assertIsDisplayed()
        }
    }

    @Test fun cancellingPreservesRestoredModernAndExplainsUnsupportedFallback() {
        val mode = GlassRendererMode.Modern
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column { GlassRendererPreference(mode) { fail("Cancel must not rewrite a restored request") } }
            }
        } }
        val message = if (Build.VERSION.SDK_INT < 33)
            "Modern is saved, but this Android version uses Legacy. Modern requires Android 13 or later."
        else "Using Modern on this device."
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Glass renderer").performClick()
        if (Build.VERSION.SDK_INT < 33) choice(GlassRendererMode.Modern).assertDoesNotExist()
        else choice(GlassRendererMode.Modern).assertIsSelected()
        choice(GlassRendererMode.Legacy).performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test fun liveScrollGlassSwitchIsOffByDefaultToggleableAndResets() {
        var value by mutableStateOf(GlassAppearance())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GlassSettingsControls(value) { value = it }
                }
            }
        } }
        val row = compose.onNode(hasText("Live glass while scrolling") and isToggleable())
        row.performScrollTo().assertIsOff()
        compose.onNodeWithText("Off holds glass controls", substring = true).assertExists()
        row.performClick().assertIsOn()
        compose.runOnIdle { assertTrue(value.liveScrollGlass) }
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(), value) }
        compose.onNode(hasText("Live glass while scrolling") and isToggleable()).performScrollTo().assertIsOff()
    }

    @Test fun actualGlassSettingsResetRestoresAutomaticWithTheOtherDefaults() {
        var value by mutableStateOf(GlassAppearance(blur = .7f, depth = .9f, renderer = GlassRendererMode.Legacy))
        var changes = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GlassSettingsControls(value) { value = it; changes++ }
                }
            }
        } }
        compose.onNodeWithText("Using Legacy on this device.").assertIsDisplayed()
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(), value); assertEquals(1, changes) }
        compose.onNodeWithText("Glass renderer").performScrollTo()
        compose.onNodeWithText("Automatic uses ${effectiveGlassBackend(GlassRendererMode.Automatic, Build.VERSION.SDK_INT).name} on this device.")
            .assertIsDisplayed()
    }
}
