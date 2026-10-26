package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounded public-component discriminator for #369, not a reproduction or visual acceptance.
 * Per size: three held phases, two rim settings, at most four presentation attempts each
 * (six accepted observations / twenty-four display captures). No bitmap is saved or logged.
 * Only presentation/geometry validity is asserted; absent bands are not a test failure. */
class OuterPopupBandsTest {
    @get:Rule val compose = createComposeRule()

    private data class RimFrame(val stage: Int, val opacity: Float)
    private var rimFrame by mutableStateOf(RimFrame(0, 1f))
    @Volatile private var rimDrawnStage = -1
    private var open by mutableStateOf(false)
    private lateinit var anchor: GlassAnchor
    private lateinit var browse: FrostedBackdrop
    private lateinit var modal: FrostedBackdrop
    private var popupView: View? = null
    private var popupWindow: Window? = null
    private var witness: BoundsWitness? = null
    private var captures = 0
    private var stage = 0

    @Test fun shortMenuSeparatesStraightEdgeProfilesFromCornerRimProfiles() = compare("short", 2)
    @Test fun tallMenuSeparatesStraightEdgeProfilesFromCornerRimProfiles() = compare("tall", 6)

    private fun compare(name: String, rows: Int) {
        assumeTrue("API33+ diagnostic scope only; this comparison does not cover API26", Build.VERSION.SDK_INT >= 33)
        val observations = JSONArray()
        val report = JSONObject().put("fixture", name).put("api", Build.VERSION.SDK_INT)
            .put("density", compose.density.density).put("rows", rows)
            .put("fixtureSettings", "Opaque #24262A; ordinary GlassAppearance defaults except shadowStrength=0; " +
                "reduced motion off; no nested lenses; trigger outside the rim override")
            .put("acceptedObservationLimit", 6).put("displayCaptureLimit", 24)
            .put("profiles", observations).put("productionAdjustmentJustified", false)
            .put("interpretation", "Numeric sRGB luma profiles, not automatic symptom classification. " +
                "A central straight strip is distinct from opposing corner highlights. If the reported bands " +
                "do not reproduce, or only intended corners change, no production adjustment is justified. " +
                "Lead interpretation is required; no private reference or API26 conclusion is supplied.")
        compose.setContent { Fixture(rows) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var primaryFailure: Throwable? = null
        try {
            compose.runOnIdle { open = true }
            advanceTo("opening", 60) { anchor.hidden && anchor.contact.expansion.value in .55f.. .85f }
            val window = requireNotNull(popupWindow)
            val producer = modal
            val producerLayer = modal.layer
            compareHeld("opening", observations)

            advanceTo("settled", 240) {
                anchor.contact.expansion.value == 1f && !anchor.contact.expansion.isRunning &&
                    anchor.contact.body.value == GlassShape()
            }
            compareHeld("settled", observations)

            compose.runOnIdle { open = false }
            advanceTo("reversal", 60) { anchor.modalPresent && anchor.contact.expansion.value in .55f.. .85f }
            compareHeld("reversal", observations)
            compose.runOnIdle {
                assertSame("All phases retain this one native popup", window, popupWindow)
                assertSame("All phases retain this one producer", producer, modal)
                assertSame("All phases retain this one recording layer", producerLayer, modal.layer)
            }
            assertEquals("Three ordinary/suppressed pairs", 6, observations.length())
            report.put("status", "Presentation and matched-geometry assertions passed; optical interpretation remains open")
        } catch (failure: Throwable) {
            primaryFailure = failure
            report.put("status", "Invalid or incomplete comparison: ${failure.message}")
            throw failure
        } finally {
            report.put("displayCaptures", captures)
            try {
                try { writeReport(name, report) }
                catch (outputFailure: Throwable) {
                    val original = primaryFailure
                    if (original == null) throw outputFailure else original.addSuppressed(outputFailure)
                }
            }
            finally {
                compose.runOnIdle { rimFrame = RimFrame(++stage, 1f); open = false }
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Composable private fun Fixture(rows: Int) {
        LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(shadowStrength = 0f),
                LocalContentColor provides Color.White, LocalReducedMotion provides false) {
                browse = rememberFrostedBackdrop(Unit, dark = true)
                modal = rememberFrostedBackdrop(Unit, dark = true)
                anchor = rememberGlassAnchor()
                CompositionLocalProvider(LocalFrostedBackdrop provides browse, LocalModalBackdrop provides modal) {
                    Box(Modifier.fillMaxSize()) {
                        // The modal records the complete ordinary host; browse excludes its trigger.
                        Box(Modifier.fillMaxSize().recordFrostedBackdrop(modal)) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(browse)) { drawRect(Color(0xFF24262A)) }
                            LiquidGlassSurface(Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                                .padding(48.dp).size(104.dp, 56.dp).glassAnchor(anchor), source = browse) {
                                Text("More", Modifier.align(Alignment.Center))
                            }
                        }
                        // The ordinary trigger/producer is outside this override. No nested lenses.
                        val rimControl = remember { {
                            val requested = rimFrame
                            rimDrawnStage = requested.stage
                            requested.opacity
                        } }
                        CompositionLocalProvider(LocalGlassRimOpacityForDiagnostics provides rimControl) {
                            GlassMenu(open, anchor, { open = false }) {
                                InstallWitness()
                                repeat(rows) { row ->
                                    Box(Modifier.width(240.dp).height(48.dp), contentAlignment = Alignment.Center) {
                                        Text(if (rows == 2) listOf("Scan music", "Settings")[row] else "Public action ${row + 1}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun InstallWitness() {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        SideEffect { popupView = view; popupWindow = window }
        DisposableEffect(window) {
            val marker = BoundsWitness { rimDrawnStage }
            witness = marker
            window.decorView.overlay.add(marker)
            onDispose {
                window.decorView.overlay.remove(marker)
                if (witness === marker) { witness = null; popupView = null; popupWindow = null }
            }
        }
    }

    /** Only geometry/readiness advances the clock. Never wait for particular optical pixels. */
    private fun advanceTo(phase: String, limit: Int, reached: () -> Boolean) {
        repeat(limit) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (compose.runOnIdle { reached() }) return
        }
        fail("$phase geometry was not ready in $limit controlled frames")
    }

    private data class Geometry(val clock: Long, val progress: Float, val shape: GlassShape,
        val vertical: Boolean, val bounds: Rect, val radius: Float, val nativeOrigin: Offset,
        val nativeWidth: Int, val nativeHeight: Int) {
        val sx get() = shape.scaleX(vertical)
        val sy get() = shape.scaleY(vertical)
        fun point(x: Float, y: Float) = bounds.center + Offset((x - bounds.width / 2) * sx, (y - bounds.height / 2) * sy)
        val drawn get() = Rect(point(0f, 0f), point(bounds.width, bounds.height))
        fun json() = JSONObject().put("clock", clock).put("progress", progress)
            .put("strain", shape.strain).put("depth", shape.depth).put("scaleX", sx).put("scaleY", sy)
            .put("layoutBounds", bounds.json()).put("drawnBounds", drawn.json()).put("radius", radius)
            .put("nativeOrigin", nativeOrigin.json()).put("nativeSize", JSONArray().put(nativeWidth).put(nativeHeight))
    }

    private fun geometry(): Geometry {
        val bounds = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val view = requireNotNull(popupView)
            assertTrue("Popup is natively attached and laid out", view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested)
            assertTrue("The actual popup owns the surface", anchor.modalPresent && anchor.hidden)
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            val offset = Offset(origin[0].toFloat(), origin[1].toFloat())
            val p = anchor.contact.expansion.value.coerceIn(0f, 1f)
            val radius = anchor.bounds.height / 2 * (1f - p) + 28f * compose.density.density * p
            Geometry(compose.mainClock.currentTime, p, anchor.contact.body.value, anchor.contact.vertical,
                bounds.translate(offset), radius.coerceAtMost(minOf(bounds.width, bounds.height) / 2),
                offset, view.width, view.height)
        }
    }

    private fun compareHeld(phase: String, observations: JSONArray) {
        val held = geometry()
        val window = requireNotNull(popupWindow)
        val producer = modal
        val layer = modal.layer
        var ordinary: List<Profile>? = null
        for (opacity in listOf(1f, 0f)) {
            val token = ++stage
            compose.runOnIdle { rimFrame = RimFrame(token, opacity); Snapshot.sendApplyNotifications() }
            compose.waitForIdle()
            assertEquals("$phase must not advance geometry to apply rim=$opacity", held, geometry())
            val capture = captureProfiles(held, token)
            val profiles = capture.profiles
            assertEquals("$phase presentation must preserve the held geometry", held, geometry())
            compose.runOnIdle {
                assertSame("A/B retains the native window", window, popupWindow)
                assertSame("A/B retains the producer", producer, modal)
                assertSame("A/B retains the recording", layer, modal.layer)
            }
            val entry = JSONObject().put("phase", phase).put("rimOpacity", opacity).put("stage", token)
                .put("geometry", held.json()).put("presentationVerified", true)
                .put("presentation", capture.presentation)
                .put("profiles", JSONArray(profiles.map { it.json() }))
            if (opacity == 1f) ordinary = profiles else {
                entry.put("ordinaryMinusSuppressed", JSONArray(requireNotNull(ordinary).zip(profiles).map { (a, b) ->
                    assertEquals("Both settings sample the same named profile", a.name, b.name)
                    JSONObject().put("name", a.name).put("lumaDelta", JSONArray(a.values.zip(b.values).map { (x, y) -> x - y }))
                }))
            }
            observations.put(entry)
        }
        // Draw-only reset; the next phase, not this paired comparison, advances motion.
        compose.runOnIdle { rimFrame = RimFrame(++stage, 1f); Snapshot.sendApplyNotifications() }
    }

    private data class Profile(val name: String, val points: List<Offset>, val values: List<Float>) {
        fun json() = JSONObject().put("name", name).put("depthDp", JSONArray(Depths.toList()))
            .put("samplePixels", JSONArray(points.map { it.json() })).put("srgbLuma255", JSONArray(values))
            .put("edgeMinusInterior", values.take(5).maxOrNull()!! - values.last())
    }

    private data class Capture(val profiles: List<Profile>, val presentation: JSONObject)

    /** Fixed capture cap. Retries concern a native stage/bounds token, never brightness. */
    private fun captureProfiles(held: Geometry, token: Int): Capture {
        val density = compose.density.density
        val gap = 6f * density
        val rect = held.drawn
        val centers = listOf(Offset(rect.left - gap, rect.top - gap), Offset(rect.right + gap, rect.top - gap),
            Offset(rect.right + gap, rect.bottom + gap), Offset(rect.left - gap, rect.bottom + gap))
        val window = requireNotNull(popupWindow)
        compose.runOnIdle {
            val decor = window.decorView
            val origin = IntArray(2)
            decor.getLocationOnScreen(origin)
            requireNotNull(witness).apply {
                stage = token
                points = centers.map { it - Offset(origin[0].toFloat(), origin[1].toFloat()) }
                halfSize = 2f * density
                assertTrue("Bounds witnesses must fit inside the native popup window", points.all {
                    it.x >= halfSize && it.x + halfSize < decor.width && it.y >= halfSize && it.y + halfSize < decor.height
                })
                setBounds(0, 0, decor.width, decor.height)
                invalidateSelf()
            }
        }
        repeat(4) { attempt ->
            val nextFrame = CountDownLatch(1)
            compose.runOnUiThread {
                window.decorView.postInvalidateOnAnimation()
                window.decorView.postOnAnimation { nextFrame.countDown() }
            }
            assertTrue("Native presentation callback within two seconds", nextFrame.await(2, TimeUnit.SECONDS))
            compose.waitForIdle()
            assertEquals("Readiness retry cannot advance the held phase", held, geometry())
            captures++
            check(captures <= 24) { "Capture budget exceeded" }
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val witnesses = JSONArray()
                val matches = centers.mapIndexed { index, point ->
                    val observed = pixel(image, point)
                    val expected = BoundsWitness.color(token, index)
                    witnesses.put(JSONObject().put("screenPoint", point.json())
                        .put("observedArgb", Integer.toHexString(observed)).put("expectedArgb", Integer.toHexString(expected)))
                    abs(AndroidColor.red(observed) - AndroidColor.red(expected)) <= 2 &&
                        abs(AndroidColor.green(observed) - AndroidColor.green(expected)) <= 2 &&
                        abs(AndroidColor.blue(observed) - AndroidColor.blue(expected)) <= 2
                }.all { it }
                if (matches) {
                    assertEquals("The surface consumed this diagnostic stage before its native witness", token, rimDrawnStage)
                    return Capture(profiles(image, held), JSONObject().put("attempt", attempt + 1)
                        .put("rimDrawnStage", rimDrawnStage).put("boundsWitnesses", witnesses))
                }
                Log.i("OuterPopupBands", "stage=$token presentation attempt=${attempt + 1}/4 not ready; no optical decision")
            } finally { image.recycle() }
        }
        error("Native stage/bounds witnesses not presented after four captures; no optical result")
    }

    private fun profiles(image: Bitmap, geometry: Geometry): List<Profile> {
        val density = compose.density.density
        val width = geometry.bounds.width
        val height = geometry.bounds.height
        val radius = geometry.radius
        assertTrue("Straight spans must remain distinct from rounded corners", width - 2 * radius > 24 * density)
        assertTrue("Corner profiles stay inward of the silhouette, before its center", radius > Depths.last() * density)
        fun profile(name: String, point: (Float) -> Offset): Profile {
            val points = Depths.map { point(it * density) }.map { Offset(it.x.roundToInt().toFloat(), it.y.roundToInt().toFloat()) }
            val values = points.map {
                val rgb = pixel(image, it)
                .2126f * AndroidColor.red(rgb) + .7152f * AndroidColor.green(rgb) + .0722f * AndroidColor.blue(rgb)
            }
            return Profile(name, points, values)
        }
        return buildList {
            for (fraction in listOf(.25f, .5f, .75f)) {
                val x = radius + (width - 2 * radius) * fraction
                add(profile("straight-top-$fraction") { depth -> geometry.point(x, depth) })
                add(profile("straight-bottom-$fraction") { depth -> geometry.point(x, height - depth) })
            }
            for ((name, signs) in listOf("top-left" to Offset(-1f, -1f), "top-right" to Offset(1f, -1f),
                "bottom-right" to Offset(1f, 1f), "bottom-left" to Offset(-1f, 1f))) {
                val center = Offset(if (signs.x < 0) radius else width - radius, if (signs.y < 0) radius else height - radius)
                add(profile("corner-$name") { depth ->
                    val local = center + signs * ((radius - depth) / sqrt(2f))
                    geometry.point(local.x, local.y)
                })
            }
        }
    }

    private fun pixel(image: Bitmap, point: Offset): Int {
        val x = point.x.roundToInt(); val y = point.y.roundToInt()
        assertTrue("Synthetic sample must be inside the display: $point", x in 0 until image.width && y in 0 until image.height)
        return image.getPixel(x, y)
    }

    /** Native tokens sit outside the shell/source; they witness stage and its four bounds. */
    private class BoundsWitness(val drawnStage: () -> Int) : Drawable() {
        var stage = -1
        var points = emptyList<Offset>()
        var halfSize = 0f
        private val paint = Paint()
        override fun draw(canvas: AndroidCanvas) {
            if (drawnStage() != stage) return
            points.forEachIndexed { index, point ->
                paint.color = color(stage, index)
                canvas.drawRect(point.x - halfSize, point.y - halfSize, point.x + halfSize, point.y + halfSize, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
        companion object {
            fun color(stage: Int, index: Int): Int {
                require(stage in 1..15 && index in 0..3) { "Native stage/bounds colors must not wrap" }
                return AndroidColor.rgb(stage * 16, 40 + index * 50, 255)
            }
        }
    }

    private fun writeReport(name: String, report: JSONObject) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
            ?.let(::File) ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val directory = File(root, "outer-popup-bands")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name-api${Build.VERSION.SDK_INT}.json").writeText(report.toString(2))
        Log.i("OuterPopupBands", "$name captures=$captures/24 status=${report.optString("status")}; " +
            "numeric profiles recorded, no symptom classification or production adjustment")
    }

    private companion object {
        val Depths = floatArrayOf(.5f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 10f)
        fun Offset.json() = JSONArray().put(x).put(y)
        fun Rect.json() = JSONArray().put(left).put(top).put(right).put(bottom)
    }
}
