package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.navigation.MainTab

@Composable
internal fun ActionIcon(resource: AppIcon, label: String, enabled: Boolean = true, anchor: GlassAnchor? = null,
    glass: Boolean = true, quiet: Boolean = false, iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, quietInputEnabled: Boolean = true,
    onClick: () -> Unit) {
    val source = LocalFrostedBackdrop.current
    SideEffect { anchor?.quiet = quiet || !glass; if (!glass) anchor?.cornerRadius = null; anchor?.source = source; anchor?.face = { Icon(appIconPainter(resource), null, Modifier.size(iconSize)) } }
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
            // The label lives on the button node itself: the merged accessibility node is identical,
            // and the unmerged tree (walked by every accessibility pass) has one node less per control.
            .semantics { contentDescription = label }
        else Modifier.clearAndSetSemantics { }
        Box(modifier.alpha(opacity).then(input), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(appIconPainter(resource), null, Modifier.size(iconSize))
        }
    } else if (glass) LiquidGlassSurface(modifier, pressFeedback = enabled) {
        anchor?.captureInk()
        ActionIconButton(onClick, Modifier.fillMaxSize(), enabled = enabled) { Icon(appIconPainter(resource), label, Modifier.size(iconSize)) }
    } else ActionIconButton(onClick, modifier, enabled = enabled) {
        anchor?.captureInk()
        Icon(appIconPainter(resource), label, Modifier.size(iconSize))
    }
}
internal fun MainTab.icon(): AppIcon = when (this) {
    MainTab.Home -> AppIcon.TabHome
    MainTab.Library -> AppIcon.TabLibrary
    MainTab.Songs, MainTab.MostPlayed -> AppIcon.TabSongs
    MainTab.Albums -> AppIcon.TabAlbums
    MainTab.Artists, MainTab.AlbumArtists -> AppIcon.TabArtists
    MainTab.Folders -> AppIcon.TabFolders
    MainTab.Playlists -> AppIcon.TabPlaylists
    MainTab.Queues -> AppIcon.TabQueues
}

/** Shared action presentation for item, artwork, queue, and application menus. */
@Composable
internal fun ActionMenuItem(label: String, enabled: Boolean = true, icon: AppIcon, onClick: () -> Unit) {
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
