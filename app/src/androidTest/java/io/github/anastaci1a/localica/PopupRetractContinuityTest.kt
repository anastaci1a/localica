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

    /** Composed-display pixel at the centre of [view]'s [tag] node: what the compositor actually shows. */
    private fun displayed(tag: String, view: () -> View): Int {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        compose.runOnIdle { view().getLocationOnScreen(origin) }
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try { screen.getPixel(origin[0] + bounds.center.x.toInt(), origin[1] + bounds.center.y.toInt()) } finally { screen.recycle() }
    }
    private fun Int.red() = android.graphics.Color.red(this) > 200 && android.graphics.Color.blue(this) < 60
    private fun Int.blue() = android.graphics.Color.blue(this) > 200 && android.graphics.Color.red(this) < 60

    /** R5 hard gate: retained consumer C records D then B ONCE; the source empties B and emits D -> empty B at its draw entry,
     * then records restored pixels into S. The dialog window presents again without C re-recording and shows no optics. */
    @Test fun retainedConsumerCannotSampleRestoredSourceAfterBodyWithdrawal() {
        val outcomes = mutableListOf<String>()
        for (sourceFirst in listOf(true, false)) for (control in Control.entries) {
            val shown = retained(sourceFirst, control)
            outcomes += "sourceFirst=$sourceFirst control=$control restored=${shown.first} cRecords=${shown.second}"
            assertEquals("C is never re-recorded after the source restores: $outcomes", 0, shown.second)
            if (control == Control.Withdraw) assertFalse("Retained C must not show the restored source: $outcomes", shown.first)
        }
        android.util.Log.i("PopupRetract", outcomes.joinToString("; "))
        // Controls are reported for discrimination; the omitted withdrawal must expose the retained optic.
        assertTrue("The no-withdrawal control must discriminate: $outcomes", outcomes.filter { "NoWithdraw" in it }.all { "restored=true" in it })
    }

    private fun retained(sourceFirst: Boolean, control: Control): Pair<Boolean, Int> {
        val anchor = GlassAnchor()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val flight = GlassPopupFlight(anchor) { (it as? GlassBodyReceipt)?.let { r -> events += "${r.event}:${r.request}" } }
        var restored by mutableStateOf(false)
        var tick by mutableIntStateOf(0)
        var cRecords = 0
        var epoch by mutableIntStateOf(0)
        lateinit var dialogView: View
        compose.setContent {
            val s = rememberGraphicsLayer(); val b = rememberGraphicsLayer(); val d = rememberGraphicsLayer()
            SideEffect { flight.body = b; flight.dependency = d }
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
                    Box(Modifier.size(120.dp).testTag("consumer").drawWithContent {
                        epoch.let { cRecords++ }
                        flight.refillBody(this) { drawLayer(s) }
                        flight.drawBody(this)
                    })
                    // A sibling forces new dialog frames without re-recording C.
                    Canvas(Modifier.size(2.dp).align(Alignment.BottomEnd)) { drawRect(if (tick % 2 == 0) Color.Green else Color.Yellow) }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("Before return, B lenses the pre-restoration source", displayed("consumer") { dialogView }.blue())
        val recorded = cRecords
        if (sourceFirst) {
            compose.runOnIdle { restored = true }; compose.waitForIdle()
            compose.runOnIdle { tick++ }
        } else compose.runOnIdle { tick++; restored = true }
        compose.waitForIdle()
        compose.runOnIdle { tick++ }
        val shown = displayed("consumer") { dialogView }.red()
        val records = cRecords - recorded
        if (control == Control.Withdraw) {
            assertEquals("Withdrawal reserved the request once", listOf("withdraw:1"), events.toList())
            // Repeated reclaim/refill/withdraw while S retains its D edge: D empties before B refills (no B -> S -> D -> B cycle).
            repeat(3) { cycle ->
                compose.runOnIdle { anchor.restoredBodyReceipt = -1L; restored = false; epoch++ }
                compose.waitForIdle()
                assertTrue("Cycle $cycle: the reclaimed B lenses the source again", displayed("consumer") { dialogView }.blue())
                compose.runOnIdle { restored = true }; compose.waitForIdle()
                compose.runOnIdle { tick++ }
                assertFalse("Cycle $cycle: retained C shows no restored optic", displayed("consumer") { dialogView }.red())
            }
            assertEquals(listOf("withdraw:1") + List(3) { listOf("unlink:0", "withdraw:1") }.flatten(), events.toList())
        }
        compose.setContent { }
        compose.waitForIdle()
        android.util.Log.i("PopupRetract", "sourceFirst=$sourceFirst control=$control events=$events")
        return shown to records
    }

    /** R5 P2 + condition 4: the actual prepared adapter withdraws only at a qualified current draw; a stale frame draws nothing. */
    @Test fun preparedSourceWithdrawsBodyOnlyAtItsQualifiedCurrentDraw() {
        lateinit var preparation: GlassOriginPreparation
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var opened = 0
        compose.setContent {
            preparation = rememberGlassOriginPreparation()
            Box(Modifier.size(80.dp).preparedOrigin(preparation) { 0f }.testTag("prepared")) {
                Box(Modifier.fillMaxSize().drawBehind { if (preparation.withdrawal == null) opened++ else calls += "content"; drawRect(Color.Blue) })
            }
        }
        compose.waitForIdle()
        assertTrue("Opening has no withdrawal callback and draws normally", opened > 0)
        compose.runOnUiThread {
            // Closing proof: the receipt bumps and the callback installs in one UI-thread step, before any relayout.
            preparation.beginOriginProof(7L)
            preparation.withdrawal = { calls += "withdraw" }
            assertFalse("A stale captured receipt never qualifies", preparation.qualified(0f, preparation.originReceipt - 1))
        }
        compose.waitForIdle()
        assertTrue("The qualified current draw withdrew: $calls", calls.isNotEmpty())
        assertEquals("Every source draw is preceded by its qualified withdrawal; no stale source pixels: $calls",
            calls.size / 2, calls.windowed(2, 2).count { it == listOf("withdraw", "content") })
        assertTrue("A mismatched progress never qualifies", compose.runOnIdle { !preparation.qualified(.5f, preparation.originReceipt) })
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

    private fun cycle(scene: Scene, open: MutableState<Boolean>) {
        compose.runOnIdle { open.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create playlist").fetchSemanticsNodes().isNotEmpty() && scene.anchor.hidden }
        compose.waitForIdle()
        compose.runOnIdle { open.value = false }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() && !scene.anchor.hidden }
    }

    private fun Scene.body() = events.filterIsInstance<GlassBodyReceipt>().map { "${it.event}:${it.request}" }

    /** R4 §1/R5: the ordinary source takes the body at its draw entry, before its posted acknowledgment, exactly once per request. */
    @Test fun pillReturnStopsFilteringOnSourceDrawBeforePost() {
        val (scene, open) = pill()
        cycle(scene, open)
        val request = scene.anchor.restoredDrawRequest
        assertEquals("One withdrawal for the return request; no rearm", listOf("withdraw:$request"), scene.body())
        assertTrue("The body moved before the posted acknowledgment: ${scene.events.filterIsInstance<String>()}",
            "presentedAtWithdraw:${request - 1}" in scene.events || scene.events.filterIsInstance<String>().single().removePrefix("presentedAtWithdraw:").toLong() < request)
        assertEquals(request, scene.anchor.restoredBodyReceipt)
        assertEquals(request, scene.anchor.restoredPresentedReceipt)
        cycle(scene, open)
        assertEquals("A reopen empties D before B refills; the next return withdraws again",
            listOf("withdraw:$request", "unlink:$request", "withdraw:${request + 1}"), scene.body())
    }

    /** R4 §3/§6: the source record is the morph's home endpoint, and a geometry-only anchor publishes none. */
    @Test fun roundReturnStopsFilteringOnSourceDrawBeforePost() {
        val (scene, open) = pill()
        val record = compose.runOnIdle { requireNotNull(scene.anchor.material).value }
        assertEquals(.2f, record.frost, 0f); assertEquals(1.3f, record.refraction, 0f); assertEquals(.23f, record.lightMix, 1e-6f)
        assertSame("Home weight zero returns the actual drawn record", record,
            record.towards(GlassSurfacePresentation(1f, 1f, 1f, Color.Transparent, record.depthScale), 0f))
        cycle(scene, open)
        assertTrue(scene.body().single().startsWith("withdraw:"))
    }

    @Test fun geometryOnlyAndContextSourcesNeedNoGlassPublication() {
        val (scene, open) = pill(geometryOnly = true)
        assertNull("A raw Box anchor is geometry-only", compose.runOnIdle { scene.anchor.material })
        cycle(scene, open)
        assertFalse(scene.anchor.hidden)
        assertTrue(scene.body().single().startsWith("withdraw:"))
    }

    /** Condition 2: a source that never reaches draw entry cannot hold the body: the existing allowance ends the request. */
    @Test fun neverDrawnSourceEndsAtTheExistingAllowance() {
        var draw by mutableStateOf(true)
        val (scene, open) = pill(drawTrigger = { draw })
        compose.runOnIdle { open.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.runOnIdle { draw = false; open.value = false }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
        assertTrue("No withdrawal without a source draw: ${scene.body()}", scene.body().isEmpty())
        assertNotEquals("No presented receipt is fabricated", scene.anchor.restoredDrawRequest, scene.anchor.restoredPresentedReceipt)
        compose.runOnIdle { draw = true }
        cycle(scene, open)
        assertTrue(scene.body().last().startsWith("withdraw:"))
    }

    /** Condition 3: a source draw that throws returns its reservation; the next draw withdraws again and the return completes. */
    @Test fun abortedSourceDrawRearmsTheSameRequest() {
        val (scene, open) = pill(geometryOnly = true, throwOnce = true)
        cycle(scene, open)
        val request = scene.anchor.restoredDrawRequest
        assertEquals("withdraw, rearm, withdraw for one request: ${scene.body()}",
            listOf("withdraw:$request", "rearm:$request", "withdraw:$request"), scene.body())
        assertEquals(request, scene.anchor.restoredBodyReceipt)
    }

    /** R4 §2: a source easing its material after withdrawal never re-acquires the body or re-requests it. */
    @Test fun continuousAppearanceEaseDoesNotReacquireTheBody() {
        val mix = mutableFloatStateOf(.1f)
        val (scene, open) = pill(lightMix = { mix.floatValue })
        compose.runOnIdle { open.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create playlist").fetchSemanticsNodes().isNotEmpty() && scene.anchor.hidden }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open.value = false }
            repeat(45) { frame -> compose.runOnIdle { mix.floatValue = .1f + .8f * (frame + 1) / 45f }; compose.mainClock.advanceTimeByFrame() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() && !scene.anchor.hidden }
        assertEquals("Monotonic owner while the source eases: ${scene.body()}", 1, scene.body().count { it.startsWith("withdraw") })
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Explicit dark").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(1f, requireNotNull(nested).lightMix, 0f)
        assertTrue("Explicit dark host gives light ink", panelInk.red > .9f)
        assertEquals("The source keeps its own fractional record", .23f, requireNotNull(anchor.material).value.lightMix, 1e-6f)
        compose.runOnIdle { open.value = false }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() && !anchor.hidden }
    }
}
