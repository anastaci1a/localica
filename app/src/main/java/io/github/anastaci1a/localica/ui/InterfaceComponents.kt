package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.MainTab

@Composable
internal fun ActionIcon(resource: Int, label: String, enabled: Boolean = true, anchor: GlassAnchor? = null,
    glass: Boolean = true, quiet: Boolean = false, onClick: () -> Unit) {
    val source = LocalFrostedBackdrop.current
    SideEffect { anchor?.source = source; anchor?.face = { Icon(painterResource(resource), null, Modifier.size(24.dp)) } }
    val modifier = Modifier.size(if (glass) CompactControlSize else 48.dp).then(if (anchor != null) Modifier.glassAnchor(anchor) else Modifier)
    if (quiet) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val reveal by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f,
            androidx.compose.animation.core.tween(if (LocalReducedMotion.current) 0 else 120), label = "Overflow glass")
        Box(modifier.glassPress().clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button,
            interactionSource = interaction, indication = null, onClick = onClick), contentAlignment = androidx.compose.ui.Alignment.Center) {
            LiquidGlassSurface(Modifier.matchParentSize().alpha(reveal), pressFeedback = false)
            Icon(painterResource(resource), label, Modifier.size(24.dp))
        }
    } else if (glass) LiquidGlassSurface(modifier.alpha(if (enabled) 1f else .38f), pressFeedback = enabled) {
        IconButton(onClick, Modifier.fillMaxSize(), enabled = enabled) { Icon(painterResource(resource), label, Modifier.size(24.dp)) }
    } else IconButton(onClick, modifier, enabled = enabled) {
        Icon(painterResource(resource), label, Modifier.size(24.dp))
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
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(24.dp)) })
}
