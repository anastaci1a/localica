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

    private data class Checkpoint(val stage: String, val frameMillis: Long, val root: Policy?, val popup: Policy?,
        val mounted: Boolean, val modal: Boolean, val hidden: Boolean, val restoreRequest: Long,
        val restorePresented: Long, val triggerDraws: Int, val nativeAttached: Boolean,
        val nativeGeneration: Long?, val nativeOpening: Boolean?, val homeGeneration: Long?, val ownsHome: Boolean?,
        val fieldRequestGeneration: Long?, val fieldSubmitted: Boolean?)

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
        var rootPolicy: (() -> Policy)? = null
        var popupPolicy: (() -> Policy)? = null
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
                    Box(Modifier.fillMaxSize().safeDrawingPadding().recordFrostedBackdrop(backdrop).background(Color.White)) {
                        // LocalicaScreen's fully open player retains browse composition but omits its draw.
                        Column(Modifier.fillMaxSize().drawWithContent {
                            if (!hasPlayer || !playerFullyOpen) drawContent()
                        }) {
                            TabPager(pager, { listOf(MainTab.Library, MainTab.Albums) }, Modifier.fillMaxSize()) { page ->
                                if (page == 0) {
                                    anchor = rememberGlassAnchor()
                                    Box(Modifier.padding(32.dp)
                                        .drawWithContent { triggerDraws.incrementAndGet(); drawContent() }) {
                                        ActionIcon(AppIcon.ActionEllipsis, "Retained popup trigger", anchor = anchor) { open = true }
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
                Checkpoint(stage, compose.mainClock.currentTime, rootPolicy?.invoke(), popupPolicy?.invoke(),
                    anchor.triggerMounted, anchor.modalPresent, anchor.hidden, anchor.restoredDrawRequest,
                    anchor.restoredPresentedReceipt, triggerDraws.get(), popupView?.isAttachedToWindow == true,
                    expansion?.nativeGeneration, expansion?.nativeOpening,
                    home?.generation, home?.let { node?.ownsWindow(it) }, field?.nativeGeneration, field?.submitted)
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
            compose.runOnIdle { open = true }
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
                    open = true
                }
                repeat(10) { frame() }
                val renewed = checkpoint("renewed-past-old-deadline")
                assertPolicy(renewed)
                assertOpen()
                assertSame("The pending close cannot dispose the renewed host", nativeView, popupView)
                assertTrue("The old deadline elapsed while this native host remained attached", nativeView.isAttachedToWindow)
                assertTrue("Reopening belongs to a fresh native generation", requireNotNull(renewed.nativeGeneration) > requireNotNull(closing.nativeGeneration))
                compose.runOnIdle { open = false }
                compose.advanceMorphOpening(2_000, anchor) { popupView }
                val retired = checkpoint("renewed-final-retirement")
                assertFalse(retired.modal)
                compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
                return
            }
            repeat(6) { frame() }
            val bounded = checkpoint("six-frame-bound")
            assertPolicy(bounded)
            if (hide) {
                assertEquals("The hidden home still cannot submit a field", closing.homeGeneration, bounded.homeGeneration)
                assertNull("The fallback never invents a home-field submission", bounded.fieldSubmitted)
            } else assertEquals("The drawable path submitted its real owned field", true, bounded.fieldSubmitted)
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
            compose.runOnIdle { open = true }
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
