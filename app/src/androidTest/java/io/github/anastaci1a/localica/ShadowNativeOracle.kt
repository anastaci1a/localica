package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertSame

/** Diagnostic-only sampling experiment. Delete this section/test independently of the authoritative check. */
internal fun collectRetainedSamplingDiagnostics(rule: ComposeContentTestRule) {
    var live by mutableStateOf(false)
    var shift by mutableStateOf(0f)
    lateinit var shadow: RetainedGlassShadow
    lateinit var clock: UiClock
    lateinit var bodies: List<Pair<RoundRect, Float>>
    rule.setContent {
        val currentClock = LocalUiClock.current
        SideEffect { clock = currentClock }
        val scope = rememberCoroutineScope()
        shadow = remember { RetainedGlassShadow(scope) }
        Box(Modifier.size(240.dp).background(Color.White).testTag("diagnostic-retained")
            .then(Modifier.drawBehind {
                bodies = listOf(60f, 110f).map { top -> RoundRect(60.dp.toPx() + .25f + shift,
                    top.dp.toPx() + .75f + shift, 160.dp.toPx() + .25f + shift,
                    (top + 40).dp.toPx() + .75f + shift, CornerRadius(20.dp.toPx())) to 1f }
                with(shadow) { draw(bodies, 1f, 1f, exclusion = if (live) GlassShadowExclusion(emptyList(),
                    androidx.compose.ui.geometry.Offset.Zero, size, 1f, 1f, 0f, 1f) else null) }
            }))
    }
    rule.waitUntil(5000) { shadow.rasterizations > 0 }
    rule.mainClock.autoAdvance = false
    try { ShadowNativeCapture(rule, rule.onNodeWithTag("diagnostic-retained")) { clock.stampNow.sequence }.use { native ->
        for (delta in listOf(0f, .5f)) for (moving in listOf(false, true)) {
            rule.runOnIdle { shift = delta; live = moving }
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            val actual = native.capture { scene -> ShadowDrawState(clock.stampNow.sequence, 0,
                shadowScreenBodies(bodies, scene.topLeft), exclusion = if (moving) ShadowExclusionState(emptyList(),
                    scene.topLeft, scene.size, 1f, 1f, 0f, 1f) else null) }
            recordRetainedSamplingVariants(rule, actual, "diagnostic-retained-$delta-$moving")
        }
    } } finally { rule.mainClock.autoAdvance = true }
}

internal fun collectNativeDestinationCutoutDiagnostics(rule: ComposeContentTestRule) {
    var shift by mutableStateOf(0f)
    lateinit var bodies: List<Pair<RoundRect, Float>>
    lateinit var clock: UiClock
    rule.setContent {
        val currentClock = LocalUiClock.current
        SideEffect { clock = currentClock }
        Box(Modifier.size(240.dp).background(Color.White).testTag("diagnostic-cutout").drawBehind {
            bodies = listOf(60f, 110f).map { top -> RoundRect(60.dp.toPx() + .25f + shift,
                top.dp.toPx() + .75f + shift, 160.dp.toPx() + .25f + shift,
                (top + 40).dp.toPx() + .75f + shift, CornerRadius(20.dp.toPx())) to 1f }
            diagnosticCutoutMask(bodies)
        })
    }
    rule.waitForIdle(); rule.mainClock.autoAdvance = false
    try { ShadowNativeCapture(rule, rule.onNodeWithTag("diagnostic-cutout")) { clock.stampNow.sequence }.use { native ->
        for (delta in listOf(0f, .5f)) {
            rule.runOnIdle { shift = delta }; rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            val actual = native.capture { scene -> ShadowDrawState(clock.stampNow.sequence, 0,
                shadowScreenBodies(bodies, scene.topLeft)) }
            val state = actual.nativeWindows.single().state
            val expected = ImageBitmap(actual.image.width, actual.image.height)
            val extent = Size(expected.width.toFloat(), expected.height.toFloat())
            val blank = ImageBitmap(expected.width, expected.height)
            CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(blank), extent) { drawRect(Color.White) }
            CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(expected), extent) {
                drawRect(Color.White); diagnosticCutoutMask(state.bodies)
            }
            val scene = checkNotNull(actual.sceneBounds)
            val roi = Rect(scene.left, scene.top + 10f, scene.right, scene.bottom)
            check(state.bodies.all { (r, _) -> val edge = Rect(r.left, r.top, r.right, r.bottom).inflate(2f)
                roi.contains(edge.topLeft) && roi.contains(edge.bottomRight) })
            val reference = actual.copy(image = expected, offscreen = true)
            val result = ShadowFieldProbe(listOf(roi), 1f / 255f).compare(actual, reference,
                actual.copy(image = blank, offscreen = true))
            shadowOracleEvidence("diagnostic-native-cutout-$delta", actual, reference, result)
        }
    } } finally { rule.mainClock.autoAdvance = true }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.diagnosticCutoutMask(bodies: List<Pair<RoundRect, Float>>) {
    val origin = androidx.compose.ui.geometry.Offset(bodies.minOf { it.first.left }, bodies.minOf { it.first.top })
    val geometry = GlassShadowGeometry().apply { update(shadowScreenBodies(bodies, -origin).map { it.first }, 0f, 0f, false) }
    translate(origin.x, origin.y) { clipPath(geometry.body, ClipOp.Difference) { drawRect(Color.Black, -origin, size) } }
}

private fun recordRetainedSamplingVariants(rule: ComposeContentTestRule, actual: PresentedCapture, label: String) {
    checkNativeShadowMarker(actual)
    val state = actual.nativeWindows.single().state
    check(state.receiver == null && state.exclusion?.bodies.isNullOrEmpty() && state.scaleX == 1f && state.scaleY == 1f)
    val density = rule.density.density
    val origin = androidx.compose.ui.geometry.Offset(state.bodies.minOf { it.first.left }, state.bodies.minOf { it.first.top })
    val local = shadowScreenBodies(state.bodies, -origin)
    val padding = (4f + state.spread * 28f + 2f) * density + 2f
    val inset = androidx.compose.ui.geometry.Offset(kotlin.math.floor(-padding), kotlin.math.floor(-padding))
    val field = ImageBitmap(kotlin.math.ceil(local.maxOf { it.first.right } + padding - inset.x).toInt(),
        kotlin.math.ceil(local.maxOf { it.first.bottom } + padding - inset.y).toInt())
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(field), Size(field.width.toFloat(), field.height.toFloat())) {
        translate(-inset.x, -inset.y) { glassShadows(local, state.strength, state.spread, GlassShadowGeometry(),
            excludeInterior = false, rasterizing = true) }
    }
    val scene = checkNotNull(actual.sceneBounds)
    val roi = Rect(scene.left, scene.top + 10f, scene.right, scene.bottom)
    // This unchanged authoritative ROI must enclose every possible tail, for both translation phases.
    check(state.bodies.all { (body, _) ->
        val tail = Rect(body.left, body.top, body.right, body.bottom).inflate(padding)
        roi.contains(tail.topLeft) && roi.contains(tail.bottomRight)
    }) { "Diagnostic ROI must not omit any shadow-field pixels" }
    val extent = Size(actual.image.width.toFloat(), actual.image.height.toFloat())
    val blank = ImageBitmap(actual.image.width, actual.image.height)
    CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(blank), extent) { drawRect(state.background) }
    val probe = ShadowFieldProbe(listOf(roi), 1f / 255f)
    val summaries = org.json.JSONArray()
    for (variant in listOf("authoritative", "A-normalized", "B-incremental")) {
        val expected = ImageBitmap(actual.image.width, actual.image.height)
        CanvasDrawScope().draw(rule.density, LayoutDirection.Ltr, Canvas(expected), extent) {
            drawRect(state.background)
            if (variant == "A-normalized") translate(origin.x, origin.y) {
                val geometry = GlassShadowGeometry().apply { update(local.map { it.first }, (4f + state.spread * 28f) * density, 2f * density) }
                clipPath(geometry.body, ClipOp.Difference) { translate(inset.x, inset.y) {
                    drawImage(field, dstSize = IntSize(field.width, field.height), filterQuality = FilterQuality.Low)
                } }
            } else {
                drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
                glassShadows(state.bodies, state.strength, state.spread, GlassShadowGeometry(), receiver = state.receiver,
                    exclusion = state.exclusion?.renderer(), rasterizing = variant == "authoritative")
                drawContext.canvas.restore()
            }
        }
        val reference = actual.copy(image = expected, offscreen = true)
        val result = probe.compare(actual, reference, actual.copy(image = blank, offscreen = true))
        shadowOracleEvidence("$label-$variant", actual, reference, result)
        fun pixel(image: ImageBitmap): Any = if (result.worstX < 0) org.json.JSONObject.NULL else {
            val argb = image.asAndroidBitmap().getPixel(result.worstX, result.worstY)
            org.json.JSONArray(listOf(16, 8, 0, 24).map { argb ushr it and 255 })
        }
        summaries.put(org.json.JSONObject().put("variant", variant).put("max", result.maximumRgbaError)
            .put("mean", result.meanRgbaError).put("missingCoverage", result.missingCoveragePixels)
            .put("worstX", result.worstX).put("worstY", result.worstY)
            .put("actualRgba", pixel(actual.image)).put("referenceRgba", pixel(expected)))
    }
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val directory = nativeEvidenceDirectory("shadow-field-oracle", context.getExternalFilesDir("shadow-field-oracle"))
    java.io.File(directory, "$label-sampling.json").writeText(org.json.JSONObject().put("diagnosticOnly", true)
        .put("actualPath", if (state.exclusion == null) "retained-rest" else "exclusion-selected-live")
        .put("opticalAcceptance", false).put("originX", origin.x.toDouble()).put("originY", origin.y.toDouble())
        .put("originXBits", origin.x.toRawBits()).put("originYBits", origin.y.toRawBits())
        .put("insetX", inset.x.toDouble()).put("insetY", inset.y.toDouble()).put("density", density.toDouble())
        .put("densityBits", density.toRawBits()).put("padding", padding.toDouble()).put("roi", roi.toString())
        .put("bodies", org.json.JSONArray(state.bodies.map { (r, a) -> org.json.JSONArray(listOf(r.left, r.top,
            r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, a).map { it.toDouble() }) }))
        .put("results", summaries).toString(2))
}

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
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
        glassShadows(bodies, strength, spread, GlassShadowGeometry(), receiver = receipt.receiver,
            exclusion = receipt.exclusion?.renderer(), rasterizing = true)
        drawContext.canvas.restore()
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
