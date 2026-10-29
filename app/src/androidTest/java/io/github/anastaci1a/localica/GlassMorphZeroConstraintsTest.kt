package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogWindowProvider
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMorphZeroConstraintsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centerDialogRetainsItsDraftAndTargetsThroughZeroAxes() = collapseAndRecover(GlassPlacement.Center)

    @Test fun searchDialogRetainsItsDraftAndNeverPublishesCollapsedGeometry() = collapseAndRecover(GlassPlacement.Search)

    private fun collapseAndRecover(placement: GlassPlacement) {
        var open by mutableStateOf(true)
        val home = Rect(40f, 40f, 160f, 96f)
        val anchor = GlassAnchor().apply { bounds = home; ink = Color.Black }
        val samples = mutableListOf<SearchMorphSample>()
        lateinit var popupView: View
        lateinit var currentOwner: Any
        var currentDraft = ""
        var mounts = 0
        var disposals = 0
        var saved = ""
        var saves = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassOrigin provides anchor, LocalSearchMorphObserver provides { samples += it }) {
                if (open) GlassDialog({ open = false }, placement = placement) {
                    val view = LocalView.current
                    val owner = remember { Any() }
                    var draft by remember { mutableStateOf("") }
                    val reportOpening = LocalSearchOpeningReady.current
                    SideEffect {
                        popupView = view; currentOwner = owner; currentDraft = draft
                        // This fixture owns native constraints, not an asynchronous IME.
                        reportOpening?.invoke(false)
                    }
                    DisposableEffect(owner) {
                        mounts++
                        onDispose { disposals++ }
                    }
                    Column {
                        GlassTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("zero-viewport-draft"),
                            label = { Text("Dialog draft") })
                        ActionTextButton(onClick = { saved = draft; saves++; open = false }) { Text("Commit draft") }
                    }
                }
            }
        } }
        // SetText exercises the real remembered input without requesting native IME
        // focus; the original IME/Back tests remain independent end-to-end coverage.
        fun replaceDraft(text: String) {
            val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("zero-viewport-draft")))
            field.performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString(text)))
            }
            field.assertTextContains(text)
        }
        replaceDraft("Night music")
        compose.waitForIdle()
        val originalOwner = currentOwner
        val originalView = popupView
        val nativeParent = popupView.parent as View
        assertTrue("Use the actual dialog's native parent", nativeParent is DialogWindowProvider)
        val padding = intArrayOf(nativeParent.paddingLeft, nativeParent.paddingTop, nativeParent.paddingRight, nativeParent.paddingBottom)
        val nativeWidth = popupView.width
        val nativeHeight = popupView.height
        val surface = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode()
        val initialSize = surface.size
        assertTrue(initialSize.width > 1 && initialSize.height > 1)
        val initialVertical = anchor.contact.vertical
        compose.runOnIdle {
            if (placement == GlassPlacement.Search) {
                // Opening has an intentional unmeasured phase; retain only the
                // settled witness before testing loss and recovery of the viewport.
                val settled = samples.last()
                samples.clear(); samples += settled
            }
        }
        try {
            for ((index, zeroAxes) in listOf(true to false, false to true, true to true).withIndex()) {
                val (zeroWidth, zeroHeight) = zeroAxes
                compose.runOnIdle {
                    // DialogLayout subtracts its native padding before measuring its
                    // Compose child. Consume an axis without replacing the window,
                    // composition, or caller, and without a production-only test seam.
                    nativeParent.setPadding(padding[0] + if (zeroWidth) nativeParent.width else 0,
                        padding[1] + if (zeroHeight) nativeParent.height else 0, padding[2], padding[3])
                }
                compose.waitUntil(5_000) {
                    (!zeroWidth || popupView.measuredWidth == 0) && (!zeroHeight || popupView.measuredHeight == 0)
                }
                compose.waitForIdle()
                compose.runOnIdle {
                    // Raw layout size, not clipped bounds: a forced 1px surface must fail.
                    if (zeroWidth) assertEquals(0, surface.size.width)
                    if (zeroHeight) assertEquals(0, surface.size.height)
                    if (!zeroWidth) assertTrue(popupView.measuredWidth > 0)
                    if (!zeroHeight) assertTrue(popupView.measuredHeight > 0)
                    assertSame(originalView, popupView)
                    assertSame(originalOwner, currentOwner)
                    assertEquals(1, mounts)
                    assertEquals(0, disposals)
                    assertEquals(if (index == 0) "Night music" else "Recovered $index", currentDraft)
                    assertEquals(home, anchor.bounds)
                    assertEquals(initialVertical, anchor.contact.vertical)
                    assertTrue(anchor.modalPresent)
                    if (placement == GlassPlacement.Search) {
                        assertTrue(samples.isNotEmpty())
                        assertTrue(samples.all { it.bounds.width > 0f && it.bounds.height > 0f &&
                            it.destination.width > 0f && it.destination.height > 0f })
                    }
                }
                // Freeze before recovery: a bogus 1px target must not get time to
                // animate back to the old size and hide its first-frame regression.
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { nativeParent.setPadding(padding[0], padding[1], padding[2], padding[3]) }
                compose.waitUntil(5_000) { popupView.measuredWidth == nativeWidth && popupView.measuredHeight == nativeHeight }
                compose.runOnIdle {
                    assertEquals("First recovered measure keeps the last real target", initialSize, surface.size)
                    assertSame(originalOwner, currentOwner)
                    assertEquals(0, disposals)
                }
                compose.mainClock.autoAdvance = true
                replaceDraft("Recovered ${index + 1}")
            }
            compose.onNodeWithText("Commit draft").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Recovered 3", saved)
                assertEquals(1, saves)
                assertFalse(open)
                assertEquals(1, mounts)
                assertEquals(1, disposals)
            }
            compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { nativeParent.setPadding(padding[0], padding[1], padding[2], padding[3]) }
        }
    }
}
