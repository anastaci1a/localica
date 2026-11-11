package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassRendererSettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun explanation(mode: GlassRendererMode) = if (mode == GlassRendererMode.Normal && Build.VERSION.SDK_INT < 33)
        "Normal uses solid average colour on this Android version. Refraction requires Android 13 or later."
        else when (mode) {
            GlassRendererMode.SingleToneBlur -> "Single-Tone Blur is a testing mode that averages the scene beneath each surface."
            GlassRendererMode.FlatNone -> "Opaque theme fills without glass effects."
            else -> "Using " + mode.label + " on this device."
        }

    private fun choice(mode: GlassRendererMode) =
        compose.onNode(hasText(mode.label) and isSelectable())

    @Test fun supportedChoicesStageUntilSelectAndUpdateTheSharedRuntime() {
        var mode by mutableStateOf(GlassRendererMode.Normal)
        var changes = 0
        val runtime = GlassRendererRuntime(mode)
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalGlassRendererRuntime provides runtime) {
                SideEffect { runtime.request(mode) }
                Column { GlassRendererPreference(mode) { mode = it; changes++ } }
            }
        } }
        val supported = listOf(GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone, GlassRendererMode.Normal)
        for ((index, next) in supported.withIndex()) {
            val before = mode
            compose.onNodeWithText("Glass renderer").performClick()
            choice(next).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(before, mode); assertEquals(index, changes) }
            compose.onNodeWithText("Select", substring = false).performClick()
            compose.runOnIdle {
                assertEquals(next, mode)
                assertEquals(index + 1, changes)
                assertEquals(next, runtime.requested)
                assertEquals(effectiveGlassBackend(next, Build.VERSION.SDK_INT), runtime.generation.backend)
            }
            val message = explanation(next)
            compose.onNodeWithText(message).assertIsDisplayed()
        }
    }

    @Test fun cancellingPreservesNormalAndExplainsOlderApiFallback() {
        val mode = GlassRendererMode.Normal
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column { GlassRendererPreference(mode) { fail("Cancel must not rewrite a restored request") } }
            }
        } }
        val message = explanation(mode)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Glass renderer").performClick()
        choice(GlassRendererMode.Normal).assertIsSelected()
        choice(GlassRendererMode.SingleToneBlur).performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test fun actualGlassSettingsResetRestoresNormalWithTheOtherDefaults() {
        var value by mutableStateOf(GlassAppearance(blur = .7f, depth = .9f, renderer = GlassRendererMode.SingleToneBlur))
        var changes = 0
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Glass",
                    interfaceContent = { section -> GlassSettingsControls(value, section) { value = it; changes++ } })
            }
        } }
        compose.onNodeWithText(explanation(GlassRendererMode.SingleToneBlur)).assertIsDisplayed()
        compose.onNodeWithText("Glass advanced").assertIsNotEnabled()
        compose.onNodeWithText("Glass renderer").performClick()
        choice(GlassRendererMode.Normal).performClick()
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.onNodeWithText("Glass advanced").performScrollTo().performClick()
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(), value); assertEquals(2, changes) }
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Glass renderer").performScrollTo()
        compose.onNodeWithText(explanation(GlassRendererMode.Normal))
            .assertIsDisplayed()
    }
}
