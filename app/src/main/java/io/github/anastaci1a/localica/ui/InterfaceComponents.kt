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
