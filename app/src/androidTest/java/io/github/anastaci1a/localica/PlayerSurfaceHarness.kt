package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.PlayerSurface as ProductionPlayerSurface
import io.github.anastaci1a.localica.ui.components.SheetContact
import io.github.anastaci1a.localica.ui.components.SheetState
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.launch

/** Visual-fixture harness: PlayerSurface's geometry/ink tests pose the phase directly with an Animatable, exactly as before
 * U10.01. Real-app tests (LocalicaScreen) use the production ExpandCollapse owner; this adapter is never production code. */
internal class AnimatablePhase(val progress: Animatable<Float, *>) : MotionValue {
    override fun value() = progress.value
    override fun velocity() = progress.velocity
    override fun sample() = Kinematic(value(), velocity())
    override fun valueAt(stamp: FrameStamp) = value()
    override fun velocityAt(stamp: FrameStamp) = velocity()
    override fun sampleAt(stamp: FrameStamp) = sample()
    override val target get() = progress.targetValue
    override val settled get() = !progress.isRunning
}

@Composable
internal fun BoxWithConstraintsScope.PlayerSurface(state: PlaybackState, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    settings: InterfaceSettings, progress: Animatable<Float, *>, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onLibrary: () -> Unit,
    lyricsOpen: Boolean = false, onLyrics: (Boolean) -> Unit = {}, track: Track? = null, onSettings: (InterfaceSettings) -> Unit = {},
    actionRows: Int = 1, queueProgress: Animatable<Float, *>? = null, frostedBackdrop: FrostedBackdrop? = null,
    glassBackdrop: FrostedBackdrop? = null, visual: PlayerVisual? = null,
    artworkVisit: String? = null, previousArtwork: ArtworkNeighbor? = null, nextArtwork: ArtworkNeighbor? = null,
    onArtworkVisit: ((String, (Boolean) -> Unit) -> Unit)? = null, bottomChrome: Dp = 80.dp, miniForeground: Color = Color.Black,
    onSeekRequest: ((Long, String?) -> Long)? = null, sampleState: (() -> PlaybackState)? = null,
    backgroundPolarity: PlayerBackgroundPolarityState? = null,
    providedPopupHome: GlassPopupHome? = null, openingPopupHome: GlassPopupHome? = null,
    actions: @Composable () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val easing = remember { CubicBezierEasing(.22f, 0f, .12f, 1f) }
    // Read through updated state: the remembered SheetState outlives the first composition, whose insets can still be
    // empty on edge-to-edge APIs (a stale travel left API 36 held drags at 0.9625).
    val currentTravel = with(LocalDensity.current) { (maxHeight - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() -
        (CompactControlSize + bottomChrome)).toPx().coerceAtLeast(1f) }
    val travel by rememberUpdatedState(currentTravel)
    val density = LocalDensity.current.density
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, settings.reducedMotion, settings.fasterMotion) {
        if (!dragging) progress.animateTo(if (expanded) 1f else 0f, tween(settings.motionDuration(400), easing = easing))
    }
    val sheet = remember(progress) { object : SheetState {
        var start = 0f; var opened = false
        override val expanded get() = progress.targetValue >= .5f
        override val expansion = object : ExpansionView {
            override val phase: MotionValue = AnimatablePhase(progress)
            override val expanded get() = progress.targetValue >= .5f
            override val inContact get() = dragging
            override val canContact = true
        }
        override val contact = SheetContact(
            onDragStart = { start = progress.value; opened = start >= .5f; dragging = true; scope.launch { progress.stop() }; true },
            onDrag = { px -> scope.launch { progress.snapTo((start + px / travel).coerceIn(0f, 1f)) } },
            onRelease = { v ->
                dragging = false
                val open = settleOpen((progress.value - start) * travel / density, v / density, progress.value, !opened,
                    velocityThreshold = if (opened) VerticalReleaseVelocity else 240f,
                    distanceThreshold = if (opened) VerticalReleaseDistance else CompactControlSize.value * .42f)
                scope.launch { progress.animateTo(if (open) 1f else 0f, tween(settings.motionDuration(400), easing = easing)) }
                ReleaseDecision(open, .4f, v / travel)
            },
            onDragCancel = { dragging = false; scope.launch { progress.animateTo(if (opened) 1f else 0f, tween(settings.motionDuration(400), easing = easing)) } })
        override fun expand() {}
        override fun collapse() {}
    } }
    // U03.01c: the lyrics split pill owns an Engine shape; production composes it under LocalicaScreen's U11.00 record.
    EngineRecord { ProductionPlayerSurface(state, expanded, onExpanded, settings, sheet, onToggle, onSeek, onPrevious, onNext,
        onQueue, onLibrary, lyricsOpen, onLyrics, track, onSettings, actionRows, queueProgress?.let { rememberAnimatableQueueSheet(it, settings) }, frostedBackdrop, glassBackdrop, visual,
        artworkVisit, previousArtwork, nextArtwork, onArtworkVisit, bottomChrome, miniForeground, onSeekRequest, sampleState,
        backgroundPolarity, providedPopupHome, openingPopupHome, actions) }
}
