package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas as UiCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShadowContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun unpresentedWindowFailsAdmissionBeforeFieldCapture() {
        lateinit var view: android.view.View
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Box(Modifier.size(100.dp).background(Color.White).testTag("unpresented-shadow"))
        }
        val window = compose.runOnUiThread {
            generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<android.app.Activity>().first().window
        }
        val alpha = compose.runOnUiThread { window.attributes.alpha }
        try {
            compose.runOnUiThread { window.attributes = window.attributes.apply { this.alpha = 0f } }
            ShadowNativeCapture(compose, compose.onNodeWithTag("unpresented-shadow")) { 0L }.use { capture ->
                val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) { capture.capture() }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty()
                    .matches(Regex("Native window admission timed out: \\[window-[0-9]+\\]")))
            }
        } finally { compose.runOnUiThread { window.attributes = window.attributes.apply { this.alpha = alpha } } }
    }

    @Test fun retainedShadowDoesNotPopWhenMotionStartsOrSettles() {
        var moving by mutableStateOf(false)
        var background by mutableStateOf(Color.White)
        var clearStatusChrome by mutableStateOf(false)
        var statusClearancePixels by mutableIntStateOf(0)
        lateinit var view: android.view.View
        lateinit var shadow: RetainedGlassShadow
        lateinit var clock: UiClock
        lateinit var currentBodies: List<Pair<RoundRect, Float>>
        compose.setContent {
            val currentClock = LocalUiClock.current
            val currentView = androidx.compose.ui.platform.LocalView.current
            SideEffect { clock = currentClock; view = currentView }
            val scope = rememberCoroutineScope()
            shadow = remember { RetainedGlassShadow(scope) }
            // Move the entire unchanged fixture clear of system chrome; no capture crop or mask.
            // The native inset is captured before moving; Compose may already consume it.
            val clearance = with(androidx.compose.ui.platform.LocalDensity.current) { statusClearancePixels.toDp() }
            Box(Modifier.padding(top = if (clearStatusChrome)
                clearance else 0.dp)) {
            Box(Modifier.size(240.dp).background(background).testTag("shadow-scene")
                .drawBehind {
                    val width = 100f
                    val body = androidx.compose.ui.geometry.RoundRect(60.dp.toPx(), 60.dp.toPx(),
                        (60 + width).dp.toPx(), 120.dp.toPx(), androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))
                    // Popup neighbor exclusion selects the live path even at identical geometry.
                    val exclusion = if (moving) GlassShadowExclusion(emptyList(),
                        androidx.compose.ui.geometry.Offset.Zero, size, 1f, 1f, 0f, 1f) else null
                    with(shadow) { draw(listOf(body to 1f).also { currentBodies = it }, 1f, 1f, exclusion = exclusion) }
                })
            }
        }
        compose.waitUntil(5000) { shadow.rasterizations > 0 }
        compose.mainClock.autoAdvance = false
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(90*d, 122*d, 130*d, 130*d))
        val scene = compose.onNodeWithTag("shadow-scene")
        fun geometryCapture(native: ShadowNativeCapture) = native.capture { bounds -> ShadowDrawState(clock.stampNow.sequence, 0,
                shadowScreenBodies(currentBodies, bounds.topLeft), background) }
        fun insetsReceipt() = compose.runOnIdle {
                val insets = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
                org.json.JSONObject().put("systemBars", insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).toString())
                    .put("systemBarsIgnoringVisibility", insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars()).toString())
                    .put("statusBarsVisible", insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()))
        }
        val included = ShadowNativeCapture(compose, scene) { clock.stampNow.sequence }.use { geometryCapture(it) }
        val includedInsets = insetsReceipt()
        compose.runOnIdle {
            statusClearancePixels = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
                .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            clearStatusChrome = true
        }
        compose.mainClock.advanceTimeByFrame()
        // Each unchanged capture owner freezes its own placement, including its native marker.
        ShadowNativeCapture(compose, scene) { clock.stampNow.sequence }.use { native ->
            val excluded = geometryCapture(native)
            val excludedInsets = insetsReceipt()
            fun localBodies(capture: PresentedCapture) = shadowScreenBodies(
                capture.nativeWindows.single().state.bodies, -checkNotNull(capture.sceneBounds).topLeft)
            val statusBottom = compose.runOnIdle {
                checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view))
                    .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            }
            shadowOracleEvidence("retained-layout-chrome-included", included)
            shadowOracleEvidence("retained-layout-chrome-excluded", excluded)
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val evidence = nativeEvidenceDirectory("shadow-field-oracle", context.getExternalFilesDir("shadow-field-oracle"))
            check(evidence.isDirectory || evidence.mkdirs())
            java.io.File(evidence, "retained-layout-insets.json").writeText(org.json.JSONObject()
                .put("included", includedInsets).put("excluded", excludedInsets)
                .put("includedBounds", included.sceneBounds.toString()).put("excludedBounds", excluded.sceneBounds.toString())
                .put("includedLocalBodies", localBodies(included).toString()).put("excludedLocalBodies", localBodies(excluded).toString())
                .put("capturedStatusInsetPixels", statusClearancePixels)
                .put("sceneRelativeLayoutUnchanged", included.sceneBounds!!.size == excluded.sceneBounds!!.size &&
                    localBodies(included) == localBodies(excluded)).toString(2))
            assertEquals("Chrome clearance preserves scene dimensions", included.sceneBounds!!.size, excluded.sceneBounds!!.size)
            assertEquals("Chrome clearance preserves every scene-local body", localBodies(included), localBodies(excluded))
            assertEquals("Chrome clearance leaves native insets unchanged", includedInsets.toString(), excludedInsets.toString())
            assertTrue("Entire scene clears status chrome", excluded.sceneBounds!!.top >= statusBottom)
            var sample = 0
            fun capture() {
                val actual = native.capture { bounds -> ShadowDrawState(clock.stampNow.sequence, 0,
                    shadowScreenBodies(currentBodies, bounds.topLeft), background) }
                val bounds = checkNotNull(actual.sceneBounds)
                val state = actual.nativeWindows.first().state
                verifyShadowUnion(compose, actual, state.bodies, state.background,
                    listOf(Rect(bounds.left, bounds.top + 10f, bounds.right, bounds.bottom)), "retained-handoff-${sample++}")
                probe.sample(actual)
            }
            for (level in listOf(1f, .5f, .25f)) {
                compose.runOnIdle { background = Color(level, level, level); moving = false }
                compose.mainClock.advanceTimeByFrame()
                probe.beginSegment()
                capture()
                repeat(4) { index ->
                    compose.runOnIdle { moving = index % 2 == 0 }
                    compose.mainClock.advanceTimeByFrame()
                    capture()
                }
            }
            probe.assertContinuous(.008f, "retained/direct handoff without perceptible geometry motion")
        }
    }

    @Test fun buttonAvailabilityDoesNotCutItsShadowAtEitherEndpoint() = availability("button")

    @Test fun retainedUnionKeepsTheInteriorCutoutOnTheSamePixels() {
        var moving by mutableStateOf(false)
        lateinit var shadow: RetainedGlassShadow
        lateinit var clock: UiClock
        lateinit var currentBodies: List<Pair<RoundRect, Float>>
        compose.setContent {
            val currentClock = LocalUiClock.current
            SideEffect { clock = currentClock }
            val scope = rememberCoroutineScope()
            shadow = remember { RetainedGlassShadow(scope) }
            Box(Modifier.size(240.dp).background(Color.White).testTag("union-shadow")
                .drawBehind {
                    val bodies = listOf(60f, 110f).map { top ->
                        androidx.compose.ui.geometry.RoundRect(60.dp.toPx() + .25f, top.dp.toPx() + .75f,
                            160.dp.toPx() + .25f, (top + 40).dp.toPx() + .75f,
                            androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())) to 1f
                    }
                    val exclusion = if (moving) GlassShadowExclusion(emptyList(),
                        androidx.compose.ui.geometry.Offset.Zero, size, 1f, 1f, 0f, 1f) else null
                    with(shadow) { draw(bodies.also { currentBodies = it }, 1f, 1f, exclusion = exclusion) }
                })
        }
        compose.waitUntil(5000) { shadow.rasterizations > 0 }
        val scene = compose.onNodeWithTag("union-shadow")
        ShadowNativeCapture(compose, scene) { clock.stampNow.sequence }.use { native ->
            fun capture(label: String): PresentedCapture {
                val actual = native.capture { bounds -> ShadowDrawState(clock.stampNow.sequence, 0,
                    shadowScreenBodies(currentBodies, bounds.topLeft)) }
                val bounds = checkNotNull(actual.sceneBounds)
                verifyShadowUnion(compose, actual, actual.nativeWindows.first().state.bodies,
                    Color.White, listOf(Rect(bounds.left, bounds.top + 10f, bounds.right, bounds.bottom)), label)
                return actual
            }
            val retainedCapture = capture("retained-union-rest")
            val retained = retainedCapture.image.toPixelMap()
            compose.runOnIdle { moving = true }
            val liveCapture = capture("retained-union-live")
            assertEquals(retainedCapture.sceneBounds, liveCapture.sceneBounds)
            val live = liveCapture.image.toPixelMap()
            val bounds = checkNotNull(liveCapture.sceneBounds)
            val markers = liveCapture.nativeWindows.map { receipt ->
                val p = receipt.generation.marker
                Rect(p.x - 1f, p.y - 1f, p.x + 7f, p.y + 7f)
            }
            assertEquals(retainedCapture.nativeWindows.map { it.generation.marker }, liveCapture.nativeWindows.map { it.generation.marker })
            val reach = 34f * compose.density.density + 2f
            assertTrue("Diagnostic markers are outside every shadow tail", markers.all { marker ->
                liveCapture.nativeWindows.first().state.bodies.all { (body, _) ->
                    !marker.overlaps(Rect(body.left, body.top, body.right, body.bottom).inflate(reach))
                }
            })
            var maximum = 0f
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt())
                if (markers.none { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
                    maximum = maxOf(maximum, kotlin.math.abs(live[x, y].red - retained[x, y].red))
            assertTrue("The same contour pixels must be cut out: maximum delta=$maximum", maximum <= .03f)
        }
    }
    @Test fun filterAvailabilityDoesNotCutItsShadowAtEitherEndpoint() = availability("filter")
    @Test fun settingsAvailabilityDoesNotCutItsShadowAtEitherEndpoint() = availability("settings")
    @Test fun dragBubbleLandingDoesNotCutItsShadowAtEitherEndpoint() = availability("bubble")

    @Test fun buttonPressAndReleaseKeepTheCompleteMovingShadow() {
        val anchor = GlassAnchor()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(240.dp).background(Color.White).testTag("press-shadow-scene"),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    GlassActionButton(AppIcon.ActionEllipsis, "Press", Modifier.width(160.dp).glassAnchor(anchor).testTag("press-shadow")) {}
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val scene = compose.onNodeWithTag("press-shadow-scene")
        val button = compose.onNodeWithTag("press-shadow")
        val body = button.fetchSemanticsNode().boundsInRoot.translate(-scene.fetchSemanticsNode().boundsInRoot.topLeft)
        val d = compose.density.density
        val region = Rect(body.center.x-8*d, body.bottom+4*d, body.center.x+8*d, body.bottom+8*d)
        val probe = ShadowFrameProbe(region)
        val reference = ShadowFrameProbe(region)
        val resting = scene.captureNativeFrame()
        probe.sample(resting)
        reference.sample(resting)
        for (pressed in listOf(true, false)) {
            button.performTouchInput { if (pressed) down(center) else up() }
            repeat(48) {
                compose.mainClock.advanceTimeByFrame()
                val image = scene.captureNativeFrame()
                val expected = ImageBitmap(image.width, image.height)
                compose.runOnIdle {
                    val shape = anchor.contact.body.value
                    CanvasDrawScope().draw(compose.density, LayoutDirection.Ltr, UiCanvas(expected),
                        Size(image.width.toFloat(), image.height.toFloat())) {
                        drawRect(Color.White)
                        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
                        scale(shape.scaleX(anchor.contact.vertical), shape.scaleY(anchor.contact.vertical), body.center) {
                            glassShadows(listOf(RoundRect(body, CornerRadius(body.height/2)) to 1f), 1f, 1f, GlassShadowGeometry(), rasterizing = true)
                        }
                        drawContext.canvas.restore()
                    }
                }
                assertEquals("Press shadow follows its complete transformed field", reference.sample(expected), probe.sample(image), 2f/255f)
                ShadowFrameProbe.assertSpreadEdge(image,
                    Rect(body.center.x-8*d, body.bottom+34*d, body.center.x+8*d, body.bottom+36*d),
                    Rect(body.center.x-8*d, body.bottom+36*d, body.center.x+8*d, body.bottom+38*d), .015f, "pressed button")
            }
        }
        assertTrue("Press visibly moves the shadow", probe.luminances.max() - probe.luminances.min() > .01f)
        probe.assertContinuous(reference.maximumDelta + 2f/255f, "button press/release")
    }

    private fun availability(context: String) {
        var enabled by mutableStateOf(true)
        lateinit var expectedOpacity: State<Float>
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(240.dp).background(Color.White).testTag("shadow-scene"),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    val opacity = animateControlStateFloat(if (enabled) 1f else if (context == "settings") .45f else .38f,
                        enabled, "Expected shadow opacity")
                    SideEffect { expectedOpacity = opacity }
                    Box(Modifier.width(160.dp).testTag("shadow-button"), propagateMinConstraints = true) {
                        when (context) {
                            "button" -> GlassActionButton(AppIcon.ActionEllipsis, "Actions", enabled = enabled) {}
                            "filter" -> GlassFilterChoice(false, {}, "Filter", enabled)
                            "settings" -> SettingsLink("Settings", enabled = enabled) {}
                            "bubble" -> {
                                GlassDragBubble(Modifier.fillMaxWidth().height(48.dp), landing = { 1f - opacity.value }) {}
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val scene = compose.onNodeWithTag("shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val subject = if (context == "filter") compose.onNode(isToggleable()) else compose.onNodeWithTag("shadow-button")
        val body = subject.fetchSemanticsNode().boundsInRoot.translate(-origin)
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(body.center.x-12*d, body.bottom+4*d, body.center.x+12*d, body.bottom+8*d))
        val resting = probe.sample(scene.captureNativeFrame())
        val expectedFrames = mutableListOf(resting)
        var maximumResidual = 0f
        for (available in listOf(false, true)) {
            compose.runOnIdle { enabled = available }
            repeat(32) {
                compose.mainClock.advanceTimeByFrame()
                val image = scene.captureNativeFrame()
                val actual = probe.sample(image)
                val expected = 1f - (1f - resting) * compose.runOnIdle { expectedOpacity.value }
                expectedFrames += expected
                maximumResidual = maxOf(maximumResidual, kotlin.math.abs(actual - expected))
                ShadowFrameProbe.assertSpreadEdge(image,
                    Rect(body.center.x-8*d, body.bottom+30*d, body.center.x+8*d, body.bottom+32*d),
                    Rect(body.center.x-8*d, body.bottom+32*d, body.center.x+8*d, body.bottom+34*d), .015f, "button")
            }
        }
        // The easing has a deliberately fast first frame. Bound changes by that
        // actual opacity curve, then allow two 8-bit levels for native compositing.
        val allowedStep = expectedFrames.zipWithNext { a,b -> kotlin.math.abs(a-b) }.max() + 2f/255f
        probe.assertContinuous(allowedStep, "$context availability")
        assertTrue("$context shadow must follow its opacity curve: residual=$maximumResidual", maximumResidual <= 2f/255f)
        assertTrue("The probe must observe a nonzero shadow", probe.luminances.min() < .97f)
    }

    @Test fun seekCancelRetainsItsFullShadowAtRest() {
        val state = SeekCancelTargetState()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(240.dp).background(Color.White).testTag("shadow-scene")) {
                    SeekCancelTarget(state, false, 48.dp, true)
                }
            }
        } }
        // The target is shown only while its drag is active (#584); hold it at rest.
        compose.runOnIdle { state.update(true) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("cancel-seek").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("shadow-scene")
        val root = scene.fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot.translate(-root.topLeft)
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(body.center.x - 8*d, body.bottom + 2*d, body.center.x + 8*d, body.bottom + 6*d))
        val image = scene.captureNativeFrame()
        val luminance = probe.sample(image)
        assertTrue("The seek X must cast onto content outside its placement layer: $luminance", luminance < .97f)
        ShadowFrameProbe.assertSpreadEdge(image,
            Rect(body.center.x - 8*d, body.bottom + 30*d, body.center.x + 8*d, body.bottom + 32*d),
            Rect(body.center.x - 8*d, body.bottom + 32*d, body.center.x + 8*d, body.bottom + 34*d), .015f, "seek X")
    }
}
