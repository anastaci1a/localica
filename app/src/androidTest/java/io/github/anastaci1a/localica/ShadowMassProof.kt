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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.engine.GlassBodyTransform
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun verifyShadowMassCallers(rule: ComposeContentTestRule, finalButtons: Boolean, motion: Boolean = false,
    bodyClasses: Boolean = false) {
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
                    if (shown || motion && kind == "panel") key(kind) {
                        val anchor = rememberGlassAnchor()
                        val width = when (kind) {
                            "circle" -> 48.dp
                            "wide-bar" -> 176.dp
                            else -> 104.dp
                        }
                        val body = Modifier.size(width, 48.dp).glassShadowOffset {
                            if (translated) IntOffset(3.dp.roundToPx(), 2.dp.roundToPx()) else IntOffset.Zero
                        }.testTag("mass-body")
                        @Composable fun button(modifier: Modifier = body) {
                            GlassActionButton(kind == "circle", AppIcon.ActionRevert, "Revert", modifier, onClick = {})
                        }
                        when (kind) {
                            "shared", "shared-anchored" -> GlassShadowScope(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize(), Alignment.Center) {
                                    button(if (kind == "shared-anchored") body.glassAnchor(anchor) else body)
                                }
                            }
                            "anchored" -> button(body.glassAnchor(anchor))
                            "strip" -> GlassActionRow { button() }
                            "presence" -> GlassPresence(true) { button() }
                            "panel" -> FloatingSelectionPanel(rememberFloatingSelectionPanel(true, false)) {
                                if (shown) button() else Spacer(Modifier.size(width, 48.dp))
                            }
                            "clear" -> Box(body.clearGlassShadow(1f, 1f, 24.dp).background(Color.Red))
                            "receiver" -> {
                                val cache = rememberGlassShadow()
                                Box(body.drawWithContent {
                                    val caster = RoundRect(Rect(Offset.Zero, size), CornerRadius(24.dp.toPx()))
                                    with(cache) { draw(listOf(caster to 1f), 1f, 1f, receiver = null) }
                                    drawContent()
                                }.background(Color.Red))
                            }
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
                                    field.draw(this, 1f, 1f, 24.dp.toPx(), GlassBodyTransform(), null, null) { drawContent() }
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
                                        density, direction, 4.dp.toPx(), lift = lift)
                                    drawRoundRect(Color.Red, part.lifted.bounds.topLeft, part.lifted.bounds.size,
                                        CornerRadius(part.lifted.corner))
                                })
                            }
                            "stock" -> GlassPanel(body) {}
                            else -> button()
                        }
                    }
                }
            }
        }
    } }
    val cases = when {
        bodyClasses -> listOf("circle", "pill", "wide-bar")
        finalButtons -> listOf("private", "shared", "anchored", "strip", "presence", "panel", "shared-anchored")
        else -> listOf("clear", "receiver", "compound", "split", "native", "selection", "stock")
    }
    val output = nativeEvidenceDirectory(if (motion) "shadow-mass-motion" else "shadow-mass", androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().targetContext.cacheDir)
    check(output.isDirectory || output.mkdirs())
    val failures = mutableListOf<String>()
    var held = false
    val autoAdvance = rule.mainClock.autoAdvance
    try {
    if (motion) rule.mainClock.autoAdvance = false
    for (background in listOf(1f, .5f)) for (caller in cases) {
        rule.runOnIdle { kind = caller; level = background; shown = false; translated = false }
        if (motion) {
            // Settle the retained host with a layout-only child before admitting a fresh button and raster.
            if (caller == "panel") rule.mainClock.advanceTimeBy(1200) else rule.mainClock.advanceTimeByFrame()
        }
        rule.waitForIdle()
        val root = rule.onNodeWithTag("mass-window")
        val scene = rule.onNodeWithTag("mass-scene").fetchSemanticsNode().boundsInRoot
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        ShadowNativeCapture(rule, root) { clock.stampNow.sequence }.use { capture ->
            val control = capture.capture()
            checkNativeShadowMarker(control)
            val baseline = control.image.toPixelMap()
            rule.runOnIdle { shown = true }
            if (!motion) rule.mainClock.advanceTimeBy(1200)
            else rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val phases = if (motion) listOf("live", "rest", "press", "mid-press", "release", "mid-release", "recovered")
                else listOf("false", "true")
            var previousBody: GlassBodyTransform? = null
            for (phase in phases) {
                val move = phase == "true"
                val target = rule.onNodeWithTag("mass-body")
                if (!motion) {
                    rule.runOnIdle { translated = move }
                    rule.mainClock.advanceTimeBy(64)
                } else when (phase) {
                    "rest" -> rule.mainClock.advanceTimeBy(1200)
                    "press" -> { target.performTouchInput { down(center) }; held = true; rule.mainClock.advanceTimeBy(32) }
                    "mid-press", "mid-release" -> {
                        if (phase == "mid-press" && caller == "shared-anchored") {
                            target.performTouchInput { moveBy(Offset(width * .35f, 0f)) }
                        }
                        rule.mainClock.advanceTimeBy(64)
                    }
                    "release" -> { target.performTouchInput { up() }; held = false; rule.mainClock.advanceTimeBy(32) }
                    "recovered" -> rule.mainClock.advanceTimeBy(1600)
                }
                rule.waitForIdle()
                if (motion && caller == "shared-anchored" && phase == "recovered") {
                    rule.awaitRetainedShadows("$caller-$background-$phase", "mass-body")
                }
                val semantics = target.fetchSemanticsNode()
                val layoutBody = semantics.boundsInRoot
                val owners = if (motion) shadowMassOwners(semantics) else null
                var transform = GlassBodyTransform()
                var prepared = false
                var rasterizations = emptyList<Int>()
                var pressed = false
                val actual = capture.capture { screen ->
                    val contact = owners?.first
                    pressed = contact?.pressed == true
                    transform = contact?.body?.value?.bodyTransform(layoutBody.size,
                        rule.density.density, contact.vertical) ?: GlassBodyTransform()
                    val half = Offset(layoutBody.width * transform.scale.x / 2, layoutBody.height * transform.scale.y / 2)
                    val presented = Rect(layoutBody.center + transform.drift - half, layoutBody.center + transform.drift + half)
                    val caches = owners?.second?.filter { shadowMassField(it, "requested") != null }.orEmpty()
                    if (motion) check(caches.isNotEmpty()) { "No actual shadow cache in $caller" }
                    prepared = caches.isNotEmpty() && caches.all { cache ->
                        val raster = (shadowMassField(cache, "raster\$delegate") as State<*>).value
                        raster?.let { shadowMassField(it, "key") } == shadowMassField(cache, "requested")
                    }
                    rasterizations = caches.map { it.rasterizations }
                    ShadowDrawState(clock.stampNow.sequence, if (prepared) 1 else 0,
                        listOf(RoundRect(presented.translate(screen.topLeft - rootBounds.topLeft),
                            CornerRadius(layoutBody.height * transform.scale.x / 2, layoutBody.height * transform.scale.y / 2)) to 1f),
                        Color(background, background, background), transform.scale.x, transform.scale.y)
                }
                val drawn = actual.nativeWindows.single().state
                val body = drawn.bodies.single().first.let { Rect(it.left, it.top, it.right, it.bottom) }
                    .translate(rootBounds.topLeft - checkNotNull(actual.sceneBounds).topLeft)
                val bodyChanged = previousBody?.let { it != transform } == true
                previousBody = transform
                if (motion) {
                    if (caller == "shared-anchored" && phase == "mid-press") {
                        check(kotlin.math.abs(drawn.scaleX - drawn.scaleY) > .001f) {
                            "The shared field must present different horizontal and vertical radii: $drawn"
                        }
                    }
                    if (phase == "live") check(drawn.epoch == 0L) { "$caller initial frame was not live" }
                    if (phase == "rest" || phase == "recovered") check(drawn.epoch == 1L && rasterizations.all { it > 0 }) {
                        "$caller $phase was not prepared: $rasterizations"
                    }
                    if (phase in listOf("press", "mid-press", "release", "mid-release")) {
                        val expectedPressed = phase == "press" || phase == "mid-press"
                        check(pressed == expectedPressed && bodyChanged &&
                            (kotlin.math.abs(drawn.scaleX - 1f) + kotlin.math.abs(drawn.scaleY - 1f)) > .0001f) {
                            "$caller $phase did not present an evolving deformed body: $drawn pressed=$pressed changed=$bodyChanged"
                        }
                    }
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
                val caster = if (motion && caller == "shared") layoutBody.translate(-scene.topLeft) else local
                val nativeField = caller == "shared" || caller == "shared-anchored"
                val sx = if (motion && !nativeField) drawn.scaleX else 1f
                val sy = if (motion && !nativeField) drawn.scaleY else 1f
                val radius = layoutBody.height / 2
                val contour = if (motion && caller == "shared-anchored") {
                    ShadowMassContour(caster.left, caster.top, caster.right, caster.bottom,
                        radius * drawn.scaleX, radius * drawn.scaleY)
                } else ShadowMassContour(caster.left, caster.top, caster.right, caster.bottom, radius)
                val reference = shadowMassReference(width, height, local.left, local.top, local.right, local.bottom,
                    radius, rule.density.density, code = 1.0 / (255 * background), contours = listOf(contour), scaleX = sx, scaleY = sy)
                val start = ceil(local.left).toInt()
                val columns = floor(local.right).toInt() - start
                val near = shadowMass(columns, height, local.top, local.bottom, (16 * rule.density.density).toInt()) { x, y ->
                    val b = baseline[x0 + start + x, y0 + y]; val a = pixels[x0 + start + x, y0 + y]
                    max(0.0, ((b.red - a.red) + (b.green - a.green) + (b.blue - a.blue)).toDouble() / (3 * background))
                }
                val nearReference = shadowMassReference(columns, height, local.left - start, local.top,
                    local.right - start, local.bottom, radius, rule.density.density,
                    code = 1.0 / (255 * background), bandDp = 16f,
                    contours = listOf(contour.copy(left = contour.left - start, right = contour.right - start)), scaleX = sx, scaleY = sy)
                val label = "$caller-$background-$phase"
                if (bodyClasses && background == 1f && !move) {
                    File(output, "$caller-stock.png").outputStream().use {
                        check(actual.image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                File(output, "$label.json").writeText(JSONObject().put("caller", caller).put("background", background)
                    .put("translated", move).put("phase", phase).put("density", rule.density.density).put("body", body.toString())
                    .put("layoutBody", layoutBody.toString()).put("scaleX", drawn.scaleX).put("scaleY", drawn.scaleY)
                    .put("prepared", drawn.epoch == 1L).put("rasterizations", rasterizations.toString())
                    .put("pressed", pressed).put("bodyChanged", bodyChanged)
                    .put("caster", caster.toString()).put("fieldScaleX", sx).put("fieldScaleY", sy)
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
    } finally {
        try { if (held) rule.onNodeWithTag("mass-body").performTouchInput { cancel() } }
        finally { rule.mainClock.autoAdvance = autoAdvance }
    }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
}

/** Passive inspection of the existing modifier captures; never changes a contact or cache. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun shadowMassOwners(node: androidx.compose.ui.semantics.SemanticsNode): Pair<GlassContact, List<RetainedGlassShadow>> {
    val contacts = linkedSetOf<GlassContact>()
    val caches = linkedSetOf<RetainedGlassShadow>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    fun visit(value: Any?, depth: Int) {
        if (value == null || !seen.add(value)) return
        when (value) {
            is GlassContact -> contacts += value
            is RetainedGlassShadow -> caches += value
            else -> if (depth > 0 && (value is Function<*> || value is Modifier.Element))
                value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
                    it.isAccessible = true; visit(it.get(value), depth - 1)
                }
        }
    }
    var layout: androidx.compose.ui.layout.LayoutInfo? = node.layoutInfo
    while (layout != null) {
        layout.getModifierInfo().forEach { visit(it.modifier, 5) }
        layout = layout.parentInfo
    }
    check(contacts.size == 1 && caches.isNotEmpty()) { "Expected one real contact and its field cache: contacts=$contacts caches=$caches" }
    return contacts.single() to caches.toList()
}

private fun shadowMassField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
    it.isAccessible = true; it.get(owner)
}

internal fun verifyHeldResizeShadow(rule: ComposeContentTestRule) {
    var resized by mutableStateOf(false)
    var castShadow by mutableStateOf(true)
    val contact = GlassContact()
    val density = 2f
    rule.setContent { LocalicaTheme {
        CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density)) {
            Box(Modifier.fillMaxSize().background(Color.White).testTag("held-resize-scene"), Alignment.Center) {
                LiquidGlassSurface(Modifier.size(if (resized) 240.dp else 104.dp, if (resized) 104.dp else 48.dp)
                    .testTag("held-resize-body"), source = null, cornerRadius = 0.dp, shadow = castShadow, sharedContact = contact) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
    } }
    val scene = rule.onNodeWithTag("held-resize-scene")
    val body = rule.onNodeWithTag("held-resize-body")
    val output = nativeEvidenceDirectory("held-resize-shadow",
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    val autoAdvance = rule.mainClock.autoAdvance
    fun checkDimensions(width: Float, height: Float, drag: Double) {
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val layout = body.fetchSemanticsNode().boundsInRoot.translate(-origin)
        org.junit.Assert.assertEquals(width * density, layout.width, 0f)
        org.junit.Assert.assertEquals(height * density, layout.height, 0f)
        val points = if (width < 120) 16.0 else 16 - 12 * (height - 44) / 116.0
        val gain = 1 + points / width
        val sx = (gain * kotlin.math.exp(drag)).toFloat()
        val sy = (gain * kotlin.math.exp(-drag)).toFloat()
        val center = layout.center + Offset((drag * layout.width).toFloat(), 0f)
        val halfSize = Offset(layout.width * sx, layout.height * sy) / 2f
        val predicted = Rect(center - halfSize, center + halfSize)
        val image = scene.captureToImage()
        val pixels = image.toPixelMap()
        fun bodyInk(pixel: Color) = pixel.red > .75f && pixel.green < .25f
        val sampleX = center.x.roundToInt()
        val sampleY = center.y.roundToInt()
        val columns = (0 until image.width).filter { bodyInk(pixels[it, sampleY]) }
        val rows = (0 until image.height).filter { bodyInk(pixels[sampleX, it]) }
        check(columns.isNotEmpty() && rows.isNotEmpty()) { "Held resize capture must contain the body" }
        val drawn = Rect(columns.first().toFloat(), rows.first().toFloat(), columns.last() + 1f, rows.last() + 1f)
        listOf("left" to (predicted.left to drawn.left), "top" to (predicted.top to drawn.top),
            "right" to (predicted.right to drawn.right), "bottom" to (predicted.bottom to drawn.bottom))
            .forEach { (side, pair) -> org.junit.Assert.assertEquals(side, pair.first, pair.second, 1.5f) }
        val observedGain = (drawn.width / kotlin.math.exp(drag) - layout.width).toFloat()
        org.junit.Assert.assertEquals("Recover P from the painted body", (points * density).toFloat(), observedGain, 1.5f)
        val pose = rule.runOnIdle { contact.body.value }
        rule.runOnIdle { castShadow = false }
        rule.mainClock.advanceTimeByFrame()
        val control = scene.captureToImage().toPixelMap()
        org.junit.Assert.assertEquals("Shadow-off capture preserves the held body snapshot", pose, rule.runOnIdle { contact.body.value })
        org.junit.Assert.assertEquals("Shadow-off horizontal body mask", columns,
            (0 until image.width).filter { bodyInk(control[it, sampleY]) })
        org.junit.Assert.assertEquals("Shadow-off vertical body mask", rows,
            (0 until image.height).filter { bodyInk(control[sampleX, it]) })
        rule.runOnIdle { castShadow = true }
        rule.mainClock.advanceTimeByFrame()
        org.junit.Assert.assertEquals("Restoring the shadow preserves the held body snapshot", pose, rule.runOnIdle { contact.body.value })
        val left = ceil(drawn.left).toInt()
        val count = floor(drawn.right).toInt() - left
        val mass = shadowMass(count, image.height, drawn.top, drawn.bottom, (16 * density).toInt()) { x, y ->
            val pixel = pixels[x + left, y]
            val base = control[x + left, y]
            max(0.0, ((base.red - pixel.red) + (base.green - pixel.green) + (base.blue - pixel.blue)) / 3.0)
        }
        val reference = shadowMassReference(count, image.height, predicted.left - left, drawn.top,
            predicted.right - left, drawn.bottom, 0f, density, bandDp = 16f,
            contours = listOf(ShadowMassContour(predicted.left - left, predicted.top,
                predicted.right - left, predicted.bottom, 0f)), scaleX = sx, scaleY = sy)
        File(output, "width-" + width.toInt() + "-drag-$drag.txt").writeText(
            "P=$points observedGain=$observedGain predicted=$predicted drawn=$drawn layout=$layout mass=$mass reference=$reference\n")
        assertTrue("Held resize keeps a visible downward field at $width dp: $mass / $reference", reference.accepts(mass))
    }
    rule.mainClock.autoAdvance = false
    var held = false
    try {
        body.performTouchInput { down(center) }
        held = true
        rule.mainClock.advanceTimeBy(2500)
        checkDimensions(104f, 48f, 0.0)
        body.performTouchInput { moveBy(Offset(100 * density, 0f)) }
        rule.mainClock.advanceTimeBy(2500)
        val drag = (52.0 / 4 + 100 - 52) / 2000
        val snapshot = rule.runOnIdle { contact.body.value }
        checkDimensions(104f, 48f, drag)
        rule.runOnIdle { resized = true }
        rule.mainClock.advanceTimeByFrame()
        checkDimensions(240f, 104f, drag)
        org.junit.Assert.assertEquals("First resized draw uses the same held press snapshot",
            snapshot, rule.runOnIdle { contact.body.value })
    } finally {
        try { if (held) body.performTouchInput { cancel() } }
        finally { rule.mainClock.autoAdvance = autoAdvance }
    }
}
