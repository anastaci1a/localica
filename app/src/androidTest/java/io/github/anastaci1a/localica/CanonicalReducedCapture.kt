package io.github.anastaci1a.localica

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as NativeColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import io.github.anastaci1a.localica.ui.LocalReducedMotion
import io.github.anastaci1a.localica.ui.SearchMorphSample
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/** Test-only passive window records. Serialization, PixelCopy and PNG work never run on Main. */
internal class CanonicalReducedProbe(val role: String, val channel: Int) {
    var view: View? = null
    var bounds = Rect.Zero
    var localReduced = false
    var engineAccess = "uncomposed"
    var specAccess = "uncomposed"
    var density = 0f
    var fontScale = 0f
    var direction = "uncomposed"
    var clock: UiClock? = null
    val marker = CanonicalReducedMarker()
    var markerInstalled = false
    var gateBlocked = false
    var surface: SemanticsNode? = null
}

@Composable internal fun canonicalReducedProbe(probe: CanonicalReducedProbe): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val reduced = LocalReducedMotion.current
    val engine = LocalEngineContext.current
    val spec = LocalUiSpec.current.spec
    val clock = LocalUiClock.current
    SideEffect {
        if (probe.view !== view) {
            probe.view?.let { if (probe.markerInstalled) it.overlay.remove(probe.marker) }
            probe.markerInstalled = false
            probe.surface = null
        }
        probe.view = view; probe.localReduced = reduced
        probe.engineAccess = engine.access.name; probe.specAccess = spec.access.name
        probe.density = density.density; probe.fontScale = density.fontScale
        probe.direction = direction.name; probe.clock = clock
    }
    return Modifier.onGloballyPositioned { probe.bounds = it.boundsInRoot() }
}

/** A draw-only generation code, independent of policy/geometry/optical results. */
internal class CanonicalReducedMarker : Drawable() {
    var requested = 0
    var drawn = 0
    private val paint = Paint()
    override fun draw(canvas: Canvas) {
        drawn = requested
        paint.color = NativeColor.BLACK or drawn
        canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        paint.color = NativeColor.MAGENTA
        canvas.drawRect((bounds.left + 4).toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated by Android") override fun getOpacity() = PixelFormat.OPAQUE
}

internal data class CanonicalReducedCheckpoint(val label: String, val phase: String, val millis: Long,
    val policyIntent: Boolean? = null)

internal class CanonicalReducedCapture(private val rule: ComposeContentTestRule, private val method: String,
    private val probes: List<CanonicalReducedProbe>, private val intendedPolicy: () -> Boolean) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
        "canonical-reduced/$method").apply { check(isDirectory || mkdirs()) }
    private val initialClock = rule.mainClock.currentTime
    private val enabled = HardwareRendererCompat.isDrawingEnabled()
    private val rows = JSONArray()
    private val listeners = mutableListOf<Pair<View, android.view.ViewTreeObserver.OnDrawListener>>()
    private var generation = 0
    init { if (!enabled) HardwareRendererCompat.setDrawingEnabled(true) }

    private data class State(val probe: CanonicalReducedProbe, val view: View?, val window: Window?,
        val attached: Boolean, val bounds: Rect, val originX: Int, val originY: Int,
        val localReduced: Boolean, val engineAccess: String, val specAccess: String, val clockAccess: String?, val clockSpecAccess: String?,
        val density: Float, val fontScale: Float, val direction: String,
        val surfaceBounds: Rect?, val insets: List<Int>, val sequence: Long?, val nanos: Long?, val clockIdentity: Int?,
        val viewIdentity: Int?, val tokenIdentity: Int?, val blocked: Boolean)

    private fun snapshot(): List<State> = rule.runOnUiThread {
        probes.map { p ->
            val view = p.view
            val position = IntArray(2)
            view?.getLocationOnScreen(position)
            val insets = view?.rootWindowInsets?.let {
                androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            State(p, view, view?.let(::canonicalReducedWindow), view?.isAttachedToWindow == true,
                p.bounds, position[0], position[1], p.localReduced, p.engineAccess, p.specAccess,
                p.clock?.context?.access?.name, p.clock?.spec?.access?.name, p.density, p.fontScale, p.direction,
                p.surface?.boundsInRoot,
                listOf(insets?.left ?: 0, insets?.top ?: 0, insets?.right ?: 0, insets?.bottom ?: 0),
                p.clock?.presentedStamp?.sequence, p.clock?.presentedStamp?.nanos,
                p.clock?.let(System::identityHashCode),
                view?.let(System::identityHashCode), view?.windowToken?.let(System::identityHashCode), p.gateBlocked)
        }
    }
    private fun rectangle(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    private fun save(name: String, image: Bitmap) {
        File(output, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun counts(image: Bitmap, r: android.graphics.Rect, channel: Int): Int {
        var count = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val c = image.getPixel(x, y)
            val rgb = intArrayOf(NativeColor.red(c), NativeColor.green(c), NativeColor.blue(c))
            if (NativeColor.alpha(c) == 255 && rgb[channel] > 0 && rgb[(channel + 1) % 3] == 0 &&
                rgb[(channel + 2) % 3] == 0) count++
        }
        return count
    }
    private fun visibleRect(state: State, width: Int, height: Int): android.graphics.Rect {
        val r = state.bounds.translate(androidx.compose.ui.geometry.Offset(state.originX.toFloat(), state.originY.toFloat()))
        return android.graphics.Rect(floor(r.left).toInt().coerceIn(0, width), floor(r.top).toInt().coerceIn(0, height),
            ceil(r.right).toInt().coerceIn(0, width), ceil(r.bottom).toInt().coerceIn(0, height))
    }

    fun capture(point: CanonicalReducedCheckpoint, supplemental: () -> Map<String, Any?>) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        generation++
        val queryClock = rule.mainClock.currentTime
        if (probes.none { it.gateBlocked }) {
            for (probe in probes.filter { it.role != "root" && it.view?.isAttachedToWindow == true }) {
                val text = when (probe.role) { "parent" -> "Parent marker"; "child" -> "Child marker"; else -> "Editor marker" }
                val nodes = rule.onAllNodes(hasTestTag("glass-morph-surface") and hasAnyDescendant(hasText(text)),
                    useUnmergedTree = true).fetchSemanticsNodes()
                    .filter { (it.root as ViewRootForTest).view === probe.view }
                check(nodes.size <= 1) { "Ambiguous native surface owner ${probe.role}" }
                rule.runOnUiThread { probe.surface = nodes.singleOrNull() }
            }
        }
        check(rule.mainClock.currentTime == queryClock) { "Geometry query advanced a hidden frame" }
        val before = snapshot()
        val row = JSONObject().put("label", point.label).put("phase", point.phase).put("phaseMillis", point.millis)
            .put("policyIntent", intendedPolicy()).put("intentAssignment", point.policyIntent ?: JSONObject.NULL)
            .put("relativeClockMs", rule.mainClock.currentTime - initialClock)
        val owners = JSONArray()
        val rootClock = before.first { it.probe.role == "root" }.clockIdentity
        for (state in before.filter { it.attached }) {
            check(rootClock != null && state.clockIdentity == rootClock) { "A subtree replaced the actual root UiClock" }
            check(state.density > 0f && state.fontScale == 1f && state.direction == "Ltr") { "Unexpected actual window profile" }
            val access = if (state.localReduced) "Reduced" else "Standard"
            check(listOf(state.engineAccess, state.specAccess, state.clockAccess, state.clockSpecAccess).all { it == access }) {
                "Applied subtree/Engine/spec/root-clock policy differs for ${state.probe.role} at ${point.label}"
            }
        }
        // Each unblocked owner gets a freshly attested native buffer. A blocked child is never invalidated here.
        for (state in before) {
            val p = state.probe
            val receipt = JSONObject().put("role", p.role).put("attached", state.attached)
                .put("bodyBoundsInRoot", rectangle(state.bounds)).put("windowOrigin", JSONArray(listOf(state.originX, state.originY)))
                .put("physicalSurfaceBoundsInRoot", state.surfaceBounds?.let(::rectangle) ?: JSONObject.NULL)
                .put("localReduced", state.localReduced).put("engineAccess", state.engineAccess).put("specAccess", state.specAccess)
                .put("clockAccess", state.clockAccess ?: JSONObject.NULL).put("clockSpecAccess", state.clockSpecAccess ?: JSONObject.NULL)
                .put("policyIntentApplied", state.localReduced == intendedPolicy())
                .put("density", state.density).put("fontScale", state.fontScale)
                .put("direction", state.direction).put("systemInsets", JSONArray(state.insets))
                .put("engineSequence", state.sequence ?: JSONObject.NULL)
                .put("engineNanos", state.nanos ?: JSONObject.NULL).put("viewIdentity", state.viewIdentity ?: JSONObject.NULL)
                .put("clockIdentity", state.clockIdentity ?: JSONObject.NULL)
                .put("tokenIdentity", state.tokenIdentity ?: JSONObject.NULL).put("blockedProducer", state.blocked)
            if (state.attached && state.window != null && state.view != null && !state.blocked) {
                val view = state.view
                val drawn = CountDownLatch(1)
                rule.runOnUiThread {
                    if (!p.markerInstalled) {
                        p.marker.setBounds(1, 1, 9, 9); view.overlay.add(p.marker); p.markerInstalled = true
                    }
                    p.marker.requested = generation
                    val listener = object : android.view.ViewTreeObserver.OnDrawListener {
                        override fun onDraw() { view.post {
                            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                            listeners.remove(view to this)
                            drawn.countDown()
                        } }
                    }
                    listeners += view to listener
                    view.viewTreeObserver.addOnDrawListener(listener)
                    p.marker.invalidateSelf(); view.invalidate()
                }
                check(drawn.await(5, TimeUnit.SECONDS)) { "Incomplete native draw ${point.label}/${p.role}" }
                val size = rule.runOnUiThread { state.window.decorView.width to state.window.decorView.height }
                check(size.first > 0 && size.second > 0)
                val buffer = copyNativeWindowOnce(state.window,
                    Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888), "Incomplete native copy ${p.role}") {
                    "Incomplete native copy ${p.role}: $it"
                }
                try {
                    val markerOrigin = rule.runOnUiThread { IntArray(2).also(view::getLocationInWindow) }
                    val x = markerOrigin[0] + 2; val y = markerOrigin[1] + 2
                    check(buffer.getPixel(x, y) == (NativeColor.BLACK or generation))
                    check(buffer.getPixel(x + 4, y) == NativeColor.MAGENTA)
                    save("${point.label}-${p.role}-original.png", buffer)
                    val originInWindow = markerOrigin
                    val r = android.graphics.Rect(floor(state.bounds.left).toInt() + originInWindow[0],
                        floor(state.bounds.top).toInt() + originInWindow[1], ceil(state.bounds.right).toInt() + originInWindow[0],
                        ceil(state.bounds.bottom).toInt() + originInWindow[1])
                    receipt.put("nativeUnclippedBodyRegion", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                    if (!r.intersect(0, 0, buffer.width, buffer.height)) r.setEmpty()
                    val rootState = before.first { it.probe.role == "root" }
                    val appGlobal = rootState.bounds.translate(androidx.compose.ui.geometry.Offset(rootState.originX.toFloat(), rootState.originY.toFloat()))
                    val windowX = state.originX - originInWindow[0]
                    val windowY = state.originY - originInWindow[1]
                    val nativeApp = android.graphics.Rect(floor(appGlobal.left).toInt() - windowX, floor(appGlobal.top).toInt() - windowY,
                        ceil(appGlobal.right).toInt() - windowX, ceil(appGlobal.bottom).toInt() - windowY)
                    check(nativeApp.intersect(0, 0, buffer.width, buffer.height)) { "Incomplete app/native intersection" }
                    val appBuffer = Bitmap.createBitmap(buffer, nativeApp.left, nativeApp.top, nativeApp.width(), nativeApp.height())
                    try { save("${point.label}-${p.role}-app.png", appBuffer) }
                    finally { if (appBuffer !== buffer) appBuffer.recycle() }
                    receipt.put("nativeAppCrop", JSONArray(listOf(nativeApp.left, nativeApp.top, nativeApp.right, nativeApp.bottom)))
                        .put("nativeAppBuffer", "${point.label}-${p.role}-app.png")
                    val ink = counts(buffer, r, p.channel)
                    receipt.put("nativePaintPixels", ink).put("nativeBodyRegion", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                        .put("nativeWidth", buffer.width).put("nativeHeight", buffer.height).put("nativeGeneration", generation)
                        .put("nativeBuffer", "${point.label}-${p.role}-original.png")
                } finally { buffer.recycle() }
            } else receipt.put("nativeCurrentClaim", if (state.blocked) "incomplete-blocked-producer" else "not-attached")
            owners.put(receipt)
        }
        // One actual system-composited image includes the currently displayed child even while its producer is blocked.
        val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Incomplete compositor capture" }
        try {
            save("${point.label}-display-original.png", screen)
            val root = before.first { it.probe.role == "root" }
            val crop = visibleRect(root, screen.width, screen.height)
            check(crop.width() > 0 && crop.height() > 0)
            check(crop.top >= root.insets[1] && screen.height - crop.bottom >= root.insets[3]) { "System bars entered app capture" }
            val app = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
            try { save("${point.label}-app.png", app) } finally { if (app !== screen) app.recycle() }
            row.put("appCrop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom)))
            for (i in before.indices) {
                val state = before[i]; val r = visibleRect(state, screen.width, screen.height)
                val owner = owners.getJSONObject(i)
                owner.put("displayIntersection", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                    .put("compositedPaintPixels", if (state.attached) counts(screen, r, state.probe.channel) else 0)
                    .put("currentCompositeClaim", true)
            }
        } finally { screen.recycle() }
        val after = snapshot()
        check(before.map { listOf(it.sequence, it.nanos, it.viewIdentity, it.tokenIdentity, it.localReduced, it.clockAccess, it.clockSpecAccess, it.bounds, it.surfaceBounds) } ==
            after.map { listOf(it.sequence, it.nanos, it.viewIdentity, it.tokenIdentity, it.localReduced, it.clockAccess, it.clockSpecAccess, it.bounds, it.surfaceBounds) }) {
            "Observation crossed Engine/policy/native geometry at ${point.label}"
        }
        val immutableValues = rule.runOnUiThread(supplemental)
        val serializedValues = immutableValues.mapValues { (_, value) -> if (value is SearchMorphSample) value.toString() else value }
        row.put("owners", owners).put("supplemental", JSONObject(serializedValues))
        rows.put(row)
        File(output, "frames.json").writeText(JSONObject().put("method", method)
            .put("initialClockMs", initialClock).put("frames", rows).toString(2))
    }
    override fun close() {
        rule.runOnUiThread {
            listeners.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
            listeners.clear()
            probes.forEach { p -> p.view?.let { if (p.markerInstalled) it.overlay.remove(p.marker) }; p.markerInstalled = false }
        }
        if (!enabled) HardwareRendererCompat.setDrawingEnabled(false)
    }
}

private fun canonicalReducedWindow(view: View): Window {
    var parent: android.view.ViewParent? = view.parent
    while (parent != null) { if (parent is DialogWindowProvider) return parent.window; parent = parent.parent }
    fun activity(context: Context): Window = when (context) {
        is Activity -> context.window
        is ContextWrapper -> activity(context.baseContext)
        else -> error("No actual native window")
    }
    return activity(view.context)
}
