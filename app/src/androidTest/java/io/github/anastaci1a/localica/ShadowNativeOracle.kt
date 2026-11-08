package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertSame

/** Existing controls and their existing root fixture; no alternate shadow presenter or motion owner. */
internal fun verifyNativeShadowCallers(rule: ComposeContentTestRule, mode: GlassRendererMode) {
    var kind by mutableStateOf("button")
    var available by mutableStateOf(true)
    lateinit var clock: UiClock
    lateinit var anchor: GlassAnchor
    val runtime = GlassRendererRuntime(mode)
    rule.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
        val currentClock = LocalUiClock.current
        SideEffect { clock = currentClock }
        CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f, renderer = mode)) {
            Box(Modifier.size(240.dp).background(Color.White).testTag("oracle-scene"), contentAlignment = Alignment.Center) {
                key(kind) {
                    anchor = rememberGlassAnchor()
                    Box(Modifier.width(160.dp).testTag("oracle-control"), propagateMinConstraints = true) {
                        when (kind) {
                            "button" -> GlassActionButton(AppIcon.ActionMore, "Actions", Modifier.glassAnchor(anchor), enabled = available) {}
                            "filter" -> GlassFilterChoice(false, {}, "Filter", available)
                            "settings" -> SettingsLink("Settings", enabled = available, anchor = anchor) {}
                            "bubble" -> GlassDragBubble(Modifier.fillMaxWidth().height(48.dp), landing = { if (available) 0f else .62f }) {}
                        }
                    }
                }
            }
        }
    } } }
    rule.waitForIdle()
    val scene = rule.onNodeWithTag("oracle-scene")
    ShadowNativeCapture(rule, scene) { clock.stampNow.sequence }.use { capture ->
        for (caller in listOf("button", "filter", "settings", "bubble")) {
            rule.runOnIdle { kind = caller; available = true }
            rule.waitForIdle()
            val subject = if (caller == "filter") rule.onNode(isToggleable()) else rule.onNodeWithTag("oracle-control")
            val body = subject.fetchSemanticsNode().boundsInRoot.translate(-scene.fetchSemanticsNode().boundsInRoot.topLeft)
            val radius = when (caller) { "settings" -> 20f * rule.density.density; "bubble" -> 16f * rule.density.density; else -> body.height / 2f }
            for (enabled in listOf(true, false, true)) {
                rule.runOnIdle { available = enabled }
                rule.waitForIdle()
                val opacity = if (enabled) 1f else if (caller == "settings") .45f else .38f
                val actual = capture.capture()
                assertShadowField(rule, actual, body, radius, opacity, 1f, 1f, "$mode-$caller-$enabled")
            }
            if (caller == "button") {
                val node = rule.runOnIdle { checkNotNull(anchor.surfaceNode) }
                val shape = node.shape
                rule.mainClock.autoAdvance = false
                try {
                    for (pressed in listOf(true, false)) {
                        subject.performTouchInput { if (pressed) down(center) else up() }
                        repeat(12) { frame ->
                            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
                            val strain = rule.runOnIdle {
                                assertSame("Press keeps its actual caster", node, anchor.surfaceNode)
                                assertSame("Press keeps its persistent shape", shape, node.shape)
                                anchor.contact.body.value
                            }
                            val actual = capture.capture(rule.runOnIdle { checkNotNull(anchor.shadowPlane).host.routeEpoch })
                            assertShadowField(rule, actual, body, radius, 1f,
                                strain.scaleX(anchor.contact.vertical), strain.scaleY(anchor.contact.vertical), "$mode-press-$pressed-$frame")
                        }
                    }
                } finally { rule.mainClock.autoAdvance = true }
            }
        }
    }
}

private fun assertShadowField(rule: ComposeContentTestRule, actual: PresentedCapture, body: Rect,
    radius: Float, opacity: Float, sx: Float, sy: Float, label: String) {
    val expected = ImageBitmap(actual.image.width, actual.image.height)
    val background = ImageBitmap(actual.image.width, actual.image.height)
    val extent = Size(expected.width.toFloat(), expected.height.toFloat())
    val scene = checkNotNull(actual.sceneBounds)
    val screenBody = body.translate(scene.topLeft)
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(background), extent) { drawRect(Color.White) }
    // Independent same-geometry field, with the existing moving-reference compositing allowance.
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(expected), extent) {
        drawRect(Color.White)
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = opacity })
        scale(sx, sy, screenBody.center) {
            glassShadows(listOf(RoundRect(screenBody, CornerRadius(radius)) to 1f), 1f, 1f, GlassShadowGeometry(), rasterizing = true)
        }
        drawContext.canvas.restore()
    }
    val box = Rect(screenBody.center.x - body.width * sx / 2, screenBody.center.y - body.height * sy / 2,
        screenBody.center.x + body.width * sx / 2, screenBody.center.y + body.height * sy / 2).inflate(2f)
    // All four exterior sides, including the complete outer tail; the control's own material is excluded.
    val regions = listOf(Rect(scene.left, scene.top + 10f, scene.right, box.top),
        Rect(scene.left, box.bottom, scene.right, scene.bottom),
        Rect(scene.left, box.top, box.left, box.bottom), Rect(box.right, box.top, scene.right, box.bottom))
    val probe = ShadowFieldProbe(regions, 1f / 255f)
    val reference = actual.copy(image = expected, offscreen = true)
    val blank = actual.copy(image = background, offscreen = true)
    var result: ShadowComparison? = null
    try {
        result = probe.compare(actual, reference, blank)
        probe.assertWithin(result, 2f / 255f, label)
    } catch (failure: Throwable) {
        shadowOracleEvidence(label, actual, reference, result, failure.toString())
        throw failure
    }
    shadowOracleEvidence(label, actual, reference, result)
}
