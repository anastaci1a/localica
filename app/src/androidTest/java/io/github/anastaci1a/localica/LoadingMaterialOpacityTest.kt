package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed shared shimmer phase and actual native window pixels; captures never drive readiness.
 * Window-buffer coordinates exclude the system's activity-launch surface transform.
 * This does not claim physical compositor presentation or activity-animation coverage.
 * This tests the leaf material, not whether a collection/player provider was wired.
 */
class LoadingMaterialOpacityTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val background = Color.Magenta

    @Test fun loadingArtAndNestedTextMaterialAreHalvedOnceAndNeverShowNotesWithEitherActivityProvider() {
        lateinit var view: View
        val activity = LoadingShimmerActivity()
        val phase = mutableStateOf(.35f)
        compose.setContent { LocalicaTheme {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            CompositionLocalProvider(LocalReducedMotion provides false, LocalLoadingShimmer provides phase) {
                Column(Modifier.safeDrawingPadding().padding(8.dp)) {
                    for (shared in listOf(false, true)) {
                        CompositionLocalProvider(LocalLoadingShimmerActivity provides if (shared) activity else null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (half in listOf(false, true)) {
                                    // The default column deliberately has no opacity provider.
                                    val content: @Composable () -> Unit = {
                                        LoadingSamples("$shared-$half")
                                    }
                                    if (half) CompositionLocalProvider(LocalLoadingMaterialOpacity provides .5f, content = content)
                                    else content()
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals("Every shared loading leaf retains the existing activity lease", 6, activity.count)
        }
        val tags = listOf(false, true).flatMap { shared -> listOf(false, true).flatMap { half ->
            listOf("art", "no-note", "text").map { "$shared-$half-$it" }
        } }
        screen(view, tags) { image, bounds ->
            for (shared in listOf(false, true)) {
                assertSamePixels(image, bounds.getValue("$shared-false-art"), bounds.getValue("$shared-false-no-note"),
                    "Loading art without a note matches the independent no-icon face, shared=$shared")
                assertSamePixels(image, bounds.getValue("$shared-true-art"), bounds.getValue("$shared-true-no-note"),
                    "Half-opacity loading also has no note, shared=$shared")
                for (kind in listOf("art", "no-note", "text")) {
                    val full = bounds.getValue("$shared-false-$kind")
                    val half = bounds.getValue("$shared-true-$kind")
                    assertEquals(full.width(), half.width()); assertEquals(full.height(), half.height())
                    var contrast = 0f
                    for (y in 1 until full.height() - 1) for (x in 1 until full.width() - 1) {
                        val a = channels(image.getPixel(full.left + x, full.top + y))
                        val b = channels(image.getPixel(half.left + x, half.top + y))
                        val bg = floatArrayOf(1f, 0f, 1f)
                        for (c in 0..2) {
                            contrast = maxOf(contrast, abs(a[c] - bg[c]))
                            assertEquals("Exactly one half-opacity material blend: shared=$shared kind=$kind x=$x y=$y channel=$c",
                                bg[c] + (a[c] - bg[c]) * .5f, b[c], .025f)
                        }
                    }
                    assertTrue("Fixture materially distinguishes half from quarter opacity", contrast > .2f)
                }
            }
        }
    }

    @Composable private fun LoadingSamples(prefix: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Slot("$prefix-art") { ArtworkImage(null, true, Modifier.fillMaxSize(), shape = RectangleShape) }
            Slot("$prefix-no-note") { ArtworkPlaceholder(true, Modifier.fillMaxSize(), showIcon = false) }
            // Existing reveal alpha remains outside the material; the nested leaf must
            // receive the context exactly once, not once again at BrowseLoadingShape.
            Slot("$prefix-text", height = 12.dp) {
                BrowseLoadingShape(Modifier.fillMaxSize(), { 0f }, alpha = { .8f })
            }
        }
    }

    @Test fun finishingLoadingKeepsRealArtworkLoadedTextAndMissingArtFallbackAtTheirOriginalOpacity() {
        lateinit var view: View
        var loading by mutableStateOf(true)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.GREEN) }
        val artwork = bitmap.asImageBitmap()
        compose.setContent { LocalicaTheme {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Row(Modifier.safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (half in listOf(false, true)) {
                        val content: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Slot("$half-real") { ArtworkImage(if (loading) null else artwork, loading,
                                    Modifier.fillMaxSize(), shape = RectangleShape) }
                                Slot("$half-missing") { ArtworkImage(null, loading, Modifier.fillMaxSize(), shape = RectangleShape) }
                                Slot("$half-no-note") { ArtworkPlaceholder(loading, Modifier.fillMaxSize(), showIcon = false) }
                                Slot("$half-loaded-text", height = 32.dp) { Text("Loaded") }
                            }
                        }
                        if (half) CompositionLocalProvider(LocalLoadingMaterialOpacity provides .5f, content = content)
                        else content()
                    }
                }
            }
        } }
        compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).assertCountEquals(6)
        compose.runOnIdle { loading = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).assertCountEquals(0)
        val tags = listOf(false, true).flatMap { half ->
            listOf("real", "missing", "no-note", "loaded-text").map { "$half-$it" }
        }
        screen(view, tags) { image, bounds ->
            for (kind in listOf("real", "missing", "no-note", "loaded-text")) {
                assertSamePixels(image, bounds.getValue("false-$kind"), bounds.getValue("true-$kind"),
                    "Loading context must not affect settled $kind")
            }
            val real = bounds.getValue("true-real")
            for (y in 1 until real.height() - 1) for (x in 1 until real.width() - 1) {
                val pixel = channels(image.getPixel(real.left + x, real.top + y))
                assertEquals(0f, pixel[0], .02f); assertEquals(1f, pixel[1], .02f); assertEquals(0f, pixel[2], .02f)
            }
            val missing = bounds.getValue("true-missing")
            val noNote = bounds.getValue("true-no-note")
            var iconPixels = 0
            for (y in 1 until missing.height() - 1) for (x in 1 until missing.width() - 1) {
                val a = channels(image.getPixel(missing.left + x, missing.top + y))
                val b = channels(image.getPixel(noNote.left + x, noNote.top + y))
                if ((0..2).any { abs(a[it] - b[it]) > .04f }) iconPixels++
            }
            assertTrue("Final missing-art note remains visible; only loading loses it", iconPixels > 10)
        }
        // The source bitmap is never manually recycled while Compose may reference it.
    }

    @Composable private fun Slot(tag: String, height: Dp = 64.dp, content: @Composable BoxScope.() -> Unit) {
        Box(Modifier.size(64.dp, height).background(background).testTag(tag), content = content)
    }

    private fun channels(pixel: Int) = floatArrayOf(AndroidColor.red(pixel) / 255f,
        AndroidColor.green(pixel) / 255f, AndroidColor.blue(pixel) / 255f)

    private fun assertSamePixels(image: Bitmap, first: Rect, second: Rect, message: String) {
        assertEquals(first.width(), second.width()); assertEquals(first.height(), second.height())
        for (y in 1 until first.height() - 1) for (x in 1 until first.width() - 1) {
            val a = channels(image.getPixel(first.left + x, first.top + y))
            val b = channels(image.getPixel(second.left + x, second.top + y))
            for (c in 0..2) assertEquals("$message at $x,$y channel=$c", a[c], b[c], .025f)
        }
    }

    private fun screen(view: View, tags: List<String>, inspect: (Bitmap, Map<String, Rect>) -> Unit) {
        compose.waitForIdle()
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        val observer = compose.runOnUiThread {
            assertTrue(view.isAttachedToWindow && view.hasWindowFocus())
            view.viewTreeObserver.also { it.addOnDrawListener(listener); view.invalidate() }
        }
        try { assertTrue("Native draw before capture", drawn.await(5, TimeUnit.SECONDS)) }
        finally { compose.runOnUiThread { if (observer.isAlive) observer.removeOnDrawListener(listener) } }
        val coordinateEvidence = mutableListOf<String>()
        val bounds = tags.associateWith { tag ->
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val rootView = (node.root as ViewRootForTest).view
            compose.runOnUiThread {
                val origin = IntArray(2).also(rootView::getLocationOnScreen)
                val windowOrigin = IntArray(2).also(rootView::getLocationInWindow)
                val b = node.boundsInRoot
                coordinateEvidence += "$tag rawBounds=$b root=${System.identityHashCode(rootView)} " +
                    "screen=${origin.contentToString()} window=${windowOrigin.contentToString()} " +
                    "matrix=${rootView.matrix} size=${rootView.width}x${rootView.height}"
                Rect((windowOrigin[0] + b.left).roundToInt(), (windowOrigin[1] + b.top).roundToInt(),
                    (windowOrigin[0] + b.right).roundToInt(), (windowOrigin[1] + b.bottom).roundToInt())
            }
        }
        // Actual native window extents/insets, independent of Compose's padding.
        // These pre-capture observations are not atomic with the screenshot below.
        val native = compose.runOnUiThread {
            val decor = view.rootView
            val origin = IntArray(2).also(decor::getLocationInWindow)
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor)) {
                "Native window insets must be available for screen crop qualification"
            }
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val safe = Rect(origin[0] + bars.left, origin[1] + bars.top,
                origin[0] + decor.width - bars.right, origin[1] + decor.height - bars.bottom)
            val visible = Rect().also(view::getWindowVisibleDisplayFrame)
            safe to "preCaptureNonAtomic decorOrigin=${origin.contentToString()} decorSize=${decor.width}x${decor.height} " +
                "barsAndCutout=$bars safe=$safe windowVisible=$visible focused=${view.hasWindowFocus()} crops=$bounds"
        }
        // One copy of the actual window buffer, after drawing. No display-level
        // launch translation can move its pixels away from window-local crops.
        val image = windowPixels()
        try {
            bounds.values.forEach { assertTrue("Measured native crop is wholly on screen: $it",
                it.left >= 0 && it.top >= 0 && it.right <= image.width && it.bottom <= image.height) }
            assertTrue("Native safe window has positive extent: ${native.second}", native.first.width() > 0 && native.first.height() > 0)
            bounds.forEach { (tag, crop) ->
                assertTrue("Whole $tag crop must fit native safe content, not just screen: crop=$crop ${native.second}",
                    native.first.contains(crop))
            }
            inspect(image, bounds)
        } catch (failure: Throwable) {
            // Same captured pixels only. Diagnostics may never replace the assertion.
            runCatching {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val artifact = java.io.File(nativeEvidenceDirectory("loading-material-failures", context.getExternalFilesDir(null)),
                    "loading-material-failure-${android.os.SystemClock.uptimeMillis()}.png")
                artifact.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.util.Log.e("LoadingMaterialOpacity", "SAME failed capture=${artifact.absolutePath} coordinates=$coordinateEvidence")
            }.onFailure { failure.addSuppressed(it) }
            runCatching {
                val failedPoint = Regex("at (\\d+),(\\d+)").find(failure.message.orEmpty())?.let {
                    it.groupValues[1].toInt() to it.groupValues[2].toInt()
                }
                val pixels = bounds.map { (tag, crop) ->
                    val probes = listOf(1 to 1, crop.width() / 2 to crop.height() / 2) + listOfNotNull(failedPoint)
                    "$tag " + probes.distinct().mapNotNull { (x, y) ->
                        val sx = crop.left + x; val sy = crop.top + y
                        if (x !in 0 until crop.width() || y !in 0 until crop.height() ||
                            sx !in 0 until image.width || sy !in 0 until image.height) null
                        else "local=$x,$y screen=$sx,$sy argb=${Integer.toHexString(image.getPixel(sx, sy))}"
                    }
                }
                val context = "${native.second} screenshot=${image.width}x${image.height} sameImagePixels=$pixels"
                android.util.Log.e("LoadingMaterialOpacity", context)
                failure.addSuppressed(AssertionError(context))
            }
            throw failure
        } finally { image.recycle() }
    }

    private fun windowPixels(): Bitmap {
        val window = compose.activity.window
        val image = compose.runOnUiThread {
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        val completed = CountDownLatch(1)
        val ownership = Any()
        var returned = false
        var abandoned = false
        var result = android.view.PixelCopy.ERROR_UNKNOWN
        try {
            android.view.PixelCopy.request(window, image, { code ->
                synchronized(ownership) {
                    result = code
                    returned = true
                    if (abandoned) image.recycle()
                }
                completed.countDown()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (failure: Throwable) {
            image.recycle()
            throw failure
        }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            synchronized(ownership) {
                if (returned) image.recycle() else abandoned = true
            }
            throw AssertionError("The single native window copy did not complete")
        }
        if (result != android.view.PixelCopy.SUCCESS) {
            image.recycle()
            throw AssertionError("Native window copy failed with result=$result")
        }
        return image
    }
}
