package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.MainTab

@Composable
internal fun ActionIcon(resource: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp), enabled = enabled) {
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
        label.contains("playlist", true) -> R.drawable.ic_playlist
        label.contains("queue", true) -> R.drawable.ic_queue
        label.contains("artwork", true) || label.contains("album", true) -> R.drawable.ic_albums
        label.contains("artist", true) -> R.drawable.ic_artist
        label.contains("folder", true) || label.contains("scan", true) -> R.drawable.ic_folder
        label.contains("lyrics", true) -> R.drawable.ic_lyrics
        label.contains("select", true) || label.startsWith("Add") -> R.drawable.ic_plus
        label.startsWith("Pin") || label.startsWith("Unpin") -> R.drawable.ic_home
        label.startsWith("Delete") || label.startsWith("Remove") || label.startsWith("Close") -> R.drawable.ic_close
        label.startsWith("Play") || label.startsWith("Insert") -> R.drawable.ic_play
        label.startsWith("Move") -> R.drawable.ic_drag
        label.contains("Settings", true) || label.contains("Edit", true) || label.contains("Configure", true) || label.contains("Rename", true) -> R.drawable.ic_settings
        else -> R.drawable.ic_more
    }
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(24.dp)) })
}
