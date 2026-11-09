package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*

internal class FloatingRowScene(val owner: ToastRowOwner, val queue: Boolean = false)
internal val LocalFloatingRowScene = staticCompositionLocalOf<FloatingRowScene?> { null }
internal val LocalFloatingRowFaces = staticCompositionLocalOf<FloatingRowFaces?> { null }
internal data class RowFaceAdmission(val drawn: Boolean, val interactive: Boolean)
internal val LocalRowFaceAdmission = compositionLocalOf { RowFaceAdmission(true, true) }

/** Composition-local face transport, separate from the app queue and passive row geometry registry.
 * Every source owns its menus/launchers. Only its face lambda and numeric measurement cross here. */
internal class FloatingRowFaces(val window: Any) {
    internal class Face(val scene: FloatingRowScene, val slot: Any, initialSize: IntSize,
        val content: @Composable () -> Unit) {
        var size by mutableStateOf(initialSize)
        var measured by mutableStateOf(false)
        private var receipt by mutableStateOf<Rect?>(null)
        private var coordinates: LayoutCoordinates? = null
        var original: Rect?
            get() {
                val measured = receipt ?: return null
                val source = coordinates ?: return measured
                if (!source.isAttached) return null
                val root = generateSequence(source) { it.parentLayoutCoordinates }.last()
                return if (root.isAttached) root.localBoundingBoxOf(source, clipBounds = false) else null
            }
            set(value) { receipt = value }
        fun placed(source: LayoutCoordinates) {
            coordinates = source
            if (source.isAttached) {
                val root = generateSequence(source) { it.parentLayoutCoordinates }.last()
                receipt = root.localBoundingBoxOf(source, clipBounds = false)
            }
        }
        var visible by mutableStateOf(false)
        var interactive by mutableStateOf(false)
    }
    private var registered by mutableStateOf<List<Face>>(emptyList())
    val faces: List<Face> get() = registered
    fun acceptsWindow(source: Any): Boolean = source === window
    fun attach(face: Face) {
        registered = registered.filterNot { it.scene === face.scene && it.slot == face.slot } + face
    }
    fun detach(face: Face) { registered = registered.filterNot { it === face } }
    fun measured(face: Face, size: IntSize) {
        if (face in registered && size.width > 0 && size.height > 0) { face.size = size; face.measured = true }
    }
    fun originals(scene: FloatingRowScene): List<Face> = registered.filter { it.scene === scene && it.original != null }
}

/** The native-window check happens at the actual source, including inherited popup composition.
 * The placeholder has no material, field or input. Its real root face supplies the size. */
@Composable internal fun FloatingRowFace(slot: Any, initialSize: IntSize, visible: Boolean,
    interactive: Boolean = visible, modifier: Modifier = Modifier, onMeasured: ((IntSize) -> Unit)? = null, inline: @Composable () -> Unit,
    face: @Composable () -> Unit) {
    val host = LocalFloatingRowFaces.current
    val scene = LocalFloatingRowScene.current
    val window = LocalView.current.rootView
    if (host == null || scene == null || !host.acceptsWindow(window)) {
        inline()
        return
    }
    val current = rememberUpdatedState(face)
    val entry = remember(host, scene, slot) { FloatingRowFaces.Face(scene, slot, initialSize) { current.value() } }
    DisposableEffect(host, entry) { host.attach(entry); onDispose { host.detach(entry) } }
    SideEffect { entry.visible = visible; entry.interactive = interactive }
    val report = rememberUpdatedState(onMeasured)
    LaunchedEffect(entry.size, entry.measured) { if (entry.measured) report.value?.invoke(entry.size) }
    Layout(content = {}, modifier = modifier.onGloballyPositioned { coordinates ->
        entry.placed(coordinates)
    }) { _, constraints ->
        val size = entry.size
        layout(constraints.constrainWidth(size.width), constraints.constrainHeight(size.height)) {}
    }
}
