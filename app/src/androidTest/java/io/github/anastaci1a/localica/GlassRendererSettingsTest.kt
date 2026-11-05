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
        else "Using "+mode.label+" on this device."

    private fun choice(mode: GlassRendererMode) =
        compose.onNode(hasText(mode.label) and isSelectable())

    @Test fun supportedChoicesStageUntilSelectAndUpdateTheSharedRuntime() {
        var mode by mutableStateOf(GlassRendererMode.Normal)
        var changes = 0
        val runtime = GlassRendererRuntime(mode)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalGlassRendererRuntime provides runtime) {
                SideEffect { runtime.request(mode) }
                Column { GlassRendererPreference(mode) { mode = it; changes++ } }
            }
        } }
        val supported = listOf(GlassRendererMode.None, GlassRendererMode.Normal)
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
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column { GlassRendererPreference(mode) { fail("Cancel must not rewrite a restored request") } }
            }
        } }
        val message = explanation(mode)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Glass renderer").performClick()
        choice(GlassRendererMode.Normal).assertIsSelected()
        choice(GlassRendererMode.None).performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test fun actualGlassSettingsResetRestoresNormalWithTheOtherDefaults() {
        var value by mutableStateOf(GlassAppearance(blur = .7f, depth = .9f, renderer = GlassRendererMode.None))
        var changes = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GlassSettingsControls(value) { value = it; changes++ }
                }
            }
        } }
        compose.onNodeWithText(explanation(GlassRendererMode.None)).assertIsDisplayed()
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(), value); assertEquals(1, changes) }
        compose.onNodeWithText("Glass renderer").performScrollTo()
        compose.onNodeWithText(explanation(GlassRendererMode.Normal))
            .assertIsDisplayed()
    }
}
