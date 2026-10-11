package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings

internal val LocalArtworkPreviewUris = staticCompositionLocalOf<List<String>> { emptyList() }

/** An isolated visual preview: no transport callbacks or queue writes. */
@Composable
internal fun ArtworkTransitionPreview(settings: InterfaceSettings) {
    val uris = LocalArtworkPreviewUris.current
    var opened by remember { mutableStateOf(false) }
    SettingsLink("Preview artwork transition", enabled = uris.size >= 2) { opened = true }
    if (opened) {
        var index by remember { mutableIntStateOf(0) }
        val visual = rememberPlayerVisual(uris[index % uris.size])
        GlassAlertDialog(onDismissRequest = { opened = false }, title = { Text(settings.artworkTransition.label) },
            text = {
                CompositionLocalProvider(LocalArtworkTransition provides settings.artworkTransition,
                    LocalReducedMotion provides settings.reducedMotion) {
                    PlayerArtwork(visual, Modifier.fillMaxWidth().aspectRatio(1f), Corners.largeArtwork,
                        ContentScale.Crop, settings.motionDuration(550))
                }
            }, confirmButton = { TextButton(onClick = { index++ }) { Text("Preview again") } },
            dismissButton = { TextButton(onClick = { opened = false }) { Text("Close") } })
    }
}
