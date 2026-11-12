package io.github.anastaci1a.localica.ui.components

import io.github.anastaci1a.localica.ui.engine.dragLanding
import io.github.anastaci1a.localica.ui.engine.dragHandoff
import io.github.anastaci1a.localica.ui.engine.edgeScroll
import io.github.anastaci1a.localica.ui.engine.warmLayout
import io.github.anastaci1a.localica.ui.engine.maintainAnchor

/** Content identity chooses the existing keyed drag recipe, never a caller-supplied timing law. */
internal enum class KeyedDragContent { ListItems, ActionSlots }

internal suspend fun BrowsePinchProgressOwner.warmDragDestination() = motion.warmLayout()
internal suspend fun BrowsePinchProgressOwner.landDrag(progress: BrowsePinchProgress, reduced: Boolean,
    content: KeyedDragContent, onFrame: () -> Unit = {}) {
    if (content == KeyedDragContent.ActionSlots && reduced) { progress.snapTo(1f); return }
    progress.mutateScene { start, write -> motion.dragLanding(start, reduced, content == KeyedDragContent.ActionSlots) { write(it); onFrame() } }
}
internal suspend fun BrowsePinchProgressOwner.handoffDrag(progress: BrowsePinchProgress, reduced: Boolean, content: KeyedDragContent = KeyedDragContent.ListItems,
    onFrame: () -> Unit = {}) {
    if (content == KeyedDragContent.ActionSlots && reduced) { progress.snapTo(1f); return }
    progress.mutateScene { start, write -> motion.dragHandoff(start, reduced) { write(it); onFrame() } }
}
internal suspend fun BrowsePinchProgressOwner.scrollDragEdge(content: KeyedDragContent, active: () -> Boolean,
    speed: () -> Float, edge: () -> Float, scroll: suspend (Float) -> Float, afterScroll: () -> Unit,
    awaitMotion: suspend () -> Unit, awaitUnblocked: suspend () -> Unit = {}) =
    motion.edgeScroll(active, speed, edge, content == KeyedDragContent.ActionSlots, scroll, afterScroll, awaitMotion, awaitUnblocked)

internal suspend fun BrowsePinchProgressOwner.maintainPinchAnchor(reduced: Boolean, contact: () -> Boolean,
    correct: suspend () -> Unit) = motion.maintainAnchor(reduced, contact, correct)

internal class KeyedDragEdgeViewport(
    val bounds: () -> androidx.compose.ui.geometry.Rect,
    val scroll: suspend (Float) -> Float,
    val horizontal: Boolean = false,
    val canScrollBefore: () -> Boolean = { true },
    val canScrollAfter: () -> Boolean = { true },
) {
    internal fun speed(point: androidx.compose.ui.geometry.Offset, edge: Float) =
        io.github.anastaci1a.localica.ui.engine.dragEdgeVelocity(point, bounds(), edge, horizontal, canScrollBefore(), canScrollAfter())
}

internal fun actionDragPosition(release: androidx.compose.ui.geometry.Offset, destination: androidx.compose.ui.geometry.Offset, fraction: Float) =
    io.github.anastaci1a.localica.ui.engine.actionDragPoint(release, destination, fraction)
