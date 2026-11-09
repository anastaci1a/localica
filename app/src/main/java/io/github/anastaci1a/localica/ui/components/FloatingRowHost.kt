package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.engine.*

/** An inactive face releases semantics and consumes no click/drag through a covered control. */
@Composable internal fun FloatingRowInput(interactive: Boolean, content: @Composable () -> Unit) {
    Box(if (interactive) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }) { content() }
}

/** Popup fallback: the one real face is measured even when hidden, but never placed then. */
@Composable internal fun FloatingRowInline(modifier: Modifier, visible: Boolean, interactive: Boolean,
    onMeasured: ((IntSize) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRowFaceAdmission provides RowFaceAdmission(visible, interactive)) {
        Layout(modifier = modifier, content = {
            Box(Modifier.onSizeChanged { onMeasured?.invoke(it) }) { content() }
        }) { measurables, constraints ->
            val face = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(if (visible) face.width else 0, if (visible) face.height else 0) {
                if (visible) face.placeRelative(0, 0)
            }
        }
    }
}

/** All source faces remain keyed once at root. Admission controls placement and material/input;
 * it never recomposes a second copy for collision measurement or cached scene retention. */
@Composable internal fun RootFloatingFaces(host: FloatingRowFaces, queue: Boolean,
    scene: FloatingRowScene?, toast: () -> Rect?, downwardPx: Float, fieldOutset: Float,
    sourceOpacity: () -> Float = { 1f }, admitted: () -> Boolean = { true }, interactive: Boolean, modifier: Modifier = Modifier) {
    val faces = host.faces.filter { it.scene.queue == queue }
    val placements = faces.map { face -> FloatingRowPlacement(face,
        original = { face.original }, drawn = { face.visible && face.scene === scene && admitted() },
        projection = { original -> toast()?.let { toastButtonProjection(it, original, downwardPx) }
            ?: ToastButtonProjection(1f, 0f, true) }, sourceOpacity = sourceOpacity) }
    FloatingRowPlane(placements, fieldOutset, modifier, measured = { index, size -> host.measured(faces[index], size) }) { index ->
        val face = faces[index]
        val drawn = face.visible && face.scene === scene
        val uncovered by remember(face, toast, downwardPx) { derivedStateOf {
            val original = face.original
            val body = toast()
            original == null || body == null || toastButtonProjection(body, original, downwardPx).uncovered
        } }
        CompositionLocalProvider(LocalRowFaceAdmission provides RowFaceAdmission(drawn,
            drawn && interactive && face.interactive && uncovered)) { face.content() }
    }
}

/** Passive root ancestor observation. It never consumes, catches or redirects the contact. */
@Composable internal fun Modifier.observeToastNavigationContact(cancel: () -> Unit): Modifier {
    val current = rememberUpdatedState(cancel)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            current.value()
        }
    }
}

/** Availability still comes from the source's existing owner; this introduces no animation. */
@Composable internal fun FloatingRowAvailability(tag: String, opacity: () -> Float,
    progress: () -> Float, offset: () -> Float, content: @Composable () -> Unit) {
    FloatingAvailabilityFace(tag, opacity, progress, offset, content)
}
