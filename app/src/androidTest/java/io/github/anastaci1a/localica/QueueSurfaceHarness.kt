package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.QueueSurface as ProductionQueueSurface
import io.github.anastaci1a.localica.ui.components.SheetContact
import io.github.anastaci1a.localica.ui.components.SheetState
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Pre-Stage-2 release thresholds, kept only for the Animatable fixture harnesses (density-independent dp and dp/s). */
internal const val VerticalReleaseVelocity = 300f
internal const val VerticalReleaseDistance = 72f
internal fun settleOpen(travel: Float, velocity: Float, progress: Float, opening: Boolean,
    velocityThreshold: Float = VerticalReleaseVelocity, distanceThreshold: Float = VerticalReleaseDistance): Boolean = when {
    velocity >= velocityThreshold -> true
    velocity <= -velocityThreshold -> false
    opening && travel >= distanceThreshold -> true
    !opening && travel <= -distanceThreshold -> false
    else -> progress >= .5f
}

/** Visual/gesture-fixture harness: direct QueueSurface tests pose the drawer with an Animatable and settle with the
 * pre-U10.02 thresholds and tween, exactly as before. Real-app tests (LocalicaScreen) use the production owner. */
@Composable
internal fun BoxWithConstraintsScope.rememberAnimatableQueueSheet(progress: Animatable<Float, *>, settings: InterfaceSettings): SheetState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val travel = with(density) { (maxHeight - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() - CollapsedQueueHeight -
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).toPx().coerceAtLeast(1f) }
    val current by rememberUpdatedState(Triple(travel, density.density, settings))
    return remember(progress) { object : SheetState {
        var start = 0f; var touching = false
        fun settle(open: Boolean) { scope.launch { progress.animateTo(if (open) 1f else 0f, tween(current.third.motionDuration(400))) } }
        // Settles target exactly 0 or 1; a posed partial phase (e.g. .55) is a drawer not requested open.
        override val expanded get() = progress.targetValue >= 1f
        override val expansion = object : ExpansionView {
            override val phase: MotionValue = AnimatablePhase(progress)
            override val expanded get() = progress.targetValue >= 1f
            override val inContact get() = touching
            override val canContact = true
        }
        override val contact = SheetContact(
            onDragStart = { start = progress.value; touching = true; scope.launch { progress.stop() }; true },
            onDrag = { px -> scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo((start + px / current.first).coerceIn(0f, 1f)) } },
            onRelease = { v -> touching = false; val (travel, dens) = current
                ReleaseDecision(settleOpen((progress.value - start) * travel / dens, v / dens, progress.value, start < .5f), .4f, v / travel) },
            onDragCancel = { touching = false; settle(start >= .5f) })
        override fun expand() = settle(true)
        override fun collapse() = settle(false)
    } }
}

@Composable
internal fun BoxWithConstraintsScope.QueueSurface(progress: Animatable<Float, *>, settings: InterfaceSettings,
    onSettled: (Boolean) -> Unit, playerProgress: () -> Float = { 1f },
    onDragTrace: ((String) -> Unit)? = null, onOpeningContact: (() -> Unit)? = null,
    providedPopupHome: GlassPopupHome? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val sheet = rememberAnimatableQueueSheet(progress, settings)
    ProductionQueueSurface(sheet, { open -> onSettled(open); if (open) sheet.expand() else sheet.collapse() }, playerProgress,
        onDragTrace, onOpeningContact, providedPopupHome, content)
}
