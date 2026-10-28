package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun InterfaceSettingsControls(value: InterfaceSettings, section: String = "Navigation", onChange: (InterfaceSettings) -> Unit) {
    when (section) {
        "Appearance" -> {
            Text("Adjust secondary text and inactive icons throughout the app.")
            SliderPreference("Interface gray · ${kotlin.math.round(value.grayTone * 100).toInt()}%", value.grayTone,
                { onChange(value.copy(grayTone = it)) }, Modifier.semantics { contentDescription = "Gray tone" }, low = "Darker", high = "Lighter")
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(io.github.anastaci1a.localica.R.drawable.ic_library), null,
                    Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Secondary text", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Selected", color = NavigationAccent)
            }
            ActionTextButton(onClick = { onChange(value.copy(grayTone = InterfaceSettings().grayTone)) }) { Text("Reset gray tone") }
        }
        "Glass" -> GlassSettingsControls(value.glass) { onChange(value.copy(glass = it)) }
        "Motion" -> {
            SettingsHeading("Animation")
            BooleanPreference("Reduce motion", value.reducedMotion) { onChange(value.copy(reducedMotion = it)) }
            BooleanPreference("Faster animations", value.fasterMotion) { onChange(value.copy(fasterMotion = it)) }
        }
        "Player" -> {
            SettingsHeading("Player behavior")
            BooleanPreference("Expand player when a song starts", value.expandOnPlay) { onChange(value.copy(expandOnPlay = it)) }
            BooleanPreference("Show Album Artist first", value.albumArtistFirst) { onChange(value.copy(albumArtistFirst = it)) }
            SettingsHeading("Lyrics timing")
            SliderPreference("Correction: ${value.lyricDelayMs} ms", value.lyricDelayMs.toFloat(),
                { onChange(value.copy(lyricDelayMs = kotlin.math.round(it / 50).toInt() * 50)) },
                valueRange = -2000f..2000f, steps = 79, low = "Earlier", high = "Later",
                description = "If lyrics lead the sound, move them later. This adjusts lyrics only; use 0 ms for normal output timing.")
            ActionTextButton(onClick = { onChange(value.copy(lyricDelayMs = 0)) }) { Text("Reset lyrics timing") }
            SettingsHeading("Artwork")
            ChoicePreference("Player artwork", value.artworkLayout, ArtworkLayout.entries, { it.label }) { onChange(value.copy(artworkLayout = it)) }
            ChoicePreference("Song-change transition", value.artworkTransition, ArtworkTransition.entries, { it.label }) { onChange(value.copy(artworkTransition = it)) }
            Text("For Previous/Next buttons and automatic song changes. Swipes use the carousel.", style = MaterialTheme.typography.bodySmall)
            BooleanPreference("Swipe artwork to change songs", value.artworkSwipe) { onChange(value.copy(artworkSwipe = it)) }
            ArtworkActionPreference("Artwork tap", value.artworkTap) { onChange(value.copy(artworkTap = it)) }
            ArtworkActionPreference("Artwork hold", value.artworkHold) { onChange(value.copy(artworkHold = it)) }
        }
        else -> {
            SettingsHeading("Back button")
            BooleanPreference("Retrace tab history", value.tabHistory) { onChange(value.copy(tabHistory = it)) }
            BooleanPreference("Confirm before exiting", value.confirmExit) { onChange(value.copy(confirmExit = it)) }
            SettingsHeading("Main tabs")
            value.tabs.forEachIndexed { index, tab ->
                Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(tab.label, style = MaterialTheme.typography.titleMedium)
                        Row {
                            ActionTextButton(enabled = index > 0, onClick = {
                                val tabs = io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(value.tabs, index, -1); onChange(value.copy(tabs = tabs))
                            }) { Text("Move up") }
                            ActionTextButton(enabled = index < value.tabs.lastIndex, onClick = {
                                val tabs = io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(value.tabs, index, 1); onChange(value.copy(tabs = tabs))
                            }) { Text("Move down") }
                            ActionTextButton(enabled = value.tabs.size > 1, onClick = { onChange(value.copy(tabs = value.tabs - tab)) }) { Text("Remove") }
                        }
                    }
                }
            }
            var adding by remember { mutableStateOf(false) }
            val addingOrigin = rememberGlassAnchor()
            SettingsLink("Add main tab", anchor = addingOrigin, enabled = value.tabs.size < MainTab.available.size) { adding = true }
            if (adding) CompositionLocalProvider(LocalGlassOrigin provides addingOrigin) {
                ChoiceDialog("Add main tab", MainTab.available.filterNot { it in value.tabs }, MainTab.Home, { it.label },
                    { adding = false }) { onChange(value.copy(tabs = value.tabs + it)) }
            }
            ActionTextButton(onClick = { onChange(value.copy(tabs = InterfaceSettings().tabs)) }) { Text("Reset main tabs") }
        }
    }
}

@Composable
internal fun ArtworkActionPreference(label: String, value: ArtworkAction, onChange: (ArtworkAction) -> Unit) {
    ChoicePreference(label, value, ArtworkAction.entries, { it.label }, onSelect = onChange)
}

@Composable
internal fun BooleanPreference(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val activate = controlActivation { onChange(!value) }
    BooleanPreferenceFrame(label, Modifier.ownButtonDrag(pass = androidx.compose.ui.input.pointer.PointerEventPass.Main)
        .toggleable(value, role = Role.Switch, onValueChange = { activate() })) {
        InterfaceSwitch(value, onChange)
    }
}

