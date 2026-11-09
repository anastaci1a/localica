package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native actual-player fields, compared with the existing kernel at identical measured geometry.
 * The reference is only a test canvas; no replacement player or production rendering hook. */
@OptIn(ExperimentalTestApi::class)
class PlayerHeaderShadowTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()
    private val progress = Animatable(1f)
    private var expanded by mutableStateOf(true)
    private var lyrics by mutableStateOf(true)
    private var reference by mutableStateOf(false)
    private var referenceFields by mutableStateOf(false)
    private var bodies by mutableStateOf(emptyList<Rect>())
    private lateinit var frame: PlayerSurfaceFrame
    private lateinit var density: Density
    private lateinit var scope: CoroutineScope
    private val receipts = JSONArray()
    private val failures = mutableListOf<String>()
    private var fullInkContrast = 0f
    private var captureNumber = 0
    private var statusBarBottom = 0f
    private val output by lazy {
        nativeEvidenceDirectory("player-header-shadow-709", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
    }

    private fun show() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val white = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }.asImageBitmap()
        val visual = PlayerVisual(white, PlayerPalette(Color.White, Color.White, Color.White))
        compose.setContent { LocalicaTheme {
            val appearance = GlassAppearance(shadowStrength = 1f, shadowSpread = 1f, contrast = 0f)
            CompositionLocalProvider(LocalGlassAppearance provides appearance) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
                    density = LocalDensity.current
                    statusBarBottom = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
                    scope = rememberCoroutineScope()
                    val corners = rememberDeviceCorners()
                    frame = playerSurfaceFrame(maxWidth, maxHeight,
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 80.dp, progress.value, corners)
                    PlayerSurface(PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000),
                        expanded, { expanded = it }, InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground, glass = appearance),
                        progress, {}, {}, {}, {}, {}, {}, lyricsOpen = lyrics, onLyrics = { lyrics = it }, visual = visual)
                    val caches = remember(scope) { List(2) { RetainedGlassShadow(scope) } }
                    // Entire-scene reference alpha has no tight header target. Preserve the real outer contour.
                    Canvas(Modifier.fillMaxSize()) {
                        if (!reference) return@Canvas
                        drawRect(Color.White)
                        if (referenceFields) {
                            val contour = Path().apply { addOutline(frame.windowShape().createOutline(size, layoutDirection, this@Canvas)) }
                            clipPath(contour) {
                                val alpha = ((progress.value - .72f) / .28f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
                                try {
                                    bodies.forEachIndexed { index, rect ->
                                        with(caches[index]) { draw(listOf(RoundRect(rect, CornerRadius(rect.height / 2f)) to 1f), 1f, 1f) }
                                    }
                                } finally { drawContext.canvas.restore() }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun bounds(node: SemanticsNode): Rect = compose.runOnUiThread {
        val coordinates = node.layoutInfo.coordinates
        coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
    }
    private fun control(label: String): SemanticsNode = generateSequence(
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()) { it.parent }
        .first { it.config.contains(SemanticsActions.OnClick) }
    private fun shot(name: String): Bitmap {
        compose.waitForIdle()
        val node = compose.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val root = view.rootView
        val origin = IntArray(2)
        val rootOrigin = IntArray(2)
        val token = ++captureNumber * 8
        val marker = ColorDrawable(android.graphics.Color.rgb(token, 255, 0))
        var x = 0; var y = 0
        compose.runOnUiThread {
            view.getLocationOnScreen(origin); root.getLocationOnScreen(rootOrigin)
            x = origin[0] + node.boundsInRoot.center.x.roundToInt()
            y = origin[1] + node.boundsInRoot.center.y.roundToInt()
            marker.setBounds(x - rootOrigin[0] - 3, y - rootOrigin[1] - 3, x - rootOrigin[0] + 3, y - rootOrigin[1] + 3)
            root.overlay.add(marker)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            root.invalidate()
        }
        var native: Bitmap? = null
        try {
            // Admit a native compositor frame using an unrelated token; never poll shadow/result pixels.
            compose.waitUntil(5000) {
                native?.recycle()
                native = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val pixel = native!!.getPixel(x, y)
                android.graphics.Color.green(pixel) >= 250 && android.graphics.Color.blue(pixel) <= 4 &&
                    abs(android.graphics.Color.red(pixel) - token) <= 4
            }
            val crop = Bitmap.createBitmap(native!!, origin[0] + node.boundsInRoot.left.roundToInt(),
                origin[1] + node.boundsInRoot.top.roundToInt(), node.boundsInRoot.width.roundToInt(), node.boundsInRoot.height.roundToInt())
            val image = crop.copy(Bitmap.Config.ARGB_8888, false)
            if (crop !== native) crop.recycle()
            File(output, "api${Build.VERSION.SDK_INT}-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return image
        } finally {
            native?.recycle()
            compose.runOnUiThread { root.overlay.remove(marker) }
        }
    }
    private fun setReference(value: Boolean, fields: Boolean = false) {
        compose.runOnIdle { reference = value; referenceFields = fields }
        compose.waitForIdle()
    }
    private fun luma(image: Bitmap, x: Int, y: Int): Float {
        val c = image.getPixel(x, y)
        return (.2126f * android.graphics.Color.red(c) + .7152f * android.graphics.Color.green(c) + .0722f * android.graphics.Color.blue(c)) / 255f
    }
    private fun sample(name: String) {
        val player = bounds(compose.onNodeWithTag("player-surface", true).fetchSemanticsNode())
        val collapse = bounds(control("Collapse player"))
        val pill = bounds(compose.onNodeWithTag("player-lyrics-pill", true).fetchSemanticsNode())
        compose.runOnIdle { bodies = listOf(collapse, pill) }
        val p = progress.value
        val expectedFrame = frame.pixels(density)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val path = Path().apply { addOutline(frame.windowShape().createOutline(root.size, LayoutDirection.Ltr, density)) }
        val insidePlayer = android.graphics.Region().apply {
            setPath(path.asAndroidPath(), android.graphics.Region(0, 0, root.width.roundToInt(), root.height.roundToInt()))
        }
        assertEquals(player.left, expectedFrame.left.toFloat(), 1f)
        assertEquals(player.top, expectedFrame.top.toFloat(), 1f)
        assertEquals(with(density) { 48.dp.toPx() }, collapse.height, 1f)
        assertEquals(collapse.top, pill.top, 1f)
        val actual = shot("$name-actual")
        val icon = bounds(compose.onNodeWithContentDescription("Collapse player", useUnmergedTree = true).fetchSemanticsNode())
        val ink = buildList {
            for (y in icon.top.roundToInt() until icon.bottom.roundToInt())
                for (x in icon.left.roundToInt() until icon.right.roundToInt()) add(luma(actual, x, y))
        }
        val contrast = ink.max() - ink.min()
        if (name == "full-open") {
            fullInkContrast = contrast
            check(fullInkContrast > .08f) { "The real Collapse glyph must be visible in the full-open reference" }
        }
        val alpha = ((p - .72f) / .28f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val inkRatio = contrast / fullInkContrast
        if (abs(inkRatio - alpha) > .10f) failures += "$name body opacity=$inkRatio expected once-only alpha=$alpha"
        setReference(true, true)
        val expected = shot("$name-reference")
        setReference(true, false)
        val white = shot("$name-reference-blank")
        setReference(false)
        val observations = JSONArray()
        val dp = density.density
        for ((index, body) in listOf(collapse, pill).withIndex()) for (side in listOf(-1, 1)) {
            var measured = 0.0; var target = 0.0; var count = 0
            // The exact original contour excludes legitimate clipping; every probe is outside
            // the unchanged48dp header target. No expanded player clip is used for acceptance.
            for (y in ((if (side < 0) body.top - 12 * dp else body.bottom + 4 * dp).roundToInt()) until
                ((if (side < 0) body.top - 4 * dp else body.bottom + 12 * dp).roundToInt())) {
                for (x in (body.center.x - body.width / 4).roundToInt() until (body.center.x + body.width / 4).roundToInt()) {
                    if (!insidePlayer.contains(x, y) || !insidePlayer.contains(x - 1, y - 1) || !insidePlayer.contains(x + 1, y + 1)) continue
                    // System-bar glyphs are a separate native layer, not header shadow pixels.
                    if (y <= statusBarBottom) continue
                    if (x !in 0 until actual.width || y !in 0 until actual.height) continue
                    // Uniform-white artwork has the same stationary wash/grain across this row.
                    // Its central lane lies beyond both controls'32dp field reach.
                    val base = (-8..8).map { offset -> luma(actual, actual.width / 2 + offset, y) }.average().toFloat()
                    if (base < .2f || luma(white, x, y) < .4f) continue
                    measured += (base - luma(actual, x, y)) / base
                    target += luma(white, x, y) - luma(expected, x, y)
                    count++
                }
            }
            if (count == 0 && side < 0 && body.top <= statusBarBottom + 1f) {
                observations.put(JSONObject().put("body", index).put("side", side).put("excluded", "native status bar"))
                continue
            }
            check(count > 10) { "No inside-player probes for $name/$index/$side: player=$player body=$body" }
            val mean = measured / count; val wanted = target / count
            val ratio = if (wanted > 0) mean / wanted else 0.0
            observations.put(JSONObject().put("body", index).put("side", side).put("count", count)
                .put("nativeField", mean).put("referenceField", wanted).put("ratio", ratio))
            if (wanted < .004 || ratio !in .65..1.35) failures += "$name body=$index side=$side p=$p field=$mean reference=$wanted ratio=$ratio"
        }
        receipts.put(JSONObject().put("name", name).put("progress", p).put("player", player.toString())
            .put("header", Rect(player.left, collapse.top, player.right, collapse.bottom).toString())
            .put("headerAlpha", alpha).put("nativeInkRatio", inkRatio)
            .put("bodies", bodies.toString()).put("maximumCornerPx", with(density) { frame.maximumCorner.toPx() })
            .put("probes", observations))
        listOf(actual, expected, white).forEach { it.recycle() }
        File(output, "api${Build.VERSION.SDK_INT}-bounds.json").writeText(receipts.toString(2))
    }

    @Test fun nativeFieldsFollowMinimizeReopenCancellationAndHeldReversalWithoutHeaderCutoff() {
        show()
        val open = bounds(control("Collapse player"))
        sample("full-open")
        compose.onNodeWithContentDescription("Hide lyrics").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertFalse(lyrics) }
        compose.onNodeWithContentDescription("Show lyrics").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertTrue(lyrics) }
        assertEquals("Lyrics input keeps the original header geometry", open, bounds(control("Collapse player")))
        compose.onNodeWithContentDescription("Collapse player").performClick()
        repeat(32) { if (progress.value > .92f) compose.mainClock.advanceTimeByFrame() }
        check(progress.value in .73f.. .995f) { "Not fractional: ${progress.value}" }
        sample("minimize")
        compose.mainClock.advanceTimeBy(600)
        assertEquals(0f, progress.value, .0001f)
        compose.onNodeWithContentDescription("Collapse player", useUnmergedTree = true).assertDoesNotExist()
        val hidden = shot("fully-hidden")
        var ghost = 0f
        for (y in open.top.roundToInt() until (open.bottom + 16 * density.density).roundToInt())
            for (x in open.left.roundToInt() until open.right.roundToInt())
                ghost = maxOf(ghost, abs(luma(hidden, x, y) - luma(hidden, hidden.width / 2, y)))
        assertTrue("The hidden header leaves no shadow at its old bounds: $ghost", ghost <= 1f / 255f)
        hidden.recycle()
        compose.onNodeWithTag("player-background-space", true).performClick()
        repeat(64) { if (progress.value < .84f) compose.mainClock.advanceTimeByFrame() }
        check(progress.value in .84f.. .995f) { "Reopen must visit fractional header opacity: ${progress.value}" }
        sample("reopen")
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1f, progress.value, .0001f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val start = Offset(8f, root.height * .4f)
        compose.onRoot().performTouchInput {
            down(start)
            repeat(8) { moveBy(Offset(0f, root.height * .015f), delayMillis = 32) }
        }
        compose.mainClock.advanceTimeByFrame()
        check(progress.value in .73f.. .995f) { "Held minimize must move the actual player: ${progress.value}" }
        sample("held-minimize")
        compose.onRoot().performTouchInput { repeat(4) { moveBy(Offset(0f, -root.height * .015f), delayMillis = 32) } }
        compose.mainClock.advanceTimeByFrame()
        sample("held-reverse")
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(600)
        assertTrue(expanded)
        assertEquals(1f, progress.value, .0001f)
        assertEquals("Cancellation restores the unchanged input bounds", open, bounds(control("Collapse player")))
        sample("cancel-restored")
        assertTrue("Native fields must match the same geometry/opacity reference above AND below both controls:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
