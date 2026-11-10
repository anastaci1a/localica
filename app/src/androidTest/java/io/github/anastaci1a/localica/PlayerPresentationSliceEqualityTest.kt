package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import io.github.anastaci1a.localica.ui.spec.ResolvedUiSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Matched actual-player captures. Evidence is retained before policy, visibility and disposal assertions. */
class PlayerPresentationSliceEqualityTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()
    private val progress = Animatable(1f)
    private var layout by mutableStateOf(ArtworkLayout.FullBackground)
    private var lyrics by mutableStateOf(false)
    private var checkpoint by mutableIntStateOf(0)
    private var oracle by mutableStateOf<Oracle?>(null)
    private lateinit var scope: CoroutineScope
    private lateinit var density: Density
    private lateinit var view: View
    private var rootSize = IntSize.Zero
    private var insetTop = 0
    private var insetBottom = 0
    private var probe: PlayerPresentationHarnessSnapshot? = null
    private var bindings: Bindings? = null
    private var ink: PlayerInkComposition? = null
    private var inkGeneration = -1
    private val polarity = PlayerBackgroundPolarityState()
    private var oracleDescriptor: PlayerBackdropDescriptor? = null

    private data class Bindings(val context: EngineContext, val spec: ResolvedUiSpec, val clock: UiClock, val owner: UiClock?)
    private data class Oracle(val width: Int, val height: Int, val blend: Float)
    private data class Snapshot(val generation: Int, val phaseBits: Int, val window: List<Float>,
        val probe: PlayerPresentationHarnessSnapshot?, val inkGeneration: Int, val ink: PlayerInkComposition?,
        val lightMix: Float, val background: Rect?, val viewport: Rect?, val retained: PlayerBackgroundGeometry?,
        val roleCount: Int, val roleSourceMatches: Boolean, val actualDescriptorMatches: Boolean)

    @Test fun normalFramesKeepExactAdmissionGeometryAndPixels() = exercise("Normal", false, false)
    @Test fun fasterFramesKeepExactAdmissionGeometryAndPixels() = exercise("Faster", false, true)
    @Test fun reducedFramesKeepExactAdmissionGeometryAndPixelsWithVisibleControls() = exercise("Reduced", true, false)

    @Composable private fun ObserveHarness(generation: Int) {
        val context = LocalEngineContext.current
        val spec = LocalUiSpec.current
        val clock = LocalUiClock.current
        val owner = LocalClockOwner.current
        val localReduced = LocalReducedMotion.current
        val localDensity = LocalDensity.current
        val appearance = LocalGlassAppearance.current
        SideEffect {
            bindings = Bindings(context, spec, clock, owner)
            probe = PlayerPresentationHarnessSnapshot(generation, context.access.name, spec.spec.access.name,
                clock.context?.access?.name ?: "Unapplied", clock.spec.access.name, localReduced, clock === owner,
                context.displayIntervalSeconds, context.fullPlayerReferenceTravelPx, localDensity.density,
                localDensity.fontScale, appearance.renderer.name, appearance.contrast)
        }
    }

    private fun exercise(policy: String, reduced: Boolean, faster: Boolean) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val navy = Color(0xFF152238)
        val artwork = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF152238.toInt()) }
        val visual = PlayerVisual(artwork.asImageBitmap(), PlayerPalette(navy, navy, navy))
        val sourceHash = bitmapHash(artwork)
        val grainHash = bitmapHash(playerGrain)
        compose.setContent { LocalicaTheme {
            val actualDensity = LocalDensity.current
            val actualView = LocalView.current
            val top = WindowInsets.safeDrawing.getTop(actualDensity)
            val bottom = WindowInsets.safeDrawing.getBottom(actualDensity)
            val generation = checkpoint
            SideEffect { density = actualDensity; view = actualView; insetTop = top; insetBottom = bottom }
            val backdrop = rememberFrostedBackdrop(Unit, enabled = false, dark = true, lightMix = 1f, retainRaster = false)
            val appearance = GlassAppearance(renderer = GlassRendererMode.None, contrast = 0f)
            CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalReducedMotion provides reduced,
                LocalGlassAppearance provides appearance,
                LocalPlayerPresentationHarnessProbe provides { ObserveHarness(generation) },
                LocalPlayerInkCompositionObserver provides { value -> ink = value; inkGeneration = generation }) {
                Box(Modifier.fillMaxSize().background(navy).safeDrawingPadding()) {
                    BoxWithConstraints(Modifier.fillMaxSize().testTag("slice-i-capture")
                        .onGloballyPositioned { rootSize = it.findRootCoordinates().size }) {
                        scope = rememberCoroutineScope()
                        PlayerSurface(PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000),
                            true, {}, InterfaceSettings(reducedMotion = reduced, fasterMotion = faster, artworkLayout = layout,
                                glass = appearance), progress, {}, {}, {}, {}, {}, {}, lyricsOpen = lyrics, visual = visual,
                            backgroundPolarity = polarity) {
                            Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("slice-i-action-ink"))
                        }
                        oracle?.let { recipe ->
                            val actual = requireNotNull(bindings)
                            CompositionLocalProvider(LocalEngineContext provides actual.context, LocalUiSpec provides actual.spec,
                                LocalUiClock provides actual.clock, LocalClockOwner provides actual.owner,
                                androidx.compose.material3.LocalContentColor provides (ink?.contentColor ?: Color.White)) {
                                Layout(modifier = Modifier.fillMaxSize(), content = {
                                    PlayerBackdrop(visual.palette, Modifier.fillMaxSize(), fullArtBlend = recipe.blend,
                                        tone = GlassTone(), ready = true, onBackgroundDescriptor = { oracleDescriptor = it })
                                }) { children, constraints ->
                                    val child = children.single().measure(Constraints.fixed(recipe.width, recipe.height))
                                    layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, 0) }
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runtimeSource = runCatching {
            instrumentation.targetContext.classLoader.loadClass("io.github.anastaci1a.localica.BuildConfig")
                .getDeclaredField("SOURCE_SHA").get(null) as String
        }.getOrElse { "Unavailable:${it.javaClass.name}" }
        val installedApp = fileHash(File(instrumentation.targetContext.applicationInfo.sourceDir))
        val installedTest = fileHash(File(instrumentation.context.applicationInfo.sourceDir))
        val output = nativeEvidenceDirectory("player-presentation-slice-i", instrumentation.targetContext.getExternalFilesDir(null))
        val receipts = JSONArray()
        val phases = listOf(0f, .7f, .72f, .73f, .78f, .8f, .83f, .84f, .9f, .97f, 1f, .9f, .8f, .72f, 0f, 1f)
        for (artworkLayout in ArtworkLayout.entries) for (selected in listOf(false, true)) {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { layout = artworkLayout; lyrics = selected; scope.launch { progress.snapTo(1f) } }
            compose.waitForIdle()
            if (selected) compose.waitUntil(10000) { nodes("empty-lyrics").isNotEmpty() }
            compose.mainClock.autoAdvance = false
            phases.forEachIndexed { index, requested ->
                compose.runOnIdle { checkpoint++; scope.launch { progress.snapTo(requested) } }
                compose.waitForIdle()
                val captureNode = compose.onNodeWithTag("slice-i-capture", useUnmergedTree = true)
                val capture = captureNode.fetchSemanticsNode().boundsInRoot
                val tags = listOf("player-surface", "player-background-space", "player-title", "player-artist", "player-artwork",
                    "player-handle", "player-actions-row", "slice-i-action-ink", "player-seek-row", "player-transport-row", "lyrics-panel", "empty-lyrics")
                val geometry = tags.associateWith { tag -> nodes(tag).map { it.boundsInRoot } }
                val before = snapshot(visual)
                val image = captureNode.captureToImage()
                val display = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val afterOriginal = snapshot(visual)
                val name = "api${Build.VERSION.SDK_INT}-$policy-${artworkLayout.name}-$selected-$index"
                save(File(output, "$name.png"), image.asAndroidBitmap())
                save(File(output, "$name-display.png"), display)
                val record = JSONObject().put("name", name).put("source", BuildConfig.SOURCE_SHA)
                    .put("runtimeAppSource", runtimeSource).put("installedAppSha256", installedApp).put("installedTestSha256", installedTest)
                    .put("api", Build.VERSION.SDK_INT).put("policy", policy).put("requestedPhaseBits", requested.toRawBits())
                    .put("capture", rect(capture)).put("before", snapshotJson(before)).put("afterOriginal", snapshotJson(afterOriginal))
                    .put("sourceBitmapSha256", sourceHash).put("grainBitmapSha256", grainHash)
                    .put("displaySize", JSONArray(listOf(display.width, display.height)))
                    .put("geometry", JSONObject().apply { geometry.forEach { (tag, boxes) -> put(tag, JSONArray(boxes.map(::rect))) } })
                File(output, "$name.json").writeText(record.toString(2))
                var restored: ImageBitmap? = null
                var oracleObserved: Snapshot? = null
                if (requested == 1f) {
                    val background = before.background
                    val recipe = Oracle(background?.width?.toInt() ?: image.width, background?.height?.toInt() ?: image.height,
                        if (artworkLayout == ArtworkLayout.FullBackground && !selected) 1f else 0f)
                    compose.runOnIdle { oracleDescriptor = null; oracle = recipe }
                    compose.waitForIdle()
                    val backing = captureNode.captureToImage()
                    val oracleDisplay = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val oracleState = snapshot(visual)
                    oracleObserved = oracleState
                    val descriptorMatches = compose.runOnIdle { oracleDescriptor?.let { descriptorMatches(it, visual) } ?: false }
                    save(File(output, "$name-oracle.png"), backing.asAndroidBitmap())
                    save(File(output, "$name-oracle-display.png"), oracleDisplay)
                    compose.runOnIdle { oracle = null }
                    compose.waitForIdle()
                    restored = captureNode.captureToImage()
                    val restoredDisplay = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    save(File(output, "$name-restored.png"), restored.asAndroidBitmap())
                    save(File(output, "$name-restored-display.png"), restoredDisplay)
                    record.put("oracle", JSONObject().put("width", recipe.width).put("height", recipe.height).put("blend", recipe.blend)
                        .put("state", snapshotJson(oracleState)).put("descriptorMatches", descriptorMatches))
                    val handle = geometry.getValue("player-handle").singleOrNull()
                    val action = geometry.getValue("slice-i-action-ink").singleOrNull()
                    val lyric = geometry.getValue("empty-lyrics").singleOrNull()
                    record.put("handleContrast", handle?.let { handleContrast(image, capture, it) } ?: false)
                        .put("handleDifference", difference(image, backing, capture, handle))
                        .put("actionPaint", painted(image, capture, action, true)).put("actionDifference", difference(image, backing, capture, action))
                        .put("lyricsPaint", painted(image, capture, lyric, false)).put("lyricsDifference", difference(image, backing, capture, lyric))
                }
                val after = snapshot(visual)
                record.put("after", snapshotJson(after))
                File(output, "$name.json").writeText(record.toString(2))
                receipts.put(record)
                // All images and immutable receipts above survive an assertion failure below.
                assertEquals("The target application's runtime source is the intended source", BuildConfig.SOURCE_SHA, runtimeSource)
                listOfNotNull(before, afterOriginal, oracleObserved, after).forEach { actual ->
                    assertEquals("Actual held phase is the requested phase", requested.toRawBits(), actual.phaseBits)
                    val actualProbe = requireNotNull(actual.probe)
                    assertEquals("The provider observation is fresh", actual.generation, actualProbe.generation)
                    assertEquals("The actual Surface ink observation is fresh", actual.generation, actual.inkGeneration)
                    val expected = if (reduced) "Reduced" else if (faster) "Faster" else "Standard"
                    assertEquals(expected, actualProbe.contextAccess); assertEquals(expected, actualProbe.specAccess)
                    assertEquals(expected, actualProbe.clockAccess); assertEquals(expected, actualProbe.clockSpecAccess)
                    assertEquals(reduced, actualProbe.localReduced); assertTrue(actualProbe.ownsRootClock)
                    assertEquals("None", actualProbe.renderer); assertEquals(0f, actualProbe.contrast, 0f)
                    val profile = if (Build.VERSION.SDK_INT == 26) listOf(1080f, 1920f, 2.625f, 1f) else listOf(720f, 1280f, 1.75f, 1f)
                    assertEquals("The real window uses its declared profile", profile, actual.window.take(4))
                    assertTrue(capture.top >= actual.window[4]); assertTrue(capture.bottom <= actual.window[1] - actual.window[5])
                }
                geometry.forEach { (tag, boxes) -> assertTrue("$tag has unique ownership", boxes.size <= 1) }
                assertEquals("The handle disposes at its original boundary", if (requested > .72f) 1 else 0, geometry.getValue("player-handle").size)
                assertEquals("The actions row disposes at its original boundary", if (!selected && requested > .83f) 1 else 0, geometry.getValue("player-actions-row").size)
                if (requested == 1f) {
                    val background = requireNotNull(before.background)
                    assertEquals(0f, background.left, 0f); assertEquals(0f, background.top, 0f)
                    assertEquals(Rect(0f, 0f, capture.width, capture.height), before.viewport)
                    assertEquals(capture, geometry.getValue("player-surface").single())
                    assertEquals(capture.width, background.width, 0f)
                    assertEquals(background.width, background.width.toInt().toFloat(), 0f)
                    assertEquals(background.height, background.height.toInt().toFloat(), 0f)
                    assertTrue(before.roleSourceMatches); assertTrue(before.actualDescriptorMatches); assertEquals(1, before.roleCount)
                    assertTrue(record.getJSONObject("oracle").getBoolean("descriptorMatches"))
                    assertEquals("The restored original is unchanged", 0, difference(image, requireNotNull(restored), capture, capture))
                    assertTrue("$policy paints its handle", record.getBoolean("handleContrast") && record.getInt("handleDifference") > 0)
                    compose.onNodeWithTag("player-handle", true).assertIsDisplayed()
                    if (selected) {
                        assertEquals(1f, requireNotNull(before.ink).lyricsProgress, 0f)
                        assertEquals(0f, playerLyricsMotion(1f, requireNotNull(before.ink).lyricsProgress).artworkAlpha, 0f)
                        assertTrue("$policy paints its lyrics icon", record.getInt("lyricsPaint") > 0 && record.getInt("lyricsDifference") > 0)
                        compose.onNodeWithTag("empty-lyrics", true).assertIsDisplayed()
                    } else {
                        assertNotNull(before.retained)
                        assertEquals(record.getJSONObject("oracle").getDouble("blend").toFloat(), requireNotNull(before.retained).fullArtBlend, 0f)
                        assertTrue(requireNotNull(before.retained).cover.top + capture.top > geometry.getValue("player-handle").single().bottom)
                        assertTrue("$policy paints its action row", record.getInt("actionPaint") > 0 && record.getInt("actionDifference") > 0)
                        compose.onNodeWithTag("slice-i-action-ink", true).assertIsDisplayed()
                    }
                }
            }
        }
        File(output, "api${Build.VERSION.SDK_INT}-$policy.json").writeText(JSONObject()
            .put("source", BuildConfig.SOURCE_SHA).put("api", Build.VERSION.SDK_INT).put("policy", policy).put("frames", receipts).toString(2))
    }

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private fun snapshot(visual: PlayerVisual): Snapshot = compose.runOnIdle {
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val frame = polarity.frame
        val roles = frame?.roles.orEmpty().filter { !it.soft }
        Snapshot(checkpoint, progress.value.toRawBits(), listOf(rootSize.width.toFloat(), rootSize.height.toFloat(),
            density.density, density.fontScale, insetTop.toFloat(), insetBottom.toFloat(), origin[0].toFloat(), origin[1].toFloat()),
            probe, inkGeneration, ink, polarity.lightMix, polarity.measured["background"], polarity.measured["viewport"],
            polarity.settledEndpoint?.let { it.copy(footprints = it.footprints.toList()) }, roles.size,
            roles.size == 1 && roles.single().visual.palette == visual.palette && roles.single().weight == 1f &&
                frame != null && !frame.transitioning && !frame.liveBlur && !frame.frozenVisible,
            roles.size == 1 && polarity.backdrops[roles.single().identity]?.let { descriptorMatches(it, visual) } == true)
    }
    private fun descriptorMatches(value: PlayerBackdropDescriptor, visual: PlayerVisual) =
        value.palette == visual.palette && value.grain === playerGrain && value.wash == null && value.softWash == null && value.tone == GlassTone()
    private fun snapshotJson(value: Snapshot) = JSONObject().put("generation", value.generation).put("actualPhaseBits", value.phaseBits)
        .put("window", JSONArray(value.window)).put("probe", value.probe?.let { p -> JSONObject()
            .put("generation", p.generation).put("contextAccess", p.contextAccess).put("specAccess", p.specAccess)
            .put("clockAccess", p.clockAccess).put("clockSpecAccess", p.clockSpecAccess).put("localReduced", p.localReduced)
            .put("ownsRootClock", p.ownsRootClock).put("displayIntervalSeconds", p.displayIntervalSeconds)
            .put("referenceTravelPx", p.referenceTravelPx).put("density", p.density).put("fontScale", p.fontScale)
            .put("renderer", p.renderer).put("contrast", p.contrast) } ?: JSONObject.NULL)
        .put("inkGeneration", value.inkGeneration).put("ink", value.ink?.let { i -> JSONObject()
            .put("rgba", JSONArray(listOf(i.contentColor.red, i.contentColor.green, i.contentColor.blue, i.contentColor.alpha)))
            .put("lyricsVisibility", i.lyricsProgress).put("decisionLightInk", i.decision.lightInk) } ?: JSONObject.NULL)
        .put("lightMix", value.lightMix).put("background", value.background?.let(::rect) ?: JSONObject.NULL)
        .put("viewport", value.viewport?.let(::rect) ?: JSONObject.NULL).put("retained", value.retained?.let(::retainedGeometry) ?: JSONObject.NULL)
        .put("roleCount", value.roleCount).put("roleSourceMatches", value.roleSourceMatches).put("actualDescriptorMatches", value.actualDescriptorMatches)
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun retainedGeometry(value: PlayerBackgroundGeometry): JSONObject {
        val scale = value.contentScale.computeScaleFactor(Size(32f, 32f), value.cover.size)
        return JSONObject().put("size", JSONArray(listOf(value.size.width, value.size.height))).put("cover", rect(value.cover))
            .put("washCover", rect(value.washCover)).put("footprints", JSONArray(value.footprints.map(::rect)))
            .put("fields", JSONArray(listOf(value.fullArtBlend, value.softness, value.edgeSoftness, value.edgeBand,
                value.coverBlend, value.artworkAlpha, value.corner, scale.scaleX, scale.scaleY)))
    }
    private fun region(image: ImageBitmap, capture: Rect, target: Rect?): IntArray? = target?.let {
        intArrayOf((it.left - capture.left).roundToInt(), (it.top - capture.top).roundToInt(),
            (it.right - capture.left).roundToInt(), (it.bottom - capture.top).roundToInt()).takeIf { r ->
            r[0] >= 0 && r[1] >= 0 && r[2] <= image.width && r[3] <= image.height && r[2] > r[0] && r[3] > r[1] }
    }
    private fun difference(image: ImageBitmap, other: ImageBitmap, capture: Rect, target: Rect?): Int {
        if (image.width != other.width || image.height != other.height) return -1
        val area = region(image, capture, target) ?: return 0
        val a = image.toPixelMap(); val b = other.toPixelMap(); var count = 0
        for (y in area[1] until area[3]) for (x in area[0] until area[2]) if (a[x, y] != b[x, y]) count++
        return count
    }
    private fun handleContrast(image: ImageBitmap, capture: Rect, bounds: Rect): Boolean {
        val pixels = image.toPixelMap()
        val x = (bounds.center.x - capture.left).roundToInt().coerceIn(0, image.width - 1)
        val y = (bounds.center.y - capture.top).roundToInt().coerceIn(0, image.height - 1)
        val below = (bounds.bottom - capture.top + 3f).roundToInt().coerceIn(0, image.height - 1)
        return pixels[x, y] != pixels[x, below]
    }
    private fun painted(image: ImageBitmap, capture: Rect, target: Rect?, action: Boolean): Int {
        val area = region(image, capture, target) ?: return 0
        val pixels = image.toPixelMap(); var count = 0
        for (y in area[1] until area[3]) for (x in area[0] until area[2]) {
            val c = pixels[x, y]
            if (if (action) c.red > c.green + .05f && c.blue > c.green + .05f else c.red > .15f && c.green > .15f && c.blue > .15f) count++
        }
        return count
    }
    private fun save(file: File, image: Bitmap) = file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun bitmapHash(image: Bitmap): String {
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        val bytes = ByteBuffer.allocate(pixels.size * 4).apply { pixels.forEach { pixel -> putInt(pixel) } }.array()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun fileHash(file: File): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
