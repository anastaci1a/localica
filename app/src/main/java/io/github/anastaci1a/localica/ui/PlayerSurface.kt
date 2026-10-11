package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** The surface, gesture owner, artwork, metadata and transport retain identity at every progress value. */
@Composable
internal fun BoxWithConstraintsScope.PlayerSurface(state: PlaybackState, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    settings: InterfaceSettings, progress: Animatable<Float, *>, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onLibrary: () -> Unit,
    onEditTags: (() -> Unit)? = null, lyricsOpen: Boolean = false, onLyrics: (Boolean) -> Unit = {},
    track: Track? = null, onSettings: (InterfaceSettings) -> Unit = {},
    actionRows: Int = 1, queueProgress: Animatable<Float, *>? = null, frostedBackdrop: FrostedBackdrop? = null,
    glassBackdrop: FrostedBackdrop? = null, visual: PlayerVisual? = null,
    artworkVisit: String? = null, previousArtwork: ArtworkNeighbor? = null, nextArtwork: ArtworkNeighbor? = null,
    onArtworkVisit: ((String, (Boolean) -> Unit) -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val windowWidth = maxWidth
    val scope = rememberCoroutineScope()
    val playerScroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) playerScroll.scrollTo(0) }
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val miniTop = maxHeight - navigationInset - (CompactControlSize + 80.dp)
    val distance = with(density) { miniTop.toPx().coerceAtLeast(1f) }
    val p = progress.value
    val artwork = visual ?: rememberPlayerVisual(state.trackUri)
    val paletteDuration = settings.motionDuration(550)
    val palette = animatedPlayerPalette(artwork.palette, paletteDuration)
    val lyricBackdrop = rememberFrostedBackdrop(artwork to settings.artworkLayout, lyricsOpen)
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
    var sourcesRequest by remember { mutableIntStateOf(0) }
    val sourcesAnchor = rememberGlassAnchor()
    LaunchedEffect(lyricsOpen) { if (!lyricsOpen) sourcesRequest = 0 }
    var viewing by remember { mutableStateOf(false) }
    var artworkMenu by remember { mutableStateOf(false) }
    var configureTap by remember { mutableStateOf(false) }
    fun artworkAction(value: ArtworkAction) { when (value) {
        ArtworkAction.Lyrics -> onLyrics(!lyricsOpen)
        ArtworkAction.Menu -> artworkMenu = true
        ArtworkAction.View -> viewing = true
        ArtworkAction.Change -> onEditTags?.invoke()
        ArtworkAction.None -> Unit
    } }
    LaunchedEffect(expanded, settings.reducedMotion, settings.fasterMotion) {
        if (!dragging) progress.animateTo(if (expanded) 1f else 0f, tween(settings.motionDuration(400)))
    }
    fun settle(target: Boolean) {
        onExpanded(target)
        scope.launch { progress.animateTo(if (target) 1f else 0f, tween(settings.motionDuration(400))) }
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
    val drag = Modifier.draggable(dragState, Orientation.Vertical,
        onDragStarted = { startExpanded = expanded; startProgress = progress.value; startQueueProgress = queueProgress?.value ?: 0f; movement = 0f
            owner = VerticalOwner.Undecided; dragging = true; progress.stop(); queueProgress?.stop() },
        onDragStopped = { velocity ->
            dragging = false
            val travelDp = -movement / density.density
            val velocityDp = -velocity / density.density
            if (owner == VerticalOwner.Queue) {
                settle(true)
                val open = settleOpen(travelDp, velocityDp, queueProgress?.value ?: 0f, startQueueProgress < .5f)
                if (open) onQueue()
                else scope.launch { queueProgress?.animateTo(0f, tween(settings.motionDuration(400))) }
            } else settle(settleOpen(travelDp, velocityDp, progress.value, !startExpanded,
                velocityThreshold = if (startExpanded) 400f else 450f,
                distanceThreshold = if (startExpanded) 72f else CompactControlSize.value * .55f))
        })
    // Resolve the full-cover feather and its color continuation together before the cover
    // separates from the screen edges. The wash must not turn into a second miniature cover.
    val reveal = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)
    val coverBlend = backgroundProgress * reveal * reveal * (3f - 2f * reveal)
    val surfaceWidth = windowWidth - 24.dp * (1f - p)
    val extraActions = 48.dp * (actionRows - 1)
    val virtualHeight = maxHeight.coerceAtLeast(560.dp + statusInset + navigationInset + extraActions)
    val floatingArt = minOf(windowWidth - 48.dp, (virtualHeight - statusInset - navigationInset - 440.dp - extraActions).coerceAtLeast(96.dp))
    val floatingWidth = minOf(floatingArt, floatingArt * palette.aspect)
    val floatingHeight = minOf(floatingArt, floatingArt / palette.aspect)
    val fullArt = floatingWidth + (windowWidth - floatingWidth) * backgroundProgress
    val fullArtHeight = floatingHeight + (windowWidth / palette.aspect - floatingHeight) * backgroundProgress
    // Leave space for metadata to pass below the growing cover, then catch up smoothly.
    val artProgress = p * (.78f + .22f * p)
    val artWidth = MiniArtworkSize + (fullArt - MiniArtworkSize) * artProgress
    val artHeight = MiniArtworkSize + (fullArtHeight - MiniArtworkSize) * artProgress
    val artX = 16.dp + ((windowWidth - fullArt) / 2 - 16.dp) * p
    val fullArtY = statusInset + 52.dp + (floatingArt - fullArtHeight) / 2
    val miniArtY = (CompactControlSize - MiniArtworkSize) / 2
    val artY = miniArtY + (fullArtY - miniArtY) * p - 24.dp * lyricProgress
    val oldMetadataY = statusInset + 52.dp + floatingArt + 8.dp
    val lyricSeekY = virtualHeight - navigationInset - 280.dp
    val latestMetadataY = virtualHeight - navigationInset - 368.dp - extraActions
    val ordinarySeekY = (oldMetadataY + (lyricSeekY - oldMetadataY) * .5f)
        .coerceAtMost(latestMetadataY).coerceAtLeast(statusInset + 160.dp)
    val metadataY = ordinarySeekY + (maxOf(ordinarySeekY, lyricSeekY) - ordinarySeekY) * lyricProgress
    val seekY = metadataY + 64.dp
    val normalTransport = ordinarySeekY + 184.dp + extraActions
    val lyricTransport = virtualHeight - navigationInset - 152.dp
    val transportMotion = p * p * (3f - 2f * p)
    fun smoothReveal(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val stackedMetadata = smoothReveal((p - .04f) / .2f) * (1f - smoothReveal((p - .8f) / .2f))
    val titleBaseY = 5.dp + (metadataY - 5.dp) * p
    val metadataShift = (artY + artHeight + 8.dp - titleBaseY).coerceAtLeast(0.dp) * stackedMetadata
    val titleY = titleBaseY + metadataShift
    val subtitleY = 26.dp + (metadataY + 34.dp - 26.dp) * p + metadataShift
    val transportBaseY = (CompactControlSize - 48.dp) / 2 +
        (normalTransport + (lyricTransport - normalTransport) * lyricProgress - (CompactControlSize - 48.dp) / 2) * transportMotion
    val transportY = transportBaseY + (subtitleY + 28.dp - transportBaseY).coerceAtLeast(0.dp) *
        smoothReveal((p - .2f) / .1f) * (1f - smoothReveal((p - .8f) / .2f))
    val backgroundReveal = smoothReveal((p - .38f) / .59f)
    val handleReveal = smoothReveal((p - .72f) / .28f)
    val glassFrost = smoothReveal((p - .08f) / .8f)
    val surfaceCorner = CompactControlSize / 2 * (1f - smoothReveal((p - .8f) / .2f))
    val edgeSoftness = backgroundProgress * smoothReveal(p / .18f) * (1f - smoothReveal((p - .9f) / .1f))
    val edgeBand = .02f + .24f * smoothReveal(p / .75f)
    val fullControlsReveal = smoothReveal((p - .8f) / .2f)
    val contentInset = 32.dp
    val contentWidth = windowWidth - contentInset * 2
    val transportWidth = minOf(contentWidth, 280.dp)
    val miniTextWidth = (windowWidth - 252.dp).coerceAtLeast(40.dp)
    val textWidth = miniTextWidth + (contentWidth - miniTextWidth) * p
    val textX = 58.dp + (contentInset - 58.dp) * p
    val foreground = androidx.compose.ui.graphics.lerp(Color.Black, Color.White, backgroundReveal)
    val artworkShape = RoundedCornerShape((8f + 16f * p).dp * (1f - p * backgroundProgress))
    val artPixels = with(density) { artWidth.toPx() }
    val (artSwipe, artworkDrag) = rememberArtworkSwipe(artworkVisit, artwork, previousArtwork, nextArtwork,
        artPixels, expanded && p >= .99f && !lyricsOpen && !artwork.loading && (queueProgress?.value ?: 0f) == 0f,
        settings, onArtworkVisit)
    val swipeOffset = artSwipe.offset.value
    val swipeFraction = (kotlin.math.abs(swipeOffset) / (artPixels * 1.1f)).coerceIn(0f, 1f)
    val swipeFeather = kotlin.math.sin(swipeFraction * kotlin.math.PI).toFloat() * .9f
    val baseColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = baseColors.copy(onSurface = foreground,
        onSurfaceVariant = foreground.copy(alpha = 0.72f), primary = androidx.compose.ui.graphics.lerp(baseColors.primary, Color.White, p),
        surface = androidx.compose.ui.graphics.lerp(baseColors.surface, palette.bottom, p),
        surfaceContainer = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainer, palette.middle, p),
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHigh, palette.middle, p),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHighest, palette.top, p))) {
    Surface(Modifier.offset(x = 12.dp * (1f - p), y = miniTop * (1f - p))
        .width(surfaceWidth).height(CompactControlSize + (maxHeight - CompactControlSize) * p)
        .then(drag).then(if ((queueProgress?.value ?: 0f) > 0f) Modifier.clearAndSetSemantics {} else Modifier).testTag("player-surface").then(if (frostedBackdrop != null) Modifier.recordFrostedBackdrop(frostedBackdrop) else Modifier),
        shape = RoundedCornerShape(surfaceCorner), color = Color.Transparent, contentColor = foreground) {
        if (backgroundReveal < 1f) LiquidGlassSurface(Modifier.fillMaxSize(), source = glassBackdrop,
            cornerRadius = surfaceCorner, frostProgress = glassFrost, pressFeedback = false)
        Column(Modifier.fillMaxSize().verticalScroll(playerScroll, enabled = p > 0.99f && virtualHeight > maxHeight)) {
        Box(Modifier.fillMaxWidth().height(virtualHeight).clipToBounds()
            .then(if (!expanded) Modifier.clickable(onClickLabel = "Expand player") { settle(true) } else Modifier)) {
            ArtworkBlend(artSwipe.source ?: artwork, Modifier.fillMaxSize(), duration = paletteDuration,
                immediate = artworkVisit != null && artSwipe.handoff == artworkVisit) { frame ->
                val destination = artSwipe.destination
                val neighborOffset = swipeOffset - (if (swipeOffset < 0f) -1f else 1f) * artPixels * 1.1f
                if (backgroundReveal > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = backgroundReveal }
                    .then(if (lyricsOpen) Modifier.recordFrostedBackdrop(lyricBackdrop) else Modifier)) {
                    PlayerBackdrop(frame.palette, Modifier.fillMaxSize(), coverBlend * (1f - lyricProgress),
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + swipeOffset, artPixels, softness = swipeFeather / .9f)
                    if (destination != null) PlayerBackdrop(destination.visual.palette,
                        Modifier.fillMaxSize().graphicsLayer { alpha = swipeFraction }, coverBlend,
                        with(density) { artY.toPx() }, with(density) { artHeight.toPx() },
                        with(density) { artX.toPx() } + neighborOffset, artPixels, softness = swipeFeather / .9f)
                }
                @Composable fun cover(frame: PlayerVisual, offset: Float, neighbor: Boolean = false) {
                    Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight).graphicsLayer {
                        val remaining = if (neighbor) kotlin.math.abs(swipeOffset) else artPixels * 1.1f - kotlin.math.abs(swipeOffset)
                        alpha = (1f - lyricProgress) * if (artSwipe.active) (remaining / (artPixels * .08f)).coerceIn(0f, 1f) else 1f
                        translationX = offset
                    }.then(if (neighbor) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (edgeSoftness > 0f && frame.softArtwork != null) androidx.compose.foundation.Image(frame.softArtwork, null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = edgeSoftness }.artworkEdgeFeather(1f, .13f),
                        contentScale = ContentScale.Fit)
                    PlayerArtworkFrame(frame, Modifier.fillMaxSize().testTag(if (neighbor) "player-neighbor-artwork" else "player-artwork").graphicsLayer {
                        compositingStrategy = if (coverBlend > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }.drawWithContent {
                        drawContent()
                        if (coverBlend > 0f) {
                            val stops = Array(33) { index ->
                                val y = index / 32f
                                val edge = if (y < 0.14f) (0.14f - y) / 0.14f else (y - 0.68f) / 0.32f
                                val t = edge.coerceIn(0f, 1f)
                                y to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t) * coverBlend)
                            }
                            drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                        }
                    }.artworkEdgeFeather(maxOf(edgeSoftness, swipeFeather), if (artSwipe.active) .13f else edgeBand).clip(artworkShape),
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
                    onClickLabel = settings.artworkTap.label, onClick = { artworkAction(settings.artworkTap) },
                    onLongClickLabel = settings.artworkHold.label, onLongClick = { artworkAction(settings.artworkHold) }) else Modifier)) {
                GlassMenu(artworkMenu, rememberGlassAnchor(), { artworkMenu = false }) {
                    ActionMenuItem("View artwork", onClick = { artworkMenu = false; viewing = true })
                    if (onEditTags != null) ActionMenuItem("Change artwork", onClick = { artworkMenu = false; onEditTags() })
                    ActionMenuItem("Configure tap action", onClick = { artworkMenu = false; configureTap = true })
                }
            }
            if (handleReveal > 0f) Box(Modifier.offset(y = statusInset * handleReveal).fillMaxWidth().height(48.dp).graphicsLayer { alpha = handleReveal }) {
                Box(Modifier.align(Alignment.TopCenter).size(96.dp, 48.dp)
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Collapse player") { settle(false) }
                    .semantics { contentDescription = "Collapse player" }) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp * handleReveal).size(72.dp, 4.dp).testTag("player-handle")
                        .background(Color.White.copy(alpha = .42f), RoundedCornerShape(2.dp)))
                }
                if (lyricProgress > 0f) Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp).graphicsLayer { alpha = lyricProgress }) {
                    CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) {
                        ActionIcon(R.drawable.ic_lyrics, "Lyrics sources", anchor = sourcesAnchor) { sourcesRequest++ }
                    }
                }
            }
            if (lyricProgress > 0f) CompositionLocalProvider(LocalFrostedBackdrop provides lyricBackdrop) { LyricsPanel(track, state.positionMs, settings.reducedMotion, onSeek,
                Modifier.offset(x = contentInset, y = statusInset + 60.dp).width(contentWidth)
                    .height((metadataY - statusInset - 64.dp).coerceAtLeast(96.dp))
                    .graphicsLayer { alpha = lyricProgress * p }, sourcesRequest = sourcesRequest, sourcesAnchor = sourcesAnchor) }
            val titleScale = MaterialTheme.typography.titleSmall.fontSize.value / MaterialTheme.typography.headlineSmall.fontSize.value
            val subtitleScale = MaterialTheme.typography.bodySmall.fontSize.value / MaterialTheme.typography.bodyLarge.fontSize.value
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
                    modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" })
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
                IconButton(onPrevious, Modifier.size(48.dp), enabled = state.connected) {
                    Icon(painterResource(R.drawable.ic_previous), "Previous", Modifier.size(24.dp + 10.dp * transportMotion), tint = foreground)
                }
                IconButton(onToggle, Modifier.size(48.dp + 16.dp * transportMotion), enabled = state.connected) {
                    Icon(painterResource(if (state.isPlaying || state.isBuffering) R.drawable.ic_pause else R.drawable.ic_play),
                        if (state.isPlaying || state.isBuffering) "Pause" else "Play", Modifier.size(24.dp + 16.dp * transportMotion), tint = foreground)
                }
                IconButton(onNext, Modifier.size(48.dp), enabled = state.connected) {
                    Icon(painterResource(R.drawable.ic_next), "Next", Modifier.size(24.dp + 10.dp * transportMotion), tint = foreground)
                }
            }


        }
    }
    }
    }
    if (viewing && state.trackUri != null) ArtworkViewer(state.trackUri) { viewing = false }
    if (configureTap) GlassAlertDialog(onDismissRequest = { configureTap = false }, title = { Text("Artwork tap") },
        text = { Column { ArtworkAction.entries.forEach { value -> TextButton(onClick = {
            onSettings(settings.copy(artworkTap = value)); configureTap = false
        }) { Text(value.label) } } } }, confirmButton = { TextButton(onClick = { configureTap = false }) { Text("Close") } })
}
