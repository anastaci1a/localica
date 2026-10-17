package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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

/** Dialog declarations can live outside the artwork theme containing their trigger. */
class GlassForegroundTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightInkTriggerKeepsItsPolarityOutsideTheArtworkTheme() = verifyOrigin(true)
    @Test fun darkInkTriggerKeepsItsPolarityOutsideTheArtworkTheme() = verifyOrigin(false)
    @Test fun contextualArtworkRetainsLightInkAfterItsPaletteLoads() = verifyOrigin(true, contextual = true)
    @Test fun contextualArtworkRetainsDarkInkAfterItsPaletteLoads() = verifyOrigin(false, contextual = true)

    @Test fun unanchoredDialogFollowsCurrentAmbientInkInsteadOfRememberedOrigin() {
        var lightInk by mutableStateOf(false)
        var observed: Triple<Color, Color, Boolean>? = null
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = checkNotNull(LocalGlassDialogs.current)
            val source = rememberFrostedBackdrop(lightInk, dark = lightInk)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    drawRect(if (lightInk) Color(0xFF202020) else Color(0xFFEAEAEA))
                }
                CompositionLocalProvider(LocalContentColor provides if (lightInk) Color.White else Color.Black,
                    LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                    GlassAlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
                        val ink = LocalContentColor.current
                        val theme = MaterialTheme.colorScheme.onSurface
                        val dark = LocalFrostedBackdrop.current?.dark == true
                        SideEffect { observed = Triple(ink, theme, dark) }
                        Text("Ambient foreground")
                    })
                }
            }
        } }
        for (target in listOf(false, true, false)) {
            compose.runOnIdle { lightInk = target }
            compose.onNodeWithText("Ambient foreground").assertIsDisplayed()
            compose.runOnIdle {
                val current = requireNotNull(observed)
                assertEquals("Unanchored foreground follows current ambient ink", target, current.first.luminance() > .5f)
                assertEquals("Theme agrees with ambient ink", target, current.second.luminance() > .5f)
                assertEquals("Material agrees with ambient ink", target, current.third)
                assertEquals("Synthetic origin's optical tone follows ambient ink", target,
                    host.entries.single().anchor.ink.luminance() > .5f)
            }
        }
    }

    private fun verifyOrigin(lightInk: Boolean, contextual: Boolean = false) {
        val ink = if (lightInk) Color.White else Color(0xFF202124)
        val outerInk = if (lightInk) Color(0xFF202124) else Color.White
        var artworkInk by mutableStateOf(if (contextual) outerInk else ink)
        var open by mutableStateOf(false)
        var nested by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var parentView: View
        lateinit var nestedView: View
        val observations = mutableMapOf<String, Triple<Color, Color, Boolean>>()
        @Composable fun ForegroundProbe(name: String) {
            val content = LocalContentColor.current
            val theme = MaterialTheme.colorScheme.onSurface
            val dark = LocalFrostedBackdrop.current?.dark == true
            val view = LocalView.current
            SideEffect {
                observations[name] = Triple(content, theme, dark)
                if (name == "Parent") parentView = view else nestedView = view
            }
            Text("$name foreground", Modifier.testTag("$name-ink"))
        }
        compose.setContent { LocalicaTheme {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = outerInk)) {
                CompositionLocalProvider(LocalContentColor provides outerInk) {
                    val source = rememberFrostedBackdrop(Unit, dark = lightInk)
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                            drawRect(if (lightInk) Color(0xFF202020) else Color(0xFFEAEAEA))
                        }
                        CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source) {
                            CompositionLocalProvider(LocalContentColor provides artworkInk) {
                                anchor = rememberGlassAnchor()
                                if (contextual) Box(Modifier.padding(32.dp).size(88.dp).contextMenuAnchor(anchor)
                                    .clickable { open = true }.testTag("artwork-trigger")) { Text("Artwork") }
                                else {
                                    anchor.captureInk()
                                    GlassActionButton(R.drawable.ic_info, "Open sheet",
                                        Modifier.padding(32.dp).glassAnchor(anchor).testTag("artwork-trigger")) { open = true }
                                }
                            }
                            // This declaration deliberately inherits the opposite outer theme.
                            if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
                                GlassAlertDialog(onDismissRequest = { open = false },
                                    title = { Text("Parent sheet") },
                                    text = {
                                        Column {
                                            ForegroundProbe("Parent")
                                            GlassActionButton(R.drawable.ic_info, "Open nested") { nested = true }
                                            if (nested) GlassAlertDialog(onDismissRequest = { nested = false },
                                                title = { Text("Nested sheet") }, text = { ForegroundProbe("Nested") },
                                                confirmButton = { ActionTextButton(onClick = { nested = false }) { Text("Close nested") } })
                                        }
                                    }, confirmButton = { ActionTextButton(onClick = { open = false }) { Text("Close parent") } })
                            }
                        }
                    }
                }
            }
        } }
        if (contextual) compose.runOnIdle { artworkInk = ink }
        fun assertPolarity(name: String, view: () -> View) {
            compose.onNodeWithTag("$name-ink").assertIsDisplayed()
            compose.runOnIdle {
                val observed = requireNotNull(observations[name])
                assertEquals("$name content follows its trigger", lightInk, observed.first.luminance() > .5f)
                assertEquals("$name theme agrees with its foreground", lightInk, observed.second.luminance() > .5f)
                assertEquals("$name glass agrees with its foreground", lightInk, observed.third)
            }
            compose.waitUntil(5000) {
                val pixels = capturePopupPixels(compose, compose.onNodeWithTag("$name-ink"), view)
                val inkPixels = (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
                    val color = pixels[x, y]
                    if (lightInk) color.red > .85f && color.green > .85f && color.blue > .85f
                    else color.red < .2f && color.green < .2f && color.blue < .2f
                } }
                inkPixels > 12
            }
        }
        repeat(2) {
            compose.onNodeWithTag("artwork-trigger").performClick()
            assertPolarity("Parent") { parentView }
            compose.onNodeWithText("Open nested").performClick()
            assertPolarity("Nested") { nestedView }
            compose.onNodeWithText("Close nested").performClick()
            compose.onNodeWithText("Nested sheet").assertDoesNotExist()
            assertPolarity("Parent") { parentView }
            compose.onNodeWithText("Close parent").performClick()
            compose.waitUntil(5000) { !anchor.hidden && !anchor.modalPresent }
            compose.onNodeWithTag("artwork-trigger").assertIsDisplayed()
            compose.runOnIdle { assertEquals("Return retains the original trigger ink", ink, anchor.ink) }
        }
    }
}
