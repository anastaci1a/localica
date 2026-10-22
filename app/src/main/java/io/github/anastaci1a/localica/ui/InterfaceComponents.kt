package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.MainTab

@Composable
internal fun ActionIcon(resource: Int, label: String, enabled: Boolean = true, anchor: GlassAnchor? = null,
    glass: Boolean = true, quiet: Boolean = false, iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, quietInputEnabled: Boolean = true,
    onClick: () -> Unit) {
    val source = LocalFrostedBackdrop.current
    SideEffect { anchor?.quiet = quiet; anchor?.source = source; anchor?.face = { Icon(painterResource(resource), null, Modifier.size(iconSize)) } }
    val modifier = Modifier.size(if (glass) CompactControlSize else 48.dp).then(if (anchor != null) Modifier.glassAnchor(anchor) else Modifier)
    if (quiet) {
        val opacity by animateControlStateFloat(if (enabled) 1f else .38f, enabled, "Quiet action availability")
        // This anchor can previously have belonged to gallery artwork. The plain
        // icon returns to a temporary circle, not that artwork's rounded square.
        SideEffect { anchor?.cornerRadius = null }
        anchor?.captureInk()
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        // Outgoing quiet drawings can remain visible without retaining a hit
        // target or changing their availability opacity during the exit fade.
        val input = if (quietInputEnabled) Modifier.ownButtonDrag(enabled).combinedClickable(enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Button, interactionSource = interaction, indication = null,
            onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = controlActivation(enabled, onClick))
        else Modifier.clearAndSetSemantics { }
        Box(modifier.alpha(opacity).then(input), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(painterResource(resource), label, Modifier.size(iconSize))
        }
    } else if (glass) LiquidGlassSurface(modifier, pressFeedback = enabled) {
        anchor?.captureInk()
        ActionIconButton(onClick, Modifier.fillMaxSize(), enabled = enabled) { Icon(painterResource(resource), label, Modifier.size(iconSize)) }
    } else ActionIconButton(onClick, modifier, enabled = enabled) {
        anchor?.captureInk()
        Icon(painterResource(resource), label, Modifier.size(iconSize))
    }
}
internal fun MainTab.icon() = when (this) {
    MainTab.Home -> R.drawable.ic_home
    MainTab.Library -> R.drawable.ic_library
    MainTab.Songs, MainTab.MostPlayed -> R.drawable.ic_music
    MainTab.Albums -> R.drawable.ic_albums
    MainTab.Artists, MainTab.AlbumArtists -> R.drawable.ic_artist
    MainTab.Folders -> R.drawable.ic_folder
    MainTab.Playlists -> R.drawable.ic_playlist
    MainTab.Queues -> R.drawable.ic_queue
}

/** Shared action presentation for item, artwork, queue, and application menus. */
@Composable
internal fun ActionMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val icon = when {
        label == "Sort" -> R.drawable.ic_sort
        label == "View options" -> R.drawable.ic_eye
        label == "Set playlist image" -> R.drawable.ic_albums
        label.contains("Information", true) -> R.drawable.ic_info
        label.startsWith("Delete") || label.startsWith("Remove") -> R.drawable.ic_trash
        label.startsWith("Close") -> R.drawable.ic_close
        label.startsWith("Save") -> R.drawable.ic_save
        label.startsWith("Add to playlist") -> R.drawable.ic_playlist_add
        label.startsWith("Rename", true) || label.startsWith("Edit", true) -> R.drawable.ic_edit
        label.contains("playlist", true) -> R.drawable.ic_playlist
        label.contains("queue", true) -> R.drawable.ic_queue
        label.contains("artwork", true) || label.contains("album", true) -> R.drawable.ic_albums
        label.contains("artist", true) -> R.drawable.ic_artist
        label.contains("folder", true) || label.contains("scan", true) -> R.drawable.ic_folder
        label.contains("lyrics", true) -> R.drawable.ic_lyrics
        label.contains("select", true) || label.startsWith("Add") -> R.drawable.ic_plus
        label.startsWith("Pin") || label.startsWith("Unpin") -> R.drawable.ic_home
        label.startsWith("Play") || label.startsWith("Insert") -> R.drawable.ic_play
        label.startsWith("Move") -> R.drawable.ic_drag
        label.contains("Settings", true) || label.contains("Configure", true) -> R.drawable.ic_settings
        else -> R.drawable.ic_more
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        GlassActionButton(icon, label, Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
    }
}

internal fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "–:––"
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** Shared separator for two-action glass pills. */
@Composable
internal fun GlassPillDivider(modifier: Modifier = Modifier) {
    Box(modifier.height(24.dp).width(1.dp).background(LocalContentColor.current.copy(alpha = .2f)))
}
