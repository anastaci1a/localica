package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidRenderEffect
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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class LiquidGlassTest {
    @get:Rule val compose = createComposeRule()

    @SdkSuppress(minSdkVersion = 36)
    @RequiresApi(33)
    @Test fun fluidApertureCoverageUsesRegisteredQuadPartners() {
        val rect = RoundRect(Rect(0f, 0f, 12f, 12f), CornerRadius(2.75f, 3.25f))
        val registration = requireNotNull(BackdropRegistration.create(.54f, 0f, 0f, .89f, 1.25f, -.375f))
        val probes = listOf(
            listOf(Triple(11, 9, 180), Triple(10, 10, 218)),
            listOf(Triple(11, 9, 198), Triple(11, 10, 196)),
            listOf(Triple(9, 11, 255), Triple(11, 10, 195)),
            listOf(Triple(11, 9, 255), Triple(11, 10, 196)),
        )
        probes.forEachIndexed { index, samples ->
            val selector = index + 10
            val node = RenderNode("Fluid aperture $selector").apply { setPosition(0, 0, 16, 16) }
            try {
                node.beginRecording().drawColor(android.graphics.Color.WHITE)
                node.endRecording()
                node.setRenderEffect(fluidApertureClip(rect, registration, selector, .3f, .6f).asAndroidRenderEffect())
                val image = renderNativeReferenceImage(16, 16, "Fluid aperture coverage", opaque = false) {
                    it.drawRenderNode(node)
                }
                try {
                    val pixels = image.toPixelMap()
                    samples.forEach { (x, y, alpha) ->
                        assertEquals("selector=$selector x=$x y=$y", alpha, (pixels[x, y].alpha * 255).roundToInt())
                    }
                } finally { image.asAndroidBitmap().recycle() }
            } finally { node.discardDisplayList() }
        }

        val precision = RuntimeShader("""
            $FluidDeviceQuadShader
            $FluidApertureCoverageShader
            half4 main(float2 p) {
                float tie = halfOffset(0.2501220703125);
                float above = halfOffset(0.2501221001148224);
                return (tie == 0.25 && above == 0.250244140625) ? half4(0, 1, 0, 1) : half4(1, 0, 0, 1);
            }
        """.trimIndent())
        val halfProbe = renderNativeReferenceImage(1, 1, "Fluid contour half conversion") {
            it.drawPaint(Paint().apply { shader = precision })
        }
        try { assertEquals(Color.Green, halfProbe.toPixelMap()[0, 0]) }
        finally { halfProbe.asAndroidBitmap().recycle() }
    }

    @SdkSuppress(minSdkVersion = 36)
    @RequiresApi(34)
    @Test fun fluidFieldTargetPreservesSignedHalfSamplesAndCoverage() {
        val width = 2
        val height = 1
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        val colorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        val packets = """
            half4 packet(float2 point) {
                return point.x < 1.0 ? half4(-2.0, 0.5, -0.25, 0.75) : half4(1.5, -0.5, 0.25, 0.25);
            }
        """.trimIndent()
        HardwareBuffer.create(width, height, HardwareBuffer.RGBA_FP16, 1, usage).use { field ->
            assertEquals(HardwareBuffer.RGBA_FP16, field.format)
            val node = RenderNode("Fluid field packet")
            try {
                node.setPosition(0, 0, width, height)
                node.beginRecording(width, height).drawPaint(Paint().apply {
                    shader = RuntimeShader("$packets\nhalf4 main(float2 point) { return packet(point); }")
                    blendMode = BlendMode.SRC
                })
                node.endRecording()
                HardwareBufferRenderer(field).use { renderer ->
                    renderer.setContentRoot(node)
                    val committed = CompletableFuture<HardwareBufferRenderer.RenderResult>()
                    renderer.obtainRenderRequest().setColorSpace(colorSpace)
                        .draw({ it.run() }) { committed.complete(it) }
                    val result = committed.get(5, TimeUnit.SECONDS)
                    result.fence.use { fence ->
                        assertEquals(HardwareBufferRenderer.RenderResult.SUCCESS, result.status)
                        assertTrue("Field target draw committed", fence.await(Duration.ofSeconds(5)))
                    }
                    val bitmap = requireNotNull(Bitmap.wrapHardwareBuffer(field, colorSpace))
                    try {
                        val sample = RuntimeShader("""
                            uniform shader field;
                            $packets
                            half4 main(float2 point) {
                                return all(equal(field.eval(point), packet(point)))
                                    ? half4(0, 1, 0, 1) : half4(1, 0, 0, 1);
                            }
                        """.trimIndent())
                        sample.setInputBuffer("field", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                        val pixels = renderNativeReferenceImage(width, height, "Fluid field sampling") { canvas ->
                            canvas.drawPaint(Paint().apply { shader = sample })
                        }.toPixelMap()
                        for (x in 0 until width) {
                            assertEquals("FP16 signed distance, normal and fractional coverage at x=$x", Color.Green, pixels[x, 0])
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                node.discardDisplayList()
            }
        }
    }

    @SdkSuppress(minSdkVersion = 36)
    @RequiresApi(34)
    @Test fun fluidMeltRendersBothRegisteredFields() {
        val extent = IntSize(192, 120)
        val aperture = LensGeometry(Rect(8f, 12f, 88f, 108f), 12f)
        val companion = RoundRect(Rect(104f, 12f, 184f, 108f), CornerRadius(12f))
        val warp = LensWarp(12.0, -0.001, 0f, extent)
        val renderer = LensRenderer()
        for (radius in listOf(0.0, 0.5, 8.0, 16.0)) {
            val field = FluidLensField(aperture.bodies + companion, radius, Offset.Zero, wideColor = false)
            val effect = renderer.effect(aperture, 0f, depth = 1f, unit = 1f, warp = warp, field = field)
            val node = RenderNode("Fluid melt map").apply { setPosition(0, 0, extent.width, extent.height) }
            try {
                node.beginRecording().drawColor(android.graphics.Color.WHITE)
                node.endRecording()
                node.setRenderEffect(effect.asAndroidRenderEffect())
                val result = renderNativeReferenceImage(extent.width, extent.height, "Fluid melt", opaque = false) {
                    it.drawRenderNode(node)
                }
                try {
                    val pixels = result.toPixelMap()
                    assertEquals("First field at radius $radius", Color.White, pixels[41, 40])
                    assertEquals("Companion field at radius $radius", Color.White, pixels[141, 40])
                    assertEquals("Outside both fields at radius $radius", 0f, pixels[0, 0].alpha, 0f)
                } finally { result.asAndroidBitmap().recycle() }
            } finally { node.discardDisplayList() }
        }
        renderer.clearField()
    }

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
            val swatchX = pixels.width / 4
            val swatchTop = (0 until pixels.height / 2).firstOrNull { y ->
                val color = pixels[swatchX, y]
                color.red - color.green > .1f
            }
            assertNotNull("The native frame contains the red source swatch", swatchTop)
            // The shared body can translate its presented pixels after the layout crop is measured.
            val cornerRow = requireNotNull(swatchTop) + 2
            val left = pixels[swatchX, cornerRow]
            val corner = pixels[1, cornerRow]
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
