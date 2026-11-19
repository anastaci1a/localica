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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun verifyShadowMassCallers(rule: ComposeContentTestRule, finalButtons: Boolean, motion: Boolean = false) {
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
        if (motion) rule.mainClock.advanceTimeByFrame()
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
            for (phase in phases) {
                val move = phase == "true"
                val target = rule.onNodeWithTag("mass-body")
                if (!motion) {
                    rule.runOnIdle { translated = move }
                    rule.mainClock.advanceTimeBy(64)
                } else when (phase) {
                    "rest" -> rule.mainClock.advanceTimeBy(1200)
                    "press" -> { target.performTouchInput { down(center) }; held = true; rule.mainClock.advanceTimeBy(32) }
                    "mid-press", "mid-release" -> rule.mainClock.advanceTimeBy(64)
                    "release" -> { target.performTouchInput { up() }; held = false; rule.mainClock.advanceTimeBy(32) }
                    "recovered" -> rule.mainClock.advanceTimeBy(1600)
                }
                rule.waitForIdle()
                val semantics = target.fetchSemanticsNode()
                val layoutBody = semantics.boundsInRoot
                val owners = if (motion) shadowMassOwners(semantics) else null
                var transform = GlassBodyTransform()
                var prepared = false
                var rasterizations = emptyList<Int>()
                var active = 0
                val actual = capture.capture { screen ->
                    val contact = owners?.first
                    transform = contact?.body?.value?.bodyTransform(contact.point, layoutBody.size,
                        contact.vertical, anchored = caller == "anchored") ?: GlassBodyTransform()
                    val half = Offset(layoutBody.width * transform.scale.x / 2, layoutBody.height * transform.scale.y / 2)
                    val presented = Rect(layoutBody.center + transform.drift - half, layoutBody.center + transform.drift + half)
                    val caches = owners?.second?.filter { shadowMassField(it, "requested") != null }.orEmpty()
                    if (motion) check(caches.isNotEmpty()) { "No actual shadow cache in $caller" }
                    prepared = caches.isNotEmpty() && caches.all { cache ->
                        shadowMassField(cache, "raster")?.let { shadowMassField(it, "key") } == shadowMassField(cache, "requested")
                    }
                    rasterizations = caches.map { it.rasterizations }
                    active = clock.activeCount
                    ShadowDrawState(clock.stampNow.sequence, if (prepared) 1 else 0,
                        listOf(RoundRect(presented.translate(screen.topLeft - rootBounds.topLeft),
                            CornerRadius(layoutBody.height * transform.scale.x / 2, layoutBody.height * transform.scale.y / 2)) to 1f),
                        Color(background, background, background), transform.scale.x, transform.scale.y)
                }
                val drawn = actual.nativeWindows.single().state
                val body = drawn.bodies.single().first.let { Rect(it.left, it.top, it.right, it.bottom) }
                    .translate(rootBounds.topLeft - checkNotNull(actual.sceneBounds).topLeft)
                if (motion) {
                    if (phase == "live") check(drawn.epoch == 0L) { "$caller initial frame was not live" }
                    if (phase == "rest" || phase == "recovered") check(drawn.epoch == 1L && rasterizations.all { it > 0 }) {
                        "$caller $phase was not prepared: $rasterizations"
                    }
                    if (phase in listOf("press", "mid-press", "release", "mid-release")) {
                        check(active > 0 && (kotlin.math.abs(drawn.scaleX - 1f) + kotlin.math.abs(drawn.scaleY - 1f)) > .0001f) {
                            "$caller $phase did not present an active deformed body: $drawn active=$active"
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
                val sx = if (motion && caller != "shared") drawn.scaleX else 1f
                val sy = if (motion && caller != "shared") drawn.scaleY else 1f
                val radius = layoutBody.height / 2
                val contour = ShadowMassContour(caster.left, caster.top, caster.right, caster.bottom, radius)
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
                File(output, "$label.json").writeText(JSONObject().put("caller", caller).put("background", background)
                    .put("translated", move).put("phase", phase).put("density", rule.density.density).put("body", body.toString())
                    .put("layoutBody", layoutBody.toString()).put("scaleX", drawn.scaleX).put("scaleY", drawn.scaleY)
                    .put("prepared", drawn.epoch == 1L).put("rasterizations", rasterizations.toString()).put("activeMotions", active)
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
