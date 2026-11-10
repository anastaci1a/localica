package io.github.anastaci1a.localica.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/** A modal keeps its native input/composition; its departing screen owns its pixels. */
internal class GlassPopupHome(private val leaving: State<Boolean>) {
    val departing get() = leaving.value
    var mounted by mutableStateOf(true)
    var backdrop by mutableStateOf<FrostedBackdrop?>(null)
    val flights = mutableStateListOf<GlassPopupFlight>()
    val children = mutableStateListOf<GlassPopupHome>()
    val handedOff: Boolean get() = flights.all { it.handedOff } && children.all { it.handedOff }
    var origin = Offset.Zero
    var currentOrigin = Offset.Zero
    var registration: BackdropRegistration? = null
    var liveRegistration: BackdropRegistration? = null
}

internal class GlassPopupFlight {
    var layer by mutableStateOf<GraphicsLayer?>(null)
    var origin = Offset.Zero
    var homeOrigin = Offset.Zero
    var revision by mutableLongStateOf(0L)
    var prepared by mutableStateOf(false)
    var handedOff by mutableStateOf(false)
    var wasProjected by mutableStateOf(false)
}

internal val LocalGlassPopupHome = staticCompositionLocalOf<GlassPopupHome?> { null }

@Composable
internal fun rememberGlassPopupHome(departing: Boolean, parent: GlassPopupHome? = LocalGlassPopupHome.current): GlassPopupHome {
    val leaving = rememberUpdatedState(departing || parent?.departing == true)
    val home = remember { GlassPopupHome(leaving) }
    DisposableEffect(home, parent) {
        parent?.children?.add(home)
        onDispose { home.mounted = false; parent?.children?.remove(home) }
    }
    return home
}

@Composable
internal fun Modifier.glassPopupHome(home: GlassPopupHome): Modifier {
    val view = LocalView.current
    // Keep size/readback subscriptions in this layout scope, not in the modal's
    // committed window owner above the per-frame geometry scope.
    val backdrop = rememberFrostedBackdrop(home, dark = LocalContentColor.current.luminance() > .5f)
    // An ambient ancestor may enclose this home; retaining it would introduce
    // a RenderNode cycle as soon as the modal is projected here.
    SideEffect { home.backdrop = backdrop }
    DisposableEffect(home, backdrop) {
        home.mounted = true
        onDispose { home.mounted = false; home.backdrop = null }
    }
    val recording = if (backdrop.consumers > 0) Modifier.recordFrostedBackdrop(backdrop) else Modifier
    return onGloballyPositioned {
        home.currentOrigin = it.backdropPosition(view)
        // Freeze this basis. The enclosing screen layer supplies all subsequent
        // movement, scale, clipping and alpha, including interrupted transitions.
        val screen = IntArray(2)
        view.getLocationOnScreen(screen)
        val offset = Offset(screen[0].toFloat(), screen[1].toFloat())
        home.liveRegistration = BackdropRegistration.fromMapping(it.size.width.toFloat(), it.size.height.toFloat()) { point ->
            it.localToRoot(point) + offset
        }
        if (!home.departing && home.flights.none { it.handedOff }) {
            home.origin = home.currentOrigin
            home.registration = home.liveRegistration
        }
    }.drawWithContent {
        val retaining = home.departing || home.flights.any { it.handedOff }
        if (!retaining) home.registration = home.liveRegistration
        val registration = home.registration.takeIf { retaining }
        if (backdrop.location.retainedRegistration != registration) {
            backdrop.location.retainedRegistration = registration
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { backdrop.location.transformRevision++ }
        }
        drawContent()
        projectFlights(home)
    // Sampling is deliberately inside the overlay. Retained ancestor RenderNodes
    // can contain this home, so merely freezing an ancestor recording is insufficient
    // to prevent a modal from sampling itself during the inline handoff.
    }.then(recording)
}

/** Reuse the native recording without including the chrome in its source plane. */
@Composable
internal fun Modifier.glassPopupProjection(home: GlassPopupHome, source: FrostedBackdrop): Modifier {
    val view = LocalView.current
    SideEffect { home.backdrop = source }
    DisposableEffect(home, source) {
        home.mounted = true
        onDispose { home.mounted = false; home.backdrop = null }
    }
    return onGloballyPositioned {
        home.currentOrigin = it.backdropPosition(view)
    }.drawWithContent {
        drawContent()
        projectFlights(home)
    }
}

private fun ContentDrawScope.projectFlights(home: GlassPopupHome) {
    home.flights.forEach { flight ->
        flight.revision
        val layer = flight.layer
        if ((home.departing || flight.handedOff) && flight.prepared && layer != null && !layer.isReleased) {
            if (!flight.handedOff) flight.homeOrigin = home.currentOrigin
            val delta = flight.origin - flight.homeOrigin
            translate(delta.x, delta.y) { drawLayer(layer) }
            flight.handedOff = true
            flight.wasProjected = true
        }
    }
}

@Composable
internal fun GlassPopupHome(departing: Boolean = false, content: @Composable () -> Unit) {
    val home = rememberGlassPopupHome(departing)
    GlassPopupHome(home, content)
}

@Composable
internal fun GlassPopupHome(home: GlassPopupHome, content: @Composable () -> Unit) {
    // An idle home adds no layer: native draw observers and backdrop producers
    // keep their existing ordering until a popup actually needs containment.
    val containing = home.flights.isNotEmpty() && (home.departing || home.flights.any { it.handedOff })
    Box(Modifier.fillMaxSize().then(if (containing) Modifier.clipToBounds() else Modifier).glassPopupHome(home)
        .then(if (home.departing) Modifier.clearAndSetSemantics { } else Modifier)) {
        CompositionLocalProvider(LocalGlassPopupHome provides home, content = content)
    }
}

internal suspend fun GlassPopupHome.awaitHandoff() {
    snapshotFlow { handedOff }.first { it }
}

private class GlassPopupPage<T : Any>(val key: Any, target: T) {
    var target by mutableStateOf(target)
    var home by mutableStateOf<GlassPopupHome?>(null)
}

/** Retain the actual outgoing screen, including its popup home, until its fade ends. */
@Composable
internal fun <T : Any> GlassPopupNavigation(target: T, modifier: Modifier = Modifier, direction: Float = 1f,
    departing: Boolean = false, onHome: ((GlassPopupHome) -> Unit)? = null, contentKey: (T) -> Any = { it }, content: @Composable (T) -> Unit) {
    val key = contentKey(target)
    val page = remember(key) { GlassPopupPage(key, target) }
    var displayed by remember { mutableStateOf(page) }
    SideEffect { page.target = target }
    LaunchedEffect(page) {
        // Admit the overlay-free native recording and acknowledge its first inline
        // draw before moving either screen. This also avoids a one-frame source cycle.
        if (displayed !== page) {
            snapshotFlow { displayed.home?.handedOff != false }.first { it }
            displayed = page
        }
    }
    val duration = if (LocalReducedMotion.current) 0 else (240 / LocalMotionSpeed.current).toInt()
    val distance = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedContent(displayed, modifier, contentKey = { it.key }, label = "Popup home navigation",
        transitionSpec = {
            (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { (direction * distance).toInt() })
                .togetherWith(fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { (-direction * distance).toInt() })
        }) { shown ->
        val home = rememberGlassPopupHome(departing || shown !== page)
        SideEffect { shown.home = home; if (shown === page) onHome?.invoke(home) }
        // Same-screen edits must reach controlled fields in this composition,
        // before a subsequent input event can observe the old value.
        GlassPopupHome(home) { content(if (shown === page) target else shown.target) }
    }
}
