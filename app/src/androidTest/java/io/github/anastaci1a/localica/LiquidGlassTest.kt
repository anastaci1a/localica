package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.*
import androidx.test.filters.SdkSuppress
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.PopupMotionStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiquidGlassTest {
    @get:Rule val compose = createComposeRule()

    @SdkSuppress(minSdkVersion = 33)
    @Test fun fluidMaskKeepsGaussianInsideTheForeground() {
        if (Build.VERSION.SDK_INT < 33) return
        var blur by mutableFloatStateOf(0f)
        val extent = IntSize(96, 48)
        val aperture = LensGeometry(Rect(8f, 0f, 88f, 48f), 4f)
        val warp = LensWarp(8.0, -0.001, 0f, extent)
        compose.setContent {
            val renderer = remember { LensRenderer() }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Canvas(with(density) { Modifier.requiredSize(extent.width.toDp(), extent.height.toDp()) }
                    .testTag("fluid-foreground-mask").graphicsLayer {
                        renderEffect = renderer.effect(aperture, blur, depth = 1f, unit = 1f, warp = warp)
                    }) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                }
            }
        }
        val foreground = compose.onNodeWithTag("fluid-foreground-mask")
        val sharp = foreground.captureNativeFrame().toPixelMap()
        assertEquals(Color.Black, sharp[7, 24])
        assertEquals(Color.Red, sharp[8, 24])
        assertEquals(Color.Red, sharp[47, 24])
        assertEquals(Color.Blue, sharp[48, 24])

        compose.runOnIdle { blur = 4f }
        val blurred = foreground.captureNativeFrame().toPixelMap()
        assertEquals("The mask is applied after the blur", Color.Black, blurred[7, 24])
        for (x in 47..48) {
            val seam = blurred[x, 24]
            assertTrue("The Gaussian mixes the two colors at x=$x: $seam", seam.red > 0f && seam.blue > 0f)
        }
    }

    @SdkSuppress(minSdkVersion = 36)
    @Test fun fluidSearchWarpUsesThePlacedForegroundAperture() {
        val root = LocalicaUiRootState(InterfaceSettings(
            popupMotion = PopupMotionStyle.Fluid, respectSystemAnimationScale = false,
        ))
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var popupView: android.view.View
        var foreground: LayoutCoordinates? = null
        compose.setContent { LocalicaUiRoot(root) { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    anchor = rememberGlassAnchor()
                    Box(Modifier.padding(start = 28.dp, top = 52.dp).size(48.dp).glassAnchor(anchor))
                    GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Search, darkOverride = true) {
                        popupView = LocalView.current
                        Canvas(Modifier.fillMaxWidth().height(240.dp).onPlaced { foreground = it }) {
                            drawRect(Color.Red)
                            drawRect(Color.Blue, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                        }
                    }
                }
            }
        } } }
        val aperture = compose.onNodeWithTag("glass-morph-surface")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open = true }
            val active = (1..30).any {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                anchor.contact.expansion.value in .45f.. .78f
            }
            assertTrue("Observe the real opening before the aperture settles", active)
            val bounds = aperture.fetchSemanticsNode().boundsInRoot
            val origin = compose.runOnIdle {
                val coordinates = requireNotNull(foreground)
                assertTrue("Search vertically pins the foreground instead of centring it", bounds.height < coordinates.size.height)
                assertTrue("The moving aperture is narrower than the foreground", bounds.width < coordinates.size.width)
                coordinates.positionInRoot()
            }
            assertEquals("Search aligns the foreground with the aperture top", bounds.top, origin.y, 1f)
            assertTrue("The aperture's left edge is inset in foreground coordinates", bounds.left > origin.x)

            val pixels = capturePopupPixels(compose, aperture) { popupView }
            val left = pixels[pixels.width / 4, 2]
            val corner = pixels[1, 2]
            assertTrue("The active field clips the foreground at the placed left corner: $corner vs $left",
                left.red - left.green > corner.red - corner.green + .1f)
            val row = pixels.height / 2
            val seam = pixels[pixels.width / 2, row]
            assertTrue("The active Gaussian mixes both channels inside the aperture: $seam",
                seam.red > seam.green + .04f && seam.blue > seam.green + .04f)

            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val settled = capturePopupPixels(compose, aperture) { popupView }
            assertEquals(Color.Red, settled[settled.width / 4, settled.height / 2])
            assertEquals(Color.Blue, settled[settled.width * 3 / 4, settled.height / 2])
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun resizingAnInactiveBackdropDoesNotRecomposeItsScreen() {
        lateinit var source: FrostedBackdrop
        var compositions = 0
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, enabled = false)
            SideEffect { compositions++ }
        } }
        compose.waitForIdle()
        val settled = compositions
        repeat(10) { frame ->
            compose.runOnIdle { source.size = androidx.compose.ui.unit.IntSize(300, 60 + frame * 40) }
            compose.waitForIdle()
        }
        assertEquals("A mini-player resize must not rebuild the surrounding screen just to schedule sampling", settled, compositions)
    }

    @Test fun restoredSelectorStartsAtRestAndOnlyDeformsWhenItMoves() {
        var position by mutableFloatStateOf(2f)
        lateinit var shape: State<GlassShape>
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme { shape = rememberGlassBody({ position }) } }
        compose.mainClock.advanceTimeBy(64)
        assertEquals("Restoring a selected option must not invent an arrival impulse", GlassShape(), shape.value)
        compose.runOnIdle { position = 1.6f }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { position = 1.4f }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { position = 1.2f }
        compose.mainClock.advanceTimeBy(32)
        assertNotEquals("Actual movement still drives deformation", GlassShape(), shape.value)
        compose.mainClock.autoAdvance = true
    }

    @Test fun defaultMaterialPreservesBlackToGrayOrderingInBothPolarities() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 33)
        var dark by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                val source = rememberFrostedBackdrop(Unit, dark = dark)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    repeat(16) { i -> drawRect(Color(i / 15f, i / 15f, i / 15f), Offset(size.width * i / 16f, 0f), Size(size.width / 16f, size.height)) }
                }
                LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(80.dp).testTag("ramp"), source,
                    refraction = 0f, inkOverride = if (dark) Color.White else Color.Black)
            }
         } }
        for (polarity in listOf(false, true)) {
            compose.runOnIdle { dark = polarity }
            val pixels = compose.onNodeWithTag("ramp").captureToImage().toPixelMap()
            val ramp = (0..15).map { pixels[(pixels.width * (it + .5f) / 16).toInt(), pixels.height / 2].red }
            assertTrue("No tonal reversal at the default (dark=$polarity): $ramp", ramp.zipWithNext().all { (a, b) -> a <= b + .005f })
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun tintLiftsShadowsMoreThanHighlightsWithoutBleachingYellow() {
        org.junit.Assume.assumeTrue("Per-pixel tone mapping requires runtime shaders", Build.VERSION.SDK_INT >= 33)
        val original = listOf(Color(.03f, .03f, .03f), Color(.4f, .4f, .4f), Color(.9f, .9f, .9f), Color.Yellow)
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    original.forEachIndexed { i, color -> drawRect(color, Offset(size.width * i / 4f, 0f), Size(size.width / 4f, size.height)) }
                }
                LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(96.dp).testTag("tonal-lens"), source, refraction = 0f)
            }
         } }
        fun colors() = compose.onNodeWithTag("tonal-lens").captureToImage().toPixelMap().let { pixels ->
            (0..3).map { pixels[(pixels.width * (it + .5f) / 4).toInt(), pixels.height / 2] }
        }
        val toned = colors()
        val lift = (0..2).map { toned[it].red - original[it].red }
        assertTrue("Shadows receive a visible, stronger lift: $lift", lift[0] > .15f && lift[0] > lift[1] && lift[1] > lift[2])
        // Frozen GlassFillRows: gray red = .7001953 + .3179475 * input; yellow = (1, 1, .4385375).
        val expected = listOf(.7097337f, .8273743f, .9863481f)
        for (i in expected.indices) assertEquals("Default face row $i", expected[i], toned[i].red, .02f)
        assertEquals("Yellow keeps the model's blue channel", .4385375f, toned[3].blue, .04f)
        assertTrue("Bright yellow retains its hue: ${toned[3]}", toned[3].red > toned[3].blue + .5f && toned[3].green > toned[3].blue + .5f)
    }

    @Test fun paddedBackdropDoesNotExpandAContentSizedControl() {
        val contacts = List(6) { GlassContact() }
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize()) {
            val source = rememberFrostedBackdrop(Unit)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Blue) }
            Column {
                LiquidGlassSurface(Modifier.fillMaxWidth().testTag("content-sized-lens"), source) {
                    Box(Modifier.fillMaxWidth().height(40.dp))
                }
                androidx.compose.material3.Text("Below the control")
                Box { contacts.forEachIndexed { index, contact ->
                    val side = listOf(119, 120, 160)[index % 3].dp
                    LiquidGlassSurface(Modifier.size(if (index < 3) side else 40.dp, if (index < 3) 40.dp else side),
                        source, sharedContact = contact)
                } }
            }
        } } }
        compose.onNodeWithTag("content-sized-lens").assertHeightIsEqualTo(40.dp)
        compose.onNodeWithText("Below the control").assertIsDisplayed()
        compose.runOnIdle {
            contacts.forEachIndexed { index, contact ->
                val expected = io.github.anastaci1a.localica.ui.spec.FlexSpec.dynamic(listOf(119f, 120f, 160f)[index % 3], 40f)
                assertEquals("Surface long side is resolved in dp in either axis", expected, contact.state.momentum.spec)
                if (index % 3 > 0) assertNotEquals(io.github.anastaci1a.localica.ui.spec.FlexSpec.Small, contact.state.momentum.spec)
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun lightPillTransmitsAndRefreshesSceneWithoutBlurringForeground() = verifyLens(false)
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun darkBubbleTransmitsAndRefreshesSceneWithoutBlurringForeground() = verifyLens(true)

    @Test fun transparentListPixelsLeaveTheDrawerVisibleThroughTheLens() {
        lateinit var source: FrostedBackdrop
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize().background(Color.Red)) {
            source = rememberFrostedBackdrop(Unit, dark = true)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                // A transparent list over a drawer must not replace that drawer with raw artwork.
                drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
            }
            LiquidGlassSurface(Modifier.align(Alignment.Center).size(300.dp, 96.dp).testTag("transparent-lens"), source)
        } } }
        // Below API 33 the average draws only once the asynchronous backdrop snapshot exists; until then the
        // lens shows the raw parent, which is not what this contract measures.
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.snapshot != null }
        val pixels = compose.onNodeWithTag("transparent-lens").captureToImage().toPixelMap()
        val edge = pixels[(pixels.width * .02f).toInt(), pixels.height / 2]
        val interior = pixels[pixels.width / 4, pixels.height / 2]
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("Transparent source pixels must preserve the immediate red backdrop: $edge",
                edge.red > .97f && edge.green < .04f && edge.blue < .04f)
            assertTrue("Curved edge reflection must not become a coating across the transparent face: $interior",
                interior.red > .97f && interior.green < .01f && interior.blue < .01f)
        } else {
            for (color in listOf(edge, interior)) {
                assertEquals("Half-transparent default tone keeps the red drawer beneath it", .5f + .5f * .0591f, color.red, .04f)
                assertEquals("The default compressed blue occupies half of the sampled footprint", .5f * .75f, color.blue, .04f)
                assertTrue("No white coating is introduced", color.green < .04f)
            }
        }
    }

    private fun verifyLens(dark: Boolean) {
        lateinit var source: FrostedBackdrop
        var swapped by mutableStateOf(false)
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize()) {
            // Keep the scene key constant: explicit invalidation also covers settled list scrolls.
            source = rememberFrostedBackdrop(Unit, dark = dark)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(if (swapped) Color.Blue else Color.Red, size = Size(size.width / 2, size.height))
                drawRect(if (swapped) Color.Red else Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
            }
            LiquidGlassSurface(Modifier.align(Alignment.Center).size(if (dark) 96.dp else 300.dp, 96.dp).testTag("lens"), source) {
                Box(Modifier.align(Alignment.Center).size(12.dp).background(Color.Black).testTag("foreground"))
            }
        } } }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        val lens = compose.onNodeWithTag("lens")
        val pixels = lens.captureToImage().toPixelMap()
        val y = pixels.height / 2
        val left = pixels[pixels.width / 4, y]
        val right = pixels[pixels.width * 3 / 4, y]
        assertTrue("Keep the red backdrop visible: $left", left.red > left.blue + .15f)
        assertTrue("Keep the blue backdrop visible: $right", right.blue > right.red + .15f)
        if (dark) {
            // Frozen .06 tint and .25 quadratic compression, evaluated at red/blue luma.
            assertEquals("Red keeps the default light-ink channel", .0473f, left.green, .03f)
            assertEquals("Blue keeps the default light-ink channel", .0551f, right.green, .03f)
        }
        else {
            assertEquals("Red backdrop receives the face row", .7001953f - .0599365f, left.green, .03f)
            assertEquals("Blue backdrop receives the face row", .7001953f - .0203705f, right.green, .03f)
        }
        val dominant = if (dark) .75f else 1f
        assertEquals("The shadow does not darken the default red center", dominant, left.red, .03f)
        assertEquals("The shadow does not darken the default blue center", dominant, right.blue, .03f)
        val center = pixels[pixels.width / 2, y]
        assertTrue("Foreground must remain black and unfiltered: $center", center.red + center.green + center.blue < .05f)
        val previous = source.fallback
        compose.runOnIdle { swapped = true; source.refresh() }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback !== previous }
        compose.waitUntil(5000) {
            val updated = lens.captureToImage().toPixelMap()
            val color = updated[updated.width / 4, updated.height / 2]
            color.blue > color.red + .15f
        }
    }
}

@Composable
internal fun ClearGlassTheme(content: @Composable () -> Unit) {

        LocalicaTheme(content = content)

}
