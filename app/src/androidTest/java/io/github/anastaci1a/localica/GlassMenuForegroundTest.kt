package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMenuForegroundTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anchoredMenuKeepsLightInkDespiteOppositeGlobalScene() = verify(true, true)
    @Test fun anchoredMenuKeepsDarkInkDespiteOppositeGlobalScene() = verify(false, true)
    @Test fun detachedMenuUsesCurrentLightInkInsteadOfItsOldAnchorSeed() = verify(true, false)
    @Test fun detachedMenuUsesCurrentDarkInkInsteadOfItsOldAnchorSeed() = verify(false, false)

    private fun verify(lightInk: Boolean, anchored: Boolean) {
        val ink = if (lightInk) Color.White else Color(0xFF202124)
        val outerInk = if (lightInk) Color(0xFF202124) else Color.White
        var open by mutableStateOf(false)
        var nested by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var popupView: View
        var observed: Triple<Color, Color, Boolean>? = null
        var nestedAppearance: Pair<Color, Boolean>? = null
        @Composable fun Menu() {
            GlassMenu(open, anchor, { open = false }) {
                val content = LocalContentColor.current
                val theme = MaterialTheme.colorScheme.onSurface
                val dark = LocalFrostedBackdrop.current?.dark == true
                val view = LocalView.current
                SideEffect { observed = Triple(content, theme, dark); popupView = view }
                Text("Menu foreground", Modifier.testTag("menu-foreground"))
                ActionTextButton({ nested = true }) { Text("Open nested") }
                ActionTextButton({ open = false }) { Text("Close menu") }
                if (nested) GlassAlertDialog({ nested = false }, title = { Text("Nested foreground") }, text = {
                    val nestedInk = LocalContentColor.current
                    val nestedDark = LocalFrostedBackdrop.current?.dark == true
                    SideEffect { nestedAppearance = nestedInk to nestedDark }
                }, confirmButton = { ActionTextButton({ nested = false }) { Text("Close nested") } })
            }
        }
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides outerInk) {
                anchor = rememberGlassAnchor()
                // The global recorder's flag is deliberately opposite to the
                // actual page/trigger, as on a dark collection with player closed.
                val source = rememberFrostedBackdrop(Unit, dark = !lightInk)
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(if (lightInk) Color(0xFF202020) else Color(0xFFEAEAEA))
                    }
                    CompositionLocalProvider(LocalModalBackdrop provides source, LocalFrostedBackdrop provides source) {
                        CompositionLocalProvider(LocalContentColor provides ink) {
                            if (anchored) {
                                anchor.captureInk()
                                GlassActionButton(R.drawable.ic_info, "Open menu",
                                    Modifier.padding(32.dp).glassAnchor(anchor).testTag("menu-trigger")) { open = true }
                            } else ActionTextButton({ open = true }, Modifier.testTag("menu-trigger")) { Text("Open menu") }
                        }
                        // Anchored declarations live outside the page's ink scope.
                        if (anchored) Menu()
                        else CompositionLocalProvider(LocalContentColor provides ink) { Menu() }
                    }
                }
            }
        } }
        compose.onNodeWithTag("menu-trigger").performClick()
        compose.onNodeWithTag("menu-foreground").assertIsDisplayed()
        compose.runOnIdle {
            val appearance = requireNotNull(observed)
            assertEquals("Menu content follows page ink", lightInk, appearance.first.luminance() > .5f)
            assertEquals("Menu theme agrees", lightInk, appearance.second.luminance() > .5f)
            assertEquals("Menu material agrees", lightInk, appearance.third)
            if (!anchored) assertEquals("Detached anchor retains its original seed", outerInk, anchor.ink)
        }
        compose.waitUntil(5000) {
            val pixels = capturePopupPixels(compose, compose.onNodeWithTag("menu-foreground")) { popupView }
            (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
                val color = pixels[x, y]
                if (lightInk) color.red > .85f && color.green > .85f && color.blue > .85f
                else color.red < .2f && color.green < .2f && color.blue < .2f
            } } > 12
        }
        compose.onNodeWithText("Open nested").performClick()
        compose.onNodeWithText("Nested foreground").assertIsDisplayed()
        compose.runOnIdle {
            val appearance = requireNotNull(nestedAppearance)
            assertEquals("Nested text inherits menu ink", lightInk, appearance.first.luminance() > .5f)
            assertEquals("Nested material agrees", lightInk, appearance.second)
        }
        compose.onNodeWithText("Close nested").performClick()
        compose.onNodeWithText("Close menu").performClick()
        awaitGlassReturn(compose, anchor) { "lightInk=$lightInk anchored=$anchored open=$open nested=$nested" }
        compose.onNodeWithTag("menu-trigger").assertIsDisplayed()
        if (anchored) compose.runOnIdle { assertEquals("Returned trigger keeps its ink", ink, anchor.ink) }
    }
}
