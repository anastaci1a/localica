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
            SettingsHeading("Interface gray")
            Text("Adjust secondary text and inactive icons throughout the app.")
            InterfaceSlider(value.grayTone, { onChange(value.copy(grayTone = it)) },
                Modifier.semantics { contentDescription = "Gray tone" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Darker", style = MaterialTheme.typography.bodySmall)
                Text("Lighter", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(io.github.anastaci1a.localica.R.drawable.ic_library), null,
                    Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Secondary text", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Selected", color = NavigationAccent)
            }
            TextButton(onClick = { onChange(value.copy(grayTone = InterfaceSettings().grayTone)) }) { Text("Reset gray tone") }
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
            SettingsHeading("Artwork")
            ChoicePreference("Player artwork", value.artworkLayout, ArtworkLayout.entries, { it.label }) { onChange(value.copy(artworkLayout = it)) }
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
                            TextButton(enabled = index > 0, onClick = {
                                val tabs = value.tabs.toMutableList().apply { add(index - 1, removeAt(index)) }; onChange(value.copy(tabs = tabs))
                            }) { Text("Move up") }
                            TextButton(enabled = index < value.tabs.lastIndex, onClick = {
                                val tabs = value.tabs.toMutableList().apply { add(index + 1, removeAt(index)) }; onChange(value.copy(tabs = tabs))
                            }) { Text("Move down") }
                            TextButton(enabled = value.tabs.size > 1, onClick = { onChange(value.copy(tabs = value.tabs - tab)) }) { Text("Remove") }
                        }
                    }
                }
            }
            var adding by remember { mutableStateOf(false) }
            SettingsLink("Add main tab", enabled = value.tabs.size < MainTab.entries.size) { adding = true }
            if (adding) ChoiceDialog("Add main tab", MainTab.entries.filterNot { it in value.tabs }, MainTab.Home, { it.label },
                { adding = false }) { onChange(value.copy(tabs = value.tabs + it)) }
            TextButton(onClick = { onChange(value.copy(tabs = InterfaceSettings().tabs)) }) { Text("Reset main tabs") }
        }
    }
}

@Composable
internal fun ArtworkActionPreference(label: String, value: ArtworkAction, onChange: (ArtworkAction) -> Unit) {
    ChoicePreference(label, value, ArtworkAction.entries, { it.label }, onChange)
}

@Composable
internal fun BooleanPreference(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        InterfaceSwitch(value)
    }
}
