package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #624: None averages exactly the refraction preimage that Modern's lens would read
 * (from the shared [lensRefractionPreimage]), over the actual scene behind the element. */
class NonePreimageTest {
    @get:Rule val compose = localicaComposeRule()
    private val appearance = GlassAppearance(whiteTint = 0f, contrast = 0f, shadowStrength = 0f)
    private val sourceSize = 320f

    /** A painted scene of non-overlapping rectangles over a background, in source pixels. */
    private data class Scene(val background: Color, val rects: List<Pair<Rect, Color>>)

    private fun Scene.mean(region: Rect): Color {
        val total = region.width * region.height
        var r = 0f; var g = 0f; var b = 0f; var rest = total
        for ((rect, color) in rects) {
            val o = rect.intersect(region)
            val area = if (o.isEmpty) 0f else o.width * o.height
            r += color.red * area; g += color.green * area; b += color.blue * area; rest -= area
        }
        return Color((r + background.red * rest) / total, (g + background.green * rest) / total, (b + background.blue * rest) / total)
    }

    private fun preimage(size: Size, corner: Float? = null): Rect = lensRefractionPreimage(Rect(Offset.Zero, size),
        liquidGlassPreimageOptics(size, corner, appearance.depth, 1f, 1f, 22f * appearance.blur, 32f))

    private fun DrawScopeScene(scene: Scene): androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
        drawRect(scene.background)
        for ((rect, color) in scene.rects) drawRect(color, rect.topLeft, rect.size)
    }

    @Composable private fun Fixture(content: @Composable () -> Unit) = CompositionLocalProvider(
        LocalDensity provides Density(1f),
        LocalGlassRendererRuntime provides remember { GlassRendererRuntime(GlassRendererMode.None) },
        LocalGlassAppearance provides appearance) { LocalicaTheme { content() } }

    private fun <T> eventually(block: () -> T): T {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            try { return block() } catch (failure: AssertionError) {
                if (android.os.SystemClock.uptimeMillis() > deadline) throw failure
                Thread.sleep(100)
            }
        }
    }

    private fun onScreen(tag: String): Color {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val origin = IntArray(2).also { (node.root as androidx.compose.ui.platform.ViewRootForTest).view.getLocationOnScreen(it) }
        val centre = node.boundsInRoot.center
        val screen = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { return Color(screen.getPixel((centre.x + origin[0]).toInt(), (centre.y + origin[1]).toInt())) } finally { screen.recycle() }
    }

    private fun centre(tag: String): Color = compose.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

    private fun assertMean(label: String, expected: Color, actual: Color, region: Rect) {
        for ((channel, e, a) in listOf(Triple("red", expected.red, actual.red), Triple("green", expected.green, actual.green),
                Triple("blue", expected.blue, actual.blue))) {
            if (Build.VERSION.SDK_INT >= 33) assertEquals("$label $channel over preimage $region", e, a, .015f)
            else assertLegacyAreaBound(a, e, region, 512, 512)
        }
    }

    /** Floating control over live content (text and album art), not inside any panel. */
    private fun floating(scene: () -> Scene, buttonAt: Offset, buttonSize: Size, corner: Float?, label: String,
        variants: List<Scene.() -> Scene>) {
        lateinit var source: FrostedBackdrop
        var current by mutableStateOf(scene())
        compose.setContent { Fixture {
            source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(sourceSize.dp)) {
                val painted = current
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source), onDraw = DrawScopeScene(painted))
                LiquidGlassSurface(Modifier.offset(buttonAt.x.dp, buttonAt.y.dp).size(buttonSize.width.dp, buttonSize.height.dp)
                    .testTag("glass"), source, cornerRadius = corner?.dp, pressFeedback = false, shadow = false)
            }
        } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val region = preimage(buttonSize, corner).translate(buttonAt)
        val fills = mutableListOf<Color>()
        for (variant in listOf<Scene.() -> Scene>({ this }) + variants) {
            compose.runOnIdle { current = scene().variant() }
            val expected = current.mean(region)
            val actual = eventually { centre("glass").also { assertMean(label, expected, it, region) } }
            fills += actual
        }
        if (variants.isNotEmpty()) assertTrue("$label: moving content behind the control must change its fill: $fills",
            fills.zipWithNext().all { (a, b) -> kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.blue - b.blue) > .04f })
    }

    @Test fun floatingButtonFollowsTextBehindItOverItsPreimage() {
        val text = Rect(0f, 152f, sourceSize, 168f)
        floating({ Scene(Color.White, emptyList()) }, Offset(110f, 110f), Size(100f, 100f), null, "button over text",
            listOf({ copy(rects = listOf(text to Color.Black)) }, { copy(rects = listOf(text.translate(0f, -52f) to Color.Black)) }))
    }

    @Test fun floatingButtonOverAlbumArtAveragesTheArtItRefracts() {
        val red = Rect(60f, 60f, 160f, 260f); val blue = Rect(160f, 60f, 260f, 260f)
        floating({ Scene(Color.White, listOf(red to Color.Red, blue to Color.Blue)) }, Offset(96f, 110f), Size(100f, 100f), null,
            "button over art", listOf({ copy(rects = listOf(red.translate(40f, 0f) to Color.Red, blue.translate(40f, 0f) to Color.Blue)) }))
    }

    @Test fun pillOverAListAveragesTheRowsItRefracts() {
        val rows = (0 until 20).map { Rect(0f, it * 16f, sourceSize, it * 16f + 8f) to Color.Black }
        floating({ Scene(Color.White, rows) }, Offset(40f, 128f), Size(240f, 64f), 32f, "pill over list",
            listOf({ copy(rects = rects.map { (r, c) -> Rect(r.left, r.top, r.right, r.top + 12f) to c }) }))
    }

    /** Controls inside a finished panel (the Queue drawer's structure: a list recorded over
     * the panel material, parented to it) reflect the list wherever it passes behind them. */
    @Test fun controlInsidePanelFollowsTheListBehindItNotOnlyThePanel() {
        lateinit var list: FrostedBackdrop
        lateinit var page: FrostedBackdrop
        var textY by mutableFloatStateOf(-100f)
        val button = Offset(110f, 110f)
        compose.setContent { Fixture {
            page = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(sourceSize.dp)) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) { drawRect(Color(.5f, .5f, .5f)) }
                FrostedSurface(Modifier.fillMaxSize().testTag("panel"), source = page, shadow = false) {
                    val material = requireNotNull(LocalFrostedBackdrop.current)
                    list = rememberFrostedBackdrop("list", dark = false)
                    SideEffect { list.parent = material }
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(list, material)) {
                            drawRect(Color.Black, Offset(0f, textY), Size(size.width, 16f))
                        }
                        LiquidGlassSurface(Modifier.offset(button.x.dp, button.y.dp).size(100.dp).testTag("glass"), list,
                            pressFeedback = false, shadow = false)
                        LiquidGlassSurface(Modifier.offset(20.dp, 250.dp).size(48.dp).testTag("material-control"), material,
                            pressFeedback = false, shadow = false)
                    }
                }
            }
        } }
        compose.waitUntil(15_000) { list.recorded && (Build.VERSION.SDK_INT >= 33 || list.snapshot != null) }
        val region = preimage(Size(100f, 100f)).translate(button)
        val fills = mutableListOf<Color>()
        for (y in listOf(-100f, 152f, 100f)) {
            compose.runOnIdle { textY = y }
            compose.waitForIdle()
            val panel = compose.onNodeWithTag("panel").captureToImage().toPixelMap()[300, 20]
            val scene = Scene(panel, listOf(Rect(0f, y, sourceSize, y + 16f) to Color.Black))
            val expected = scene.mean(region)
            // Inside a finished panel the material already carries tint and tone: no second mapping.
            val actual = eventually { centre("glass").also { assertMean("control over list at $y", expected, it, region) } }
            fills += actual
            // A control over the bare material shows exactly that material.
            assertMean("control over material", panel, centre("material-control"), preimage(Size(48f, 48f)).translate(Offset(20f, 250f)))
        }
        assertTrue("Text passing behind the control must change its fill: $fills",
            fills.zipWithNext().all { (a, b) -> kotlin.math.abs(a.red - b.red) > .02f })
    }

    /** Controls inside a popup sample the popup's finished material over their preimage. */
    @Test fun controlInsidePopupShowsThePopupMaterial() {
        compose.setContent { Fixture {
            val page = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(page)) {
                    drawRect(Color.White); drawRect(Color.Black, Offset.Zero, Size(size.width / 2, size.height))
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides page) {
                    GlassAlertDialog(onDismissRequest = {}, title = { androidx.compose.material3.Text("Preimage popup") }, text = {
                        Row(Modifier.fillMaxWidth().testTag("popup-row"), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.size(48.dp).testTag("popup-gap"))
                            Spacer(Modifier.width(24.dp))
                            LiquidGlassSurface(Modifier.size(48.dp).testTag("popup-control"), pressFeedback = false, shadow = false)
                        }
                    }, confirmButton = {})
                }
            }
        } }
        compose.onNodeWithText("Preimage popup").assertExists()
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            // Dialog windows cannot be captured per node below API 28: read the screen instead.
            val panel = onScreen("popup-gap")
            val control = onScreen("popup-control")
            listOf(panel.red - control.red, panel.green - control.green, panel.blue - control.blue).all { kotlin.math.abs(it) < .03f }
        }
    }
}
