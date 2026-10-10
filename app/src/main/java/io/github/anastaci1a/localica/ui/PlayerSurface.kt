package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
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
    actionRows: Int = 1, queueProgress: Animatable<Float, *>? = null, actions: @Composable () -> Unit = {}) {
    val windowWidth = maxWidth
    val scope = rememberCoroutineScope()
    val playerScroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) playerScroll.scrollTo(0) }
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val miniTop = maxHeight - navigationInset - 144.dp
    val distance = with(density) { miniTop.toPx().coerceAtLeast(1f) }
    val p = progress.value
    val palette = rememberPlayerPalette(state.trackUri)
    val lyricProgress by animateFloatAsState(if (lyricsOpen && expanded) 1f else 0f, tween(settings.motionDuration(450)), label = "Lyrics layout")
    val backgroundProgress by animateFloatAsState(if (settings.artworkLayout == ArtworkLayout.FullBackground) 1f else 0f,
        tween(settings.motionDuration(400)), label = "Artwork layout")
    var startExpanded by remember { mutableStateOf(expanded) }
    var startProgress by remember { mutableFloatStateOf(progress.value) }
    var movement by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
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
        movement += delta
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo((startProgress - movement / distance).coerceIn(0f, 1f))
            if (startExpanded) queueProgress?.snapTo((-movement / distance).coerceIn(0f, 1f))
        }
    }
    val drag = Modifier.draggable(dragState, Orientation.Vertical,
        onDragStarted = { startExpanded = expanded; startProgress = progress.value; movement = 0f; dragging = true; progress.stop() },
        onDragStopped = { velocity ->
            dragging = false
            val threshold = with(density) { 48.dp.toPx() }
            if (startExpanded && (movement < -threshold || velocity < -with(density) { 300.dp.toPx() })) { settle(true); onQueue() }
            else {
            scope.launch { queueProgress?.animateTo(0f, tween(settings.motionDuration(400))) }
            settle(when {
                velocity < -900f -> true
                velocity > with(density) { 300.dp.toPx() } -> false
                !startExpanded && movement < -threshold -> true
                startExpanded && movement > threshold -> false
                else -> progress.value >= 0.5f
            })
            }
        })
    val surfaceWidth = windowWidth - 24.dp * (1f - p)
    val extraActions = 48.dp * (actionRows - 1)
    val virtualHeight = maxHeight.coerceAtLeast(560.dp + statusInset + navigationInset + extraActions)
    val floatingArt = minOf(windowWidth - 48.dp, (virtualHeight - statusInset - navigationInset - 440.dp - extraActions).coerceAtLeast(96.dp))
    val floatingWidth = minOf(floatingArt, floatingArt * palette.aspect)
    val floatingHeight = minOf(floatingArt, floatingArt / palette.aspect)
    val fullArt = floatingWidth + (windowWidth - floatingWidth) * backgroundProgress
    val fullArtHeight = floatingHeight + (windowWidth / palette.aspect - floatingHeight) * backgroundProgress
    val artWidth = 48.dp + (fullArt - 48.dp) * p
    val artHeight = 48.dp + (fullArtHeight - 48.dp) * p
    val artX = 12.dp + ((windowWidth - fullArt) / 2 - 12.dp) * p
    val fullArtY = statusInset + 52.dp + (floatingArt - fullArtHeight) / 2
    val artY = 8.dp + (fullArtY - 8.dp) * p - 24.dp * lyricProgress
    val ordinarySeekY = statusInset + 52.dp + floatingArt + 8.dp
    val lyricSeekY = virtualHeight - navigationInset - 320.dp
    val seekY = ordinarySeekY + (maxOf(ordinarySeekY, lyricSeekY) - ordinarySeekY) * lyricProgress
    val titleY = 10.dp + (seekY + 64.dp - 10.dp) * p
    val subtitleY = 34.dp + (seekY + 98.dp - 34.dp) * p
    val normalTransport = ordinarySeekY + 184.dp + extraActions
    val lyricTransport = virtualHeight - navigationInset - 152.dp
    val transportY = 8.dp + (normalTransport + (lyricTransport - normalTransport) * lyricProgress - 8.dp) * p
    val miniTextWidth = (windowWidth - 252.dp).coerceAtLeast(40.dp)
    val textWidth = miniTextWidth + (windowWidth - 48.dp - miniTextWidth) * p
    val textX = 68.dp + (24.dp - 68.dp) * p
    val foreground = androidx.compose.ui.graphics.lerp(Color.Black, Color.White, p)
    val baseColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = baseColors.copy(onSurface = foreground,
        onSurfaceVariant = foreground.copy(alpha = 0.72f), primary = androidx.compose.ui.graphics.lerp(baseColors.primary, Color.White, p),
        surface = androidx.compose.ui.graphics.lerp(baseColors.surface, palette.bottom, p),
        surfaceContainer = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainer, palette.middle, p),
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHigh, palette.middle, p),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(baseColors.surfaceContainerHighest, palette.top, p))) {
    Surface(Modifier.offset(x = 12.dp * (1f - p), y = miniTop * (1f - p))
        .width(surfaceWidth).height(64.dp + (maxHeight - 64.dp) * p)
        .then(drag).then(if ((queueProgress?.value ?: 0f) > 0f) Modifier.clearAndSetSemantics {} else Modifier).testTag("player-surface"),
        shape = RoundedCornerShape((32 * (1f - p)).dp), color = Color(0xFFF2F0EC), contentColor = foreground, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(playerScroll, enabled = p > 0.99f && virtualHeight > maxHeight)) {
        Box(Modifier.fillMaxWidth().height(virtualHeight).clipToBounds()) {
            if (p > 0f) PlayerBackdrop(palette, Modifier.fillMaxSize().graphicsLayer { alpha = p }, backgroundProgress * (1f - lyricProgress))
            if (p < 1f) Box(Modifier.fillMaxSize().clickable(onClickLabel = "Expand player") { settle(true) })
            Box(Modifier.offset(artX, artY).width(artWidth).height(artHeight).graphicsLayer { alpha = 1f - lyricProgress }
                .then(if (lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                AlbumArtwork(state.trackUri, Modifier.fillMaxSize().testTag("player-artwork").drawWithContent {
                    drawContent()
                    if (p > 0f && backgroundProgress > 0f) drawRect(Brush.verticalGradient(
                        0f to palette.bottom.copy(alpha = p * backgroundProgress),
                        0.15f to Color.Transparent, 0.56f to Color.Transparent,
                        1f to palette.bottom.copy(alpha = p * backgroundProgress)))
                }.combinedClickable(enabled = !lyricsOpen,
                    onClickLabel = if (expanded) settings.artworkTap.label else "Expand player",
                    onClick = { if (expanded) artworkAction(settings.artworkTap) else settle(true) },
                    onLongClickLabel = settings.artworkHold.label,
                    onLongClick = { if (expanded) artworkAction(settings.artworkHold) }),
                    shape = RoundedCornerShape((8f + 16f * p).dp * (1f - p * backgroundProgress)),
                    contentScale = if (p == 0f) ContentScale.Crop else ContentScale.Fit)
                DropdownMenu(artworkMenu, { artworkMenu = false }) {
                    ActionMenuItem("View artwork", onClick = { artworkMenu = false; viewing = true })
                    if (onEditTags != null) ActionMenuItem("Change artwork", onClick = { artworkMenu = false; onEditTags() })
                    ActionMenuItem("Configure tap action", onClick = { artworkMenu = false; configureTap = true })
                }
            }
            if (p > 0f) Row(Modifier.offset(y = statusInset).fillMaxWidth().height(48.dp).graphicsLayer { alpha = p },
                verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_down, "Collapse player") { settle(false) }
                Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ActionIcon(R.drawable.ic_lyrics, if (lyricsOpen) "Hide lyrics" else "Show lyrics") { onLyrics(!lyricsOpen) }
            }
            if (lyricProgress > 0f) LyricsPanel(track, state.positionMs, settings.reducedMotion, onSeek,
                Modifier.offset(x = 24.dp, y = statusInset + 60.dp).width(windowWidth - 48.dp)
                    .height((seekY - statusInset - 64.dp).coerceAtLeast(96.dp))
                    .graphicsLayer { alpha = lyricProgress * p })
            MarqueeTitle(state.title, settings.reducedMotion || (p > 0f && p < 1f), Modifier.offset(textX, titleY).width(textWidth).clickable(enabled = !expanded) { settle(true) },
                style = lerp(MaterialTheme.typography.titleSmall, MaterialTheme.typography.headlineSmall, p))
            Text((if (settings.albumArtistFirst) state.albumArtist ?: state.artist else state.artist ?: state.albumArtist).orEmpty() +
                if (p > 0.99f && state.album != null) " · " + state.album else "",
                Modifier.offset(textX, subtitleY).width(textWidth), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = lerp(MaterialTheme.typography.bodySmall, MaterialTheme.typography.bodyLarge, p), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p > 0f) Column(Modifier.offset(x = 24.dp, y = seekY).width(windowWidth - 48.dp).graphicsLayer { alpha = p }) {
                var seeking by remember(state.trackUri) { mutableStateOf<Float?>(null) }
                val duration = (state.durationMs ?: 0).coerceAtLeast(1).toFloat()
                Slider((seeking ?: state.positionMs.toFloat()).coerceIn(0f, duration), { seeking = it },
                    valueRange = 0f..duration, onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
                    colors = SliderDefaults.colors(activeTrackColor = Color.White, thumbColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f), inactiveTickColor = Color.Transparent),
                    enabled = state.connected && (state.durationMs ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(seeking?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (p > 0f && lyricProgress < 1f) Box(Modifier.offset(y = ordinarySeekY + 128.dp + 32.dp * lyricProgress)
                .fillMaxWidth().height(48.dp * actionRows).graphicsLayer { alpha = p * (1f - lyricProgress) }
                .then(if (lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) { actions() }
            Row(Modifier.offset(x = (surfaceWidth - 148.dp) * (1 - p) + ((windowWidth - 176.dp) / 2) * p, y = transportY),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp * p)) {
                IconButton(onPrevious, Modifier.size(48.dp), enabled = state.connected) {
                    Icon(painterResource(R.drawable.ic_previous), "Previous", Modifier.size(24.dp + 10.dp * p), tint = foreground)
                }
                IconButton(onToggle, Modifier.size(48.dp + 16.dp * p), enabled = state.connected) {
                    Icon(painterResource(if (state.isPlaying || state.isBuffering) R.drawable.ic_pause else R.drawable.ic_play),
                        if (state.isPlaying || state.isBuffering) "Pause" else "Play", Modifier.size(24.dp + 16.dp * p), tint = foreground)
                }
                IconButton(onNext, Modifier.size(48.dp), enabled = state.connected) {
                    Icon(painterResource(R.drawable.ic_next), "Next", Modifier.size(24.dp + 10.dp * p), tint = foreground)
                }
            }
            if (p > 0f) {
                val message = when {
                    state.problem != null -> when (state.problem) {
                        io.github.anastaci1a.localica.playback.PlaybackProblem.Unreadable -> "File unavailable"
                        io.github.anastaci1a.localica.playback.PlaybackProblem.Unsupported -> "Unsupported audio format"
                        else -> "Playback failed. Try again."
                    }
                    state.isBuffering -> "Loading audio…"
                    state.ended -> "Track finished"
                    !state.connected -> "Connecting…"
                    else -> null
                }
                if (message != null) Text(message, Modifier.offset(x = 24.dp, y = transportY + 72.dp).width(windowWidth - 48.dp),
                    style = MaterialTheme.typography.bodySmall, color = if (state.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p > 0f && (queueProgress?.value ?: 0f) == 0f) Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                .padding(bottom = navigationInset + 8.dp).fillMaxWidth().height(64.dp).graphicsLayer { alpha = p },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = androidx.compose.ui.graphics.lerp(palette.bottom, Color.White, 0.1f).copy(alpha = 0.94f),
                contentColor = Color.White, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxSize().clickable(onClickLabel = "Open queue", onClick = onQueue)
                    .semantics { contentDescription = "Open queue" },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_queue), null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp)); Text("Queue")
                }
            }
        }
    }
    }
    }
    if (viewing && state.trackUri != null) ArtworkViewer(state.trackUri) { viewing = false }
    if (configureTap) AlertDialog(onDismissRequest = { configureTap = false }, title = { Text("Artwork tap") },
        text = { Column { ArtworkAction.entries.forEach { value -> TextButton(onClick = {
            onSettings(settings.copy(artworkTap = value)); configureTap = false
        }) { Text(value.label) } } } }, confirmButton = { TextButton(onClick = { configureTap = false }) { Text("Close") } })
}
