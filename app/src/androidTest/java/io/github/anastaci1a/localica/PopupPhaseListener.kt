package io.github.anastaci1a.localica

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.DerivedPopupPhase
import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.GlassPopupFlight
import io.github.anastaci1a.localica.ui.IllegalPopupState
import io.github.anastaci1a.localica.ui.PopupFlags
import io.github.anastaci1a.localica.ui.PopupPhase
import io.github.anastaci1a.localica.ui.PopupTakeover
import io.github.anastaci1a.localica.ui.popupPhaseOf
import io.github.anastaci1a.localica.ui.popupPhaseProbe
import io.github.anastaci1a.localica.ui.legalPopupEdge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.WeakHashMap

/**
 * #920 step 1 assertion hook (instrumentation argument `listener`). Samples every live popup's derived model phase at
 * each snapshot write or apply, before each window draws, and when the popup leaves composition. It reports:
 * illegal states (by sample kind), phase edges outside the model's transition table, and cross-role differences:
 * the same event in the same phase must give the same phase sequence for every trigger role (no TriggerPolicy
 * data declares a difference yet). Nothing here changes the app; the report is popup-phase-report.json.
 */
class PopupPhaseListener : RunListener() {
    private class Popup(val anchor: GlassAnchor, var reader: () -> PopupFlags) {
        var flags: PopupFlags? = null
        var legal: PopupPhase? = null
        var pending: String? = null // "phase|event" whose outcome is being recorded
        val outcome = mutableListOf<String>()
    }
    private class Tally(val witness: String, val flags: String) { var count = 0 }

    private val popups = LinkedHashMap<GlassPopupFlight, Popup>()
    private val states = LinkedHashMap<String, Tally>()
    private val edges = LinkedHashMap<String, Tally>()
    private val perTest = LinkedHashMap<String, MutableMap<String, Int>>()
    private val outcomes = LinkedHashMap<String, MutableMap<String, MutableMap<String, String>>>()
    private val drawn = WeakHashMap<View, Boolean>()
    private var test = "run"
    private var handles = emptyList<ObserverHandle>()
    private val onDraw = ViewTreeObserver.OnDrawListener { sampleAll("draw") }

    override fun testRunStarted(description: Description) {
        popupPhaseProbe = { flight, anchor, reader -> synchronized(this) {
            if (reader == null) popups.remove(flight)?.let { sample(it, "dispose"); event(it, "Leave") }
            else popups.getOrPut(flight) { Popup(anchor, reader) }.reader = reader
        } }
        handles = listOf(Snapshot.registerGlobalWriteObserver { sampleAll("write") },
            Snapshot.registerApplyObserver { _, _ -> sampleAll("write") })
    }

    override fun testStarted(description: Description) = synchronized(this) {
        test = description.className.substringAfterLast('.') + "#" + description.methodName
    }

    private fun sampleAll(kind: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) attachDrawListeners()
        synchronized(this) { popups.values.forEach { sample(it, kind) } }
    }

    private fun attachDrawListeners() {
        @Suppress("UNCHECKED_CAST", "DiscouragedPrivateApi")
        val roots = if (Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
            else Class.forName("android.view.WindowManagerGlobal").let { c ->
                c.getDeclaredField("mViews").apply { isAccessible = true }.get(c.getMethod("getInstance").invoke(null)) as List<View>
            }
        roots.toList().forEach { if (drawn.put(it, true) == null) it.viewTreeObserver.addOnDrawListener(onDraw) }
    }

    private fun sample(popup: Popup, kind: String) {
        val flags = Snapshot.withoutReadObservation { popup.reader() }
        val previous = popup.flags
        if (flags == previous && kind == "write") return
        popup.flags = flags
        if (previous != null) {
            if (flags.shown != previous.shown) event(popup, if (flags.shown) "Show" else "Hide")
            if (flags.homeDeparting != previous.homeDeparting) event(popup, if (flags.homeDeparting) "HomeDeparts" else "HomeReturns")
            if (flags.homeGone && !previous.homeGone) event(popup, "HomeGone")
        }
        val derived = popupPhaseOf(flags)
        val state = "$kind ${name(derived)} ${role(popup, flags)}"
        tally(states, state, flags)
        perTest.getOrPut(test) { LinkedHashMap() }.merge(state, 1, Int::plus)
        val phase = derived as? PopupPhase ?: return
        val from = popup.legal
        popup.legal = phase
        if (from == null || from == phase) return
        if (!legalPopupEdge(from, phase)) tally(edges, "${name(from)} -> ${name(phase)} ${role(popup, flags)}", flags)
        if (popup.pending != null) popup.outcome += name(phase)
        if (phase.takeover == PopupTakeover.Closed) close(popup)
    }

    private fun event(popup: Popup, event: String) {
        close(popup)
        popup.pending = "${popup.legal?.let(::name) ?: "Closed"} | $event"
    }

    /** Record what followed the pending (phase, event) for this role; prefixes are compatible outcomes. */
    private fun close(popup: Popup) {
        val key = popup.pending ?: return
        val role = popup.flags?.let { role(popup, it) } ?: "unknown"
        outcomes.getOrPut(key) { LinkedHashMap() }.getOrPut(role) { LinkedHashMap() }
            .putIfAbsent(popup.outcome.joinToString(" > ").ifEmpty { "(no change)" }, test)
        popup.pending = null
        popup.outcome.clear()
    }

    private fun tally(into: MutableMap<String, Tally>, key: String, flags: PopupFlags) {
        into.getOrPut(key) { Tally(test, flags.toString()) }.count++
    }

    private fun name(phase: DerivedPopupPhase) = when (phase) {
        is IllegalPopupState -> "ILLEGAL(${phase.rule})"
        is PopupPhase -> phase.takeover.name + (phase.presenter?.let { "/$it" } ?: "")
    }

    private fun role(popup: Popup, flags: PopupFlags) = when {
        flags.preparedOrigin -> "prepared-origin"
        popup.anchor.chromeTrigger -> "navigation"
        popup.anchor.contextual -> "contextual"
        popup.anchor.quiet -> "plain-icon"
        else -> "surface"
    }

    override fun testRunFinished(result: Result) {
        handles.forEach { it.dispose() }
        popupPhaseProbe = null
        val report = synchronized(this) {
            popups.values.forEach(::close)
            fun JSONObject.put(map: Map<String, Tally>) = apply {
                map.forEach { (k, t) -> put(k, JSONObject().put("count", t.count).put("first", t.witness).put("flags", t.flags)) }
            }
            val roles = JSONObject()
            outcomes.forEach { (key, byRole) ->
                val all = byRole.values.flatMap { it.keys }.distinct()
                val conflict = all.any { a -> all.any { b -> !a.startsWith(b) && !b.startsWith(a) } }
                if (conflict) roles.put(key, JSONObject().apply { byRole.forEach { (r, o) -> put(r, JSONObject(o as Map<*, *>)) } })
            }
            JSONObject().put("states", JSONObject().put(states)).put("illegalEdges", JSONObject().put(edges))
                .put("outcomeConflicts", roles)
                .put("perTest", JSONObject(perTest as Map<*, *>))
                .put("outcomes", JSONObject().apply { outcomes.forEach { (k, v) -> put(k, JSONObject(v.mapValues { JSONArray(it.value.keys) })) } })
        }
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.path
        File(root).apply { mkdirs() }.resolve("popup-phase-report.json").writeText(report.toString(1))
    }
}
