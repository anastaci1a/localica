package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import kotlinx.coroutines.launch

/** A single progress value controls geometry and both shelves; materials can be replaced independently. */
@Composable
internal fun BoxWithConstraintsScope.PlayerSurface(state: PlaybackState, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    settings: InterfaceSettings, progress: Animatable<Float, *>, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onLibrary: () -> Unit,
    onEditTags: (() -> Unit)? = null, lyricsOpen: Boolean = false, onLyrics: (Boolean) -> Unit = {},
    track: io.github.anastaci1a.localica.library.Track? = null, onSettings: (InterfaceSettings) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val miniTop = maxHeight - navigationInset - 64.dp - 8.dp - 64.dp
    val distance = with(density) { miniTop.toPx().coerceAtLeast(1f) }
    val p = progress.value
    LaunchedEffect(expanded, settings.reducedMotion) {
        if (settings.reducedMotion) progress.snapTo(if (expanded) 1f else 0f)
        else progress.animateTo(if (expanded) 1f else 0f, tween(280))
    }
    fun settle(target: Boolean) {
        onExpanded(target)
        scope.launch { if (settings.reducedMotion) progress.snapTo(if (target) 1f else 0f)
            else progress.animateTo(if (target) 1f else 0f, tween(220)) }
    }
    val drag = Modifier.pointerInput(distance, expanded, settings.reducedMotion) {
        var moved = 0f
        detectVerticalDragGestures(onDragStart = { moved = 0f }, onVerticalDrag = { change, dy ->
            change.consume(); moved += dy
            if (!settings.reducedMotion) scope.launch { progress.snapTo((progress.value - dy / distance).coerceIn(0f, 1f)) }
        }, onDragEnd = {
            val threshold = with(density) { 48.dp.toPx() }
            settle(if (moved < -threshold) true else if (moved > threshold) false else expanded)
        }, onDragCancel = { settle(expanded) })
    }
    Surface(modifier = Modifier.offset(x = 8.dp * (1f - p), y = miniTop * (1f - p))
        .width(maxWidth - 16.dp * (1f - p)).height(64.dp + (maxHeight - 64.dp) * p)
        .testTag("player-surface"), shape = RoundedCornerShape((16 * (1f - p)).dp), tonalElevation = 6.dp) {
        if (p < 0.5f) Row(Modifier.fillMaxSize().then(drag).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Expand player") { onExpanded(true) },
                verticalAlignment = Alignment.CenterVertically) {
                AlbumArtwork(state.trackUri, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    MarqueeTitle(state.title, settings.reducedMotion)
                    Text((if (settings.albumArtistFirst) state.albumArtist ?: state.artist else state.artist ?: state.albumArtist).orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            ActionIcon(R.drawable.ic_previous, "Previous", state.connected, onPrevious)
            ActionIcon(if (state.isPlaying || state.isBuffering) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.isPlaying || state.isBuffering) "Pause" else "Play", state.connected, onToggle)
            ActionIcon(R.drawable.ic_next, "Next", state.connected, onNext)
        } else Column(Modifier.fillMaxSize().padding(top = statusInset, bottom = navigationInset)) {
            Row(Modifier.fillMaxWidth().then(drag), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_down, "Collapse player") { onExpanded(false) }
                Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ActionIcon(R.drawable.ic_lyrics, if (lyricsOpen) "Hide lyrics" else "Show lyrics") { onLyrics(!lyricsOpen) }
                if (onEditTags != null) TextButton(onClick = onEditTags) { Text("Edit tags") }
            }
            Box(Modifier.weight(1f).then(if (lyricsOpen) Modifier else drag)) {
                PlayerScreen(state, onToggle, onSeek, onLibrary, onPrevious, onNext, settings.albumArtistFirst, onEditTags,
                    settings, lyricsOpen, onLyrics, track, onSettings)
            }
            Surface(Modifier.padding(8.dp).fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(R.drawable.ic_queue, "Open queue", onClick = onQueue)
                    Text("Queue")
                }
            }
        }
    }
}
