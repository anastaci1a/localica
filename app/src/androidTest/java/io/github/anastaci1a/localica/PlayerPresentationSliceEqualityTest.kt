package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.LocalClockOwner
import io.github.anastaci1a.localica.ui.engine.LocalEngineContext
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import io.github.anastaci1a.localica.ui.engine.UiClock
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Matched actual-player captures. All policies claim exact geometry and pixels; Reduced also requires painted controls. */
class PlayerPresentationSliceEqualityTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()
    private val progress = Animatable(1f)
    private var layout by mutableStateOf(ArtworkLayout.FullBackground)
    private var lyrics by mutableStateOf(false)
    private lateinit var scope: CoroutineScope
    private lateinit var density: Density
    private var rootSize = IntSize.Zero
    private var insetTop = 0
    private var insetBottom = 0
    private var actualClock: UiClock? = null
    private var localAccess = ""
    private var specAccess = ""
    private var ownsRootClock = false
    private var localReduced = false

    @Test fun normalFramesKeepExactAdmissionGeometryAndPixels() = exercise("Normal", false, false)
    @Test fun fasterFramesKeepExactAdmissionGeometryAndPixels() = exercise("Faster", false, true)
    @Test fun reducedFramesKeepExactAdmissionGeometryAndPixelsWithVisibleControls() = exercise("Reduced", true, false)

    private fun exercise(policy: String, reduced: Boolean, faster: Boolean) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val navy = Color(0xFF152238)
        val visual = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(21, 34, 56))
        }.asImageBitmap(), PlayerPalette(navy, navy, navy))
        compose.setContent { LocalicaTheme {
            val actualDensity = LocalDensity.current
            val top = WindowInsets.safeDrawing.getTop(actualDensity)
            val bottom = WindowInsets.safeDrawing.getBottom(actualDensity)
            SideEffect { density = actualDensity; insetTop = top; insetBottom = bottom }
            val backdrop = rememberFrostedBackdrop(Unit, enabled = false, dark = true, lightMix = 1f, retainRaster = false)
            CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalReducedMotion provides reduced) {
                Box(Modifier.fillMaxSize().background(navy).safeDrawingPadding()) {
                    BoxWithConstraints(Modifier.fillMaxSize().testTag("slice-i-capture")
                        .onGloballyPositioned { rootSize = it.findRootCoordinates().size }) {
                        scope = rememberCoroutineScope()
                        PlayerSurface(PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000),
                            true, {}, InterfaceSettings(reducedMotion = reduced, fasterMotion = faster, artworkLayout = layout,
                                glass = GlassAppearance(renderer = GlassRendererMode.None)),
                            progress, {}, {}, {}, {}, {}, {}, lyricsOpen = lyrics, visual = visual) {
                            val clock = LocalUiClock.current
                            val rootClock = LocalClockOwner.current
                            val context = LocalEngineContext.current
                            val spec = LocalUiSpec.current
                            val appliedReduced = LocalReducedMotion.current
                            SideEffect {
                                actualClock = clock; localAccess = context.access.name
                                specAccess = spec.spec.access.name; ownsRootClock = clock === rootClock
                                localReduced = appliedReduced
                            }
                            Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("slice-i-action-ink"))
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Directory creation and all serialization remain on the instrumentation thread.
        val output = nativeEvidenceDirectory("player-presentation-slice-i", instrumentation.targetContext.getExternalFilesDir(null))
        val receipts = JSONArray()
        val phases = listOf(0f, .7f, .72f, .73f, .78f, .8f, .83f, .84f, .9f, .97f, 1f, .9f, .8f, .72f, 0f, 1f)
        for (artworkLayout in ArtworkLayout.entries) for (selected in listOf(false, true)) {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { layout = artworkLayout; lyrics = selected; scope.launch { progress.snapTo(1f) } }
            compose.waitForIdle()
            if (selected) compose.waitUntil(10000) { nodes("empty-lyrics").isNotEmpty() }
            compose.mainClock.autoAdvance = false
            phases.forEachIndexed { index, p ->
                compose.runOnIdle { scope.launch { progress.snapTo(p) } }
                compose.waitForIdle()
                val captureNode = unique("slice-i-capture")
                val captureBounds = captureNode.fetchSemanticsNode().boundsInRoot
                val window = compose.runOnIdle {
                    listOf(rootSize.width.toFloat(), rootSize.height.toFloat(), density.density, density.fontScale,
                        insetTop.toFloat(), insetBottom.toFloat(), progress.value)
                }
                val engine = compose.runOnIdle {
                    val expected = if (reduced) "Reduced" else if (faster) "Faster" else "Standard"
                    val context = requireNotNull(requireNotNull(actualClock).context)
                    assertEquals(expected, localAccess)
                    assertEquals(expected, specAccess)
                    assertEquals(expected, context.access.name)
                    assertEquals(expected, requireNotNull(actualClock).spec.access.name)
                    assertEquals(reduced, localReduced)
                    assertTrue("The actual player consumes its root clock", ownsRootClock)
                    listOf(localAccess, specAccess, context.access.name, ownsRootClock.toString(),
                        context.displayIntervalSeconds.toString(), context.fullPlayerReferenceTravelPx.toString(),
                        requireNotNull(actualClock).spec.access.name, localReduced.toString())
                }
                assertTrue("The complete capture begins below the real native inset", captureBounds.top >= window[4])
                assertTrue("The complete capture ends above the real native inset", captureBounds.bottom <= window[1] - window[5])
                val tags = listOf("player-surface", "player-background-space", "player-title", "player-artist",
                    "player-handle", "player-actions-row", "player-seek-row", "player-transport-row", "lyrics-panel", "empty-lyrics")
                val geometry = JSONObject()
                for (tag in tags) {
                    val matches = nodes(tag)
                    assertTrue("$tag has unique semantic ownership", matches.size <= 1)
                    geometry.put(tag, matches.singleOrNull()?.boundsInRoot?.let(::rect) ?: JSONObject.NULL)
                }
                if (p == 1f) {
                    unique("player-handle").assertIsDisplayed()
                    if (selected) unique("empty-lyrics").assertIsDisplayed()
                    else unique("slice-i-action-ink").assertIsDisplayed()
                }
                val image = captureNode.captureToImage()
                val handlePaint = if (p == 1f) handleContrast(image, captureBounds) else JSONObject.NULL
                val actionPaint = if (p == 1f && !selected) magentaPixels(image) else 0
                val lyricsPaint = if (p == 1f && selected) lyricsPixels(image, captureBounds) else 0
                if (p == 1f) {
                    assertTrue("$policy paints the revealed handle", handlePaint == true)
                    if (selected) assertTrue("$policy paints the revealed lyrics icon", lyricsPaint > 0)
                    else assertTrue("$policy paints the revealed action row", actionPaint > 0)
                }
                val name = "api${Build.VERSION.SDK_INT}-$policy-${artworkLayout.name}-$selected-$index"
                File(output, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                receipts.put(JSONObject().put("name", name).put("phaseBits", p.toRawBits()).put("window", JSONArray(window))
                    .put("engine", JSONArray(engine))
                    .put("capture", rect(captureBounds)).put("geometry", geometry).put("handlePaint", handlePaint)
                    .put("actionPaint", actionPaint).put("lyricsPaint", lyricsPaint))
            }
        }
        File(output, "api${Build.VERSION.SDK_INT}-$policy.json").writeText(JSONObject()
            .put("source", BuildConfig.SOURCE_SHA).put("api", Build.VERSION.SDK_INT).put("policy", policy)
            .put("frames", receipts).toString(2))
    }

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private fun unique(tag: String): SemanticsNodeInteraction {
        assertEquals("$tag has exactly one semantic owner", 1, nodes(tag).size)
        return compose.onNodeWithTag(tag, useUnmergedTree = true)
    }
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))

    private fun handleContrast(image: ImageBitmap, capture: Rect): Boolean {
        val bounds = unique("player-handle").fetchSemanticsNode().boundsInRoot
        val pixels = image.toPixelMap()
        val x = (bounds.center.x - capture.left).roundToInt().coerceIn(0, image.width - 1)
        val y = (bounds.center.y - capture.top).roundToInt().coerceIn(0, image.height - 1)
        val backingY = (bounds.bottom - capture.top + 3f).roundToInt().coerceIn(0, image.height - 1)
        return pixels[x, y] != pixels[x, backingY]
    }

    private fun magentaPixels(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = pixels[x, y]
            if (c.red > c.green + .05f && c.blue > c.green + .05f) count++
        }
        return count
    }

    private fun lyricsPixels(image: ImageBitmap, capture: Rect): Int {
        val bounds = unique("empty-lyrics").fetchSemanticsNode().boundsInRoot
        val pixels = image.toPixelMap()
        var count = 0
        val left = (bounds.left - capture.left).roundToInt().coerceIn(0, image.width)
        val right = (bounds.right - capture.left).roundToInt().coerceIn(left, image.width)
        val top = (bounds.top - capture.top).roundToInt().coerceIn(0, image.height)
        val bottom = (bounds.bottom - capture.top).roundToInt().coerceIn(top, image.height)
        for (y in top until bottom) for (x in left until right) {
            val c = pixels[x, y]
            if (c.red > .15f && c.green > .15f && c.blue > .15f) count++
        }
        return count
    }
}
