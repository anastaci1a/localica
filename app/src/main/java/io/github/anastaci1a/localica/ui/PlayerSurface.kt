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
import androidx.compose.ui.graphics.graphicsLayer
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
    val miniTop = maxHeight - navigationInset - 136.dp
    val distance = with(density) { miniTop.toPx().coerceAtLeast(1f) }
    val p = progress.value
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
    val surfaceWidth = windowWidth - 16.dp * (1f - p)
    val extraActions = 48.dp * (actionRows - 1)
    val virtualHeight = maxHeight.coerceAtLeast(560.dp + statusInset + navigationInset + extraActions)
    val fullArt = minOf(windowWidth - 48.dp, (virtualHeight - statusInset - navigationInset - 440.dp - extraActions).coerceAtLeast(96.dp))
    val artSize = 48.dp + (fullArt - 48.dp) * p
    val artX = 4.dp + ((windowWidth - fullArt) / 2 - 4.dp) * p
    val artY = 8.dp + (statusInset + 52.dp - 8.dp) * p
    val seekY = statusInset + 52.dp + fullArt + 8.dp
    val titleY = 10.dp + (seekY + 64.dp - 10.dp) * p
    val subtitleY = 34.dp + (seekY + 98.dp - 34.dp) * p
    val transportY = 8.dp + (seekY + 184.dp + extraActions - 8.dp) * p
    val miniTextWidth = (windowWidth - 228.dp).coerceAtLeast(40.dp)
    val textWidth = miniTextWidth + (windowWidth - 48.dp - miniTextWidth) * p
    val textX = 60.dp + (24.dp - 60.dp) * p
    Surface(Modifier.offset(x = 8.dp * (1f - p), y = miniTop * (1f - p))
        .width(surfaceWidth).height(64.dp + (maxHeight - 64.dp) * p)
        .then(drag).then(if ((queueProgress?.value ?: 0f) > 0f) Modifier.clearAndSetSemantics {} else Modifier).testTag("player-surface"), shape = RoundedCornerShape((16 + 12 * p).dp), tonalElevation = 6.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(playerScroll, enabled = p > 0.99f && virtualHeight > maxHeight)) {
        Box(Modifier.fillMaxWidth().height(virtualHeight).clipToBounds()) {
            if (p < 1f) Box(Modifier.fillMaxSize().clickable(onClickLabel = "Expand player") { settle(true) })
            if (p > 0f) Row(Modifier.offset(y = statusInset).fillMaxWidth().height(48.dp).graphicsLayer { alpha = p },
                verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_down, "Collapse player") { settle(false) }
                Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ActionIcon(R.drawable.ic_lyrics, if (lyricsOpen) "Hide lyrics" else "Show lyrics") { onLyrics(!lyricsOpen) }
            }
            Box(Modifier.offset(artX, artY).size(artSize).clip(RoundedCornerShape((8 + 16 * p).dp))) {
                AlbumArtwork(state.trackUri, Modifier.fillMaxSize().testTag("player-artwork").combinedClickable(
                    onClickLabel = if (expanded) settings.artworkTap.label else "Expand player",
                    onClick = { if (expanded) artworkAction(settings.artworkTap) else settle(true) },
                    onLongClickLabel = settings.artworkHold.label,
                    onLongClick = { if (expanded) artworkAction(settings.artworkHold) }))
                androidx.compose.animation.AnimatedVisibility(lyricsOpen && p > 0.99f,
                    enter = androidx.compose.animation.fadeIn(tween(settings.motionDuration(350))),
                    exit = androidx.compose.animation.fadeOut(tween(settings.motionDuration(350)))) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        LyricsPanel(track, state.positionMs, settings.reducedMotion, onSeek, Modifier.fillMaxSize())
                    }
                }
                DropdownMenu(artworkMenu, { artworkMenu = false }) {
                    ActionMenuItem("View artwork", onClick = { artworkMenu = false; viewing = true })
                    if (onEditTags != null) ActionMenuItem("Change artwork", onClick = { artworkMenu = false; onEditTags() })
                    ActionMenuItem("Configure tap action", onClick = { artworkMenu = false; configureTap = true })
                }
            }
            MarqueeTitle(state.title, settings.reducedMotion || (p > 0f && p < 1f), Modifier.offset(textX, titleY).width(textWidth).clickable(enabled = !expanded) { settle(true) },
                style = lerp(MaterialTheme.typography.titleSmall, MaterialTheme.typography.headlineSmall, p))
            Text((if (settings.albumArtistFirst) state.albumArtist ?: state.artist else state.artist ?: state.albumArtist).orEmpty() +
                if (p > 0.99f && state.album != null) " · " + state.album else "",
                Modifier.offset(textX, subtitleY).width(textWidth), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p > 0f) Column(Modifier.offset(x = 24.dp, y = seekY).width(windowWidth - 48.dp).graphicsLayer { alpha = p }) {
                var seeking by remember(state.trackUri) { mutableStateOf<Float?>(null) }
                val duration = (state.durationMs ?: 0).coerceAtLeast(1).toFloat()
                Slider((seeking ?: state.positionMs.toFloat()).coerceIn(0f, duration), { seeking = it },
                    valueRange = 0f..duration, onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
                    enabled = state.connected && (state.durationMs ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(seeking?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (p > 0f) Box(Modifier.offset(y = seekY + 128.dp).fillMaxWidth().height(48.dp * actionRows).graphicsLayer { alpha = p }) { actions() }
            Row(Modifier.offset(x = (surfaceWidth - 148.dp) * (1 - p) + ((windowWidth - 176.dp) / 2) * p, y = transportY),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp * p)) {
                ActionIcon(R.drawable.ic_previous, "Previous", state.connected, onPrevious)
                FilledIconButton(onToggle, Modifier.size(48.dp + 16.dp * p), enabled = state.connected) {
                    Icon(painterResource(if (state.isPlaying || state.isBuffering) R.drawable.ic_pause else R.drawable.ic_play),
                        if (state.isPlaying || state.isBuffering) "Pause" else "Play", Modifier.size(24.dp + 12.dp * p))
                }
                ActionIcon(R.drawable.ic_next, "Next", state.connected, onNext)
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
            if (p > 0f && (queueProgress?.value ?: 0f) == 0f) Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp)
                .padding(bottom = navigationInset + 8.dp).fillMaxWidth().height(64.dp).graphicsLayer { alpha = p },
                shape = Corners.panel, tonalElevation = 3.dp) {
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
    if (viewing && state.trackUri != null) ArtworkViewer(state.trackUri) { viewing = false }
    if (configureTap) AlertDialog(onDismissRequest = { configureTap = false }, title = { Text("Artwork tap") },
        text = { Column { ArtworkAction.entries.forEach { value -> TextButton(onClick = {
            onSettings(settings.copy(artworkTap = value)); configureTap = false
        }) { Text(value.label) } } } }, confirmButton = { TextButton(onClick = { configureTap = false }) { Text("Close") } })
}
