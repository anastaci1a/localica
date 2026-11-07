package io.github.anastaci1a.localica.diagnostics

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/** Opt-in, main-thread lifecycle. Before Start it only weakly remembers open modal windows:
 * no listener, polling, worker or frame storage. */
internal object FrameStats {
    val recording = mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var started = 0L
    private var ended = Long.MAX_VALUE
    private var environment = ""
    private var glass = ""
    private var effectiveGlass = ""
    private var windowLimitReached = false
    private var timeLimitReached = false
    private val windows = IdentityHashMap<Window, Window.OnFrameMetricsAvailableListener>()
    // Modal windows the app has shown, weakly held: Start must also measure one that is already open
    // (the Settings popup itself) without recomposing the app to rediscover it (#620).
    private val modalWindows = java.util.WeakHashMap<Window, Unit>()
    private val samples = mutableListOf<Pair<String, FrameTiming>>()
    private val timeout = Runnable { timeLimitReached = true; stop() }
    private var owner: Activity? = null

    @Synchronized fun start(activity: Activity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!recording.value && worker == null) { "A capture is still closing" }
        samples.clear(); windowLimitReached = false; timeLimitReached = false
        owner = activity
        environment = DiagnosticsExport.environment(activity).toString()
        glass = io.github.anastaci1a.localica.navigation.InterfacePreferences(activity).read().glass.toString()
        effectiveGlass = io.github.anastaci1a.localica.ui.effectiveGlassBackend(
            io.github.anastaci1a.localica.navigation.InterfacePreferences(activity).read().glass.renderer, Build.VERSION.SDK_INT).name
        started = System.nanoTime(); ended = Long.MAX_VALUE
        worker = HandlerThread("localica-frame-stats").also { it.start() }
        handler = Handler(requireNotNull(worker).looper)
        recording.value = true
        attach(activity.window, "activity")
        modalWindows.keys.toList().filter { it.peekDecorView()?.isAttachedToWindow == true }.forEach { attach(it) }
        main.postDelayed(timeout, 120_000)
    }

    /** Registers a modal window on every composition of its content; attaches it while recording.
     * The app provides this once, so Start and Stop never change composition state above the button. */
    @Synchronized fun observe(window: Window?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (window == null) return
        modalWindows[window] = Unit
        attach(window)
    }

    /** Also observes the app's separate modal windows through the existing committed-scope observer. */
    @Synchronized fun attach(window: Window?, role: String = "modal") {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!recording.value || window == null || windows.containsKey(window)) return
        if (windows.size >= 32) { windowLimitReached = true; return }
        val sample = FrameTiming()
        samples += "$role-${samples.size}" to sample
        val display = window.decorView.display ?: owner?.window?.decorView?.display
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, drops ->
            val hz = display?.refreshRate?.toDouble() ?: 0.0
            sample.add(frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP), frame.getMetric(FrameMetrics.TOTAL_DURATION),
                if (hz > 0 && hz.isFinite()) (1e9 / hz).toLong() else 0L,
                if (Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.DEADLINE) else -1, drops)
        }
        window.addOnFrameMetricsAvailableListener(listener, requireNotNull(handler))
        windows[window] = listener
    }

    @Synchronized fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!recording.value) return
        ended = System.nanoTime()
        recording.value = false
        main.removeCallbacks(timeout)
        windows.forEach { (window, listener) -> window.removeOnFrameMetricsAvailableListener(listener) }
        windows.clear(); owner = null
        // A barrier drains already posted callbacks only; native delivery completeness is not claimed.
        val closing = worker
        handler?.post { closing?.quitSafely() }
        worker = null; handler = null
    }

    @Synchronized fun stopFor(activity: Activity) { if (owner === activity) stop() }

    @Synchronized fun report(): String {
        val end = if (recording.value) System.nanoTime() else ended
        return JSONObject().put("schema", 1).put("environment", environment).put("glass", glass)
            .put("effectiveGlass", effectiveGlass).put("windowLimitReached", windowLimitReached)
            .put("timeLimitReached", timeLimitReached)
            .put("recording", recording.value).put("durationMs", if (started == 0L) 0 else (end - started) / 1e6)
            .put("scope", "Received app Window frames; excludes system IME. Native tail may be incomplete.")
            .put("jankDefinition", "TOTAL_DURATION > sampled display refresh period; not presentation latency")
            .put("missedVsyncDefinition", "estimate: sum(max(0, ceil(duration / refreshPeriod) - 1)); not idle gaps")
            .put("windows", JSONArray(samples.map { (name, sample) -> json(name, sample.snapshot(started, end)) })).toString(2)
    }

    fun json(name: String, s: FrameTiming.Summary): JSONObject = JSONObject().put("window", name)
        .put("frames", s.count).put("p50Ms", s.p50Ms ?: JSONObject.NULL)
        .put("p90Ms", s.p90Ms ?: JSONObject.NULL).put("p99Ms", s.p99Ms ?: JSONObject.NULL)
        .put("jankPercent", s.jankPercent ?: JSONObject.NULL).put("estimatedMissedVsyncs", s.estimatedMissedVsyncs)
        .put("deadlineMisses", if (s.deadlineSamples > 0) s.deadlineMisses else JSONObject.NULL)
        .put("deadlineSamples", s.deadlineSamples).put("listenerDrops", s.listenerDrops)
        .put("overflow", s.overflow).put("invalid", s.invalid)
        .put("refreshPeriodsNanos", JSONArray(s.frames.map { it.budget }.distinct()))
}
