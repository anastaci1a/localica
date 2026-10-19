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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal fun playerHandleColor(ink: Color): Color =
    ink.copy(alpha = .45f)

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
    actions: @Composable () -> Unit = {}) {
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
    val artwork = visual ?: rememberPlayerVisual(state.trackUri)
    val lightInk = !artwork.palette.isLight()
    val playerInk = if (lightInk) Color.White else Color.Black
    val tone = GlassTone(settings.glass.contrast, settings.glass.contrastWeight, lightInk)
    val paletteDuration = settings.motionDuration(550)
    val palette = animatedPlayerPalette(artwork.palette, paletteDuration)
    // Header controls already sample this scene before lyrics open. Older
    // adapters need its prepared pixels whenever that header is visible too.
    val lyricBackdrop = rememberFrostedBackdrop(listOf(artwork, settings.artworkLayout, settings.glass), p > .7f, dark = lightInk)
    val lyricVisibility by animateFloatAsState(if (lyricsOpen && expanded) 1f else 0f, tween(settings.motionDuration(550)), label = "Lyrics layout")
    // While the finger is down expanded remains true. Progress, rather than release,
    // must restore the same artwork and metadata as the surface returns to the miniplayer.
    val lyricProgress = lyricVisibility * p
    val backgroundProgress by animateFloatAsState(if (settings.artworkLayout == ArtworkLayout.FullBackground) 1f else 0f,
        tween(settings.motionDuration(400)), label = "Artwork layout")
    var startExpanded by remember { mutableStateOf(expanded) }
    var startProgress by remember { mutableFloatStateOf(progress.value) }
    var startQueueProgress by remember { mutableFloatStateOf(0f) }
    var movement by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(VerticalOwner.Undecided) }
    var scrubDragging by remember { mutableStateOf(false) }
    var scrubOverCancel by remember { mutableStateOf(false) }
    var scrubCancelBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
    val sourcesPreparation = rememberGlassOriginPreparation()
    val lyricsResult = if (lyricsOpen || lyricProgress > 0f) rememberLyrics(track) else null
    val sourcesAnchor = rememberGlassAnchor()
    LaunchedEffect(lyricsOpen, expanded) { if (!lyricsOpen || !expanded) sourcesVisible = false }
    val editArtwork = LocalArtworkEdit.current
    var viewing by remember { mutableStateOf(false) }
    var artworkMenu by remember { mutableStateOf(false) }
    var configureTap by remember { mutableStateOf(false) }
    fun artworkAction(value: ArtworkAction) { when (value) {
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
    val drag = Modifier.playerVerticalDrag(canStart = { !scrubDragging }, onDelta = { dragState.dispatchRawDelta(it) },
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
    // Leave space for metadata to pass below the growing cover, then catch up smoothly.
    val artProgress = p * (.58f + .42f * p)
    val artWidth = MiniArtworkSize + (fullArt - MiniArtworkSize) * artProgress
    val artHeight = MiniArtworkSize + (fullArtHeight - MiniArtworkSize) * artProgress
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
    val metadataY = ordinarySeekY + (lyricMetadataY - ordinarySeekY) * lyricProgress
    val seekY = metadataY + 64.dp
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
    val backgroundReveal = smoothReveal((p - .38f) / .59f)
    val handleReveal = smoothReveal((p - .72f) / .28f)
    val glassFrost = smoothReveal((p - .08f) / .8f)
    val deviceCorners = rememberDeviceCorners()
    val surfaceFrame = playerSurfaceFrame(maxWidth, maxHeight, navigationInset, bottomChrome, p, deviceCorners)
    val surfaceWidth = surfaceFrame.width
    val surfaceCorner = surfaceFrame.maximumCorner
    // This feather belongs to the cover, including its final full-background state.
    // Removing it during the handoff briefly reveals the square beneath the wash.
    val edgeSoftness = backgroundProgress * smoothReveal(p / .18f)
    val edgeBand = .02f + .24f * smoothReveal(p / .75f)
    val fullControlsReveal = smoothReveal((p - .8f) / .2f)
    val contentInset = 32.dp
    val contentWidth = windowWidth - contentInset * 2
    val transportWidth = minOf(contentWidth, 280.dp)
    val miniTextWidth = (windowWidth - 252.dp).coerceAtLeast(40.dp)
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
        .bottomGlassShadow(surfaceCorner, opacity = { 1f - progress.value })
        .then(drag).then(if ((queueProgress?.value ?: 0f) > 0f) Modifier.clearAndSetSemantics {} else Modifier).testTag("player-surface")
        // The Queue must never sample the light browsing glass. Delaying readback alone
        // is insufficient: an asynchronous capture can still see the previous recording.
        .then(if (frostedBackdrop != null) Modifier.recordFrostedBackdrop(frostedBackdrop, enabled = p >= .99f) else Modifier),
        shape = surfaceFrame.shape, color = Color.Transparent, contentColor = foreground) {
        if (backgroundReveal < 1f) LiquidGlassSurface(Modifier.fillMaxSize(), source = glassBackdrop,
            cornerRadius = surfaceCorner, frostProgress = glassFrost, pressFeedback = false, shadow = false,
            depthScale = RaisedGlassDepthScale + (1f - RaisedGlassDepthScale) * p)
        Column(Modifier.fillMaxSize().controlVerticalScroll(playerScroll, enabled = p > 0.99f && virtualHeight > maxHeight)) {
        Box(Modifier.fillMaxWidth().height(virtualHeight).clipToBounds().testTag("player-background-space")
            .then(if (!expanded) Modifier.clickable(onClickLabel = "Expand player") { settle(true) } else Modifier)) {
            ArtworkBlend(artSwipe.source ?: artwork, Modifier.fillMaxSize().recordFrostedBackdrop(lyricBackdrop, enabled = p > .7f), duration = paletteDuration,
                immediate = artSwipe.active || artworkVisit != null && artSwipe.handoff == artworkVisit, opacityBlend = lyricsOpen || settings.artworkTransition != io.github.anastaci1a.localica.navigation.ArtworkTransition.BlurThrough) { frame ->
                val destination = artSwipe.destination
                val neighborOffset = swipeOffset - (if (swipeOffset < 0f) -1f else 1f) * artPixels
                if (backgroundReveal > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = backgroundReveal }) {
                    PlayerBackdrop(frame.palette, Modifier.fillMaxSize(), backgroundProgress * (1f - lyricProgress),
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + swipeOffset, artPixels, softness = maxOf(1f - coverBlend, swipeFeather / .9f), tone = tone)
                    if (destination != null) PlayerBackdrop(destination.visual.palette,
                        Modifier.fillMaxSize().graphicsLayer { alpha = swipeFraction }, backgroundProgress,
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + neighborOffset, artPixels, softness = maxOf(1f - coverBlend, swipeFeather / .9f), tone = tone)
                }
                @Composable fun cover(frame: PlayerVisual, offset: Float, neighbor: Boolean = false) {
                    Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight).graphicsLayer {
                        val remaining = if (neighbor) kotlin.math.abs(swipeOffset) else artPixels - kotlin.math.abs(swipeOffset)
                        alpha = (1f - lyricProgress) * if (artSwipe.active) (remaining / (artPixels * .08f)).coerceIn(0f, 1f) else 1f
                        translationX = offset
                        rotationY = (offset / artPixels).coerceIn(-1f, 1f) * -27f
                        cameraDistance = 24f * density.density
                        val depth = kotlin.math.abs(offset / artPixels).coerceIn(0f, 1f)
                        scaleX = 1f - .035f * depth
                        scaleY = 1f - .035f * depth
                    }.then(if (neighbor) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (edgeSoftness > 0f && frame.softArtwork != null) androidx.compose.foundation.Image(frame.softArtwork, null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = edgeSoftness }.artworkEdgeFeather(1f, .13f),
                        contentScale = ContentScale.Fit)
                    PlayerArtworkFrame(frame, Modifier.fillMaxSize().testTag(if (neighbor) "player-neighbor-artwork" else "player-artwork")
                        .artworkEdgeFeather(maxOf(edgeSoftness, swipeFeather),
                            edgeBand + (.22f - edgeBand) * swipeFeather, coverBlend).clip(artworkShape),
                        shape = artworkShape,
                        contentScale = if (p == 0f) ContentScale.Crop else ContentScale.Fit)
                    }
                }
                // Soft edges reveal the two blended color continuations in the space between covers.
                cover(frame, swipeOffset)
                if (destination != null) cover(destination.visual, neighborOffset, neighbor = true)

            }
            Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight).testTag("artwork-gesture")
                .then(artworkDrag).then(if (expanded && !lyricsOpen) Modifier.combinedClickable(interactionSource = null, indication = null,
                    onClickLabel = settings.artworkTap.label,
                    onClick = { artworkAction(settings.artworkTap) },
                    onLongClickLabel = settings.artworkHold.label,
                    onLongClick = { artworkAction(settings.artworkHold) }) else Modifier)) {
                GlassMenu(artworkMenu, rememberGlassAnchor(), { artworkMenu = false }) {
                    ActionMenuItem("View artwork", onClick = { artworkMenu = false; viewing = true })
                    if (editArtwork != null && track != null) ActionMenuItem("Change artwork", onClick = { artworkMenu = false; editArtwork(track) })
                    ActionMenuItem("Configure tap action", onClick = { artworkMenu = false; configureTap = true })
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
            if (lyricProgress > 0f) CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) { LyricsPanel(track, state.positionMs, settings.reducedMotion, onSeek,
                Modifier.offset(x = contentInset, y = statusInset + 60.dp).width(contentWidth)
                    .height((lyricMetadataY - statusInset - 64.dp).coerceAtLeast(96.dp))
                    .graphicsLayer { alpha = lyricProgress * p },
                contentHeight = (metadataY - statusInset - 64.dp).coerceAtLeast(96.dp), sourcesAnchor = sourcesAnchor,
                result = lyricsResult, sourcesVisible = sourcesVisible, onSourcesDismiss = { sourcesVisible = false },
                originPreparation = sourcesPreparation,
                sampledAtMs = state.sampledAtMs, playing = state.isPlaying, speed = state.playbackSpeed,
                delayMs = settings.lyricDelayMs, durationMs = state.durationMs) }
            val titleScale = 13f / MaterialTheme.typography.headlineSmall.fontSize.value
            val subtitleScale = 11f / MaterialTheme.typography.bodyLarge.fontSize.value
            MarqueeTitle(state.title, settings.reducedMotion || (p > 0f && p < 1f),
                Modifier.offset(textX, titleY).width(textWidth).scalePlayerText(titleScale + (1f - titleScale) * p).testTag("player-title"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(buildAnnotatedString {
                append((if (settings.albumArtistFirst) state.albumArtist ?: state.artist else state.artist ?: state.albumArtist).orEmpty())
                state.album?.let { album -> withStyle(SpanStyle(color = subtitleColor.copy(alpha = subtitleColor.alpha * smoothReveal((p - .65f) / .35f)))) {
                    append(" · "); append(album)
                } }
            },
                Modifier.offset(textX, subtitleY).width(textWidth).scalePlayerText(subtitleScale + (1f - subtitleScale) * p),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fullControlsReveal > 0f) Column(Modifier.offset(x = contentInset, y = seekY).width(contentWidth).graphicsLayer { alpha = fullControlsReveal }) {
                var seeking by remember(state.trackUri) { mutableStateOf<Float?>(null) }
                val duration = (state.durationMs ?: 0).coerceAtLeast(1).toFloat()
                PlayerSeekBar((seeking ?: state.positionMs.toFloat()).coerceIn(0f, duration), { seeking = it },
                    valueRange = 0f..duration, onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
                    enabled = state.connected && (state.durationMs ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" },
                    cancelBounds = scrubCancelBounds, onCancel = { seeking = null }, liveValue = state.positionMs.toFloat(),
                    onDrag = { active, hovered -> scrubDragging = active; scrubOverCancel = hovered })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(seeking?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (fullControlsReveal > 0f && lyricProgress < 1f) Box(Modifier.offset(x = contentInset, y = ordinarySeekY + 128.dp + 32.dp * lyricProgress)
                .width(contentWidth).height(48.dp * actionRows).graphicsLayer { alpha = fullControlsReveal * (1f - lyricProgress) }
                .then(if (lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) { actions() }
            Row(Modifier.offset(x = (surfaceWidth - 148.dp) * (1 - transportMotion) + ((windowWidth - transportWidth) / 2) * transportMotion,
                y = transportY),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((transportWidth - 160.dp) / 2 * transportMotion)) {
                TransportButton(TransportGlyph.Previous, false, "Previous", onPrevious,
                    Modifier.size(48.dp), 24.dp + 10.dp * transportMotion, foreground, state.connected, settings.reducedMotion)
                TransportButton(TransportGlyph.PlayPause, state.isPlaying || state.isBuffering,
                    if (state.isPlaying || state.isBuffering) "Pause" else "Play", onToggle,
                    Modifier.size(48.dp + 16.dp * transportMotion), 24.dp + 16.dp * transportMotion,
                    foreground, state.connected, settings.reducedMotion)
                TransportButton(TransportGlyph.Next, false, "Next", onNext,
                    Modifier.size(48.dp), 24.dp + 10.dp * transportMotion, foreground, state.connected, settings.reducedMotion)
            }

        }
    }
    if (scrubDragging) CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) {
        Box(Modifier.fillMaxSize()) {
            DragCancelTarget(scrubOverCancel, "seek",
                Modifier.align(Alignment.BottomCenter).padding(bottom = navigationInset + CollapsedQueueHeight + 20.dp)
                    .onGloballyPositioned { scrubCancelBounds = it.boundsInRoot() }.testTag("cancel-seek"))
        }
    }
    }
    }
    if (viewing && state.trackUri != null) ArtworkViewer(state.trackUri) { viewing = false }
    if (configureTap) GlassAlertDialog(onDismissRequest = { configureTap = false }, title = { Text("Artwork tap") },
        text = { Column { ArtworkAction.entries.forEach { value -> ActionTextButton(onClick = {
            onSettings(settings.copy(artworkTap = value)); configureTap = false
        }) { Text(value.label) } } } }, confirmButton = { ActionTextButton(onClick = { configureTap = false }) { Text("Close") } })
}
}
