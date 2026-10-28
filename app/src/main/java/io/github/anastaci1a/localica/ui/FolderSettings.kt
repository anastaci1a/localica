package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.scanFolderLabels
import io.github.anastaci1a.localica.scanner.ScanPreferences

private data class SettingsDestination(val title: String, val parent: String?, val icon: Int, val summary: String)
private val settingsDestinations = listOf(
    SettingsDestination("Interface", null, R.drawable.ic_settings, "Navigation, player, glass, and motion"),
    SettingsDestination("Appearance", "Interface", R.drawable.ic_library, "Typography and interface gray"),
    SettingsDestination("Glass", "Interface", R.drawable.ic_settings, "Blur, white tint, and floating shadows"),
    SettingsDestination("Navigation", "Interface", R.drawable.ic_home, "Main tabs, Back history, and exit confirmation"),
    SettingsDestination("Player", "Interface", R.drawable.ic_albums, "Artwork, gestures, and player actions"),
    SettingsDestination("Motion", "Interface", R.drawable.ic_next, "Animation speed and reduced motion"),
    SettingsDestination("Playback and queues", null, R.drawable.ic_play, "Preloading, queue switching, and retention"),
    SettingsDestination("Song tap behavior", "Playback and queues", R.drawable.ic_music, "Default action and per-list overrides"),
    SettingsDestination("Library and tags", null, R.drawable.ic_library, "Folders, scanner configuration, tag jobs and recovery"),
    SettingsDestination("Library filters", "Library and tags", R.drawable.ic_library, "Visible filters, their order, and unfiltered collections"),
    SettingsDestination("Folders to scan", "Library and tags", R.drawable.ic_folder, "Choose music folders and excluded subfolders"),
    SettingsDestination("Scanner", "Library and tags", R.drawable.ic_search, "Quick scan, full scan, startup scans, subfolders and short files"),
    SettingsDestination("Metadata read failures", "Scanner", R.drawable.ic_info, "Files with incomplete metadata"),
    SettingsDestination("Tag jobs and recovery", "Library and tags", R.drawable.ic_music, "Background jobs, outcomes and interrupted tag edits"),
    SettingsDestination("About and licenses", null, R.drawable.ic_info, "Version, source code, help, and open-source licenses"),
)
private fun settingsPath(title: String?): List<String> {
    if (title == null) return emptyList()
    val name = when (title) { "Interface and gestures" -> "Interface"; "Scanner configuration" -> "Scanner"; else -> title }
    val destination = settingsDestinations.firstOrNull { it.title == name } ?: return emptyList()
    return settingsPath(destination.parent) + name
}

@Composable
fun FolderSettings(state: LibraryUiState, onDismiss: () -> Unit, onChooseFolder: () -> Unit,
    onRemove: (String) -> Unit, extraContent: @Composable () -> Unit = {},
    interfaceContent: @Composable (String) -> Unit = {}, playbackContent: @Composable (String) -> Unit = {},
    aboutContent: @Composable () -> Unit = {}, initialCategory: String? = null, onFullScan: () -> Unit = {}, onQuickScan: () -> Unit = {}, libraryFiltersContent: @Composable () -> Unit = {}) {
    var path by rememberSaveable { mutableStateOf(settingsPath(initialCategory)) }
    var search by rememberSaveable { mutableStateOf("") }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<FolderSelection?>(null) }
    val folderLabels = remember(state.snapshot.folders) { scanFolderLabels(state.snapshot.folders) }
    val page = path.lastOrNull()
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val preferences = remember(context) { ScanPreferences(context) }
    val pageKey = (page ?: "root") + if (page == "Folder options") folderUri.orEmpty() else ""
    fun back() { if (path.isEmpty()) onDismiss() else path = path.dropLast(1) }
    SettingsPage(page ?: "Settings", ::back, onDismiss, navigationDepth = path.size,
        onSystemBack = { if (initialCategory != null && path == settingsPath(initialCategory)) onDismiss() else back() }) {
        if (page == null) GlassSearchField(search, { search = it }, "Search settings",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        key(pageKey) { holder.SaveableStateProvider(pageKey) {
            if (page == "Metadata read failures") MetadataReadFailures(state, Modifier.weight(1f))
            else Column(Modifier.fillMaxWidth().weight(1f).controlVerticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page == null) {
                    val shown = settingsDestinations.filter {
                        if (search.isBlank()) it.parent == null else (it.title + " " + it.summary).contains(search, true)
                    }
                    if (shown.isEmpty()) Text("No matching settings")
                    shown.forEach { destination -> SettingsLink(destination.title,
                        if (search.isBlank()) destination.summary else listOfNotNull(destination.parent, destination.summary).joinToString(" · "), destination.icon) {
                        path = settingsPath(destination.title)
                    } }
                } else when (page) {
                    "Interface", "Library and tags" -> settingsDestinations.filter { it.parent == page }.forEach { child ->
                        SettingsLink(child.title, child.summary, child.icon) { path = path + child.title }
                    }
                    "Navigation", "Player", "Motion", "Glass", "Appearance" -> interfaceContent(page)
                    "Playback and queues" -> {
                        playbackContent(page)
                        SettingsLink("Song tap behavior", "Default action and per-list overrides", R.drawable.ic_music) { path = path + "Song tap behavior" }
                    }
                    "Song tap behavior" -> playbackContent(page)
                    "About and licenses" -> aboutContent()
                    "Library filters" -> libraryFiltersContent()
                    "Tag jobs and recovery" -> { Text("Interrupted edits keep a recovery copy until you resolve them."); extraContent() }
                    "Folders to scan" -> {
                        Text("Your files stay where they are. Start scanning from More → Scan music.", style = MaterialTheme.typography.bodyMedium)
                        LibraryNotice(state)
                        state.snapshot.folders.forEach { folder ->
                            val count = state.snapshot.tracks.count { track -> track.locations.any { it.folderUri == folder.uri } }
                            SettingsLink(folderLabels.getValue(folder.uri), if (folder.uri in state.unavailableFolders) "Access lost · select this folder again" else "$count songs", R.drawable.ic_folder) {
                                folderUri = folder.uri; path = path + "Folder options"
                            }
                        }
                        if (state.updatingFolders) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = onChooseFolder, enabled = !state.loading && !state.scanning && !state.updatingFolders,
                            modifier = Modifier.fillMaxWidth()) { Text(if (state.snapshot.folders.isEmpty()) "Choose folder" else "Add folder") }
                    }
                    "Folder options" -> state.snapshot.folders.firstOrNull { it.uri == folderUri }?.let { folder ->
                        Text(folderLabels.getValue(folder.uri), style = MaterialTheme.typography.titleMedium)
                        Text("Removing a folder only removes it from Localica. Audio files are kept.")
                        ExcludedFoldersPreference(folder, preferences, !state.scanning && !state.updatingFolders)
                        ActionTextButton(onClick = { removing = folder }, enabled = !state.scanning && !state.updatingFolders) { Text("Remove folder") }
                    }
                    "Scanner" -> ScannerConfiguration(preferences, state, onFullScan, onQuickScan) { path = path + "Metadata read failures" }
                }
            }
        } }
    }
    removing?.let { folder -> GlassAlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${folderLabels[folder.uri] ?: folder.name}?") },
        text = { Text("Audio files are kept. Queues and playlists keep their references to these songs.") },
        confirmButton = { ActionTextButton(onClick = { onRemove(folder.uri); removing = null; back() }) { Text("Remove") } },
        dismissButton = { ActionTextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun ScannerConfiguration(preferences: ScanPreferences, state: LibraryUiState, onFullScan: () -> Unit,
    onQuickScan: () -> Unit, failures: () -> Unit) {
    var options by remember { mutableStateOf(preferences.read("")) }
    var onLaunch by remember { mutableStateOf(preferences.scanOnLaunch) }
    var fullOnLaunch by remember { mutableStateOf(preferences.fullScanOnLaunch) }
    var durationOpen by remember { mutableStateOf(false) }
    ScannerActions(state, onFullScan, onQuickScan)
    SettingsLink("Metadata read failures", "${state.snapshot.metadataFailures.map { it.trackId }.distinct().size} songs", onClick = failures)
    SettingsHeading("Music library")
    BooleanPreference("Include subfolders", options.includeSubfolders) { options = options.copy(includeSubfolders = it); preferences.write(options) }
    SettingsLink("Exclude short files", if (options.minimumDurationSeconds == 0) "Off" else "Shorter than ${options.minimumDurationSeconds} seconds") { durationOpen = true }
    Text("Unknown durations are kept. Use Scan music to apply changed rules to indexed songs.", style = MaterialTheme.typography.bodySmall)
    SettingsHeading("On launch")
    BooleanPreference("Quick scan", onLaunch) { onLaunch = it; preferences.scanOnLaunch = it }
    BooleanPreference("Full scan", fullOnLaunch) { fullOnLaunch = it; preferences.fullScanOnLaunch = it }
    Text("Both are off by default. Quick scan finds new songs; full scan also refreshes tags and removes missing files. Enable both to run quick scan first, then full scan.", style = MaterialTheme.typography.bodySmall)
    if (durationOpen) {
        var input by remember { mutableStateOf(options.minimumDurationSeconds.toString()) }
        val seconds = input.toIntOrNull()
        GlassAlertDialog(onDismissRequest = { durationOpen = false }, title = { Text("Exclude short files") }, text = {
            GlassTextField(input, { input = it }, label = { Text("Minimum seconds (0 = off)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = seconds == null || seconds !in 0..3600)
        }, confirmButton = { ActionTextButton(enabled = seconds != null && seconds in 0..3600, onClick = {
            options = options.copy(minimumDurationSeconds = seconds!!); preferences.write(options); durationOpen = false
        }) { Text("Save") } }, dismissButton = { ActionTextButton(onClick = { durationOpen = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ExcludedFoldersPreference(folder: FolderSelection, preferences: ScanPreferences, enabled: Boolean) {
    var paths by remember(folder.uri) { mutableStateOf(preferences.read(folder.uri).excludedPaths) }
    var open by remember { mutableStateOf(false) }
    SettingsLink("Excluded subfolders", if (paths.isEmpty()) "None" else paths.joinToString(", "), enabled = enabled) { open = true }
    if (open) {
        var input by remember { mutableStateOf(paths.joinToString("\n")) }
        val lines = input.lineSequence().map { it.trim().replace('\\', '/').trimEnd('/') }.filter { it.isNotBlank() }.toSet()
        val valid = lines.all { !it.startsWith('/') && it.split('/').none { part -> part in setOf("", ".", "..") } }
        GlassAlertDialog(onDismissRequest = { open = false }, title = { Text("Excluded subfolders") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("One path per line, relative to ${folder.name}. For example: Podcasts or Albums/Demos. No wildcards. Quick Scan skips these paths. Run a full scan from Scan music to remove already indexed songs from excluded paths.")
                GlassTextField(input, { input = it }, label = { Text("Subfolder paths") }, minLines = 3, maxLines = 6, isError = !valid)
            }
        }, confirmButton = { ActionTextButton(enabled = valid, onClick = { paths = lines; preferences.excluded(folder.uri, paths); open = false }) { Text("Save") } },
            dismissButton = { ActionTextButton(onClick = { open = false }) { Text("Cancel") } })
    }
}

