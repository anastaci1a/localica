package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as UiCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #553: the lift shadow is the Glass shadow's spread, a multiple of its strength, and a glow on light-icon menus. */
class LiftShadowTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var scene: SelectionScene
    private var selected by mutableStateOf(false)

    private var glassState by mutableStateOf(GlassAppearance())
    private var inkState by mutableStateOf(Color.Black)
    private var started = false

    @Composable private fun Fixture() {
        val glass = glassState; val ink = inkState
        LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(360.dp, 320.dp).testTag("lift-scene")) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(.5f, .5f, .5f)) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalGlassAppearance provides glass,
                    LocalContentColor provides ink) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        scene = checkNotNull(LocalSelectionScene.current)
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 80.dp)) {
                            Box(Modifier.fillMaxWidth().height(72.dp).selectionLens(selected, false, itemKey = "first"))
                        }
                    }
                }
            }
        }
    }

    private fun lum(c: Color) = .3f * c.red + .59f * c.green + .11f * c.blue
    private fun capture(): PixelMap = compose.onNodeWithTag("lift-scene").captureToImage().toPixelMap()

    /** Signed luminance change below the lifted bottom edge, per pixel row, from the selected frame against rest. */
    private class Profile(val signed: List<Float>, val extentDp: Float, val peak: Float)

    private fun profile(glass: GlassAppearance, ink: Color): Profile {
        if (!started) { started = true; compose.setContent { Fixture() } }
        compose.runOnIdle { selected = false; glassState = glass; inkState = ink }
        compose.waitForIdle()
        val rest = capture()
        val d = rest.width / 360f
        compose.runOnIdle { selected = true }
        compose.waitUntil(5000) { scene.lastDrawnRegions.isNotEmpty() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val now = capture()
        val bottom = scene.lastDrawnRegions.first().bounds.bottom.toInt()
        val x = now.width / 2
        val signed = (bottom until now.height).map { lum(now[x, it]) - lum(rest[x, it]) }
        val extent = signed.indexOfLast { kotlin.math.abs(it) > .004f }.let { if (it < 0) 0f else (it + 1) / d }
        return Profile(signed, extent, signed.maxOf { kotlin.math.abs(it) })
    }

    /** The reference: Glass shadow drawn directly with the lifted body, at the same pixel density. */
    private fun glassReference(glass: GlassAppearance, d: Float): Profile {
        val width = (360 * d).toInt(); val height = (320 * d).toInt()
        val image = ImageBitmap(width, height)
        val lifted = scene.lastDrawnRegions.first()
        CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, UiCanvas(image), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color(.5f, .5f, .5f))
            // Write cumulative coverage into a transparent reference field, as the retained
            // renderer does. Software source-over rounds away the fine contour increments.
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
            glassShadows(listOf(RoundRect(lifted.bounds, CornerRadius(lifted.corner ?: 0f)) to 1f),
                glass.shadowStrength, glass.shadowSpread, GlassShadowGeometry(), rasterizing = true)
            drawContext.canvas.restore()
        }
        val pixels = image.toPixelMap()
        val base = lum(Color(.5f, .5f, .5f))
        val bottom = lifted.bounds.bottom.toInt()
        val signed = (bottom until height).map { lum(pixels[width / 2, it]) - base }
        val extent = signed.indexOfLast { kotlin.math.abs(it) > .004f }.let { if (it < 0) 0f else (it + 1) / d }
        return Profile(signed, extent, signed.maxOf { kotlin.math.abs(it) })
    }

    @Test fun darkIconMenusGetADarkShadowAndLightIconMenusAWhiteGlow() {
        val glass = GlassAppearance(shadowStrength = 1f, shadowSpread = .6f)
        val dark = profile(glass, Color.Black)
        assertTrue("Dark shadow darkens: ${dark.peak}", dark.signed.minOrNull()!! < -.02f && dark.signed.maxOrNull()!! < .004f)
    }

    @Test fun lightIconMenusGetAWhiteGlow() {
        val glass = GlassAppearance(shadowStrength = 1f, shadowSpread = .6f)
        val light = profile(glass, Color.White)
        assertTrue("White glow lightens: ${light.peak}", light.signed.maxOrNull()!! > .02f && light.signed.minOrNull()!! > -.004f)
    }

    @Test fun spreadEqualsTheGlassShadowSpreadAndStrengthIsTheSharedMultiple() {
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for (spread in listOf(.1f, .6f, 1f)) {
            val glass = GlassAppearance(shadowStrength = 1f, shadowSpread = spread)
            val lift = profile(glass, Color.Black)
            val reference = glassReference(glass, d)
            assertEquals("Lift spread at Glass spread $spread", reference.extentDp, lift.extentDp, 4f)
            assertEquals("100% default equals Glass strength at $spread", reference.peak, lift.peak, .03f)
        }
    }

    @Test fun strengthMultiplierScalesBothShadowAndGlow() {
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val low = profile(GlassAppearance(shadowStrength = .6f, liftShadowStrength = .5f), Color.Black)
        assertEquals("50% of Glass", .5f * glassReference(GlassAppearance(shadowStrength = .6f), d).peak, low.peak, .03f)
    }

    private fun liftLabels(): List<String> = listOf(true, false).flatMap { merged ->
        compose.onAllNodes(hasText("Lift shadow strength ·", substring = true), merged).fetchSemanticsNodes().flatMap { n ->
            if (n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text))
                n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } else emptyList() }
    }

    @Test fun liftShadowStrengthSettingSitsNextToTheShadowControlsSnapsToFivePercentAndResets() {
        var value by mutableStateOf(GlassAppearance())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GlassSettingsControls(value, "Glass appearance") { value = it }
                }
            }
        } }
        compose.onNodeWithContentDescription("Lift shadow strength").performScrollTo()
        assertTrue("Default label: ${liftLabels()}", liftLabels().any { it.contains("100%") })
        assertEquals(1f, value.liftShadowStrength, 0f)
        compose.onNodeWithContentDescription("Lift shadow strength").performSemanticsAction(SemanticsActions.SetProgress) { it(1.23f) }
        compose.runOnIdle { assertEquals(1.25f, value.liftShadowStrength, .0001f) }
        compose.waitForIdle()
        assertTrue("Label shows 125%: ${liftLabels()}", liftLabels().any { it.contains("125%") })
        compose.onNodeWithContentDescription("Lift shadow strength").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.runOnIdle { assertEquals(1.5f, value.liftShadowStrength, .0001f) }
        compose.onNodeWithContentDescription("Lift shadow strength").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(.5f, value.liftShadowStrength, .0001f) }
        compose.onNodeWithText("Reset glass appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1f, value.liftShadowStrength, 0f) }
    }

    @Test fun settingPersistsAcrossReads() {
        val preferences = InterfacePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.read()
        try {
            assertEquals(1f, InterfaceSettings().glass.liftShadowStrength, 0f)
            for (value in listOf(.5f, .65f, 1.25f, 1.5f)) {
                preferences.write(original.copy(glass = original.glass.copy(liftShadowStrength = value)))
                assertEquals(value, preferences.read().glass.liftShadowStrength, .0001f)
            }
        } finally { preferences.write(original) }
    }
}
