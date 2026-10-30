package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal fun playerHandleColor(ink: Color): Color =
    ink.copy(alpha = .45f)

/** Provider composition evidence, not a draw/presentation acknowledgement. The sampling
 * frame and decision are captured with the composition that selected the supplied ink. */
internal data class PlayerInkComposition(val decision: PlayerBackgroundDecision,
    val frame: ArtworkBackgroundFrame?, val contentColor: Color)
internal val LocalPlayerInkCompositionObserver = staticCompositionLocalOf<((PlayerInkComposition) -> Unit)?> { null }

/** The surface, gesture owner, artwork, metadata and transport retain identity at every progress value. */
@Composable
internal fun BoxWithConstraintsScope.PlayerSurface(state: PlaybackState, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    settings: InterfaceSettings, progress: Animatable<Float, *>, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onLibrary: () -> Unit,
    lyricsOpen: Boolean = false, onLyrics: (Boolean) -> Unit = {},
    track: Track? = null, onSettings: (InterfaceSettings) -> Unit = {},
    actionRows: Int = 1, queueProgress: Animatable<Float, *>? = null, frostedBackdrop: FrostedBackdrop? = null,
    glassBackdrop: FrostedBackdrop? = null, visual: PlayerVisual? = null,
    artworkVisit: String? = null, previousArtwork: ArtworkNeighbor? = null, nextArtwork: ArtworkNeighbor? = null,
    onArtworkVisit: ((String, (Boolean) -> Unit) -> Unit)? = null, bottomChrome: androidx.compose.ui.unit.Dp = 80.dp, miniForeground: Color = Color.Black,
    onSeekRequest: ((Long, String?) -> Long)? = null,
    sampleState: (() -> PlaybackState)? = null,
    backgroundPolarity: PlayerBackgroundPolarityState? = null, actions: @Composable () -> Unit = {}) {
    val sampled = sampleState?.invoke() ?: state
    val windowWidth = maxWidth
    val scope = rememberCoroutineScope()
    val playerScroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) playerScroll.scrollTo(0) }
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val miniTop = maxHeight - navigationInset - (CompactControlSize + bottomChrome)
    val distance = with(density) { miniTop.toPx().coerceAtLeast(1f) }
    val p = progress.value
    // A paused skip can buffer without requesting playback. Only show Pause
    // while playing or buffering with an explicit play-when-ready intent.
    val showPause = sampled.isPlaying || (sampled.isBuffering && sampled.playWhenReady)
    val artwork = visual ?: rememberPlayerVisual(sampled.trackUri)
    val polarity = backgroundPolarity ?: remember { PlayerBackgroundPolarityState() }
    DisposableEffect(polarity) { onDispose { polarity.detach() } }
    val inkObserver = LocalPlayerInkCompositionObserver.current
    val inkDecision = polarity.decision
    // Do not subscribe the ordinary player to the sampling-frame clock for diagnostics.
    val inkFrame = if (inkObserver != null) polarity.frame else null
    val lightInk = inkDecision.lightInk
    val playerInk = if (lightInk) Color.White else Color.Black
    // Preserve the pre-#473 wash policy; foreground selection must not retone the background.
    val tone = GlassTone(settings.glass.contrast, settings.glass.contrastWeight, !artwork.palette.isLight())
    val paletteDuration = settings.motionDuration(550)
    val palette = animatedPlayerPalette(artwork.palette, paletteDuration)
    // Header controls already sample this scene before lyrics open. Older
    // adapters need its prepared pixels whenever that header is visible too.
    val lyricBackdrop = rememberFrostedBackdrop(listOf(artwork, settings.artworkLayout, settings.glass), p > .7f, dark = lightInk)
    val lyricVisibility by animateFloatAsState(if (lyricsOpen) 1f else 0f, tween(settings.motionDuration(550)), label = "Lyrics layout")
    // Mode selection survives minimization. The finger drives its layout and reveal
    // immediately, including the fully expanded endpoint before onExpanded changes.
    val lyricMotion = playerLyricsMotion(p, lyricVisibility)
    val lyricProgress = lyricMotion.layout
    val backgroundProgress by animateFloatAsState(if (settings.artworkLayout == ArtworkLayout.FullBackground) 1f else 0f,
        tween(settings.motionDuration(400)), label = "Artwork layout")
    var startExpanded by remember { mutableStateOf(expanded) }
    var startProgress by remember { mutableFloatStateOf(progress.value) }
    var startQueueProgress by remember { mutableFloatStateOf(0f) }
    var movement by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(VerticalOwner.Undecided) }
    val seekPosition = rememberPlayerSeekPosition(sampled, artworkVisit)
    val scrubCancel = remember { SeekCancelTargetState() }
    val scrubDragging = scrubCancel.active
    var scrubOverCancel by remember { mutableStateOf(false) }
    DisposableEffect(scrubCancel) { onDispose { scrubCancel.update(false) } }
    var sourcesVisible by remember { mutableStateOf(false) }
    val sourcesPreparation = rememberGlassOriginPreparation()
    val lyricsResult = if (lyricsOpen || lyricProgress > 0f) rememberLyrics(track) else null
    val sourcesAnchor = rememberGlassAnchor()
    LaunchedEffect(lyricsOpen, expanded) { if (!lyricsOpen || !expanded) sourcesVisible = false }
    var viewing by remember { mutableStateOf(false) }
    var artworkMenu by remember { mutableStateOf(false) }
    var configureTap by remember { mutableStateOf(false) }
    fun artworkAction(value: ArtworkAction, editArtwork: ((Track) -> Unit)?) { when (value) {
        ArtworkAction.Lyrics -> onLyrics(!lyricsOpen)
        ArtworkAction.Menu -> artworkMenu = true
        ArtworkAction.View -> viewing = true
        ArtworkAction.Change -> track?.let { editArtwork?.invoke(it) }
        ArtworkAction.None -> Unit
    } }
    LaunchedEffect(expanded, settings.reducedMotion, settings.fasterMotion) {
        if (!dragging) progress.animateTo(if (expanded) 1f else 0f, tween(settings.motionDuration(400), easing = PlayerSettleEasing))
    }
    fun settle(target: Boolean) {
        onExpanded(target)
        scope.launch { progress.animateTo(if (target) 1f else 0f, tween(settings.motionDuration(400), easing = PlayerSettleEasing)) }
    }
    val dragState = rememberDraggableState { delta ->
        if (owner == VerticalOwner.Undecided) owner = if (startExpanded && (delta < 0f || startQueueProgress > 0f)) VerticalOwner.Queue else VerticalOwner.Player
        movement += delta
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (owner == VerticalOwner.Queue) {
                progress.snapTo(1f)
                queueProgress?.snapTo((startQueueProgress - movement / distance).coerceIn(0f, 1f))
            } else progress.snapTo((startProgress - movement / distance).coerceIn(0f, 1f))
        }
    }
    val drag = Modifier.playerVerticalDrag(canStart = { !scrubCancel.active }, onDelta = { dragState.dispatchRawDelta(it) },
        onStart = { startExpanded = expanded; startProgress = progress.value; startQueueProgress = queueProgress?.value ?: 0f; movement = 0f
            owner = VerticalOwner.Undecided; dragging = true; progress.stop(); queueProgress?.stop() },
        onStop = { velocity ->
            dragging = false
            val travelDp = -movement / density.density
            val velocityDp = -velocity / density.density
            if (owner == VerticalOwner.Queue) {
                settle(true)
                val open = settleOpen(travelDp, velocityDp, queueProgress?.value ?: 0f, startQueueProgress < .5f)
                if (open) onQueue()
                else scope.launch { queueProgress?.animateTo(0f, tween(settings.motionDuration(400))) }
            } else settle(settleOpen(travelDp, velocityDp, progress.value, !startExpanded,
                velocityThreshold = if (startExpanded) VerticalReleaseVelocity else 240f,
                distanceThreshold = if (startExpanded) VerticalReleaseDistance else CompactControlSize.value * .42f))
        }, onCancel = {
            dragging = false
            // Cancellation restores the previous logical surface, not a zero-velocity
            // distance-based release that could still open the player or Queue.
            kotlinx.coroutines.coroutineScope {
                launch { progress.animateTo(if (startExpanded) 1f else 0f,
                    tween(settings.motionDuration(400), easing = PlayerSettleEasing)) }
                launch { queueProgress?.animateTo(startQueueProgress, tween(settings.motionDuration(400))) }
            }
        })
    // Finish the cover mask late, independently of the backing's color. Delaying the
    // color continuation too would reveal a dark average palette before light artwork.
    val reveal = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)
    val coverBlend = backgroundProgress * reveal * reveal * (3f - 2f * reveal)
    val extraActions = 48.dp * (actionRows - 1)
    val virtualHeight = maxHeight.coerceAtLeast(560.dp + statusInset + navigationInset + extraActions)
    val floatingArt = minOf(windowWidth - 48.dp, (virtualHeight - statusInset - navigationInset - 440.dp - extraActions).coerceAtLeast(96.dp))
    val floatingWidth = minOf(floatingArt, floatingArt * palette.aspect)
    val floatingHeight = minOf(floatingArt, floatingArt / palette.aspect)
    val fullArt = floatingWidth + (windowWidth - floatingWidth) * backgroundProgress
    val fullArtHeight = floatingHeight + (windowWidth / palette.aspect - floatingHeight) * backgroundProgress
    val artX = 16.dp + ((windowWidth - fullArt) / 2 - 16.dp) * p * p
    val fullArtY = statusInset + 52.dp + (floatingArt - fullArtHeight) / 2
    val miniArtY = (CompactControlSize - MiniArtworkSize) / 2
    val artY = miniArtY + (fullArtY - miniArtY) * p - 48.dp * p * (1f - p) - 24.dp * lyricProgress
    val oldMetadataY = statusInset + 52.dp + floatingArt + 8.dp
    val lyricSeekY = virtualHeight - navigationInset - 280.dp
    val latestMetadataY = virtualHeight - navigationInset - 368.dp - extraActions
    val ordinarySeekY = (oldMetadataY + (lyricSeekY - oldMetadataY) * .5f)
        .coerceAtMost(latestMetadataY).coerceAtLeast(statusInset + 160.dp)
    val lyricMetadataY = maxOf(ordinarySeekY, lyricSeekY)
    // Preserve the delivered title trajectory, including selected-lyrics expansion.
    val metadataY = ordinarySeekY + (lyricMetadataY - ordinarySeekY) * lyricProgress
    val contentInset = 32.dp
    val contentWidth = windowWidth - contentInset * 2
    val miniTextWidth = (windowWidth - 252.dp).coerceAtLeast(40.dp)
    val titleStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    val titleMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val miniTitleScale = 13f / titleStyle.fontSize.value
    val fullTitleHeight = remember(titleMeasurer, sampled.title, titleStyle, density.density, density.fontScale) {
        titleMeasurer.measure(sampled.title, marqueeTextStyle(titleStyle), maxLines = 1, softWrap = false)
            .size.height / density.density
    }
    val artTrajectory = remember(windowWidth, fullArt, fullArtY, miniArtY, ordinarySeekY,
        lyricMetadataY, lyricVisibility, fullTitleHeight, miniTitleScale) {
        List(257) { index ->
            val t = index / 256f
            val titleTop = 7f + (ordinarySeekY.value - 7f) * t +
                (lyricMetadataY - ordinarySeekY).value * lyricVisibility * t * t
            PlayerArtworkTrajectorySample(t,
                16f + (((windowWidth - fullArt) / 2).value - 16f) * t * t,
                miniArtY.value + (fullArtY - miniArtY).value * t - 48f * t * (1f - t) - 24f * lyricVisibility * t,
                titleTop, titleTop + fullTitleHeight * (miniTitleScale + (1f - miniTitleScale) * t),
                58f + miniTextWidth.value + (-26f + (contentWidth - miniTextWidth).value) * t,
                titleLeft = 58f - 26f * t)
        }
    }
    val growthCurve = remember(artTrajectory, fullArt, fullArtHeight) {
        playerArtworkGrowthCurve(MiniArtworkSize.value, fullArt.value, fullArtHeight.value, artTrajectory)
    }
    // Layout/lyrics geometry already animates above. Do not independently tween a constraint
    // computed for an older geometry: that can push artwork through the moving title.
    LocalPlayerArtworkGrowthObserver.current?.let { observer ->
        SideEffect { observer(PlayerArtworkGrowthPresentation(p, growthCurve,
            artTrajectory, fullArt.value, fullArtHeight.value)) }
    }
    // Crop compatibility remains independent of immediate frame growth.
    val clearanceTarget = if (minOf(fullArt, fullArtHeight) < MiniArtworkSize) 0f else
        playerArtworkClearance((fullArtY - 24.dp * lyricVisibility).value,
            fullArtHeight.value, (ordinarySeekY + (lyricMetadataY - ordinarySeekY) * lyricVisibility).value,
            miniArtY.value, MiniArtworkSize.value, ((lyricMetadataY - ordinarySeekY) * lyricVisibility).value)
    val artCropRetention by animateFloatAsState(if (clearanceTarget > 0f) 1f else 0f,
        tween(settings.motionDuration(300)), label = "Artwork crop retention")
    val artContentScale = playerArtworkContentScale(with(density) { MiniArtworkSize.toPx() }, artCropRetention, p == 0f)
    val artProgress = playerArtworkSizeProgress(p, growthCurve)
    val artWidth = MiniArtworkSize + (fullArt - MiniArtworkSize) * artProgress
    val artHeight = MiniArtworkSize + (fullArtHeight - MiniArtworkSize) * artProgress
    val normalTransport = ordinarySeekY + 184.dp + extraActions
    val lyricBarCenter = lyricMetadataY + 64.dp + 20.dp
    val shelfTop = virtualHeight - navigationInset - CollapsedQueueHeight
    val lyricTransport = (lyricBarCenter + shelfTop) / 2 - 32.dp
    val transportMotion = p * p * (3f - 2f * p)
    fun smoothReveal(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val titleY = 7.dp + (metadataY - 7.dp) * p
    val subtitleY = 24.dp + (metadataY + 34.dp - 24.dp) * p
    val transportY = (CompactControlSize - 48.dp) / 2 +
        (normalTransport + (lyricTransport - normalTransport) * lyricProgress - (CompactControlSize - 48.dp) / 2) * transportMotion
    val fullTransportY = normalTransport + (lyricTransport - normalTransport) * lyricProgress
    val rowMotion = playerRowMotion(p, (titleY - metadataY).value, (transportY - fullTransportY).value, lyricVisibility)
    val seekY = metadataY + 64.dp + rowMotion.seekOffset.dp
    val actionsY = ordinarySeekY + 128.dp + 32.dp * lyricVisibility + rowMotion.actionsOffset.dp
    val backgroundReveal = smoothReveal((p - .38f) / .59f)
    val handleReveal = smoothReveal((p - .72f) / .28f)
    val glassFrost = smoothReveal((p - .08f) / .8f)
    val deviceCorners = rememberDeviceCorners()
    val surfaceFrame = playerSurfaceFrame(maxWidth, maxHeight, navigationInset, bottomChrome, p, deviceCorners)
    val lyricsViewport = playerLyricsViewport(surfaceFrame.y.value, titleY.value, metadataY.value,
        (statusInset + 60.dp).value)
    val surfaceWidth = surfaceFrame.width
    val surfaceCorner = surfaceFrame.maximumCorner
    // This feather belongs to the cover, including its final full-background state.
    // Removing it during the handoff briefly reveals the square beneath the wash.
    val edgeSoftness = backgroundProgress * smoothReveal(p / .18f)
    val edgeBand = .02f + .24f * smoothReveal(p / .75f)
    val transportWidth = minOf(contentWidth, 280.dp)
    val textWidth = miniTextWidth + (contentWidth - miniTextWidth) * p
    val textX = 58.dp + (contentInset - 58.dp) * p
    val foreground = androidx.compose.ui.graphics.lerp(miniForeground, playerInk, backgroundReveal)
    val artworkShape = RoundedCornerShape((8f + 16f * p).dp * (1f - p * backgroundProgress))
    val artPixels = with(density) { artWidth.toPx() }
    val (artSwipe, artworkDrag) = rememberArtworkSwipe(artworkVisit, artwork, previousArtwork, nextArtwork,
        artPixels, expanded && p >= .99f && !lyricsOpen && (queueProgress?.value ?: 0f) == 0f,
        settings, onArtworkVisit)
    val swipeOffset = artSwipe.offset.value
    val swipeFraction = (kotlin.math.abs(swipeOffset) / (artPixels)).coerceIn(0f, 1f)
    val swipeFeather = if (settings.artworkLayout == ArtworkLayout.FullBackground)
        smoothReveal(swipeFraction / .18f) * smoothReveal((1f - swipeFraction) / .18f) else 0f
    val measuredBackground = polarity.measured["background"]
    val measuredCover = polarity.measured["cover"]
    val measuredViewport = polarity.measured["viewport"]
    val controlNames = buildList {
        // Metadata uses the same foreground as transport. Its darker upper
        // backing must participate too, rather than letting lower controls
        // choose unreadable ink for the title and artist.
        add("title")
        add("artist")
        if (rowMotion.seekAlpha > 0f) add("seek")
        if (rowMotion.actionsAlpha > 0f) add("actions")
        add("transport")
    }
    val footprints = playerBackgroundFootprints(measuredBackground, measuredViewport,
        controlNames.mapNotNull { polarity.measured[it] })
    val backgroundGeometry = if (measuredBackground != null && measuredCover != null && footprints.isNotEmpty())
        PlayerBackgroundGeometry(Size(measuredBackground.width, measuredBackground.height), measuredCover,
            with(density) { Rect(artX.toPx() + swipeOffset, artY.toPx(), artX.toPx() + swipeOffset + artPixels, artY.toPx() + artHeight.toPx()) },
            footprints, backgroundProgress * (1f - lyricProgress), maxOf(1f - coverBlend, swipeFeather / .9f),
            edgeSoftness, edgeBand, coverBlend, lyricMotion.artworkAlpha,
            with(density) { ((8f + 16f * p).dp * (1f - p * backgroundProgress)).toPx() },
            with(density) { MiniArtworkSize.toPx() }, artCropRetention) else null
    ObservePlayerBackgroundPolarity(polarity, backgroundGeometry, when {
        p < .999f || backgroundReveal < 1f -> PlayerBackgroundUnavailable.PartialPlayer
        artSwipe.active || swipeOffset != 0f -> PlayerBackgroundUnavailable.MovingCover
        controlNames.any { polarity.measured[it] == null } -> PlayerBackgroundUnavailable.Unmeasured
        else -> null
    }, tone)
    val baseColors = MaterialTheme.colorScheme
    GlassPanelTheme(lightInk) {
    val playerColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = playerColors.copy(onSurface = foreground,
        onSurfaceVariant = foreground.copy(alpha = 0.72f), primary = androidx.compose.ui.graphics.lerp(baseColors.primary, playerInk, p),
        surface = androidx.compose.ui.graphics.lerp(baseColors.surface, palette.bottom, p),
        surfaceContainer = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainer, palette.middle, p),
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHigh, palette.middle, p),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHighest, palette.top, p))) {
    Surface(Modifier.offset(x = surfaceFrame.x, y = surfaceFrame.y)
        .width(surfaceFrame.width).height(surfaceFrame.height)
        .onGloballyPositioned { polarity.measure("viewport", it) }
        .bottomGlassShadow(surfaceCorner, opacity = { 1f - progress.value })
        .then(drag).then(if ((queueProgress?.value ?: 0f) > 0f) Modifier.clearAndSetSemantics {} else Modifier).testTag("player-surface")
        // The Queue must never sample the light browsing glass. Delaying readback alone
        // is insufficient: an asynchronous capture can still see the previous recording.
        .then(if (frostedBackdrop != null) Modifier.recordFrostedBackdrop(frostedBackdrop, enabled = p >= .99f) else Modifier),
        shape = surfaceFrame.shape, color = Color.Transparent, contentColor = foreground) {
        if (inkObserver != null) {
            // Read inside Surface's actual provider, including when the actions row is absent.
            val observed = PlayerInkComposition(inkDecision, inkFrame, LocalContentColor.current)
            SideEffect { inkObserver(observed) }
        }
        val editArtwork = presentedArtworkEdit()
        if (backgroundReveal < 1f) LiquidGlassSurface(Modifier.fillMaxSize(), source = glassBackdrop,
            cornerRadius = surfaceCorner, frostProgress = glassFrost, pressFeedback = false, shadow = false,
            depthScale = RaisedGlassDepthScale + (1f - RaisedGlassDepthScale) * p)
        Column(Modifier.fillMaxSize().controlVerticalScroll(playerScroll, enabled = p > 0.99f && virtualHeight > maxHeight)) {
        Box(Modifier.fillMaxWidth().height(virtualHeight).clipToBounds().testTag("player-background-space")
            .onGloballyPositioned { polarity.measure("background", it) }
            .then(if (!expanded) Modifier.clickable(onClickLabel = "Expand player") { settle(true) } else Modifier)) {
            ArtworkBlend(artSwipe.source ?: artwork, Modifier.fillMaxSize().recordFrostedBackdrop(lyricBackdrop, enabled = p > .7f), duration = paletteDuration, occurrence = artworkVisit ?: sampled.trackId,
                immediate = artSwipe.active || artworkVisit != null && artSwipe.handoff == artworkVisit, opacityBlend = lyricsOpen || settings.artworkTransition != io.github.anastaci1a.localica.navigation.ArtworkTransition.BlurThrough,
                onBackgroundFrame = polarity::frame) { frame ->
                val backgroundRole = LocalArtworkBackgroundRole.current
                val destination = artSwipe.destination
                val neighborOffset = swipeOffset - (if (swipeOffset < 0f) -1f else 1f) * artPixels
                if (backgroundReveal > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = backgroundReveal }) {
                    PlayerBackdrop(frame.palette, Modifier.fillMaxSize(), backgroundProgress * (1f - lyricProgress),
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + swipeOffset, artPixels, softness = maxOf(1f - coverBlend, swipeFeather / .9f), tone = tone,
                        onBackgroundDescriptor = { descriptor -> backgroundRole?.let { polarity.backdrop(it, descriptor) } })
                    if (destination != null) PlayerBackdrop(destination.visual.palette,
                        Modifier.fillMaxSize().graphicsLayer { alpha = swipeFraction }, backgroundProgress,
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + neighborOffset, artPixels, softness = maxOf(1f - coverBlend, swipeFeather / .9f), tone = tone)
                }
                @Composable fun cover(frame: PlayerVisual, offset: Float, neighbor: Boolean = false) {
                    Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight)
                        .then(if (!neighbor) Modifier.onGloballyPositioned { polarity.measure("cover", it) } else Modifier).graphicsLayer {
                        val remaining = if (neighbor) kotlin.math.abs(swipeOffset) else artPixels - kotlin.math.abs(swipeOffset)
                        alpha = lyricMotion.artworkAlpha * if (artSwipe.active) (remaining / (artPixels * .08f)).coerceIn(0f, 1f) else 1f
                        translationX = offset
                        rotationY = (offset / artPixels).coerceIn(-1f, 1f) * -27f
                        cameraDistance = 24f * density.density
                        val depth = kotlin.math.abs(offset / artPixels).coerceIn(0f, 1f)
                        scaleX = 1f - .035f * depth
                        scaleY = 1f - .035f * depth
                    }.then(if (neighbor) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (edgeSoftness > 0f && frame.softArtwork != null) androidx.compose.foundation.Image(frame.softArtwork, null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = edgeSoftness }.artworkEdgeFeather(1f, .13f),
                        contentScale = artContentScale)
                    PlayerArtworkFrame(frame, Modifier.fillMaxSize().testTag(if (neighbor) "player-neighbor-artwork" else "player-artwork")
                        .artworkEdgeFeather(maxOf(edgeSoftness, swipeFeather),
                            edgeBand + (.22f - edgeBand) * swipeFeather, coverBlend).clip(artworkShape),
                        shape = artworkShape,
                        contentScale = artContentScale)
                    }
                }
                // Soft edges reveal the two blended color continuations in the space between covers.
                cover(frame, swipeOffset)
                if (destination != null) cover(destination.visual, neighborOffset, neighbor = true)

            }
            Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight).testTag("artwork-gesture")
                .then(artworkDrag).then(if (expanded && !lyricsOpen) Modifier.combinedClickable(interactionSource = null, indication = null,
                    onClickLabel = settings.artworkTap.label,
                    onClick = { artworkAction(settings.artworkTap, editArtwork) },
                    onLongClickLabel = settings.artworkHold.label,
                    onLongClick = { artworkAction(settings.artworkHold, editArtwork) }) else Modifier)) {
                GlassMenu(artworkMenu, rememberGlassAnchor(), { artworkMenu = false }) {
                    val menuEditArtwork = presentedArtworkEdit()
                    ActionMenuItem("View artwork", icon = R.drawable.ic_albums, onClick = { artworkMenu = false; viewing = true })
                    if (menuEditArtwork != null && track != null) ActionMenuItem("Change artwork", icon = R.drawable.ic_albums, onClick = { artworkMenu = false; menuEditArtwork(track) })
                    ActionMenuItem("Configure tap action", icon = R.drawable.ic_settings, onClick = { artworkMenu = false; configureTap = true })
                }
            }
            if (handleReveal > 0f) Box(Modifier.offset(y = statusInset).fillMaxWidth().height(48.dp).graphicsLayer { alpha = handleReveal }) {
                Box(Modifier.align(Alignment.TopCenter).size(96.dp, 48.dp)
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Collapse player") { settle(false) }
                    .semantics { contentDescription = "Player handle" }) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(72.dp, 4.dp).testTag("player-handle")
                        .background(playerHandleColor(foreground), RoundedCornerShape(2.dp)))
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) {
                    Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp)) {
                        ActionIcon(R.drawable.ic_down, "Collapse player") { settle(false) }
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp)) {
                        LyricsHeaderActions(lyricsOpen, lyricProgress, sourcesAnchor, sourcesPreparation,
                            onShow = { onLyrics(true) },
                            onSources = { if (lyricsResult?.sources?.isNotEmpty() == true) sourcesVisible = true },
                            onHide = { sourcesVisible = false; onLyrics(false) })
                    }
                }
            }
            if (lyricProgress > 0f) CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) { LyricsPanel(track, sampled.positionMs, settings.reducedMotion, onSeek,
                Modifier.offset(x = contentInset, y = lyricsViewport.top.dp).width(contentWidth)
                    .height(lyricsViewport.height.dp).clipToBounds()
                    .graphicsLayer { alpha = lyricMotion.contentAlpha }
                    // Keep scroll/source identity through a cancelled collapse, but invisible
                    // lyrics must not intercept the next player gesture or accessibility.
                    .layout { measurable, constraints ->
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) {
                            if (lyricMotion.contentAlpha > 0f) child.place(0, 0)
                        }
                    },
                contentHeight = (lyricMetadataY - statusInset - 64.dp).coerceAtLeast(96.dp),
                contentOffset = lyricsViewport.contentOffset.dp, sourcesAnchor = sourcesAnchor,
                result = lyricsResult, sourcesVisible = sourcesVisible, onSourcesDismiss = { sourcesVisible = false },
                originPreparation = sourcesPreparation, contentAlpha = { lyricMotion.contentAlpha },
                sampledAtMs = sampled.sampledAtMs, playing = sampled.isPlaying, speed = sampled.playbackSpeed,
                delayMs = settings.lyricDelayMs, durationMs = sampled.durationMs) }
            val titleScale = 13f / MaterialTheme.typography.headlineSmall.fontSize.value
            val subtitleScale = 11f / MaterialTheme.typography.bodyLarge.fontSize.value
            val textMotionPaused = playerMarqueePaused(p, expanded, dragging, progress.isRunning)
            // Measure the presented title, including its scale, before sizing its input
            // band. Padding changes only the target: the title still draws at titleY.
            // Logical closing immediately re-enables opening; the existing lyricVisibility
            // animation reverses from its current value rather than waiting for zero.
            val titleOpensLyrics = expanded && p >= .99f && !lyricsOpen && (queueProgress?.value ?: 0f) == 0f
            val titlePadding = 8.dp
            // Keep the original pixel rounding without adding a translated graphics layer.
            val titleInputY = with(density) { (titleY.roundToPx() - titlePadding.roundToPx()).toDp() }
            Layout(modifier = Modifier.offset(textX, titleInputY).width(textWidth), content = {
                MarqueeTitle(sampled.title, settings.reducedMotion,
                    Modifier.fillMaxWidth().scalePlayerText(titleScale + (1f - titleScale) * p).testTag("player-title").onGloballyPositioned { polarity.measure("title", it) },
                    style = titleStyle, motionPaused = textMotionPaused)
                if (titleOpensLyrics) Box(Modifier.testTag("player-lyrics-title-target")
                    .clickable(interactionSource = null, indication = null, role = Role.Button,
                        onClickLabel = "Show lyrics", onClick = controlActivation(true) { onLyrics(true) })
                    .semantics { contentDescription = "Show lyrics from title" })
            }) { children, constraints ->
                val title = children[0].measure(constraints.copy(minHeight = 0))
                val padding = titlePadding.roundToPx()
                val height = title.height + 2 * padding
                // Never extend this new input band into the header or seek controls.
                // The player's existing viewport clip also bounds it while scrolling.
                val origin = titleY.roundToPx() - padding
                val inputTop = ((statusInset + 48.dp).roundToPx() - origin).coerceIn(0, height)
                val inputBottom = minOf(height, seekY.roundToPx() - padding - origin).coerceAtLeast(inputTop)
                val target = children.getOrNull(1)?.measure(Constraints.fixed(title.width, inputBottom - inputTop))
                layout(title.width, height) {
                    target?.place(0, inputTop)
                    title.place(0, padding)
                }
            }
            val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
            val artist = (if (settings.albumArtistFirst) sampled.albumArtist ?: sampled.artist else sampled.artist ?: sampled.albumArtist).orEmpty()
            val albumVisibility = smoothReveal((p - .65f) / .35f)
            MarqueeTitle(buildAnnotatedString {
                append(artist)
                sampled.album?.let { album -> withStyle(SpanStyle(color = subtitleColor.copy(alpha = subtitleColor.alpha * albumVisibility))) {
                    append(" · "); append(album)
                } }
            }, settings.reducedMotion,
                Modifier.offset(textX, subtitleY).width(textWidth).scalePlayerText(subtitleScale + (1f - subtitleScale) * p).testTag("player-artist").onGloballyPositioned { polarity.measure("artist", it) },
                style = MaterialTheme.typography.bodyLarge.copy(color = subtitleColor), motionPaused = textMotionPaused,
                visiblePrefixLength = artist.length, suffixVisibility = albumVisibility)
            if (rowMotion.seekAlpha > 0f) Column(Modifier.offset(x = contentInset, y = seekY).width(contentWidth)
                .testTag("player-seek-row").onGloballyPositioned { polarity.measure("seek", it) }.graphicsLayer { alpha = rowMotion.seekAlpha }) {
                PlayerSeekControls(sampled, onSeek, artworkVisit, onSeekRequest = onSeekRequest, position = seekPosition,
                    cancelTarget = { scrubCancel.bounds },
                    onDrag = { active, hovered -> scrubCancel.update(active); scrubOverCancel = active && hovered })
            }
            if (rowMotion.actionsAlpha > 0f) Box(Modifier.offset(x = contentInset, y = actionsY)
                .width(contentWidth).height(48.dp * actionRows).testTag("player-actions-row").onGloballyPositioned { polarity.measure("actions", it) }.graphicsLayer { alpha = rowMotion.actionsAlpha }
                .then(if (lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) { actions() }
            Row(Modifier.offset(x = (surfaceWidth - 148.dp) * (1 - transportMotion) + ((windowWidth - transportWidth) / 2) * transportMotion,
                y = transportY).testTag("player-transport-row").onGloballyPositioned { polarity.measure("transport", it) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((transportWidth - 160.dp) / 2 * transportMotion)) {
                TransportButton(TransportGlyph.Previous, false, "Previous", onPrevious,
                    Modifier.size(48.dp), 24.dp + 10.dp * transportMotion, foreground, sampled.connected, settings.reducedMotion)
                TransportButton(TransportGlyph.PlayPause, showPause,
                    if (showPause) "Pause" else "Play", onToggle,
                    Modifier.size(48.dp + 16.dp * transportMotion), 24.dp + 16.dp * transportMotion,
                    foreground, sampled.connected, settings.reducedMotion)
                TransportButton(TransportGlyph.Next, false, "Next", onNext,
                    Modifier.size(48.dp), 24.dp + 10.dp * transportMotion, foreground, sampled.connected, settings.reducedMotion)
            }

        }
    }
    if (scrubDragging) CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) {
        SeekCancelTarget(scrubCancel, scrubOverCancel,
            navigationInset + CollapsedQueueHeight + 20.dp, settings.reducedMotion)
    }
    }
    }
    if (viewing && sampled.trackUri != null) ArtworkViewer(sampled.trackUri) { viewing = false }
    if (configureTap) GlassAlertDialog(onDismissRequest = { configureTap = false }, title = { Text("Artwork tap") },
        text = { Column { ArtworkAction.entries.forEach { value -> ActionTextButton(onClick = {
            onSettings(settings.copy(artworkTap = value)); configureTap = false
        }) { Text(value.label) } } } }, confirmButton = { ActionTextButton(onClick = { configureTap = false }) { Text("Close") } })
}
}

