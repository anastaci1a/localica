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

internal fun shadowScreenBodies(bodies: List<Pair<RoundRect, Float>>, offset: androidx.compose.ui.geometry.Offset) = bodies.map { (r, alpha) ->
    RoundRect(r.left + offset.x, r.top + offset.y, r.right + offset.x, r.bottom + offset.y,
        r.topLeftCornerRadius, r.topRightCornerRadius, r.bottomRightCornerRadius, r.bottomLeftCornerRadius) to alpha
}

/** Current canonical bodies are supplied in screen space, after their actual plane/layout mapping. */
internal fun verifyShadowUnion(rule: ComposeContentTestRule, actual: PresentedCapture,
    bodies: List<Pair<RoundRect, Float>>, background: Color, regions: List<Rect>, label: String,
    strength: Float = 1f, spread: Float = 1f) {
    val receipt = actual.nativeWindows.first().state
    check(receipt.bodies == bodies && receipt.background == background && receipt.scaleX == 1f && receipt.scaleY == 1f &&
        receipt.strength == strength && receipt.spread == spread) {
        "Incomplete: reference differs from the decoded native draw geometry"
    }
    val expected = ImageBitmap(actual.image.width, actual.image.height)
    val blank = ImageBitmap(actual.image.width, actual.image.height)
    val extent = Size(expected.width.toFloat(), expected.height.toFloat())
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(blank), extent) { drawRect(background) }
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(expected), extent) {
        drawRect(background)
        glassShadows(bodies, strength, spread, GlassShadowGeometry(), receiver = receipt.receiver,
            exclusion = receipt.exclusion?.renderer(), rasterizing = true)
    }
    val reference = actual.copy(image = expected, offscreen = true)
    val probe = ShadowFieldProbe(regions, 1f / 255f)
    var result: ShadowComparison? = null
    try {
        result = probe.compare(actual, reference, actual.copy(image = blank, offscreen = true))
        probe.assertWithin(result, 2f / 255f, label)
    } catch (failure: Throwable) {
        shadowOracleEvidence(label, actual, reference, result, failure.toString())
        throw failure
    }
    shadowOracleEvidence(label, actual, reference, result)
}

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
            for ((sample, enabled) in listOf(true, false, true).withIndex()) {
                rule.runOnIdle { available = enabled }
                rule.waitForIdle()
                val opacity = if (enabled) 1f else if (caller == "settings") .45f else .38f
                val actual = capture.capture { scene -> ShadowDrawState(clock.stampNow.sequence, 0,
                    shadowScreenBodies(listOf(RoundRect(body, CornerRadius(radius)) to opacity), scene.topLeft)) }
                assertShadowField(rule, actual, "$mode-$caller-$sample-$enabled")
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
                            rule.runOnIdle {
                                assertSame("Press keeps its actual caster", node, anchor.surfaceNode)
                                assertSame("Press keeps its persistent shape", shape, node.shape)
                            }
                            val actual = capture.capture { scene ->
                                val strain = anchor.contact.body.value
                                ShadowDrawState(clock.stampNow.sequence, checkNotNull(anchor.shadowPlane).host.routeEpoch,
                                    shadowScreenBodies(listOf(RoundRect(body, CornerRadius(radius)) to 1f), scene.topLeft),
                                    scaleX = strain.scaleX(anchor.contact.vertical), scaleY = strain.scaleY(anchor.contact.vertical))
                            }
                            assertShadowField(rule, actual, "$mode-press-$pressed-$frame")
                        }
                    }
                } finally { rule.mainClock.autoAdvance = true }
            }
        }
    }
}

private fun assertShadowField(rule: ComposeContentTestRule, actual: PresentedCapture, label: String) {
    val expected = ImageBitmap(actual.image.width, actual.image.height)
    val background = ImageBitmap(actual.image.width, actual.image.height)
    val extent = Size(expected.width.toFloat(), expected.height.toFloat())
    val scene = checkNotNull(actual.sceneBounds)
    val receipt = actual.nativeWindows.single().state
    val (caster, opacity) = receipt.bodies.single()
    val screenBody = Rect(caster.left, caster.top, caster.right, caster.bottom)
    val sx = receipt.scaleX; val sy = receipt.scaleY
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(background), extent) { drawRect(Color.White) }
    // Independent same-geometry field, with the existing moving-reference compositing allowance.
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(expected), extent) {
        drawRect(Color.White)
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = opacity })
        scale(sx, sy, screenBody.center) {
            glassShadows(listOf(caster to 1f), 1f, 1f, GlassShadowGeometry(), rasterizing = true)
        }
        drawContext.canvas.restore()
    }
    val box = Rect(screenBody.center.x - screenBody.width * sx / 2, screenBody.center.y - screenBody.height * sy / 2,
        screenBody.center.x + screenBody.width * sx / 2, screenBody.center.y + screenBody.height * sy / 2).inflate(2f)
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
