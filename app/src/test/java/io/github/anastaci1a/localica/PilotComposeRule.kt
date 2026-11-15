package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*

internal fun pilotComposeRule(): ComposeContentTestRule {
    val rule = localicaComposeRule()
    return object : ComposeContentTestRule by rule {
        override fun setContent(composable: @Composable () -> Unit) = rule.setContent {
            val appearance = remember { GlassAppearance(renderer = GlassRendererMode.FlatNone) }
            val renderer = remember { GlassRendererRuntime(appearance.renderer) }
            CompositionLocalProvider(LocalGlassAppearance provides appearance, LocalGlassRendererRuntime provides renderer) {
                check(LocalGlassAppearance.current.renderer == GlassRendererMode.FlatNone && flatGlass())
                composable()
            }
        }
    }
}
