package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real native windows with origins retained by the production pager and browse draw gates. */
class PopupRetainedTriggerRetirementTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun adjacentTabRetiresInNormalMotion() = retirement(adjacentTab = true)
    @Test fun adjacentTabRetiresInFasterMotion() = retirement(adjacentTab = true, speed = 2f)
    @Test fun adjacentTabRetiresInReducedMotion() = retirement(adjacentTab = true, reduced = true)
    @Test fun playerOcclusionRetiresInNormalMotion() = retirement(adjacentTab = false)
    @Test fun playerOcclusionRetiresInFasterMotion() = retirement(adjacentTab = false, speed = 2f)
    @Test fun playerOcclusionRetiresInReducedMotion() = retirement(adjacentTab = false, reduced = true)
    @Test fun drawableOriginKeepsItsNativeReceiptInNormalMotion() = retirement(adjacentTab = false, hide = false)
    @Test fun drawableOriginKeepsItsNativeReceiptInReducedMotion() = retirement(adjacentTab = false, reduced = true, hide = false)

    @Test fun adjacentTabReopenCancelsOldDeadlineInNormalMotion() = retirement(adjacentTab = true, renew = true)
    @Test fun adjacentTabReopenCancelsOldDeadlineInFasterMotion() = retirement(adjacentTab = true, speed = 2f, renew = true)
    @Test fun adjacentTabReopenCancelsOldDeadlineInReducedMotion() = retirement(adjacentTab = true, reduced = true, renew = true)
    @Test fun playerOcclusionReopenCancelsOldDeadlineInNormalMotion() = retirement(adjacentTab = false, renew = true)
    @Test fun playerOcclusionReopenCancelsOldDeadlineInFasterMotion() = retirement(adjacentTab = false, speed = 2f, renew = true)
    @Test fun playerOcclusionReopenCancelsOldDeadlineInReducedMotion() = retirement(adjacentTab = false, reduced = true, renew = true)

    private data class Policy(val engine: MotionAccess, val spec: MotionAccess, val clock: MotionAccess?,
        val clockSpec: MotionAccess, val localReduced: Boolean, val localSpeed: Float,
        val density: Float, val fontScale: Float, val clockId: Int)

    @Composable private fun ObservePolicy(receive: (() -> Policy) -> Unit) {
        val engine = LocalEngineContext.current
        val spec = LocalUiSpec.current.spec
        val clock = ownerClock()
        val localReduced = LocalReducedMotion.current
        val localSpeed = LocalMotionSpeed.current
        val density = LocalDensity.current
        SideEffect { receive { Policy(engine.access, spec.access, clock.context?.access, clock.spec.access,
            localReduced, localSpeed, density.density, density.fontScale, System.identityHashCode(clock)) } }
    }

    // Read-only reflection identifies actual Engine receipts on these pinned DEBUG proof sources.
    private fun expansionOwner(anchor: GlassAnchor): ExpandCollapse? {
        val adapter = anchor.contact.expansion
        val phase = adapter.javaClass.getDeclaredField("phase").apply { isAccessible = true }.get(adapter)
        return phase.javaClass.getDeclaredMethod("getOwner").apply { isAccessible = true }.invoke(phase) as ExpandCollapse?
    }

    private fun fieldRequest(node: SurfaceView?): OwnedFieldRequest? {
        if (node !is SurfaceNode) return null
        val state = SurfaceNode::class.java.getDeclaredField("fieldRequest\$delegate").apply { isAccessible = true }.get(node) as State<*>
        return state.value as OwnedFieldRequest?
    }

    private data class ObservedField(val request: OwnedFieldRequest, val sourceGeneration: Long,
        val nativeGeneration: Long?, val homeMatches: Boolean)

    private data class Checkpoint(val stage: String, val frameMillis: Long, val root: Policy?, val popup: Policy?,
        val mounted: Boolean, val modal: Boolean, val hidden: Boolean, val restoreRequest: Long,
        val restorePresented: Long, val triggerDraws: Int, val nativeAttached: Boolean,
        val nativeGeneration: Long?, val nativeOpening: Boolean?, val homeGeneration: Long?, val ownsHome: Boolean?,
        val fieldRequestGeneration: Long?, val fieldSubmitted: Boolean?, val retainedFieldSubmitted: Boolean?,
        val retainedSourceGeneration: Long?, val observedSourceGeneration: Long?,
        val retainedNativeGeneration: Long?, val observedNativeGeneration: Long?, val retainedHomeMatches: Boolean?,
        val nativeGateHeld: Boolean, val expanded: Boolean?, val playerFullyOpen: Boolean, val page: Int, val suppressedBrowseDraws: Int)

    private fun retirement(adjacentTab: Boolean, reduced: Boolean = false, speed: Float = 4f / 3f,
        hide: Boolean = true, renew: Boolean = false) {
        var open by mutableStateOf(false)
        var playerFullyOpen by mutableStateOf(false)
        val hasPlayer = true
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        val triggerDraws = AtomicInteger()
        val suppressedBrowseDraws = AtomicInteger()
        var rootPolicy: (() -> Policy)? = null
        var popupPolicy: (() -> Policy)? = null
        var observedField: ObservedField? = null
        fun admitOpening() {
            // Match BrowseSortDialog: reclaim the native host before publishing the new open intent.
            anchor.reclaimOpening?.invoke()
            open = true
        }
        compose.setContent {
            val density = LocalDensity.current
            val settings = remember { InterfaceSettings(reducedMotion = reduced, fasterMotion = speed == 2f) }
            val spec = remember(density, settings) { uiSpec(settings, ExpansionGeometryDomain(1000f, 3000f, 1f / 60), density) }
            val context = remember(spec) { EngineContext(1f / 60, 1000f, spec.spec.access) }
            CompositionLocalProvider(LocalEngineContext provides context, LocalUiSpec provides spec,
                LocalReducedMotion provides reduced, LocalMotionSpeed provides speed) {
            LocalicaTheme {
                ObservePolicy { rootPolicy = it }
                val backdrop = rememberFrostedBackdrop(Unit, dark = false, lightMix = 0f)
                CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalModalBackdrop provides backdrop) {
                    pager = rememberPagerState { 2 }
                    scope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize().safeDrawingPadding().background(Color.White)) {
                        // LocalicaScreen's fully open player retains browse composition but omits its draw.
                        Column(Modifier.fillMaxSize().drawWithContent {
                            if (!hasPlayer || !playerFullyOpen) drawContent() else suppressedBrowseDraws.incrementAndGet()
                        }.recordFrostedBackdrop(backdrop).background(Color.White)) {
                            TabPager(pager, { listOf(MainTab.Library, MainTab.Albums) }, Modifier.fillMaxSize()) { page ->
                                if (page == 0) {
                                    anchor = rememberGlassAnchor()
                                    LaunchedEffect(anchor) {
                                        // Retain the real request before native acknowledgment clears the live slot.
                                        // The collector stores references/scalars only; all export stays on instrumentation.
                                        snapshotFlow { fieldRequest(anchor.surfaceNode ?: anchor.returningNode) }
                                            .filterNotNull().collect { request ->
                                                val node = (anchor.surfaceNode ?: anchor.returningNode) as SurfaceNode
                                                observedField = ObservedField(request, node.sourceGeneration,
                                                    expansionOwner(anchor)?.nativeGeneration, request.plane === node.returnPlane())
                                            }
                                    }
                                    Box(Modifier.padding(32.dp)
                                        .drawWithContent { triggerDraws.incrementAndGet(); drawContent() }) {
                                        ActionIcon(AppIcon.ActionEllipsis, "Retained popup trigger", anchor = anchor) { admitOpening() }
                                    }
                                    GlassMorphPopup(open, anchor, { open = false }, GlassPlacement.Center) {
                                        popupView = LocalView.current
                                        ObservePolicy { popupPolicy = it }
                                        Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Retained trigger popup") }
                                    }
                                } else Box(Modifier.fillMaxSize().background(Color.Green))
                            }
                        }
                        if (playerFullyOpen) Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        } }
        val expectedAccess = if (reduced) MotionAccess.Reduced else if (speed == 2f) MotionAccess.Faster else MotionAccess.Standard
        val evidence = nativeEvidenceDirectory("popup-retained-trigger-retirement",
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val case = "adjacent-$adjacentTab-reduced-$reduced-speed-$speed-hide-$hide-renew-$renew"
        fun checkpoint(stage: String): Checkpoint {
            // Read Main-owned values once, return immutable data, then export on instrumentation.
            val snapshot = compose.runOnIdle {
                val node = anchor.surfaceNode ?: anchor.returningNode
                val home = node?.returnPlane()
                val expansion = expansionOwner(anchor)
                val field = fieldRequest(node)
                val retained = observedField
                Checkpoint(stage, compose.mainClock.currentTime, rootPolicy?.invoke(), popupPolicy?.invoke(),
                    anchor.triggerMounted, anchor.modalPresent, anchor.hidden, anchor.restoredDrawRequest,
                    anchor.restoredPresentedReceipt, triggerDraws.get(), popupView?.isAttachedToWindow == true,
                    expansion?.nativeGeneration, expansion?.nativeOpening,
                    home?.generation, home?.let { node?.ownsWindow(it) }, field?.nativeGeneration, field?.submitted,
                    retained?.request?.submitted, retained?.request?.sourceGeneration, retained?.sourceGeneration,
                    retained?.request?.nativeGeneration, retained?.nativeGeneration, retained?.homeMatches,
                    expansion?.awaitingNativeGate == true, expansion?.expanded, playerFullyOpen, pager.currentPage, suppressedBrowseDraws.get())
            }
            File(evidence, "$case-$stage.txt").writeText(snapshot.toString() + "\n")
            return snapshot
        }
        fun assertPolicy(snapshot: Checkpoint) {
            for (policy in listOf(requireNotNull(snapshot.root), requireNotNull(snapshot.popup))) {
                assertEquals(expectedAccess, policy.engine)
                assertEquals(expectedAccess, policy.spec)
                assertEquals(expectedAccess, policy.clock)
                assertEquals(expectedAccess, policy.clockSpec)
                assertEquals(reduced, policy.localReduced)
                assertEquals(speed, policy.localSpeed, 0f)
                assertTrue(policy.density > 0f && policy.fontScale > 0f)
            }
            assertEquals(snapshot.root?.clockId, snapshot.popup?.clockId)
        }
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        fun assertOpen() {
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
            compose.onNodeWithTag("glass-morph-surface").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue("A real native host was admitted", anchor.modalPresent)
                assertTrue("The trigger surrendered its visible surface", anchor.hidden)
                assertTrue("The composed origin remains mounted", anchor.triggerMounted)
                assertNotNull("A production glass control supplies the leased source node", anchor.surfaceNode)
                assertTrue(requireNotNull(popupView).isAttachedToWindow)
            }
        }
        compose.waitForIdle()
        assertTrue("The origin was positively drawn before opening, including Reduced", triggerDraws.get() > 0)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { admitOpening() }
            compose.advanceMorphOpening(2_000, anchor) { popupView }
            val opened = checkpoint("opened")
            assertPolicy(opened)
            assertNotNull("The leased source has an observed return plane", opened.homeGeneration)
            assertTrue("The real source plane has drawn before opening", requireNotNull(opened.homeGeneration) > 0L)
            assertOpen()
            val nativeView = requireNotNull(popupView)
            val oldRequest = compose.runOnIdle { anchor.restoredDrawRequest }
            compose.runOnIdle {
                open = false
                if (hide) {
                    if (adjacentTab) scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(1) }
                    else playerFullyOpen = true
                }
            }
            frame()
            val hiddenDraws = triggerDraws.get()
            // Reach the real closing native gate, independently of the spring duration.
            var framesToGate = 1
            while (compose.runOnIdle { anchor.restoredDrawRequest == oldRequest } && framesToGate < 240) {
                frame()
                framesToGate++
            }
            val closing = checkpoint("closing-gate")
            assertPolicy(closing)
            if (hide) {
                assertTrue("The close is held at the real native gate", closing.nativeGateHeld)
                assertEquals(false, closing.nativeOpening)
                if (!adjacentTab) assertTrue("The actual browse draw gate suppressed content", closing.suppressedBrowseDraws > 0)
                assertEquals("The hidden home did not draw a new field", opened.homeGeneration, closing.homeGeneration)
                assertNull("A missing restore receipt has not admitted a home-field request", closing.fieldRequestGeneration)
            }
            compose.runOnIdle {
                assertTrue("The actual closing gate requested a fresh restoration receipt", anchor.restoredDrawRequest > oldRequest)
                assertTrue("This is retained composition, not disposal", anchor.triggerMounted)
                if (hide) {
                    if (adjacentTab) assertEquals("The production pager retained its adjacent origin", 1, pager.currentPage)
                    else assertTrue("The opaque player covers browse", playerFullyOpen)
                    assertNotEquals("The undrawn origin cannot supply this receipt", anchor.restoredDrawRequest, anchor.restoredPresentedReceipt)
                }
            }
            if (renew) {
                compose.runOnIdle {
                    playerFullyOpen = false
                    if (adjacentTab) scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(0) }
                    admitOpening()
                }
                repeat(10) { index ->
                    frame()
                    val continuing = checkpoint("renewed-frame-" + (index + 1))
                    assertPolicy(continuing)
                    assertSame("Every frame retains the renewed native host", nativeView, popupView)
                    assertTrue("The renewed host stays attached through and beyond the old six-frame deadline", continuing.nativeAttached)
                    assertTrue("The old close never releases the renewed modal", continuing.modal)
                    assertEquals("The real opening intent owns the continuing expansion", true, continuing.expanded)
                }
                compose.advanceMorphOpening(2_000, anchor) { popupView }
                val renewed = checkpoint("renewed-settled")
                assertPolicy(renewed)
                assertOpen()
                assertSame("Settled reopening retains the same native host", nativeView, popupView)
                assertTrue("Native generations never regress", requireNotNull(renewed.nativeGeneration) >= requireNotNull(closing.nativeGeneration))
                assertFalse("The old closing-gate predicate is no longer current", renewed.nativeOpening == false)
                compose.runOnIdle { assertEquals("The renewed surface settles fully open", 1f, anchor.contact.expansion.value, 0f) }
                compose.runOnIdle { open = false }
                compose.advanceMorphOpening(2_000, anchor) { popupView }
                val retired = checkpoint("renewed-final-retirement")
                assertFalse(retired.modal)
                compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
                compose.waitUntil(5_000) { !nativeView.isAttachedToWindow }
                return
            }
            repeat(6) { frame() }
            val bounded = checkpoint("six-frame-bound")
            assertPolicy(bounded)
            if (hide) {
                assertEquals("The hidden home still cannot submit a field", closing.homeGeneration, bounded.homeGeneration)
                assertNull("The fallback never invents a home-field submission", bounded.fieldSubmitted)
            } else {
                assertEquals("The retained real owned-field request was submitted", true, bounded.retainedFieldSubmitted)
                assertEquals("The receipt belongs to the actual source attachment", bounded.observedSourceGeneration, bounded.retainedSourceGeneration)
                assertEquals("The receipt belongs to the observed native generation", bounded.observedNativeGeneration, bounded.retainedNativeGeneration)
                assertEquals("The receipt belongs to this closing gate", closing.nativeGeneration, bounded.retainedNativeGeneration)
                assertEquals("The receipt belongs to the actual return home", true, bounded.retainedHomeMatches)
            }
            compose.runOnIdle {
                assertFalse("The closing gate must release the modal within six frames", anchor.modalPresent)
                assertFalse(anchor.hidden)
                assertFalse(anchor.shadowInModal)
                assertTrue("Retirement does not dispose the retained origin", anchor.triggerMounted)
                if (!hide) assertEquals("The drawable path keeps the posted receipt", anchor.restoredDrawRequest, anchor.restoredPresentedReceipt)
            }
            if (hide) assertEquals("No hidden trigger draw supplied a false acknowledgment", hiddenDraws, triggerDraws.get())
            // Retirement acknowledgment and native window disposal drain after the gate releases.
            repeat(4) { frame() }
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
            compose.waitUntil(5_000) { !nativeView.isAttachedToWindow }
            compose.runOnIdle {
                playerFullyOpen = false
                if (adjacentTab) scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(0) }
            }
            frame()
            assertTrue("The restored origin draws again, including Reduced", triggerDraws.get() > hiddenDraws)
            compose.runOnIdle { admitOpening() }
            compose.advanceMorphOpening(2_000, anchor) { popupView }
            assertPolicy(checkpoint("reopened-after-retirement"))
            assertOpen()
            assertNotSame("Reopening creates a fresh native host after retirement", nativeView, popupView)
            compose.runOnIdle { open = false }
            compose.advanceMorphOpening(2_000, anchor) { popupView }
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
