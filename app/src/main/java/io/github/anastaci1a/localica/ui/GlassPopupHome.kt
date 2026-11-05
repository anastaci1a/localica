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
    var registration: BackdropRegistration? = null
}

internal class GlassPopupFlight(val layer: GraphicsLayer) {
    var origin = Offset.Zero
    var revision by mutableLongStateOf(0L)
    var prepared by mutableStateOf(false)
    var handedOff by mutableStateOf(false)
}

internal val LocalGlassPopupHome = staticCompositionLocalOf<GlassPopupHome?> { null }

@Composable
internal fun rememberGlassPopupHome(departing: Boolean): GlassPopupHome {
    val parent = LocalGlassPopupHome.current
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
    val backing = LocalFrostedBackdrop.current
    // Keep size/readback subscriptions in this layout scope, not in the modal's
    // committed window owner above the per-frame geometry scope.
    val backdrop = rememberFrostedBackdrop(home, dark = LocalContentColor.current.luminance() > .5f)
    SideEffect { backdrop.parent = backing; home.backdrop = backdrop }
    DisposableEffect(home, backdrop) {
        home.mounted = true
        onDispose { home.mounted = false; home.backdrop = null }
    }
    return onGloballyPositioned {
        // Freeze this basis. The enclosing screen layer supplies all subsequent
        // movement, scale, clipping and alpha, including interrupted transitions.
        if (!home.departing) {
            home.origin = it.backdropPosition(view)
            val screen = IntArray(2)
            view.getLocationOnScreen(screen)
            val offset = Offset(screen[0].toFloat(), screen[1].toFloat())
            home.registration = BackdropRegistration.fromMapping(it.size.width.toFloat(), it.size.height.toFloat()) { point ->
                it.localToRoot(point) + offset
            }
        }
        backdrop.location.retainedRegistration = home.registration.takeIf { home.departing }
    }.drawWithContent {
        drawContent()
        if (home.departing) home.flights.forEach { flight ->
            flight.revision
            if (flight.prepared && !flight.layer.isReleased) {
                val delta = flight.origin - home.origin
                translate(delta.x, delta.y) { drawLayer(flight.layer) }
                flight.handedOff = true
            }
        }
    // Sampling is deliberately inside the overlay. Retained ancestor RenderNodes
    // can contain this home, so merely freezing an ancestor recording is insufficient
    // to prevent a modal from sampling itself during the inline handoff.
    }.recordFrostedBackdrop(backdrop, backing)
}

@Composable
internal fun GlassPopupHome(departing: Boolean = false, content: @Composable () -> Unit) {
    val home = rememberGlassPopupHome(departing)
    GlassPopupHome(home, content)
}

@Composable
private fun GlassPopupHome(home: GlassPopupHome, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clipToBounds().glassPopupHome(home)
        .then(if (home.departing) Modifier.clearAndSetSemantics { } else Modifier)) {
        CompositionLocalProvider(LocalGlassPopupHome provides home, content = content)
    }
}

private class GlassPopupPage(val key: Any, content: @Composable () -> Unit) {
    var content by mutableStateOf(content)
    var home by mutableStateOf<GlassPopupHome?>(null)
}

/** Retain the actual outgoing screen, including its popup home, until its fade ends. */
@Composable
internal fun GlassPopupNavigation(key: Any, modifier: Modifier = Modifier, direction: Float = 1f,
    departing: Boolean = false, content: @Composable () -> Unit) {
    val page = remember(key) { GlassPopupPage(key, content) }
    var displayed by remember { mutableStateOf(page) }
    SideEffect { page.content = content }
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
        SideEffect { shown.home = home }
        GlassPopupHome(home) { shown.content() }
    }
}
