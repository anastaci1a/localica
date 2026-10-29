package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mostlyDarkArtworkKeepsLightInkAndSettingsTrimItsWhiteExtension() {
        val art = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
            for (y in 72 until height) for (x in 0 until width) setPixel(x, y, android.graphics.Color.WHITE)
        }
        val sampled = runBlocking { playerPalette(art) }
        val whiteWash = ArtworkWash.create(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        })
        assertEquals("The blur must not bake in its own dark gradient", android.graphics.Color.WHITE,
            whiteWash.getPixel(whiteWash.width / 2, whiteWash.height - 1))
        val palette = sampled.copy(wash = whiteWash, softWash = whiteWash)
        assertFalse("White bottom must not flip page-wide ink", palette.isLight())
        var appearance by mutableStateOf(GlassAppearance(contrast = 0f))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides appearance) {
                PlayerBackdrop(palette, Modifier.size(160.dp, 300.dp).testTag("extension"),
                    fullArtBlend = 1f, artHeight = 100f, artWidth = 100f,
                    tone = GlassTone(appearance.contrast, appearance.contrastWeight, !palette.isLight()))
            }
        } }
        fun bottom() = compose.onNodeWithTag("extension").captureToImage().toPixelMap().let { it[it.width / 2, it.height - 10].red }
        compose.waitUntil(5000) { bottom() > .9f }
        val original = bottom()
        compose.runOnIdle { appearance = appearance.copy(contrast = .5f, contrastWeight = 2f) }
        compose.waitUntil(5000) { bottom() < .6f }
        assertEquals("White extension uses the same configured curve as glass", GlassTone(.5f, 2f, true).channel(original), bottom(), .025f)
        compose.runOnIdle { appearance = appearance.copy(contrast = 0f) }
        compose.waitUntil(5000) { bottom() > .9f }
    }

    @Test fun collectionKeepsItsExistingBackdropAndInkTreatment() {
        for (value in listOf(0, 70, 160, 220, 255)) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(value, value, value))
            }
            val palette = runBlocking { playerPalette(bitmap) }
            val previousMiddle = androidx.compose.ui.graphics.lerp(Color(value, value, value), Color.Black, .4f)
            val previousLight = Color((previousMiddle.red / .6f).coerceAtMost(1f), (previousMiddle.green / .6f).coerceAtMost(1f),
                (previousMiddle.blue / .6f).coerceAtMost(1f)).luminance()
            assertEquals(previousLight > .45f, collectionIsLight(PlayerVisual(palette = palette)))
            val legacy = requireNotNull(palette.collectionWash)
            assertEquals("Collection continuation retains its established bottom shading", (value * .52f).toDouble(),
                android.graphics.Color.red(legacy.getPixel(legacy.width / 2, legacy.height - 1)).toDouble(), 1.0)
        }
    }

    @Test fun fullPlayerAndItsPopupFollowArtworkChanges() {
        var lightArtwork by mutableStateOf(false)
        var popup by mutableStateOf(false)
        val polarity = PlayerBackgroundPolarityState()
        var composedInk: PlayerInkComposition? = null
        var playerInk = Color.Unspecified
        var popupInk = Color.Unspecified
        var popupSecondary = Color.Unspecified
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalPlayerInkCompositionObserver provides { composedInk = it }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val color = if (lightArtwork) Color.White else Color.Black
            PlayerSurface(io.github.anastaci1a.localica.playback.PlaybackState(title = "Contrast"), true, {},
                io.github.anastaci1a.localica.navigation.InterfaceSettings(reducedMotion = true),
                remember { androidx.compose.animation.core.Animatable(1f) }, {}, {}, {}, {}, {}, {},
                visual = PlayerVisual(palette = PlayerPalette(color, color, color)), backgroundPolarity = polarity) {
                playerInk = MaterialTheme.colorScheme.onSurface
                if (popup) GlassDialog({ popup = false }) {
                    popupInk = LocalContentColor.current
                    popupSecondary = MaterialTheme.colorScheme.onSurfaceVariant
                    Box(Modifier.size(80.dp))
                }
            }
        } } } }
        for (light in listOf(false, true)) {
            compose.runOnIdle { lightArtwork = light; popup = true }
            // UI idle does not join the background sampler. Require the current
            // source decision to reach the real provider; never poll for expected ink.
            try {
                compose.waitUntil(5000) {
                    val frame = polarity.frame
                    val observed = composedInk
                    val color = if (light) Color.White else Color.Black
                    polarity.decision.confidence == PlayerBackgroundConfidence.Current &&
                        frame != null && frame.roles.isNotEmpty() &&
                        frame.roles.all { it.visual.palette.top == color } &&
                        observed != null && observed.decision === polarity.decision && observed.frame === frame
                }
            } catch (failure: Throwable) {
                failure.addSuppressed(AssertionError("Player contrast readiness: decision=" + polarity.decision +
                    "; frame=" + polarity.frame + "; composed=" + composedInk))
                throw failure
            }
            compose.runOnIdle {
                assertEquals(!light, playerInk.red > .5f)
                assertEquals(!light, popupInk.red > .5f)
                assertEquals(!light, popupSecondary.red > .5f)
                popup = false
            }
            compose.waitForIdle()
        }
    }

    @Test fun queueAndNestedGlassInheritBothArtworkPolarities() {
        var dark by mutableStateOf(true)
        var outerInk = Color.Unspecified
        var nestedInk = Color.Unspecified
        var nestedSecondary = Color.Unspecified
        var inheritedDark = false
        compose.setContent { LocalicaTheme { GlassPanelTheme(dark) {
            val source = rememberFrostedBackdrop(Unit, dark = dark)
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                FrostedSurface(Modifier.size(180.dp)) {
                    outerInk = LocalContentColor.current
                    GlassPanelTheme(LocalFrostedBackdrop.current?.dark == true) {
                        nestedInk = LocalContentColor.current
                        nestedSecondary = MaterialTheme.colorScheme.onSurfaceVariant
                        inheritedDark = LocalFrostedBackdrop.current?.dark == true
                    }
                }
            }
        } } }
        for (lightInk in listOf(true, false, true)) {
            compose.runOnIdle { dark = lightInk }
            compose.runOnIdle {
                assertEquals(lightInk, inheritedDark)
                assertEquals(lightInk, outerInk.red > .5f)
                assertEquals(lightInk, nestedInk.red > .5f)
                assertEquals(lightInk, nestedSecondary.red > .5f)
            }
        }
    }
}
