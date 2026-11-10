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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
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

    private fun retirement(adjacentTab: Boolean, reduced: Boolean = false, speed: Float = 4f / 3f, hide: Boolean = true) {
        var open by mutableStateOf(false)
        var playerFullyOpen by mutableStateOf(false)
        val hasPlayer = true
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        lateinit var anchor: GlassAnchor
        var popupView: View? = null
        val triggerDraws = AtomicInteger()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced, LocalMotionSpeed provides speed) {
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
            compose.runOnIdle {
                assertTrue("The actual closing gate requested a fresh restoration receipt", anchor.restoredDrawRequest > oldRequest)
                assertTrue("This is retained composition, not disposal", anchor.triggerMounted)
                if (hide) {
                    if (adjacentTab) assertEquals("The production pager retained its adjacent origin", 1, pager.currentPage)
                    else assertTrue("The opaque player covers browse", playerFullyOpen)
                    assertNotEquals("The undrawn origin cannot supply this receipt", anchor.restoredDrawRequest, anchor.restoredPresentedReceipt)
                }
            }
            repeat(6) { frame() }
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
            assertOpen()
            assertNotSame("Reopening creates a fresh native host after retirement", nativeView, popupView)
            compose.runOnIdle { open = false }
            compose.advanceMorphOpening(2_000, anchor) { popupView }
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
