@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.*
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.*
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.components.NativeViewportRelocationHandle
import io.github.anastaci1a.localica.ui.components.rememberNativeViewportRelocation
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

@Composable
internal fun rememberPopupEditorFocus(state: ScrollState, admission: () -> Boolean): PopupEditorFocus {
    val view = LocalView.current
    val window = LocalWindowInfo.current
    val latestAdmission by rememberUpdatedState(admission)
    val handle = rememberNativeViewportRelocation(state, view) {
        view.isAttachedToWindow && view.hasWindowFocus() && latestAdmission()
    }
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val layout = remember(view, state, handle) { PopupEditorFocus(state, ime, source, target, handle) }
    DisposableEffect(layout) { onDispose { layout.dispose() } }
    LaunchedEffect(layout, window) { snapshotFlow { window.isWindowFocused }.collect { handle.admissionChanged() } }
    return layout
}

@Composable
internal fun PopupEditorFocusPolicy(owner: PopupEditorFocus, content: @Composable () -> Unit) = owner.Body(content)

/** Native geometry and request propagation only. This adapter has no mutation lease or animator. */
internal class PopupEditorFocus(private val state: ScrollState,
    private val ime: WindowInsets, private val source: WindowInsets, private val target: WindowInsets,
    private val handle: NativeViewportRelocationHandle) {
    private var disposed = false
    private var measure = 0L
    private var placed = 0L
    private var focus = 0L
    private var nativeStep = 0L
    private var frame: NativeRelocationFrame? = null
    private var viewport: LayoutCoordinates? = null
    private var focused: LayoutCoordinates? = null
    private var appliedScroll = 0
    private var placementStep = 0L
    private var requestSequence = 0L
    private var request: Request? = null
    private data class Request(val id: Long, val focus: Long, val child: LayoutCoordinates, val bounds: () -> Rect?)
    val bodySpec get() = handle.bodyPolicy
    @Composable fun Body(content: @Composable () -> Unit) = handle.Body(content)
    @Composable fun Children(content: @Composable () -> Unit) = handle.Children(content)
    fun progress(running: Boolean) = handle.progress(running)
    fun modalCancellation(reason: String = "modal") = handle.cancel(reason)

    private val flingEvidence = object : NestedScrollConnection {
        override suspend fun onPreFling(available: Velocity): Velocity {
            handle.preFling(currentCoroutineContext().job)
            return Velocity.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            handle.postFling(currentCoroutineContext().job)
            return Velocity.Zero
        }
    }
    val beforeScroll: Modifier = Modifier.layout { measurable, constraints ->
        val version = beforeMeasure(this)
        val child = measurable.measure(constraints)
        layout(child.width, child.height) {
            val actualScroll = state.value
            val actualStep = nativeStep
            child.place(0, 0)
            appliedScroll = actualScroll
            placementStep = actualStep
            afterPlacement(version)
        }
    }.onGloballyPositioned { viewport = it; afterPlacement(measure) }
        .onFocusedBoundsChanged { coordinates ->
            if (focused !== coordinates) {
                focused = coordinates; focus++
                request = null
                frame = frame?.copy(focus = focus, focused = coordinates != null)
                handle.focus(focus, coordinates != null)
            }
            if (coordinates != null) afterPlacement(placed)
        }.nestedScroll(flingEvidence)
    val afterScroll: Modifier = RequestElement(this)

    private fun beforeMeasure(density: Density): Long {
        if (disposed) return measure
        measure++
        val current = ime.getBottom(density)
        val from = source.getBottom(density)
        val to = target.getBottom(density)
        val old = frame
        if (old != null && (current != old.currentIme || from != old.sourceIme || to != old.targetIme)) nativeStep++
        val next = NativeRelocationFrame(handle.window, measure, nativeStep, focus, focused != null, current, from, to)
        frame = next
        handle.native(next)
        return measure
    }
    private fun afterPlacement(version: Long) {
        if (disposed || version != measure) return
        placed = version
        val evidence = frame ?: return
        if (placementStep != nativeStep) return
        val body = viewport?.takeIf { it.isAttached } ?: return
        if (body.size.height != state.viewportSize) return
        val field = focused?.takeIf { it.isAttached }
        val bounds = field?.let { actualTarget(body, it) }
        handle.placed(NativeRelocationPlacement(evidence, appliedScroll, state.viewportSize,
            bounds?.let { NativeRelocationTarget(it.top, it.bottom) }))
    }
    private fun actualTarget(body: LayoutCoordinates, field: LayoutCoordinates): Rect? {
        val active = request?.takeIf { it.focus == focus && it.child.isAttached && sameFocusedSubtree(it.child, field) }
        val caret = active?.bounds?.invoke()
        return if (active != null && caret != null)
            Rect(body.localPositionOf(active.child, caret.topLeft), body.localPositionOf(active.child, caret.bottomRight))
        else body.localBoundingBoxOf(field, clipBounds = false)
    }
    private fun sameFocusedSubtree(child: LayoutCoordinates, field: LayoutCoordinates): Boolean {
        fun reaches(start: LayoutCoordinates, end: LayoutCoordinates): Boolean {
            var cursor: LayoutCoordinates? = start
            while (cursor != null && cursor.isAttached) {
                if (cursor === end) return true
                cursor = cursor.parentCoordinates
            }
            return false
        }
        return reaches(child, field) || reaches(field, child)
    }
    suspend fun request(node: RequestNode, child: LayoutCoordinates, bounds: () -> Rect?) {
        if (disposed) return
        val token = Request(++requestSequence, focus, child, bounds)
        request = token
        handle.request(token.id, token.focus) {
            if (child.isAttached && !disposed) node.parentRequest(child) {
                if (!disposed && child.isAttached && handle.currentRequest(token.id, token.focus)) bounds() else null
            }
        }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        request = null; focused = null; viewport = null
        handle.cancel("layout-disposed")
    }
}

private data class RequestElement(val owner: PopupEditorFocus) : ModifierNodeElement<RequestNode>() {
    override fun create() = RequestNode(owner)
    override fun update(node: RequestNode) { node.owner = owner }
    override fun InspectorInfo.inspectableProperties() { name = "popupEditorFocusRequest" }
}
internal class RequestNode(var owner: PopupEditorFocus) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) =
        owner.request(this, childCoordinates, boundsProvider)
    suspend fun parentRequest(child: LayoutCoordinates, bounds: () -> Rect?) {
        this.bringIntoView {
            if (!isAttached || !child.isAttached) null
            else bounds()?.let { rect ->
                val own = requireLayoutCoordinates()
                Rect(own.localPositionOf(child, rect.topLeft), own.localPositionOf(child, rect.bottomRight))
            }
        }
    }
}
