package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.TriggerRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/** #748 R4/R5: one optical body owner across the popup return, from the actual shared withdrawal operation. */
class PopupRetractContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    private enum class Control { Withdraw, NoWithdraw, NoDependency }

    /** Composed-display pixel at the centre of [view]'s [tag] node: what the compositor actually shows. With [tick], only a
     * screenshot whose "tick" marker shows that dialog frame (green even, yellow odd) qualifies; a stale frame never does. */
    private fun displayed(tag: String, view: () -> View, tick: Int? = null, captureLabel: String? = null): Int {
        compose.waitForIdle()
        fun center(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val target = center(tag); val marker = if (tick == null) null else center("tick")
        val origin = IntArray(2)
        compose.runOnIdle { view().getLocationOnScreen(origin) }
        repeat(60) {
            val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val current = marker == null || screen.getPixel(origin[0] + marker.x.toInt(), origin[1] + marker.y.toInt()).let {
                    android.graphics.Color.green(it) > 200 && (android.graphics.Color.red(it) > 200) == (tick!! % 2 == 1) }
                if (current) {
                    if (captureLabel != null && InstrumentationRegistry.getArguments().getString("retainPopupCaptures") == "true") {
                        val dir = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "popup-retract-captures").apply { mkdirs() }
                        val file = java.io.File(dir, "$captureLabel-tick-$tick.png")
                        file.outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        android.util.Log.i("PopupRetractCapture", "label=$captureLabel tick=$tick path=$file")
                    }
                    return screen.getPixel(origin[0] + target.x.toInt(), origin[1] + target.y.toInt())
                }
            } finally { screen.recycle() }
            Thread.sleep(50)
        }
        fail("No composed frame showed dialog frame marker $tick")
        error("unreachable")
    }
    private fun Int.red() = android.graphics.Color.red(this) > 200 && android.graphics.Color.blue(this) < 60
    private fun Int.blue() = android.graphics.Color.blue(this) > 200 && android.graphics.Color.red(this) < 60

    /** R5 hard gate: retained consumer C records D then B ONCE; the source empties B and emits D -> empty B at its draw entry,
     * then records restored pixels into S. The dialog window presents again without C re-recording and shows no optics. */
    @Test fun retainedConsumerCannotSampleRestoredSourceAfterBodyWithdrawal() {
        var round by mutableIntStateOf(0)
        var control = Control.Withdraw
        var anchor = GlassAnchor()
        val events = Collections.synchronizedList(mutableListOf<String>())
        var flight = GlassPopupFlight(anchor) { (it as? GlassBodyReceipt)?.let { r -> events += "${r.event}:${r.request}" } }
        var restored by mutableStateOf(false)
        var tick by mutableIntStateOf(0)
        var epoch by mutableIntStateOf(0)
        var cRecords = 0
        lateinit var dialogView: View
        compose.setContent { key(round) {
            val s = rememberGraphicsLayer(); val b = rememberGraphicsLayer(); val d = rememberGraphicsLayer()
            val optical = remember(b, d) { io.github.anastaci1a.localica.ui.engine.OpticalBody(b, d) }
            SideEffect { flight.body = optical }
            Box(Modifier.size(120.dp).testTag("source").drawWithContent {
                val restoring = restored
                s.record {
                    // The actual shared operation at source draw entry; S then retains its D edge.
                    if (restoring) when (control) {
                        Control.Withdraw -> flight.withdrawBody(this, 1L)
                        Control.NoDependency -> b.record { }
                        Control.NoWithdraw -> Unit
                    }
                    drawRect(if (restoring) Color.Red else Color.Blue)
                }
                drawLayer(s)
            })
            Dialog({}, DialogProperties(usePlatformDefaultWidth = false)) {
                dialogView = LocalView.current
                Box(Modifier.size(200.dp)) {
                    // C is its own retained RenderNode, as the popup body node is; sibling frames never re-record it.
                    Box(Modifier.size(120.dp).testTag("consumer").graphicsLayer().drawWithContent {
                        epoch.let { cRecords++ }
                        flight.refillBody(this) { drawLayer(s) }
                        optical.draw(this)
                    })
                    // A sibling forces new dialog frames without re-recording C.
                    Canvas(Modifier.size(12.dp).align(Alignment.BottomEnd).testTag("tick")) { drawRect(if (tick % 2 == 0) Color.Green else Color.Yellow) }
                }
            }
        } }
        val outcomes = mutableListOf<String>()
        for (sourceFirst in listOf(true, false)) for (c in Control.entries) {
            compose.runOnIdle {
                control = c; anchor = GlassAnchor(); events.clear(); restored = false
                flight = GlassPopupFlight(anchor) { (it as? GlassBodyReceipt)?.let { r -> events += "${r.event}:${r.request}" } }
                round++
            }
            compose.waitForIdle()
            assertTrue("Before return, B lenses the pre-restoration source", displayed("consumer", { dialogView }, tick).blue())
            val recorded = cRecords
            if (sourceFirst) {
                compose.runOnIdle { restored = true }; compose.waitForIdle()
                compose.runOnIdle { tick++ }
            } else compose.runOnIdle { tick++; restored = true }
            compose.waitForIdle()
            compose.runOnIdle { tick++ }
            val shown = displayed("consumer", { dialogView }, tick, "sourceFirst-$sourceFirst-control-$c").red()
            outcomes += "sourceFirst=$sourceFirst control=$c restored=$shown cRecords=${cRecords - recorded}"
            assertEquals("C is never re-recorded after the source restores: $outcomes", recorded, cRecords)
            if (c != Control.Withdraw) continue
            assertFalse("Retained C must not show the restored source: $outcomes", shown)
            assertEquals("Withdrawal reserved the request once", listOf("withdraw:1"), events.toList())
            // Repeated reclaim/refill/withdraw while S retains its D edge: D empties before B refills (no B -> S -> D -> B cycle).
            repeat(3) { cycle ->
                compose.runOnIdle { anchor.restoredBodyReceipt = -1L; restored = false; epoch++ }
                compose.waitForIdle()
                compose.runOnIdle { tick++ }
                assertTrue("Cycle $cycle: the reclaimed B lenses the source again", displayed("consumer", { dialogView }, tick).blue())
                compose.runOnIdle { restored = true }; compose.waitForIdle()
                compose.runOnIdle { tick++ }
                assertFalse("Cycle $cycle: retained C shows no restored optic", displayed("consumer", { dialogView }, tick).red())
            }
            assertEquals(listOf("withdraw:1") + List(3) { listOf("unlink:0", "withdraw:1") }.flatten(), events.toList())
        }
        android.util.Log.i("PopupRetract", outcomes.joinToString("; "))
        // The omitted withdrawal must expose the retained optic, or this oracle proves nothing.
        assertTrue("The no-withdrawal control must discriminate: $outcomes", outcomes.filter { "NoWithdraw" in it }.all { "restored=true" in it })
    }

    /** R5 P2 + condition 4: the actual prepared adapter withdraws only at a qualified current draw; a stale frame draws nothing. */
    @Test fun preparedSourceWithdrawsBodyOnlyAtItsQualifiedCurrentDraw() {
        lateinit var preparation: GlassOriginPreparation
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var opened = 0
        // The placed layout sample differs from the current progress (0 while unbound) until the test moves it.
        var sample by mutableFloatStateOf(.5f)
        compose.setContent {
            preparation = rememberGlassOriginPreparation()
            Box(Modifier.size(80.dp).preparedOrigin(preparation) { sample }.testTag("prepared")) {
                Box(Modifier.fillMaxSize().drawBehind { if (preparation.withdrawal == null) opened++ else calls += "content"; drawRect(Color.Blue) })
            }
        }
        compose.waitForIdle()
        assertTrue("Opening has no withdrawal callback and draws normally", opened > 0)
        // Closing proof: the receipt bumps and the callback installs in one UI-thread step, before any relayout.
        compose.runOnUiThread { preparation.beginOriginProof(7L); preparation.withdrawal = { calls += "withdraw" } }
        compose.waitForIdle()
        assertTrue("A current receipt placed away from the current progress neither withdraws nor draws stale pixels: $calls", calls.isEmpty())
        // A new proof at the moved sample recomposes, relayouts and redraws the origin at the current progress.
        compose.runOnIdle { sample = 0f; preparation.beginOriginProof(8L) }
        compose.waitForIdle()
        assertTrue("The qualified current draw withdrew: $calls", calls.isNotEmpty())
        assertEquals("Every source draw is preceded by its qualified withdrawal; no stale source pixels: $calls",
            calls.size / 2, calls.windowed(2, 2).count { it == listOf("withdraw", "content") })
        // Receipts are checked independently of progress: at the current progress, a stale captured receipt neither
        // withdraws nor draws, while the same call with the current receipt does (so this check discriminates).
        val direct = mutableListOf<String>()
        compose.runOnUiThread {
            val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
            preparation.withdrawal = { direct += "withdraw" }
            preparation.drawSource(scope, 0f, preparation.originReceipt - 1) { direct += "stale content" }
            assertTrue("A stale captured receipt at the current progress neither withdraws nor draws: $direct", direct.isEmpty())
            preparation.drawSource(scope, 0f, preparation.originReceipt) { direct += "content" }
            assertEquals(listOf("withdraw", "content"), direct)
        }
        compose.runOnIdle { preparation.reset() }
        assertNull("Disposal/reset clears only this owner's callback", preparation.withdrawal)
    }

    private class Scene(val anchor: GlassAnchor, val events: MutableList<Any>)

    private fun pill(geometryOnly: Boolean = false, throwOnce: Boolean = false, drawTrigger: () -> Boolean = { true },
        lightMix: () -> Float = { .23f }): Pair<Scene, MutableState<Boolean>> {
        val open = mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        val events = Collections.synchronizedList(mutableListOf<Any>())
        var pending = throwOnce
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalGlassSurfaceDrawObserver provides {
            events += it
            // The posted acknowledgment as seen at the withdrawal itself: ownership moves before the post.
            if (it is GlassBodyReceipt && it.event == "withdraw") events += "presentedAtWithdraw:${anchor.restoredPresentedReceipt}"
        }) {
            Box(Modifier.fillMaxSize()) {
                anchor = rememberGlassAnchor()
                val mix = lightMix()
                val source = rememberFrostedBackdrop(Unit, dark = mix >= .5f, lightMix = mix)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    repeat(8) { x -> drawRect(if (x % 2 == 0) Color(0xFF8A5A3C) else Color(0xFF3C6A8A),
                        androidx.compose.ui.geometry.Offset(x * size.width / 8, 0f), androidx.compose.ui.geometry.Size(size.width / 8, size.height)) }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(24.dp).drawWithContent {
                        if (drawTrigger()) try { drawContent() } catch (_: UnsupportedOperationException) { }
                    }) {
                        val face = Modifier.size(104.dp, 56.dp).glassAnchor(anchor, source).clickable { open.value = true }.testTag("trigger")
                        val throwing = Modifier.fillMaxSize().drawBehind {
                            if (pending && !anchor.hidden && !open.value && anchor.restoredDrawRequest > 0) { pending = false; throw UnsupportedOperationException("draw aborted") }
                        }
                        if (geometryOnly) Box(face) { Box(throwing) }
                        else LiquidGlassSurface(face, source, cornerRadius = 28.dp, frostProgress = .2f, refraction = 1.3f,
                            tint = Color(0x2200FF88)) { Box(throwing) }
                    }
                    GlassMenu(open.value, anchor, { open.value = false }) { Text("Create playlist"); Text("Import playlist") }
                }
            }
        } } }
        compose.waitForIdle()
        return Scene(anchor, events) to open
    }

    /** Step the clock one frame at a time with real Android draws between frames; bulk virtual frames would expire the
     * six-frame allowance before the restored trigger ever draws (that is the fallback path, tested separately). */
    private fun frames(what: String, limit: Int = 900, until: () -> Boolean) {
        compose.mainClock.autoAdvance = false
        try {
            repeat(limit) { if (until()) return; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            fail("Timed out waiting for $what")
        } finally { compose.mainClock.autoAdvance = true }
    }
    private fun opened() = compose.onAllNodesWithText("Create playlist").fetchSemanticsNodes().isNotEmpty()
    private fun closed(scene: Scene) = compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() &&
        scene.anchor.popup == null && !scene.anchor.hidden

    private fun cycle(scene: Scene, open: MutableState<Boolean>) {
        compose.runOnIdle { open.value = true }
        frames("open rest") { opened() && scene.anchor.hidden && scene.anchor.contact.expansion.value == 1f }
        compose.runOnIdle { open.value = false }
        frames("return") { closed(scene) }
    }

    private fun Scene.body() = events.filterIsInstance<GlassBodyReceipt>().map { "${it.event}:${it.request}" }
    private fun Scene.trace() = events.filter { it is GlassBodyReceipt || it is GlassTransitionReceipt || it is String }

    /** R4 §1/R5: the ordinary source takes the body at its draw entry, before its posted acknowledgment, exactly once per request. */
    @Test fun pillReturnStopsFilteringOnSourceDrawBeforePost() {
        val (scene, open) = pill()
        cycle(scene, open)
        val request = scene.anchor.restoredDrawRequest
        assertEquals("One withdrawal for the return request; no rearm: ${scene.trace()}", listOf("withdraw:$request"), scene.body())
        assertTrue("The body moved before the posted acknowledgment: ${scene.events.filterIsInstance<String>()}",
            "presentedAtWithdraw:${request - 1}" in scene.events || scene.events.filterIsInstance<String>().single().removePrefix("presentedAtWithdraw:").toLong() < request)
        assertEquals(request, scene.anchor.restoredBodyReceipt)
        assertEquals(request, scene.anchor.restoredPresentedReceipt)
        cycle(scene, open)
        // A new presentation allocates fresh B/D (the old pair was released with the closed flight), so no unlink is due;
        // the same-pair reclaim (D emptied before B refills) is the retained-consumer method's cycle.
        assertEquals("The next return withdraws its own request once: ${scene.trace()}",
            listOf("withdraw:$request", "withdraw:${request + 1}"), scene.body())
    }

    /** R4 §3/§6: the source record is the morph's home endpoint, and a geometry-only anchor publishes none. */
    @Test fun roundReturnStopsFilteringOnSourceDrawBeforePost() {
        val (scene, open) = pill()
        val record = compose.runOnIdle { requireNotNull(scene.anchor.material).value }
        assertEquals(.2f, record.frost, 0f); assertEquals(1.3f, record.refraction, 0f); assertEquals(.23f, record.lightMix, 1e-6f)
        assertSame("Home weight zero returns the actual drawn record", record,
            record.towards(GlassSurfacePresentation(1f, 1f, 1f, Color.Transparent, record.depthScale), 0f))
        cycle(scene, open)
        val request = scene.anchor.restoredDrawRequest
        assertEquals("${scene.trace()}", listOf("withdraw:$request"), scene.body())
        assertTrue("The body moved before the posted acknowledgment: ${scene.trace()}", "presentedAtWithdraw:${request - 1}" in scene.events)
    }

    @Test fun geometryOnlyAndContextSourcesNeedNoGlassPublication() {
        val (scene, open) = pill(geometryOnly = true)
        assertNull("A raw Box anchor is geometry-only", compose.runOnIdle { scene.anchor.material })
        cycle(scene, open)
        assertFalse(scene.anchor.hidden)
        assertTrue("${scene.trace()}", scene.body().singleOrNull()?.startsWith("withdraw:") == true)
    }

    /** Condition 2: a source that never reaches draw entry cannot hold the body: the existing allowance ends the request. */
    @Test fun neverDrawnSourceEndsAtTheExistingAllowance() {
        var draw by mutableStateOf(true)
        val (scene, open) = pill(drawTrigger = { draw })
        compose.runOnIdle { open.value = true }
        frames("open rest") { opened() && scene.anchor.hidden && scene.anchor.contact.expansion.value == 1f }
        compose.runOnIdle { draw = false; open.value = false }
        frames("fallback retirement") { closed(scene) }
        assertTrue("No withdrawal without a source draw: ${scene.trace()}", scene.body().isEmpty())
        assertNotEquals("No presented receipt is fabricated", scene.anchor.restoredDrawRequest, scene.anchor.restoredPresentedReceipt)
        compose.runOnIdle { draw = true }
        cycle(scene, open)
        assertTrue("${scene.trace()}", scene.body().lastOrNull()?.startsWith("withdraw:") == true)
    }

    /** Condition 3: a source draw that throws returns its reservation; the next draw withdraws again and the return completes. */
    @Test fun abortedSourceDrawRearmsTheSameRequest() {
        val (scene, open) = pill(geometryOnly = true, throwOnce = true)
        cycle(scene, open)
        val request = scene.anchor.restoredDrawRequest
        assertEquals("withdraw, rearm, withdraw for one request: ${scene.trace()}",
            listOf("withdraw:$request", "rearm:$request", "withdraw:$request"), scene.body())
        assertEquals(request, scene.anchor.restoredBodyReceipt)
    }

    /** R4 §2: a source easing its material after withdrawal never re-acquires the body or re-requests it. */
    @Test fun continuousAppearanceEaseDoesNotReacquireTheBody() {
        val mix = mutableFloatStateOf(.1f)
        val (scene, open) = pill(lightMix = { mix.floatValue })
        compose.runOnIdle { open.value = true }
        frames("open rest") { opened() && scene.anchor.hidden && scene.anchor.contact.expansion.value == 1f }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open.value = false }
            repeat(45) { frame -> compose.runOnIdle { mix.floatValue = .1f + .8f * (frame + 1) / 45f }; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        } finally { compose.mainClock.autoAdvance = true }
        frames("return") { closed(scene) }
        assertEquals("Monotonic owner while the source eases: ${scene.trace()}", 1, scene.body().count { it.startsWith("withdraw") })
        assertTrue(scene.body().none { it.startsWith("rearm") })
        assertEquals(.9f, compose.runOnIdle { requireNotNull(scene.anchor.material).value.lightMix }, 1e-5f)
    }

    /** R4 §4: one appearance owner at open rest; an explicit host intent wins over a fractional source. */
    @Test fun openAppearanceOwnsMaterialInkAndNestedMetadata() {
        val open = mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var panelInk = Color.Unspecified
        var nested: FrostedBackdrop? = null
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            anchor = rememberGlassAnchor()
            val source = rememberFrostedBackdrop(Unit, dark = false, lightMix = .23f)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFFB4D8C8)) }
            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).padding(24.dp).size(CompactControlSize).glassAnchor(anchor)) { }
                GlassMorphPopup(open.value, anchor, { open.value = false }, GlassPlacement.Center, darkOverride = true) {
                    val ink = androidx.compose.material3.LocalContentColor.current
                    val backdrop = LocalFrostedBackdrop.current
                    SideEffect { panelInk = ink; nested = backdrop }
                    Text("Explicit dark")
                }
            }
        } } }
        compose.runOnIdle { open.value = true }
        frames("open rest") { compose.onAllNodesWithText("Explicit dark").fetchSemanticsNodes().isNotEmpty() && anchor.contact.expansion.value == 1f }
        assertEquals(1f, requireNotNull(nested).lightMix, 0f)
        assertTrue("Explicit dark host gives light ink", panelInk.red > .9f)
        assertEquals("The source keeps its own fractional record", .23f, requireNotNull(anchor.material).value.lightMix, 1e-6f)
        compose.runOnIdle { open.value = false }
        frames("return") { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() && anchor.popup == null }
    }
}
