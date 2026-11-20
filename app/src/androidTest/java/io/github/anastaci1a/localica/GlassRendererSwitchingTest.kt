package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real mounted windows and retained captures. These checks do not claim frame-perfect optics
 * or native-copy completion ordering; the existing optics/readback lifetime suites own those. */
class GlassRendererSwitchingTest {
    @get:Rule val compose = createComposeRule()

    private data class Mounted(val owner: Any, val view: View,
        val draft: String = "", val material: FrostedBackdrop? = null)

    @Test fun retainedPopupFollowsCurrentScene() = retainedScene()

    private fun retainedScene() {

        var blue by mutableStateOf(false)
        lateinit var upstream: FrostedBackdrop
        val mounted = AtomicReference<Mounted?>()
        val childMounted = AtomicReference<Mounted?>()
        compose.setContent {

                EngineTheme(reduced = true) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        val scene = rememberFrostedBackdrop(Unit, dark = false)
                        SideEffect { upstream = scene }
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(scene)) {
                                drawRect(if (blue) Color.Blue else Color.Red)
                            }
                            CompositionLocalProvider(LocalModalBackdrop provides scene, LocalFrostedBackdrop provides scene) {
                                GlassDialog(onDismiss = {}) {
                                    val owner = remember { Any() }
                                    var draft by remember { mutableStateOf("Retained draft") }
                                    val view = LocalView.current

                                    val material = requireNotNull(LocalModalBackdrop.current)
                                    SideEffect { mounted.set(Mounted(owner, view, draft, material)) }
                                    Column {
                                        Box(Modifier.size(96.dp, 64.dp).testTag("retained-sampling"))
                                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("retained-draft"))
                                        GlassDialog(onDismiss = {}) {
                                            val childOwner = remember { Any() }
                                            val childView = LocalView.current

                                            val childMaterial = requireNotNull(LocalModalBackdrop.current)
                                            SideEffect { childMounted.set(Mounted(childOwner, childView, material = childMaterial)) }
                                            Box(Modifier.size(96.dp, 64.dp).testTag("retained-child-sampling"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

        }
        compose.onNodeWithTag("retained-draft").assertIsDisplayed()
        val first = compose.runOnIdle { requireNotNull(mounted.get()) }
        val firstChild = compose.runOnIdle { requireNotNull(childMounted.get()) }
        for (refresh in 0..4) {
            compose.runOnIdle { upstream.refresh() }
            compose.waitForIdle()
            compose.runOnIdle {
                val now = requireNotNull(mounted.get())
                assertSame(first.owner, now.owner)
                assertSame(first.view, now.view)
                assertEquals("Retained draft", now.draft)
                assertSame("The retained popup samples the current descriptor", upstream, now.material?.parent)
                assertFalse(upstream.layer.isReleased)
                val child = requireNotNull(childMounted.get())
                assertSame(firstChild.owner, child.owner)
                assertSame(firstChild.view, child.view)
                assertSame("Nested windows hand off the current finished panel", now.material, child.material?.parent)
            }
            // Change only the scene's draw state while the popup stays mounted.
            // Native pixels must follow both colors, not a stale published epoch.
            for (target in listOf(false, true)) {
                compose.runOnIdle { blue = target }
                compose.waitUntil(20_000) {
                    upstream.recorded &&
                        (Build.VERSION.SDK_INT >= 33 || upstream.fallback != null)
                }
                compose.waitUntil(20_000) {
                    val pixels = capturePopupPixels(compose, compose.onNodeWithTag("retained-child-sampling")) {
                        requireNotNull(childMounted.get()).view
                    }
                    val color = pixels[pixels.width / 2, pixels.height / 2]
                    if (target) color.blue > color.red + .05f else color.red > color.blue + .05f
                }
            }
        }
    }

    @Test fun redrawRetainsOpenClosingAndReopenedDialogOwnerAndLatestDraft() {

        val observed = AtomicReference<Mounted?>()
        var caption by mutableStateOf("Scene")
        var open by mutableStateOf(true)
        var commits = 0
        var saved = ""
        lateinit var host: GlassDialogs
        compose.setContent {

                LocalicaTheme {
                    val anchor = rememberGlassAnchor()
                    val dialogs = requireNotNull(LocalGlassDialogs.current)
                    SideEffect { host = dialogs }
                    CompositionLocalProvider(LocalGlassOrigin provides anchor) {
                        if (open) GlassDialog(onDismiss = { open = false }) {
                            val owner = remember { Any() }
                            var draft by remember { mutableStateOf("") }

                            val view = LocalView.current
                            SideEffect { observed.set(Mounted(owner, view, draft)) }
                            Column {
                                Text(caption)
                                OutlinedTextField(draft, { draft = it }, Modifier.testTag("renderer-draft"), singleLine = true)
                                TextButton(onClick = { saved = draft; commits++; open = false }) { Text("Save renderer draft") }
                            }
                        }
                    }
                }

        }
        compose.onNodeWithTag("renderer-draft").performTextInput("First draft")
        val initial = compose.runOnIdle { requireNotNull(observed.get()) }
        val entry = compose.runOnIdle { host.entries.single() }
        for (update in 0..4) {
            compose.runOnIdle { caption = "Scene $update" }
            compose.waitForIdle()
            compose.runOnIdle {
                val now = requireNotNull(observed.get())
                assertSame(initial.owner, now.owner)
                assertSame("A backend change cannot recreate the native dialog view", initial.view, now.view)
                assertSame(entry, host.entries.single())
                assertEquals("First draft", now.draft)
            }
        }
        compose.onNodeWithTag("renderer-draft").performTextReplacement("Latest draft")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { open = false }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertFalse(entry.mounted)
                assertTrue("The caller is gone while the retained window is still returning", entry.anchor.modalPresent)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val closing = requireNotNull(observed.get())
                assertSame(initial.owner, closing.owner)
                assertSame(initial.view, closing.view)
                assertEquals("Latest draft", closing.draft)
                open = true
            }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle {
                val reopened = requireNotNull(observed.get())
                assertSame(entry, host.entries.single())
                assertSame(initial.owner, reopened.owner)
                assertSame(initial.view, reopened.view)
                assertEquals("Latest draft", reopened.draft)
                assertTrue(entry.mounted)
            }
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithText("Save renderer draft").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(1200)
            compose.runOnIdle {
                assertEquals("Latest draft", saved)
                assertEquals(1, commits)
                assertTrue(host.entries.isEmpty())
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun redrawRetainsNestedWindowsAndFinishedMaterialOwners() {

        val parent = AtomicReference<Mounted?>()
        val child = AtomicReference<Mounted?>()
        var caption by mutableStateOf("Scene")
        var childOpen by mutableStateOf(false)
        compose.setContent {

                EngineTheme(reduced = true) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        GlassAlertDialog(onDismissRequest = {}, title = { Text("Renderer parent") }, text = {
                            val owner = remember { Any() }
                            val view = LocalView.current

                            val material = requireNotNull(LocalModalBackdrop.current)
                            SideEffect { parent.set(Mounted(owner, view, material = material)) }
                            TextButton(onClick = { childOpen = true }) { Text("Open renderer child") }
                            if (childOpen) GlassAlertDialog(onDismissRequest = { childOpen = false }, title = { Text("Renderer child") }, text = {
                                val childOwner = remember { Any() }
                                val childView = LocalView.current

                                val childMaterial = requireNotNull(LocalModalBackdrop.current)
                                SideEffect { child.set(Mounted(childOwner, childView, material = childMaterial)) }
                                Text("Nested material $caption")
                            }, confirmButton = { TextButton(onClick = { childOpen = false }) { Text("Close renderer child") } })
                        }, confirmButton = {})
                    }
                }

        }
        compose.onNodeWithText("Open renderer child").performClick()
        val firstParent = compose.runOnIdle { requireNotNull(parent.get()) }
        val firstChild = compose.runOnIdle { requireNotNull(child.get()) }
        assertNotSame(firstParent.view, firstChild.view)
        assertNotSame(firstParent.material, firstChild.material)
        var previousParent = firstParent
        var previousChild = firstChild
        for (update in 0..4) {
            compose.runOnIdle { caption = "Scene $update" }
            compose.waitForIdle()
            compose.runOnIdle {
                for ((initial, current) in listOf(previousParent to requireNotNull(parent.get()), previousChild to requireNotNull(child.get()))) {
                    assertSame(initial.owner, current.owner)
                    assertSame(initial.view, current.view)
                    assertSame(initial.material, current.material)
                }
            }
            previousParent = requireNotNull(parent.get())
            previousChild = requireNotNull(child.get())
        }
        compose.onNodeWithText("Close renderer child").performClick()
        compose.onNodeWithText("Renderer child").assertDoesNotExist()
        compose.onNodeWithText("Renderer parent").assertIsDisplayed()
    }

}
