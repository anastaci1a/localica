package io.github.anastaci1a.localica

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #633: Brightness weighting spans 1–8 in Settings and storage; saved pre-#633 values load unchanged. */
class BrightnessWeightingTest {
    @get:Rule val compose = createComposeRule()

    private fun labels(): List<String> = listOf(true, false).flatMap { merged ->
        compose.onAllNodes(hasText("Brightness weighting ·", substring = true), merged).fetchSemanticsNodes().flatMap { n ->
            if (n.config.contains(SemanticsProperties.Text)) n.config[SemanticsProperties.Text].map { it.text } else emptyList()
        }
    }

    @Test fun sliderReachesEightSnapsToTheDefaultStepAndResets() {
        var value by mutableStateOf(GlassAppearance())
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GlassSettingsControls(value, "Glass advanced") { value = it }
                }
            }
        } }
        val slider = compose.onNodeWithContentDescription("Brightness weighting")
        slider.performScrollTo()
        assertTrue("Default label: ${labels()}", labels().any { it.endsWith("· 2.00") })
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f..8f, range.range)
        assertEquals(GlassContrastWeightSteps, range.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.runOnIdle { assertEquals(8f, value.contrastWeight, 0f) }
        compose.waitForIdle()
        assertTrue("Maximum label: ${labels()}", labels().any { it.endsWith("· 8.00") })
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2.03f) }
        compose.runOnIdle { assertEquals(2f, value.contrastWeight, 0f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertTrue("${value.contrastWeight}", value.contrastWeight in 3.5f..4.5f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(1f, value.contrastWeight, 0f) }
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2f, value.contrastWeight, 0f) }
    }

    @Test fun weightsRoundTripThroughPreferencesAndClamp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val store = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        val original = preferences.read()
        try {
            for (weight in listOf(1f, 1.2f, 1.95f, 2f, 2.75f, 5.123f, 8f)) {
                preferences.write(original.copy(glass = original.glass.copy(contrastWeight = weight)))
                assertEquals(weight, InterfacePreferences(context).read().glass.contrastWeight, 0f)
            }
            // A value saved before #633 under the same key still loads as itself.
            store.edit().putFloat("glass-contrast-weight", 1.65f).commit()
            assertEquals(1.65f, preferences.read().glass.contrastWeight, 0f)
            store.edit().putFloat("glass-contrast-weight", 12f).commit()
            assertEquals(8f, preferences.read().glass.contrastWeight, 0f)
            store.edit().putFloat("glass-contrast-weight", .2f).commit()
            assertEquals(1f, preferences.read().glass.contrastWeight, 0f)
            store.edit().putFloat("glass-contrast-weight", Float.NaN).commit()
            assertEquals(2f, preferences.read().glass.contrastWeight, 0f)
            store.edit().remove("glass-contrast-weight").commit()
            assertEquals(2f, preferences.read().glass.contrastWeight, 0f)
        } finally { preferences.write(original) }
    }
}
