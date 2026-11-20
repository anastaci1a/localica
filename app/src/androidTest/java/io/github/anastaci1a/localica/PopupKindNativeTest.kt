package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.SharedContentKind
import io.github.anastaci1a.localica.ui.components.TriggerRole
import io.github.anastaci1a.localica.ui.components.rememberPopupSharedContent
import io.github.anastaci1a.localica.ui.engine.LocalPopupKind
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.PopupMotionVariant
import io.github.anastaci1a.localica.ui.spec.StockPopupMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #854 review F1/F7, native proof set: a real popup runs the kind derived from its placement and trigger role (read where it
 * applies, in the popup's own content), a popup first composed visible before its trigger is measured keeps one kind and owner
 * for that presentation, and shared content runs a centred popup's kind. */
class PopupKindNativeTest {
    @get:Rule val compose = createComposeRule()
    private val measured = Rect(40f, 1600f, 140f, 1664f)

    private class Scene { var open by mutableStateOf(false); val seen = mutableListOf<PopupKind>() }

    @Composable private fun MenuScene(anchor: GlassAnchor, scene: Scene) {
        Box(Modifier.fillMaxSize()) {
            GlassMenu(scene.open, anchor, { scene.open = false }) {
                val kind = LocalPopupKind.current
                SideEffect { scene.seen += kind }
                Text("Kind", Modifier.testTag("kind-content"))
            }
        }
    }

    private fun menu(anchor: GlassAnchor, scene: Scene) {
        compose.setContent { LocalicaTheme { MenuScene(anchor, scene) } }
    }

    private fun present(scene: Scene): List<PopupKind> {
        scene.seen.clear(); compose.runOnIdle { scene.open = true }
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        return compose.runOnIdle { scene.seen.toList() }
    }

    private fun close(scene: Scene) {
        compose.runOnIdle { scene.open = false }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("kind-content").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    @Test fun eachTriggerRoleOpensItsDerivedKind() {
        // The rule allows one setContent per test: each role gets a fresh anchor and scene, keyed into the same content.
        var current by mutableStateOf<Pair<GlassAnchor, Scene>?>(null)
        compose.setContent { LocalicaTheme { current?.let { (anchor, scene) -> key(anchor) { MenuScene(anchor, scene) } } } }
        for ((role, contextual, expected) in listOf(Triple(TriggerRole.Surface, false, PopupKind.Menu), Triple(TriggerRole.Navigation, false, PopupKind.BottomMenu),
                Triple(TriggerRole.PlainIcon, false, PopupKind.Menu), Triple(TriggerRole.Surface, true, PopupKind.RowMenu), Triple(TriggerRole.Navigation, true, PopupKind.RowMenu))) {
            val anchor = GlassAnchor().apply { bounds = measured; bindRole(role); this.contextual = contextual }
            val scene = Scene()
            compose.runOnIdle { current = anchor to scene }
            val kinds = present(scene)
            assertTrue("$role contextual=$contextual: $kinds", kinds.isNotEmpty() && kinds.all { it == expected })
            if (expected == PopupKind.BottomMenu) assertEquals(PopupMotionVariant.CrossAxisSnap, StockPopupMotion.variants[expected])
            close(scene)
        }
    }

    @Test fun aMenuFirstComposedVisibleKeepsItsKindUntilClosedRestThenReopensAsItsTrigger() {
        val anchor = GlassAnchor()
        val scene = Scene().apply { open = true }
        menu(anchor, scene)
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        // The trigger is measured and its navigation role binds while the first presentation is in flight.
        compose.runOnIdle { anchor.bounds = measured; anchor.bindRole(TriggerRole.Navigation) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithTag("kind-content").assertIsDisplayed()
        val first = compose.runOnIdle { scene.seen.toList() }
        assertTrue("one kind per presentation: $first", first.isNotEmpty() && first.all { it == PopupKind.Dialog })
        close(scene)
        val reopened = present(scene)
        assertTrue("the settled kind applies from the next presentation: $reopened", reopened.isNotEmpty() && reopened.all { it == PopupKind.BottomMenu })
        close(scene)
    }

    @Test fun nativeSharedChildShadowsStopAtThePopupBoundary() {
        val scene = Scene()
        var shadow by mutableStateOf(false)
        var replay by mutableStateOf(true)
        lateinit var presentedAnchor: GlassAnchor
        lateinit var window: android.view.Window
        compose.setContent { EngineTheme(reduced = true) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                val anchor = rememberGlassAnchor()
                SideEffect { presentedAnchor = anchor }
                val caster: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.size(80.dp, 76.dp), contentAlignment = Alignment.Center) {
                        LiquidGlassSurface(Modifier.size(40.dp), source = null, shadow = shadow,
                            pressFeedback = false) { Box(Modifier.fillMaxSize().background(Color.Red)) }
                    }
                }
                val shared = rememberPopupSharedContent(anchor) { caster(Modifier) }
                LiquidGlassSurface(Modifier.align(Alignment.Center).size(80.dp, 76.dp)
                    .glassAnchor(anchor, face = { shared.CompactCopy() }), source = null, shadow = false) {
                    CompositionLocalProvider(LocalContentColor provides Color.Black) { shared.Compact() }
                }
                GlassMorphPopup(scene.open, anchor, { scene.open = false }, GlassPlacement.Center,
                    sharedContent = shared.takeIf { replay }) {
                    val view = LocalView.current
                    SideEffect { window = (view.parent as DialogWindowProvider).window }
                    Box(Modifier.fillMaxWidth().height(144.dp).testTag("kind-content")) {
                        // The slot crosses the panel edge, while the caster's body is inside.
                        CompositionLocalProvider(LocalContentColor provides Color.Black) {
                            val slot = Modifier.align(Alignment.BottomCenter).offset(y = 12.dp)
                            if (replay) shared.Expanded(slot) else caster(slot)
                        }
                    }
                }
            }
        } }
        val output = nativeEvidenceDirectory("popup-child-shadow-clip",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val frames = PopupWindowEvidence(compose)
        fun captureChild(name: String, castsShadow: Boolean, shared: Boolean = true): Pair<Rect, android.graphics.Bitmap> {
            compose.runOnIdle { shadow = castsShadow; replay = shared }
            present(scene)
            compose.mainClock.advanceTimeBy(1200)
            val surface = compose.onNodeWithTag("glass-morph-surface")
            val coordinates = surface.fetchSemanticsNode().layoutInfo.coordinates
            val bounds = compose.runOnUiThread { coordinates.boundsInWindow() }
            val pixels = frames.capture(window, output, name)
            val ownership = compose.runOnIdle {
                "anchor=${System.identityHashCode(presentedAnchor)} win=${System.identityHashCode(window)} " +
                    "takeover=${presentedAnchor.takeover} gen=${presentedAnchor.popup?.gen} bounds=$bounds"
            }
            java.io.File(output, "$name-ownership.txt").writeText("$ownership\n")
            close(scene)
            return bounds to pixels
        }
        fun paintedCaster(image: android.graphics.Bitmap, bounds: Rect): IntRect {
            var left = image.width
            var top = image.height
            var right = -1
            var bottom = -1
            val start = bounds.left.toInt()
            val end = bounds.right.toInt()
            val row = IntArray(end - start)
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                image.getPixels(row, 0, row.size, start, y, row.size, 1)
                for ((i, rgb) in row.withIndex()) {
                    if ((rgb ushr 16 and 255) <= 191 || (rgb ushr 8 and 255) >= 64) continue
                    val x = start + i
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            check(left <= right && top <= bottom) { "The native popup must show the measured shared caster body" }
            return IntRect(left, top, right + 1, bottom + 1)
        }
        try {
            val (bounds, off) = captureChild("child-shadow-off", false)
            try {
                // Record a fresh shared slot with its caster already enabled.
                val (onBounds, on) = captureChild("child-shadow-on", true)
                try {
                    assertEquals(bounds, onBounds)
                    val body = paintedCaster(on, bounds)
                    assertEquals("Both native captures must show the same replayed caster geometry", body, paintedCaster(off, bounds))
                    val density = compose.density.density
                    val x = body.center.x
                    val y = bounds.bottom.toInt()
                    val halfWidth = (8 * density).toInt()
                    val inside = (y - (5 * density).toInt()) until (y - (2 * density).toInt())
                    val outside = (y + (2 * density).toInt()) until (y + (12 * density).toInt())
                    assertTrue("Both witness bands must be inside the native window",
                        inside.first >= 0 && outside.last < on.height && x - halfWidth >= 0 && x + halfWidth < on.width)
                    assertTrue("Witness bands must be outside the painted caster and inside its original shifted support: body=$body",
                        body.bottom <= inside.first && outside.last - body.bottom < 56 * density)
                    fun changed(rows: IntRange): Int = rows.sumOf { row ->
                        (x - halfWidth..x + halfWidth).count { column -> off.getPixel(column, row) != on.getPixel(column, row) }
                    }
                    val insideChanged = changed(inside)
                    val outsideChanged = changed(outside)
                    val (referenceBounds, reference) = captureChild("child-shadow-reference", true, shared = false)
                    val insideError = try {
                        assertEquals(bounds, referenceBounds)
                        val referenceBody = paintedCaster(reference, bounds)
                        assertEquals("The reference must align with the sampled rows",
                            body.top to body.bottom, referenceBody.top to referenceBody.bottom)
                        assertEquals("The reference must align with the sampled center column", x, referenceBody.center.x)
                        inside.maxOf { row ->
                            (x - halfWidth..x + halfWidth).maxOf { column ->
                                val actual = on.getPixel(column, row)
                                val expected = reference.getPixel(column, row)
                                (0..24 step 8).maxOf { shift ->
                                    kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255))
                                }
                            }
                        }
                    } finally { reference.recycle() }
                    java.io.File(output, "clip-measurements.txt").writeText(
                        "bounds=$bounds body=$body density=$density insideChanges=$insideChanged " +
                            "insideMaxCodeError=$insideError outsideChanges=$outsideChanged\n")
                    assertTrue("The shared child must cast inside the popup", insideChanged > 0)
                    assertTrue("The interior tail must match the ordinary caster within one code: $insideError", insideError <= 1)
                    assertEquals("Child shadow must add no pixels outside the popup", 0, outsideChanged)
                } finally { on.recycle() }
            } finally { off.recycle() }
        } finally {
            frames.dispose()
            if (scene.open) close(scene)
        }
    }
    @Test fun nativeActionStripKeepsBothEndpointTailsInsideThePopup() {
        val anchor = GlassAnchor().apply { bounds = measured }
        val scene = Scene()
        val buttons = arrayOfNulls<LayoutCoordinates>(2)
        lateinit var panel: LayoutCoordinates
        lateinit var window: android.view.Window
        var drawnIndex by mutableIntStateOf(-1)
        var reference by mutableStateOf<IntOffset?>(null)
        compose.setContent { EngineTheme(reduced = true) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassMorphPopup(scene.open, anchor, { scene.open = false }, GlassPlacement.Center) {
                    val view = LocalView.current
                    SideEffect { window = (view.parent as DialogWindowProvider).window }
                    Box(Modifier.fillMaxWidth().height(160.dp).onGloballyPositioned { panel = it }
                        .testTag("kind-content")) {
                        // The reference shares the panel and caster, bypassing only the strip's clip and replay.
                        reference?.let { origin ->
                            Box(Modifier.glassShadowOffset { origin }.size(88.dp, 40.dp).clearGlassShadow(1f, 1f, null))
                        }
                        GlassActionRow(Modifier.width(144.dp).align(Alignment.Center)) {
                            repeat(buttons.size) { index ->
                                Box(Modifier.size(88.dp, 40.dp).onGloballyPositioned { buttons[index] = it }
                                    .clearGlassShadow(if (drawnIndex == index) 1f else 0f, 1f, null)
                                    .background(Color.White, CircleShape))
                            }
                        }
                    }
                }
            }
        } }
        val output = nativeEvidenceDirectory("popup-action-strip-tails",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val frames = PopupWindowEvidence(compose)
        val measurements = java.io.File(output, "endpoint-measurements.txt")
        fun openEndpoint(index: Int, caster: Int = -1, bypass: IntOffset? = null) {
            compose.runOnIdle { drawnIndex = caster; reference = bypass }
            present(scene)
            if (index != 0) {
                compose.onNodeWithContentDescription("More actions").assertIsEnabled().performClick()
                compose.onNodeWithContentDescription("More actions").assertIsNotEnabled()
            }
        }
        fun endpoint(index: Int): Pair<IntOffset, Offset> = compose.runOnUiThread {
            val button = checkNotNull(buttons[index])
            val local = panel.localPositionOf(button, Offset.Zero)
            val side = if (index == 0) 0f else button.size.width.toFloat()
            val point = button.localToWindow(Offset(side, button.size.height / 2f))
            IntOffset(kotlin.math.round(local.x).toInt(), kotlin.math.round(local.y).toInt()) to point
        }
        try {
            for (index in buttons.indices) {
                openEndpoint(index)
                val (origin, edge) = endpoint(index)
                val bounds = compose.runOnUiThread { panel.boundsInWindow() }
                val density = compose.density.density
                val sign = if (index == 0) -1 else 1
                val columns = ((25 * density).toInt()..(31 * density).toInt()).map { edge.x.toInt() + sign * it }
                val rows = (edge.y + 5 * density).toInt()..(edge.y + 11 * density).toInt()
                assertTrue("Endpoint $index must leave room for the in-panel tail: edge=$edge panel=$bounds",
                    columns.all { it > bounds.left && it < bounds.right } && rows.first > bounds.top && rows.last < bounds.bottom)
                fun pixels(name: String): List<Int> {
                    assertEquals(origin to edge, endpoint(index))
                    assertEquals(bounds, compose.runOnUiThread { panel.boundsInWindow() })
                    val image = frames.capture(window, output, "endpoint-$index-$name")
                    val samples = try { rows.flatMap { row -> columns.map { image.getPixel(it, row) } } }
                    finally { image.recycle() }
                    close(scene)
                    return samples
                }
                val off = pixels("off")
                openEndpoint(index, caster = index)
                val actual = pixels("actual")
                openEndpoint(index, bypass = origin)
                val uncut = pixels("reference")
                val maximum = actual.indices.maxOf { pixel ->
                    (0..24 step 8).maxOf { shift -> kotlin.math.abs(
                        (actual[pixel] ushr shift and 255) - (uncut[pixel] ushr shift and 255)) }
                }
                val changed = actual.indices.count { actual[it] != off[it] }
                val referenceDrop = uncut.indices.maxOf { pixel ->
                    val before = off[pixel]
                    val after = uncut[pixel]
                    maxOf((after ushr 24) - (before ushr 24), (0..16 step 8).maxOf { shift ->
                        (before ushr shift and 255) - (after ushr shift and 255)
                    })
                }
                measurements.appendText("index=$index edge=$edge window=$bounds changed=$changed referenceDrop=$referenceDrop parity=$maximum\n")
                assertTrue("Endpoint $index has observable reference shadow beyond the layout gutter", referenceDrop > 1)
                assertTrue("Endpoint $index draws its own in-panel tail", changed > 0)
                assertTrue("Endpoint $index agrees with the unclipped field within one code, actual=$maximum", maximum <= 1)
            }
        } finally {
            frames.dispose()
            if (scene.open) close(scene)
        }
    }

    @Test fun sharedContentRunsTheCentredPopupsKind() {
        val anchor = GlassAnchor().apply { bounds = measured }
        val scene = Scene()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                // As the queue selector: the shared title is composed in its trigger and in the dialog.
                val shared = rememberPopupSharedContent(anchor) { Text("Title") }
                shared.Compact()
                GlassMorphPopup(scene.open, anchor, { scene.open = false }, GlassPlacement.Center, sharedContent = shared) {
                    val kind = LocalPopupKind.current
                    SideEffect { scene.seen += kind }
                    shared.Expanded()
                    Text("Kind", Modifier.testTag("kind-content"))
                }
            }
        } }
        val kinds = present(scene)
        assertTrue("$kinds", kinds.isNotEmpty() && kinds.all { it == SharedContentKind })
        close(scene)
    }
}
