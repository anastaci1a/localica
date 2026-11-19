package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun verifyShadowMassCallers(rule: ComposeContentTestRule, finalButtons: Boolean) {
    var kind by mutableStateOf("private")
    var shown by mutableStateOf(false)
    var level by mutableFloatStateOf(1f)
    var translated by mutableStateOf(false)
    lateinit var clock: UiClock
    rule.setContent { LocalicaTheme {
        clock = LocalUiClock.current
        val backing = Color(level, level, level)
        val source = rememberFrostedBackdrop(level, dark = false, lightMix = 0f)
        Box(Modifier.fillMaxSize().background(backing).testTag("mass-window"), Alignment.Center) {
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(backing) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                Box(Modifier.size(248.dp, 224.dp).testTag("mass-scene"), Alignment.Center) {
                    if (shown) key(kind) {
                        val anchor = rememberGlassAnchor()
                        val body = Modifier.size(104.dp, 48.dp).glassShadowOffset {
                            if (translated) IntOffset(3.dp.roundToPx(), 2.dp.roundToPx()) else IntOffset.Zero
                        }.testTag("mass-body")
                        @Composable fun button(modifier: Modifier = body) {
                            GlassActionButton(AppIcon.ActionRevert, "Revert", modifier, onClick = {})
                        }
                        when (kind) {
                            "shared" -> GlassShadowScope(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize(), Alignment.Center) { button() }
                            }
                            "anchored" -> button(body.glassAnchor(anchor))
                            "strip" -> GlassActionRow { button() }
                            "presence" -> GlassPresence(true) { button() }
                            "panel" -> FloatingSelectionPanel(rememberFloatingSelectionPanel(true, false)) { button() }
                            "clear" -> Box(body.clearGlassShadow(1f, 1f, 24.dp).background(Color.Red))
                            "receiver" -> Box(body.clearGlassReceiverShadow(1f, 1f, 24.dp) { null }.background(Color.Red))
                            "compound", "split" -> {
                                val silhouette = rememberGlassSilhouette { splitPillBodies(it, 0f) }
                                LiquidGlassSurface(if (kind == "split") body.splitGlassShadow(anchor, silhouette) else body,
                                    silhouette = silhouette, pressFeedback = false, shadow = kind != "split") {}
                            }
                            "native" -> {
                                val plane = rememberShadowPlane()
                                val node = rememberWindowCaster(plane, "mass-native")
                                val field = rememberNativeSurfaceField(plane, node, 1f)
                                Box(body.drawWithContent {
                                    field.draw(this, 1f, 1f, 24.dp.toPx(), 1f, 1f, 0f, null, null) { drawContent() }
                                }.background(Color.Red))
                            }
                            "selection" -> {
                                val graphics = LocalGraphicsContext.current
                                val scope = rememberCoroutineScope()
                                val group = remember { HoverGroupRenderer(graphics, scope) }
                                DisposableEffect(group) { onDispose { group.release() } }
                                val location = remember { BackdropLocation() }
                                val density = LocalDensity.current
                                val direction = LocalLayoutDirection.current
                                val lift = rememberLiftShadow(Color.Black)
                                Box(body.drawWithContent {
                                    val rest = MediaItemOutline(Rect(Offset.Zero, size), size.height / 2)
                                    val part = hoverGeometryAtScale(rest, Rect.Zero, 1f, 1f)
                                    drawHoverGroup(group, listOf(part), { _, _ -> 1f }, null, location, false,
                                        density, direction, 4.dp.toPx(), lift = lift, flatColor = Color.Red)
                                })
                            }
                            "stock" -> FrostedSurface(body, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {}
                            else -> button()
                        }
                    }
                }
            }
        }
    } }
    val cases = if (finalButtons) listOf("private", "shared", "anchored", "strip", "presence", "panel")
        else listOf("clear", "receiver", "compound", "split", "native", "selection", "stock")
    val output = nativeEvidenceDirectory("shadow-mass", androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().targetContext.cacheDir)
    check(output.isDirectory || output.mkdirs())
    val failures = mutableListOf<String>()
    for (background in listOf(1f, .5f)) for (caller in cases) {
        rule.runOnIdle { kind = caller; level = background; shown = false; translated = false }
        rule.waitForIdle()
        val root = rule.onNodeWithTag("mass-window")
        val scene = rule.onNodeWithTag("mass-scene").fetchSemanticsNode().boundsInRoot
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        ShadowNativeCapture(rule, root) { clock.stampNow.sequence }.use { capture ->
            val control = capture.capture()
            checkNativeShadowMarker(control)
            val baseline = control.image.toPixelMap()
            rule.runOnIdle { shown = true }
            rule.mainClock.advanceTimeBy(1200)
            rule.waitForIdle()
            for (move in listOf(false, true)) {
                rule.runOnIdle { translated = move }
                rule.mainClock.advanceTimeBy(64)
                rule.waitForIdle()
                val body = rule.onNodeWithTag("mass-body").fetchSemanticsNode().boundsInRoot
                val actual = capture.capture { screen ->
                    ShadowDrawState(clock.stampNow.sequence, 0,
                        listOf(RoundRect(body.translate(screen.topLeft - rootBounds.topLeft),
                            CornerRadius(body.height / 2)) to 1f), Color(background, background, background))
                }
                val screen = checkNotNull(actual.sceneBounds)
                checkNativeShadowMarker(actual)
                val offset = screen.topLeft + scene.topLeft - rootBounds.topLeft
                val local = body.translate(-scene.topLeft)
                val width = scene.width.roundToInt(); val height = scene.height.roundToInt()
                val x0 = offset.x.roundToInt(); val y0 = offset.y.roundToInt()
                val pixels = actual.image.toPixelMap()
                val mass = shadowMass(width, height, local.top, local.bottom) { x, y ->
                    val b = baseline[x0 + x, y0 + y]; val a = pixels[x0 + x, y0 + y]
                    max(0.0, ((b.red - a.red) + (b.green - a.green) + (b.blue - a.blue)).toDouble() / (3 * background))
                }
                val reference = shadowMassReference(width, height, local.left, local.top, local.right, local.bottom,
                    local.height / 2, rule.density.density, code = 1.0 / (255 * background))
                val start = ceil(local.left).toInt()
                val columns = floor(local.right).toInt() - start
                val near = shadowMass(columns, height, local.top, local.bottom, (16 * rule.density.density).toInt()) { x, y ->
                    val b = baseline[x0 + start + x, y0 + y]; val a = pixels[x0 + start + x, y0 + y]
                    max(0.0, ((b.red - a.red) + (b.green - a.green) + (b.blue - a.blue)).toDouble() / (3 * background))
                }
                val nearReference = shadowMassReference(columns, height, local.left - start, local.top,
                    local.right - start, local.bottom, local.height / 2, rule.density.density,
                    code = 1.0 / (255 * background), bandDp = 16f)
                val label = "$caller-$background-$move"
                File(output, "$label.json").writeText(JSONObject().put("caller", caller).put("background", background)
                    .put("translated", move).put("density", rule.density.density).put("body", body.toString())
                    .put("scene", scene.toString()).put("windows", actual.windowIds.toString())
                    .put("generation", actual.drawGeneration).put("native", actual.nativeWindows.toString())
                    .put("above", mass.above).put("below", mass.below).put("ratio", mass.ratio)
                    .put("expected", reference.expected.ratio).put("minimum", reference.minimum)
                    .put("maximum", reference.maximum).toString(2))
                if (!reference.accepts(mass)) failures += "$label: final contrast mass=$mass reference=$reference"
                File(output, "$label-near.json").writeText(JSONObject().put("rowsDp", 16).put("columns", columns)
                    .put("above", near.above).put("below", near.below).put("ratio", near.ratio)
                    .put("expected", nearReference.expected.ratio).put("minimum", nearReference.minimum)
                    .put("maximum", nearReference.maximum).toString(2))
                if (!nearReference.accepts(near)) failures += "$label: visible near-field mass=$near reference=$nearReference"
            }
        }
    }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
}
