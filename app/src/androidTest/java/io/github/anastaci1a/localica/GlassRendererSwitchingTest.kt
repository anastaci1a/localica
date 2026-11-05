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
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
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

    private data class Mounted(val owner: Any, val view: View, val generation: GlassRendererGeneration,
        val draft: String = "", val material: FrostedBackdrop? = null)

    @Test fun switchingRetainsOpenClosingAndReopenedDialogOwnerAndLatestDraft() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        val observed = AtomicReference<Mounted?>()
        var open by mutableStateOf(true)
        var commits = 0
        var saved = ""
        lateinit var host: GlassDialogs
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
                LocalicaTheme {
                    val anchor = rememberGlassAnchor()
                    val dialogs = requireNotNull(LocalGlassDialogs.current)
                    SideEffect { host = dialogs }
                    CompositionLocalProvider(LocalGlassOrigin provides anchor) {
                        if (open) GlassDialog(onDismiss = { open = false }) {
                            val owner = remember { Any() }
                            var draft by remember { mutableStateOf("") }
                            val generation = currentGlassRendererGeneration()
                            val view = LocalView.current
                            SideEffect { observed.set(Mounted(owner, view, generation, draft)) }
                            Column {
                                OutlinedTextField(draft, { draft = it }, Modifier.testTag("renderer-draft"), singleLine = true)
                                TextButton(onClick = { saved = draft; commits++; open = false }) { Text("Save renderer draft") }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("renderer-draft").performTextInput("First draft")
        val initial = compose.runOnIdle { requireNotNull(observed.get()) }
        val entry = compose.runOnIdle { host.entries.single() }
        for (mode in listOf(GlassRendererMode.None, GlassRendererMode.Normal, GlassRendererMode.None)) {
            compose.runOnIdle { runtime.request(mode) }
            compose.waitForIdle()
            compose.runOnIdle {
                val now = requireNotNull(observed.get())
                assertSame(initial.owner, now.owner)
                assertSame("A backend change cannot recreate the native dialog view", initial.view, now.view)
                assertSame(entry, host.entries.single())
                assertEquals(runtime.generation, now.generation)
                assertEquals("First draft", now.draft)
                assertEquals(mode, runtime.requested)
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
                runtime.request(GlassRendererMode.Normal)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val closing = requireNotNull(observed.get())
                assertSame(initial.owner, closing.owner)
                assertSame(initial.view, closing.view)
                assertEquals("Closing content observes the shared live holder, not frozen caller mode", runtime.generation, closing.generation)
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

    @Test fun switchingRetainsNestedWindowsAndFinishedMaterialOwners() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        val parent = AtomicReference<Mounted?>()
        val child = AtomicReference<Mounted?>()
        var childOpen by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
                LocalicaTheme {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        GlassAlertDialog(onDismissRequest = {}, title = { Text("Renderer parent") }, text = {
                            val owner = remember { Any() }
                            val view = LocalView.current
                            val generation = currentGlassRendererGeneration()
                            val material = requireNotNull(LocalModalBackdrop.current)
                            SideEffect { parent.set(Mounted(owner, view, generation, material = material)) }
                            TextButton(onClick = { childOpen = true }) { Text("Open renderer child") }
                            if (childOpen) GlassAlertDialog(onDismissRequest = { childOpen = false }, title = { Text("Renderer child") }, text = {
                                val childOwner = remember { Any() }
                                val childView = LocalView.current
                                val childGeneration = currentGlassRendererGeneration()
                                val childMaterial = requireNotNull(LocalModalBackdrop.current)
                                SideEffect { child.set(Mounted(childOwner, childView, childGeneration, material = childMaterial)) }
                                Text("Nested material")
                            }, confirmButton = { TextButton(onClick = { childOpen = false }) { Text("Close renderer child") } })
                        }, confirmButton = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Open renderer child").performClick()
        val firstParent = compose.runOnIdle { requireNotNull(parent.get()) }
        val firstChild = compose.runOnIdle { requireNotNull(child.get()) }
        assertNotSame(firstParent.view, firstChild.view)
        assertNotSame(firstParent.material, firstChild.material)
        for (mode in listOf(GlassRendererMode.None, GlassRendererMode.Normal, GlassRendererMode.None, GlassRendererMode.Normal)) {
            compose.runOnIdle { runtime.request(mode) }
            compose.waitForIdle()
            compose.runOnIdle {
                for ((initial, current) in listOf(firstParent to requireNotNull(parent.get()), firstChild to requireNotNull(child.get()))) {
                    assertSame(initial.owner, current.owner)
                    assertSame(initial.view, current.view)
                    assertSame(initial.material, current.material)
                    assertEquals(runtime.generation, current.generation)
                }
            }
        }
        compose.onNodeWithText("Close renderer child").performClick()
        compose.onNodeWithText("Renderer child").assertDoesNotExist()
        compose.onNodeWithText("Renderer parent").assertIsDisplayed()
    }

}
