package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.engine.LayoutShape
import io.github.anastaci1a.localica.ui.engine.ShadowPlane
import io.github.anastaci1a.localica.ui.engine.SurfaceNode
import io.github.anastaci1a.localica.ui.engine.SurfaceView
import io.github.anastaci1a.localica.ui.engine.windowRetained
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic only. Weak observations never keep a former fixture alive. All reads run on Main. */
internal object NoneShadowLeakReceipt {
    private val planes = mutableListOf<WeakReference<ShadowPlane>>()
    private val anchors = mutableListOf<WeakReference<GlassAnchor>>()
    private val nodes = mutableListOf<WeakReference<SurfaceNode>>()

    fun track(value: Any?) {
        when (value) {
            is ShadowPlane -> if (planes.none { it.get() === value }) planes += WeakReference(value)
            is GlassAnchor -> if (anchors.none { it.get() === value }) anchors += WeakReference(value)
            is SurfaceNode -> if (nodes.none { it.get() === value }) nodes += WeakReference(value)
        }
    }

    private fun identity(value: Any?): Any = value?.let { System.identityHashCode(it) } ?: JSONObject.NULL

    private fun node(value: SurfaceView?): Any {
        if (value == null) return JSONObject.NULL
        val owned = value as SurfaceNode
        return JSONObject().put("identity", identity(value)).put("keyIdentity", identity(value.key))
            .put("keyType", value.key.javaClass.name).put("disposed", owned.isDisposed)
            .put("windowRetained", value.windowRetained()).put("retained", value.retained)
            .put("stratum", value.stratum.name).put("shadowRole", owned.role.name)
            .put("sourcePlaneIdentity", identity(owned.sourcePlane))
            .put("sourceGeneration", owned.sourceGeneration)
            .put("layoutAttached", (value.shape as? LayoutShape)?.coordinates?.isAttached == true)
    }

    fun snapshot(): String {
        val observedPlanes = planes.mapNotNull { it.get() }
        val observedAnchors = anchors.mapNotNull { it.get() }
        val observedNodes = nodes.mapNotNull { it.get() }
        return JSONObject().put("sample", "before selected row fixture setContent")
            .put("origin", "first queue parity predecessor existing composition data walks")
            .put("strongOwnershipAdded", false).put("motionValuesRead", false)
            .put("planeWeakReferences", planes.size).put("anchorWeakReferences", anchors.size)
            .put("nodeWeakReferences", nodes.size)
            .put("planes", JSONArray(observedPlanes.map { plane ->
                JSONObject().put("identity", identity(plane)).put("routeEpoch", plane.host.routeEpoch)
                    .put("windowIdentity", identity(plane.host.window))
                    .put("viewAttached", plane.view?.isAttachedToWindow == true)
                    .put("layoutAttached", plane.layout?.isAttached == true)
                    .put("entries", JSONArray(plane.casters.map { caster ->
                        JSONObject().put("node", node(caster)).put("ownsPlane", caster.owns(plane))
                    }))
            }))
            .put("anchors", JSONArray(observedAnchors.map { anchor ->
                JSONObject().put("identity", identity(anchor)).put("shadowPlaneIdentity", identity(anchor.shadowPlane))
                    .put("surfaceNode", node(anchor.surfaceNode)).put("returningNode", node(anchor.returningNode))
            }))
            .put("nodes", JSONArray(observedNodes.map { node(it) })).toString(2)
    }

    fun pageSnapshot(coordinates: androidx.compose.ui.layout.LayoutCoordinates?, host: android.view.View?): String {
        val root = host?.rootView
        var context = host?.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) {
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        val activity = context as? android.app.Activity
        val decor = activity?.window?.decorView ?: root
        fun location(view: android.view.View?): Any = view?.let {
            JSONArray(IntArray(2).also(it::getLocationOnScreen).toList())
        } ?: JSONObject.NULL
        val attached = coordinates?.takeIf { it.isAttached }
        val position = attached?.let { it.positionInWindow() }
        val bounds = attached?.let { it.boundsInWindow() }
        val insets = host?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
        fun inset(type: Int, ignoringVisibility: Boolean): Any {
            val value = if (ignoringVisibility) insets?.getInsetsIgnoringVisibility(type) else insets?.getInsets(type)
            return value?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL
        }
        return JSONObject().put("sampleUptimeMillis", android.os.SystemClock.uptimeMillis())
            .put("pagePositionInWindow", position?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            .put("pageBoundsInWindow", bounds?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            .put("pageCoordinatesAttached", attached != null)
            .put("rootLocationOnScreen", location(root)).put("hostLocationOnScreen", location(host))
            .put("rootIdentity", identity(root)).put("rootClass", root?.javaClass?.name ?: JSONObject.NULL)
            .put("decorIdentity", identity(decor)).put("decorResolvedFromActivity", activity != null)
            .put("decorScrollY", decor?.scrollY ?: JSONObject.NULL)
            .put("decorTranslationY", decor?.translationY ?: JSONObject.NULL)
            .put("decorTop", decor?.top ?: JSONObject.NULL)
            .put("statusInsets", inset(androidx.core.view.WindowInsetsCompat.Type.statusBars(), false))
            .put("statusInsetsIgnoringVisibility", inset(androidx.core.view.WindowInsetsCompat.Type.statusBars(), true))
            .put("navigationInsets", inset(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), false))
            .put("navigationInsetsIgnoringVisibility", inset(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), true))
            .put("hostViewTop", host?.top ?: JSONObject.NULL)
            .put("hostViewTranslationY", host?.translationY ?: JSONObject.NULL)
            .put("hostWindowTokenIdentity", identity(host?.windowToken))
            .put("hostAttached", host?.isAttachedToWindow == true).toString(2)
    }
}
