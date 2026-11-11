package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import android.view.Window
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Byte-identical production-path observer on independently frozen before/after sources. */
class ReleaseLintAdoptionTest {
    @get:Rule val compose = localicaComposeRule()
    private val output by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(nativeEvidenceDirectory("release-lint-adoption", context.getExternalFilesDir(null)),
            "api${Build.VERSION.SDK_INT}").apply { mkdirs() }
    }
    private fun bits(value: Float) = value.toRawBits()
    @Composable private fun ControlledScene(content: @Composable () -> Unit) {
        val backdrop = rememberFrostedBackdrop(Unit, dark = false, lightMix = 0f)
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Magenta)) {
            GlassPanelTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backdrop)) { drawRect(Color.White) }
                    CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalModalBackdrop provides backdrop,
                        androidx.compose.material3.LocalContentColor provides Color.Black, content = content)
                }
            }
        }
    }
    private fun geometry(node: androidx.compose.ui.semantics.SemanticsNode): JSONObject {
        val bounds = node.boundsInRoot
        val density = node.layoutInfo.density
        return JSONObject().put("left", bits(bounds.left)).put("top", bits(bounds.top))
            .put("right", bits(bounds.right)).put("bottom", bits(bounds.bottom))
            .put("density", bits(density.density)).put("fontScale", bits(density.fontScale))
    }
    private fun capture(node: SemanticsNodeInteraction, name: String): Bitmap {
        val measured = node.fetchSemanticsNode()
        val view = (measured.root as ViewRootForTest).view
        val dialog = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().singleOrNull()?.window
        assertEquals("Capture must use the selected node's actual activity or GlassDialog window",
            name.startsWith("settings-") || name == "excluded-dialog", dialog != null)
        val window = dialog ?: view.context.activityWindow()
        val crop = android.graphics.Rect(measured.boundsInRoot.left.roundToInt(), measured.boundsInRoot.top.roundToInt(),
            measured.boundsInRoot.right.roundToInt(), measured.boundsInRoot.bottom.roundToInt())
        val safe = compose.runOnUiThread {
            val visible = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val bounds = measured.boundsInRoot
            assertTrue("Capture is within native safe app space", bounds.left + origin[0] >= visible.left &&
                bounds.top + origin[1] >= visible.top && bounds.right + origin[0] <= visible.right &&
                bounds.bottom + origin[1] <= visible.bottom)
            val inWindow = IntArray(2).also(view::getLocationInWindow)
            crop.offset(inWindow[0], inWindow[1])
            JSONObject().put("visible", visible.toShortString()).put("originX", origin[0]).put("originY", origin[1])
                .put("nativeDialogWindow", dialog != null).put("nativeWindowType", window.attributes.type)
                .put("copyRect", crop.toShortString())
        }
        // Flush this real window's draw without advancing the paused Compose clock. API 26's
        // Compose capture wrapper rejects dialogs, while Window PixelCopy supports them.
        val drawn = CountDownLatch(1)
        val enabled = HardwareRendererCompat.isDrawingEnabled()
        if (!enabled) HardwareRendererCompat.setDrawingEnabled(true)
        var listener: ViewTreeObserver.OnDrawListener? = null
        val image = try {
            compose.runOnUiThread {
                listener = ViewTreeObserver.OnDrawListener { view.post { drawn.countDown() } }
                view.viewTreeObserver.addOnDrawListener(listener)
                Snapshot.sendApplyNotifications()
                view.invalidate()
            }
            check(drawn.await(5, TimeUnit.SECONDS)) { "Native window draw timed out: $crop" }
            copyNativeWindowOnce(window, Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888),
                "Native window copy timed out: $crop", crop) { "Native window copy failed: $it ($crop)" }
        } finally {
            compose.runOnUiThread { listener?.let { view.viewTreeObserver.removeOnDrawListener(it) } }
            if (!enabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
        File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(output, "$name-capture.json").writeText(geometry(measured).put("safe", safe).toString(2))
        return image
    }
    private fun Context.activityWindow(): Window = when (this) {
        is Activity -> window
        is ContextWrapper -> baseContext.activityWindow()
        else -> error("The capture node must belong to a real activity or dialog window")
    }
    private fun save(name: String, frames: JSONArray, changedPixels: Int? = null) {
        File(output, "$name.json").writeText(JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("backdropDark", false).put("backdropLightMix", bits(0f)).put("ink", "black")
            .put("nativeHighlightChangedPixels", changedPixels ?: JSONObject.NULL).put("frames", frames).toString(2))
        File(output, "source.txt").writeText(BuildConfig.SOURCE_SHA + "\n")
    }

    private fun settings(name: String, reduced: Boolean, speed: Float, interrupt: Boolean = false) {
        compose.setContent { EngineTheme(reduced = reduced) {
            ControlledScene { CompositionLocalProvider(LocalReducedMotion provides reduced, LocalMotionSpeed provides speed) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize().background(Color.White).testTag("lint-content")) {
                        FolderSettings(LibraryUiState(loading = false), {}, {}, {},
                            interfaceContent = { section -> InterfaceSettingsControls(InterfaceSettings(reducedMotion = reduced), section) {} })
                    }
                }
            } }
        } }
        compose.onNodeWithContentDescription("Search settings input").performTextInput("Control expansion")
        compose.onAllNodesWithTag("search-result:Control expansion").assertCountEquals(1)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("search-result:Control expansion").performClick()
            val frames = JSONArray()
            var visible = false
            var admitted = false
            var highlighted: Bitmap? = null
            var highlightedRow: androidx.compose.ui.geometry.Rect? = null
            var highlightedRoot: androidx.compose.ui.geometry.Rect? = null
            val positions = mutableSetOf<Int>()
            val selector = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyDescendant(hasTestTag("setting:Control expansion"))
            for (frame in 1..64) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val rows = compose.onAllNodesWithTag("setting:Control expansion", useUnmergedTree = true)
                val candidates = rows.fetchSemanticsNodes()
                assertTrue("The destination row must never have ambiguous ownership", candidates.size <= 1)
                if (candidates.isEmpty()) {
                    assertFalse("An admitted destination row must not disappear", admitted)
                    assertTrue("The destination must be admitted before the first fixed native capture", frame < 4)
                    frames.put(JSONObject().put("frame", frame).put("rowPresent", false)
                        .put("position", JSONObject.NULL).put("alpha", JSONObject.NULL)
                        .put("row", JSONObject.NULL).put("scroll", JSONObject.NULL))
                    continue
                }
                rows.assertCountEquals(1)
                admitted = true
                val row = rows.fetchSemanticsNodes().single()
                val owners = compose.onAllNodes(selector, useUnmergedTree = true)
                owners.assertCountEquals(1)
                val owner = owners.fetchSemanticsNodes().single()
                val position = owner.config[SemanticsProperties.VerticalScrollAxisRange].value()
                val alpha = row.config.getOrNull(SettingsHighlightAlpha) ?: 0f
                visible = visible || alpha > 0f
                positions += bits(position)
                frames.put(JSONObject().put("frame", frame).put("rowPresent", true).put("position", bits(position))
                    .put("alpha", bits(alpha)).put("row", geometry(row)).put("scroll", geometry(owner)))
                if (frame in listOf(4, 8, 12, 20, 32, 48, 64)) {
                    val image = capture(compose.onNode(selector, useUnmergedTree = true), "$name-$frame")
                    if (!interrupt && frame == 32) {
                        assertTrue("The positive native control captures a revealed highlight", alpha > 0f)
                        highlighted = image
                        highlightedRow = row.boundsInRoot
                        highlightedRoot = owner.boundsInRoot
                    }
                }
                if (interrupt && frame == 4) compose.onNode(selector, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
            }
            assertTrue("The destination row must be admitted", admitted)
            if (interrupt) assertFalse("A competing scroll cancels pending completion and highlight", visible)
            else {
                assertTrue("Every policy, including Reduced, must visibly reveal the selected row", visible)
                compose.onNodeWithTag("setting:Control expansion").assertIsDisplayed()
            }
            if (!reduced) assertTrue("Retain intermediate scroll positions", positions.size > 2)
            var changedPixels: Int? = null
            if (!interrupt) {
                repeat(64) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                val row = compose.onNodeWithTag("setting:Control expansion", useUnmergedTree = true).fetchSemanticsNode()
                assertEquals(0f, row.config.getOrNull(SettingsHighlightAlpha) ?: 0f, 0f)
                assertEquals("Highlight retirement must preserve row geometry", highlightedRow, row.boundsInRoot)
                val retired = capture(compose.onNode(selector, useUnmergedTree = true), "$name-retired")
                val root = compose.onNode(selector, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(highlightedRoot, root)
                val bounds = row.boundsInRoot.translate(-root.left, -root.top)
                var count = 0
                for (y in (kotlin.math.ceil(bounds.top).toInt() + 1) until kotlin.math.floor(bounds.bottom).toInt() - 1)
                    for (x in (kotlin.math.ceil(bounds.left).toInt() + 1) until kotlin.math.floor(bounds.right).toInt() - 1) {
                        val peak = requireNotNull(highlighted).getPixel(x, y)
                        val rest = retired.getPixel(x, y)
                        val red = android.graphics.Color.red(peak) - android.graphics.Color.red(rest)
                        val green = android.graphics.Color.green(peak) - android.graphics.Color.green(rest)
                        val blue = android.graphics.Color.blue(peak) - android.graphics.Color.blue(rest)
                        if ((red > 10 && blue > 10) || (red >= 0 && blue >= 0 && green < -10)) count++
                    }
                assertTrue("The actual native highlight changes visible row pixels, including Reduced", count > 0)
                changedPixels = count
            }
            save(name, frames, changedPixels)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun settingsNormal() = settings("settings-normal", false, 4f / 3f)
    @Test fun settingsFaster() = settings("settings-faster", false, 2f)
    @Test fun settingsReducedPositiveVisibility() = settings("settings-reduced", true, 4f / 3f)
    @Test fun settingsArbitrarySpeed() = settings("settings-speed1.1", false, 1.1f)
    @Test fun settingsCompetingScrollCancelsCompletion() = settings("settings-interrupted", false, 4f / 3f, true)

    private fun player(name: String, reduced: Boolean, faster: Boolean) {
        var expanded by mutableStateOf(false)
        var lyrics by mutableStateOf(false)
        lateinit var phase: Animatable<Float, *>
        val settings = InterfaceSettings(reducedMotion = reduced, fasterMotion = faster)
        compose.setContent { LocalicaTheme {
            ControlledScene { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).testTag("lint-content")) {
                    phase = remember { Animatable(0f) }
                    PlayerSurface(PlaybackState(connected = true, title = "Lint fixture", artist = "Static artist", durationMs = 125000),
                        expanded, { expanded = it }, settings, phase, {}, {}, {}, {}, {}, {},
                        lyricsOpen = lyrics, onLyrics = { lyrics = it }) {
                        Box(Modifier.fillMaxWidth().height(48.dp).background(Color.Magenta)) { Text("Visible action", color = Color.Black) }
                    }
                }
            } }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val frames = JSONArray()
            var sawVisible = false
            var sawAbsent = false
            for (part in 0..2) {
                compose.runOnIdle { expanded = true; lyrics = part == 1 }
                for (frame in 1..48) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val rows = compose.onAllNodesWithTag("player-actions-row", useUnmergedTree = true).fetchSemanticsNodes()
                    assertTrue("Unique action owner", rows.size <= 1)
                    sawVisible = sawVisible || rows.isNotEmpty()
                    sawAbsent = sawAbsent || rows.isEmpty()
                    frames.put(JSONObject().put("part", part).put("frame", frame).put("phase", bits(phase.value))
                        .put("present", rows.isNotEmpty()).put("row", rows.singleOrNull()?.let(::geometry) ?: JSONObject.NULL))
                    if (frame in listOf(4, 12, 24, 48)) {
                        val image = capture(compose.onNodeWithTag("lint-content"), "$name-$part-$frame")
                        if (part == 2 && frame == 48) {
                            val pixels = IntArray(image.width * image.height)
                            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                            assertTrue("The action must paint visible native magenta pixels, including Reduced", pixels.any {
                                android.graphics.Color.red(it) > 200 && android.graphics.Color.blue(it) > 200 && android.graphics.Color.green(it) < 80
                            })
                        }
                    }
                }
            }
            assertTrue("Actions must be positively visible, including Reduced", sawVisible)
            assertTrue("Zero-alpha actions must be absent", sawAbsent)
            compose.onNodeWithText("Visible action").assertIsDisplayed()
            save(name, frames)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun playerNormal() = player("player-normal", false, false)
    @Test fun playerFaster() = player("player-faster", false, true)
    @Test fun playerReducedPositiveVisibility() = player("player-reduced", true, false)

    @Test fun excludedFoldersNativeDialogGeometryDensityAndPixels() {
        compose.setContent { EngineTheme(reduced = true) {
            ControlledScene { CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    FolderSettings(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("content://lint-fixture", "Lint folder"))),
                        loading = false), {}, {}, {}, initialCategory = "Folders to scan")
                }
            } }
        } }
        compose.onNodeWithText("Lint folder").performClick()
        compose.onNodeWithText("Excluded subfolders").performClick()
        val face = compose.onNode(hasTestTag("glass-morph-surface") and hasAnyDescendant(hasText("Subfolder paths")), useUnmergedTree = true)
        compose.awaitExpectedWindowFocus(face)
        val editor = compose.onNodeWithText("Subfolder paths", useUnmergedTree = true).fetchSemanticsNode()
        val density = editor.layoutInfo.density
        val view = (editor.root as ViewRootForTest).view
        val receipt = compose.runOnUiThread {
            assertTrue("Observe the actual native dialog window", view.parent is DialogWindowProvider)
            assertEquals(view.resources.displayMetrics.density, density.density, 0f)
            assertEquals(view.resources.configuration.fontScale, density.fontScale, 0f)
            geometry(editor).put("nativeDensity", bits(view.resources.displayMetrics.density))
                .put("nativeFontScale", bits(view.resources.configuration.fontScale))
        }
        capture(face, "excluded-dialog")
        save("excluded-dialog", JSONArray().put(receipt))
    }
}
