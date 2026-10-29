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
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    fun modernSwitchingDoesNotReplaceAnUnavailableRequestedHomeWithThePrimary() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Modern)
        var requestedWeight by mutableFloatStateOf(1f)
        var stage by mutableIntStateOf(0)
        val sampled = mutableListOf<HomeBackdropSamplingFrame>()
        lateinit var primary: FrostedBackdrop
        lateinit var home: FrostedBackdrop
        lateinit var view: View
        lateinit var density: androidx.compose.ui.unit.Density
        lateinit var direction: androidx.compose.ui.unit.LayoutDirection
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
                LocalicaTheme {
                    CompositionLocalProvider(
                        LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f),
                        LocalHomeBackdropSamplingObserver provides { frame -> sampled += frame },
                    ) {
                        val primarySource = rememberFrostedBackdrop(Unit, enabled = false, dark = false)
                        val requestedHome = rememberFrostedBackdrop(Unit, enabled = false, dark = false)
                        val currentView = LocalView.current
                        val currentDensity = LocalDensity.current
                        val currentDirection = LocalLayoutDirection.current
                        SideEffect {
                            primary = primarySource; home = requestedHome; view = currentView
                            density = currentDensity; direction = currentDirection
                        }
                        Box(Modifier.size(180.dp).testTag("renderer-home-readiness")) {
                            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(primarySource)) { drawRect(Color.Red) }
                            // Real placement without automatic recording: the fixture owns exactly
                            // when this same home layer becomes ready for the new renderer epoch.
                            Canvas(Modifier.matchParentSize().recordFrostedBackdrop(requestedHome, enabled = false)) { }
                            Canvas(Modifier.matchParentSize()) { drawRect(Color.Green) }
                            LiquidGlassSurface(Modifier.matchParentSize(), source = primarySource,
                                homeSource = requestedHome, homeWeight = { requestedWeight },
                                cornerRadius = 12.dp, refraction = 0f, depthScale = 0f,
                                pressFeedback = false, shadow = false)
                            PresentationMarker { stage }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val primaryOwner = compose.runOnIdle { primary }
        val homeOwner = compose.runOnIdle { home }
        val homeLayer = compose.runOnIdle { home.layer }
        fun recordHome(color: Color) {
            assertFalse(home.readbackPending)
            assertTrue(home.size.width > 0 && home.size.height > 0)
            home.layer.record(density, direction, home.size) { drawRect(color) }
            // Mark only a recording that really completed, never readiness without pixels.
            home.recordedSize = home.size
            home.recordedGeneration = runtime.generation
        }
        fun present(weight: Float, expected: Color, admitted: Boolean, change: () -> Unit = {}) {
            val token = compose.runOnIdle {
                sampled.clear()
                change()
                requestedWeight = weight
                stage++
                stage
            }
            val pixels = capturePresentedPopupPixels(compose,
                compose.onNodeWithTag("renderer-home-readiness"), token) { view }
            // The unrelated marker establishes a fresh presented frame. We never poll
            // for the desired optical color or use absent callbacks as proof of a draw.
            for (fy in listOf(.45f, .55f)) for (fx in listOf(.45f, .55f)) {
                val actual = pixels[(pixels.width * fx).toInt(), (pixels.height * fy).toInt()]
                assertEquals("Presented red at weight=$weight admitted=$admitted", expected.red, actual.red, .2f)
                assertEquals("Presented green at weight=$weight admitted=$admitted", expected.green, actual.green, .2f)
                assertEquals("Presented blue at weight=$weight admitted=$admitted", expected.blue, actual.blue, .2f)
            }
            compose.runOnIdle {
                assertSame(primaryOwner, primary)
                assertSame(homeOwner, home)
                assertSame(homeLayer, home.layer)
                assertTrue("The primary really is ready in every checked phase", primary.recorded)
                assertEquals(GlassBackend.Modern, runtime.generation.backend)
                if (admitted) {
                    assertTrue("The modern sampling path admitted the requested scene", sampled.isNotEmpty())
                    assertTrue(sampled.all { it.weight == weight })
                    if (weight > 0f) assertTrue(home.recorded)
                } else {
                    assertFalse(home.recorded)
                    assertTrue("A non-null requested home cannot silently become primary-only sampling", sampled.isEmpty())
                }
            }
        }

        present(1f, Color.Blue, admitted = true) { recordHome(Color.Blue) }
        val oldEpoch = compose.runOnIdle { runtime.generation }
        present(0f, Color.Red, admitted = true) {
            runtime.request(GlassRendererMode.Legacy)
            runtime.request(GlassRendererMode.Modern)
            assertEquals(oldEpoch.epoch + 2L, runtime.generation.epoch)
            assertFalse("Home still contains real but obsolete blue pixels", home.recorded)
            assertTrue(home.layer.size.width > 0 && home.layer.size.height > 0)
        }
        present(.35f, Color.Green, admitted = false)
        present(1f, Color.Green, admitted = false)
        // A stale unused home must not suppress a valid primary-only frame.
        present(0f, Color.Red, admitted = true)
        present(1f, Color.Green, admitted = false)
        // Keep weight=1 and both source identities. Readiness alone must invalidate the
        // unavailable frame, and the fresh cyan recording must replace obsolete blue.
        present(1f, Color.Cyan, admitted = true) { recordHome(Color.Cyan) }
    }

    @Test fun switchingRetainsOpenClosingAndReopenedDialogOwnerAndLatestDraft() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Automatic)
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
        for (mode in listOf(GlassRendererMode.Legacy, GlassRendererMode.Modern, GlassRendererMode.Legacy)) {
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
                runtime.request(GlassRendererMode.Modern)
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
        val runtime = GlassRendererRuntime(GlassRendererMode.Automatic)
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
        for (mode in listOf(GlassRendererMode.Legacy, GlassRendererMode.Modern, GlassRendererMode.Legacy, GlassRendererMode.Automatic)) {
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

    @Test fun switchingKeepsOddSizedLegacySamplesSafeWhileControlAndSelectionStayMounted() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Automatic)
        val optics = AtomicInteger()
        var appearance by mutableStateOf(GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f))
        lateinit var source: FrostedBackdrop
        lateinit var nested: FrostedBackdrop
        lateinit var selection: SelectionScene
        var logical = IntSize.Zero
        fun preparedForCurrentAppearance(): Boolean {
            val raw = source.snapshot ?: return false
            val lens = source.lensSnapshot ?: return false
            // Same-generation appearance changes deliberately retain the preceding
            // prepared endpoints. Non-null alone cannot acknowledge the requested phase.
            return if (appearance.blur > 0f) source.glassFallback != null && lens !== raw
                else source.glassFallback == null && appearance.whiteTint == 0f && lens === raw
        }
        compose.setContent {
            CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
                LocalicaTheme {
                    CompositionLocalProvider(LocalGlassAppearance provides appearance,
                        LocalControlLensDraw provides { optics.incrementAndGet(); Unit }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val density = LocalDensity.current
                            val availableWidth = (constraints.maxWidth - 13).coerceAtLeast(1)
                            val availableHeight = (minOf(constraints.maxHeight, with(density) { 240.dp.roundToPx() }) - 11).coerceAtLeast(1)
                            val width = if (availableWidth % 64 == 0) availableWidth - 1 else availableWidth
                            val height = if (availableHeight % 64 == 0) availableHeight - 1 else availableHeight
                            val backing = rememberFrostedBackdrop(Unit, dark = false,
                                glassBlur = appearance.blur, whiteTint = appearance.whiteTint)
                            SideEffect { source = backing; logical = IntSize(width, height) }
                            Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
                                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) {
                                    drawRect(Color.Red, size = Size(size.width / 2f, size.height))
                                    drawRect(Color.Blue, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                                }
                                CompositionLocalProvider(LocalFrostedBackdrop provides backing) {
                                    Column {
                                        BooleanPreference("Runtime switch", false) { }
                                        ConnectedSelectionHost(Modifier.fillMaxWidth().height(48.dp)) {
                                            val current = requireNotNull(LocalSelectionScene.current)
                                            SideEffect { selection = current }
                                            Row {
                                                Box(Modifier.weight(1f).fillMaxHeight().selectionLens(true, false, "first").testTag("renderer-first")) { Text("First") }
                                                Box(Modifier.weight(1f).fillMaxHeight().selectionLens(true, false, "second").testTag("renderer-second")) { Text("Second") }
                                            }
                                        }
                                        FrostedSurface(Modifier.fillMaxWidth().height(64.dp), source = backing, shadow = false) {
                                            val material = requireNotNull(LocalFrostedBackdrop.current)
                                            SideEffect { nested = material }
                                            LiquidGlassSurface(Modifier.size(40.dp), pressFeedback = false, shadow = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val sourceOwner = compose.runOnIdle { source }
        val layer = compose.runOnIdle { source.layer }
        val nestedOwner = compose.runOnIdle { nested }
        val sceneOwner = compose.runOnIdle { selection }
        val entries = compose.runOnIdle { selection.regions.toMap() }
        assertEquals(2, entries.size)
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { down(center); held = true }
            compose.mainClock.advanceTimeBy(300)
            var previousLegacy: Bitmap? = null
            var previousLegacyGeneration: GlassRendererGeneration? = null
            val phases = listOf(GlassRendererMode.Legacy, GlassRendererMode.Modern, GlassRendererMode.Legacy,
                GlassRendererMode.Automatic, GlassRendererMode.Legacy)
            for ((index, mode) in phases.withIndex()) {
                val beforeGeneration = compose.runOnIdle { runtime.generation }
                val beforeOptics = optics.get()
                compose.runOnIdle {
                    appearance = if (index == 2) appearance.copy(blur = .2f, whiteTint = .3f, contrast = .2f)
                        else appearance.copy(blur = 0f, whiteTint = 0f, contrast = 0f)
                    runtime.request(mode)
                }
                compose.mainClock.advanceTimeBy(64)
                if (runtime.generation.backend == GlassBackend.Legacy) {
                    compose.waitUntil(10_000) {
                        compose.mainClock.advanceTimeByFrame()
                        preparedForCurrentAppearance() && nested.snapshot != null
                    }
                    compose.runOnIdle {
                        val image = requireNotNull(source.snapshot).asAndroidBitmap()
                        assertEquals(Bitmap.Config.ARGB_8888, image.config)
                        assertEquals(logical.width, image.width)
                        assertEquals(logical.height, image.height)
                        assertEquals("Legacy records the logical extent, not a Modern allocation bucket", logical, source.layer.size)
                        assertNotEquals(0, logical.width % 64)
                        assertNotEquals(0, logical.height % 64)
                        assertEquals("Logical edge is not padded/scaled", android.graphics.Color.BLUE,
                            image.getPixel(image.width - 1, image.height - 1))
                        assertEquals(android.graphics.Color.RED, image.getPixel(0, 0))
                        if (previousLegacyGeneration != null && previousLegacyGeneration != runtime.generation)
                            assertNotSame("A→B→A needs a fresh-generation sample", previousLegacy, image)
                        previousLegacy = image
                        previousLegacyGeneration = runtime.generation
                        val target = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        try { android.graphics.Canvas(target).drawBitmap(image, 0f, 0f, null) }
                        finally { target.recycle() }
                    }
                }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle {
                    assertSame(sourceOwner, source)
                    assertSame(layer, source.layer)
                    assertSame(nestedOwner, nested)
                    assertSame(sceneOwner, selection)
                    assertEquals(entries.keys, selection.regions.keys)
                    for ((key, entry) in entries) assertSame(entry, selection.regions[key])
                    assertTrue(selection.regions.values.all { it.selected })
                    assertEquals(mode, runtime.requested)
                    if (beforeGeneration != runtime.generation)
                        assertTrue("An effective backend change reaches the held control's optical draw", optics.get() > beforeOptics)
                    if (Build.VERSION.SDK_INT < 33) assertEquals(GlassBackend.Legacy, runtime.generation.backend)
                }
                compose.onNodeWithTag("renderer-first").assertIsSelected()
                compose.onNodeWithTag("renderer-second").assertIsSelected()
            }
            val beforeFastReturn = compose.runOnIdle {
                val oldGeneration = runtime.generation
                val oldImage = requireNotNull(source.snapshot)
                val oldLens = requireNotNull(source.lensSnapshot)
                assertEquals(GlassBackend.Legacy, oldGeneration.backend)
                // No frame or recomposition between these writes: checking only the final
                // backend would incorrectly admit the old Legacy generation.
                runtime.request(GlassRendererMode.Modern)
                runtime.request(GlassRendererMode.Legacy)
                if (Build.VERSION.SDK_INT >= 33) {
                    assertEquals(oldGeneration.epoch + 2L, runtime.generation.epoch)
                    assertNull("Fast A→B→A retires old raw pixels immediately", source.snapshot)
                    assertNull("Fast A→B→A retires old prepared pixels immediately", source.lensSnapshot)
                    assertFalse("The old native recording cannot attest the new generation", source.recorded)
                } else {
                    assertSame("Unsupported Modern is not an effective transition", oldGeneration, runtime.generation)
                    assertSame(oldImage, source.snapshot)
                    assertSame(oldLens, source.lensSnapshot)
                    assertTrue(source.recorded)
                }
                assertSame(sourceOwner, source)
                assertSame(layer, source.layer)
                assertSame(sceneOwner, selection)
                oldImage
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                preparedForCurrentAppearance() && nested.snapshot != null
            }
            compose.runOnIdle {
                if (Build.VERSION.SDK_INT >= 33)
                    assertNotSame("A new admitted generation publishes a new sample", beforeFastReturn, source.snapshot)
                assertEquals(logical, source.layer.size)
                assertTrue(source.recorded)
                assertSame(nestedOwner, nested)
                for ((key, entry) in entries) assertSame(entry, selection.regions[key])
            }
            compose.runOnIdle { assertTrue("The held native control actually submitted optical draws", optics.get() > 0) }
        } finally {
            try { if (held) compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { cancel() } }
            finally { compose.mainClock.autoAdvance = true }
        }
    }
}
