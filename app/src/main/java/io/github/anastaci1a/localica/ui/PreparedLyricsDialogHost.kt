package io.github.anastaci1a.localica.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Attached, measured, pre-draw evidence only: neither a rendered frame nor compositor readiness. */
internal class PreparedLyricsReadiness internal constructor(
    private val incarnation: Any,
    private val viewportGeneration: Long,
) {
    internal fun matches(owner: Any, generation: Long) =
        incarnation === owner && viewportGeneration == generation
}

/**
 * Owns only this native window. It does not hide a trigger, move glass, or activate modal gestures.
 * Call prepare(), then explicitly claim the current token. retire() is terminal; a new composition
 * key creates a new incarnation. The caller coordinates its source/shadow with a successful claim.
 * Resize replaces readiness/geometry evidence, not an already claimed window's modal ownership.
 */
@Composable
internal fun rememberPreparedLyricsDialogHost(
    onReadinessChanged: (PreparedLyricsReadiness?) -> Unit,
    onOwnershipChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
): PreparedLyricsDialogHost {
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val viewId = rememberSaveable { View.generateViewId() }
    val readiness = rememberUpdatedState(onReadinessChanged)
    val ownership = rememberUpdatedState(onOwnershipChanged)
    val dismiss = rememberUpdatedState(onDismissRequest)
    val body = rememberUpdatedState(content)
    val host = remember(view, parent, density, direction, viewId) {
        PreparedLyricsDialogHost(view, parent, direction, viewId,
            { readiness.value(it) }, { ownership.value(it) }, { dismiss.value() }) { body.value() }
    }
    DisposableEffect(host) { onDispose { host.retire() } }
    return host
}

internal class PreparedLyricsDialogHost internal constructor(
    private val caller: View,
    parent: CompositionContext,
    direction: LayoutDirection,
    viewId: Int,
    private val onReadinessChanged: (PreparedLyricsReadiness?) -> Unit,
    private val onOwnershipChanged: (Boolean) -> Unit,
    private val onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    private val incarnation = Any()
    private var generation = 0L
    private var prepared = false
    private var retired = false
    private var viewport: Viewport? = null
    private var token: PreparedLyricsReadiness? = null
    var ownsWindow: Boolean = false
        private set

    private val dialog = ComponentDialog(caller.context, android.R.style.Theme_Material_Light_NoActionBar)
    // Exposed inside this module for geometry integration and native-boundary assertions only.
    val window: Window = requireNotNull(dialog.window)
    val composeView: AbstractComposeView = object : AbstractComposeView(dialog.context), DialogWindowProvider {
        override val window: Window get() = this@PreparedLyricsDialogHost.window
        @Composable override fun Content() {
            // A caller may explicitly provide its dispatcher as a composition local. Never let
            // prepared child BackHandlers register there; this window owns their dispatcher.
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dialog) { content() }
        }
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!retired && ownsWindow) onDismissRequest()
        }
    }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> observeViewport() }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { observeViewport() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        observeViewport()
        if (!retired && prepared && token == null && completedLayout() && actualViewport() != null) {
            val next = PreparedLyricsReadiness(incarnation, generation)
            token = next
            onReadinessChanged(next)
        }
        true
    }
    private var observedTree: ViewTreeObserver? = null
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (retired) return
            observedTree = view.viewTreeObserver.also {
                it.addOnGlobalLayoutListener(globalLayout)
                it.addOnPreDrawListener(preDraw)
            }
        }
        override fun onViewDetachedFromWindow(view: View) { retire() }
    }

    init {
        mainThread()
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setWindowAnimations(0)
        window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.id = viewId
        composeView.layoutDirection = if (direction == LayoutDirection.Rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        // ComponentDialog installs its own owners on the decor. These nearer caller-owner tags
        // belong to our host and are installed before attachment/child composition can happen.
        composeView.setViewTreeLifecycleOwner(requireNotNull(caller.findViewTreeLifecycleOwner()))
        composeView.setViewTreeSavedStateRegistryOwner(requireNotNull(caller.findViewTreeSavedStateRegistryOwner()))
        composeView.setViewTreeViewModelStoreOwner(caller.findViewTreeViewModelStoreOwner())
        composeView.setParentCompositionContext(parent)
        composeView.addOnAttachStateChangeListener(attachment)
        composeView.addOnLayoutChangeListener(layout)
        dialog.setContentView(composeView, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.onBackPressedDispatcher.addCallback(back)
        dialog.setOnCancelListener { retire() }
        dialog.setOnDismissListener { retire() }
        // No content effect is involved: these attributes exist before the only show() below.
        setNativeOwnership(false)
    }

    fun prepare(): Boolean {
        mainThread()
        if (retired || !caller.isAttachedToWindow) return false
        if (prepared) return true
        prepared = true
        try {
            // Preparation may follow construction later, after the caller changed FLAG_SECURE.
            setNativeOwnership(false)
            dialog.show()
        } catch (failure: Throwable) {
            try { retire() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        return !retired
    }

    fun claim(readiness: PreparedLyricsReadiness): Boolean {
        mainThread()
        if (retired || !prepared) return false
        observeViewport()
        if (retired || token !== readiness || !readiness.matches(incarnation, generation) ||
            !completedLayout() || actualViewport() != viewport) return false
        val firstClaim = !ownsWindow
        try { setNativeOwnership(true) } catch (failure: Throwable) {
            try { retire() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        ownsWindow = true
        if (firstClaim) onOwnershipChanged(true)
        return !retired && ownsWindow
    }

    /** Cancellation and composition disposal use the same irreversible, owned teardown. */
    fun retire() {
        mainThread()
        if (retired) return
        retired = true
        prepared = false
        generation++
        val hadToken = token != null
        val wasOwner = ownsWindow
        token = null
        ownsWindow = false
        val tree = observedTree
        observedTree = null
        // Detachment can make an attributes update fail. Never let that skip dismissal,
        // composition disposal or either notification, and retain the first failure's identity.
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            }
        }
        attempt { back.remove() }
        attempt { composeView.removeOnAttachStateChangeListener(attachment) }
        attempt { composeView.removeOnLayoutChangeListener(layout) }
        attempt { if (tree?.isAlive == true) tree.removeOnGlobalLayoutListener(globalLayout) }
        attempt { if (tree?.isAlive == true) tree.removeOnPreDrawListener(preDraw) }
        attempt { setNativeOwnership(false) }
        attempt { dialog.dismiss() }
        attempt { composeView.disposeComposition() }
        attempt { if (hadToken) onReadinessChanged(null) }
        attempt { if (wasOwner) onOwnershipChanged(false) }
        failure?.let { throw it }
    }

    private fun observeViewport() {
        if (retired || !prepared) return
        val actual = actualViewport()
        if (actual == viewport) return
        viewport = actual
        generation++
        val hadToken = token != null
        token = null
        // Before claim this stays inert. After claim, retain input/Back/visibility until retire;
        // the next pre-draw reports fresh geometry for the route owner without reactivating source.
        if (hadToken) onReadinessChanged(null)
    }

    private fun setNativeOwnership(enabled: Boolean) {
        back.isEnabled = enabled
        val inaccessible = if (enabled) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.importantForAccessibility = inaccessible
        window.decorView.importantForAccessibility = inaccessible
        window.attributes = window.attributes.apply {
            flags = if (enabled) flags and InertInputFlags.inv() else flags or InertInputFlags
            flags = flags and LayoutParams.FLAG_DIM_BEHIND.inv()
            // Match Compose 1.8.2 SecureFlagPolicy.Inherit via public caller-root attributes.
            if (!retired) {
                val callerSecure = ((caller.rootView.layoutParams as? LayoutParams)?.flags ?: 0) and LayoutParams.FLAG_SECURE != 0
                flags = if (callerSecure) flags or LayoutParams.FLAG_SECURE else flags and LayoutParams.FLAG_SECURE.inv()
            }
            // Teardown never relaxes security because the caller has already detached.
            dimAmount = 0f
            alpha = if (enabled) 1f else 0f
            windowAnimations = 0
        }
    }

    private fun completedLayout() = composeView.isLaidOut && !composeView.isLayoutRequested &&
        composeView.childCount > 0 && !composeView.getChildAt(0).isLayoutRequested

    private fun actualViewport(): Viewport? {
        if (!composeView.isAttachedToWindow ||
            composeView.width <= 0 || composeView.height <= 0 || composeView.childCount == 0) return null
        val child = composeView.getChildAt(0)
        if (child.measuredWidth <= 0 || child.measuredHeight <= 0) return null
        val position = IntArray(2)
        composeView.getLocationOnScreen(position)
        val insets = ViewCompat.getRootWindowInsets(composeView)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            ?: return null
        val configuration = composeView.resources.configuration
        return Viewport(position[0], position[1], composeView.width, composeView.height, insets,
            configuration.densityDpi, configuration.fontScale, configuration.layoutDirection)
    }

    private data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int,
        val insets: Insets, val densityDpi: Int, val fontScale: Float, val layoutDirection: Int)

    private companion object {
        const val InertInputFlags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE
        fun mainThread() { check(Looper.myLooper() === Looper.getMainLooper()) { "Lyrics host is main-thread owned" } }
    }
}
