package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.anastaci1a.localica.ui.components.rememberSettingsScroll
import io.github.anastaci1a.localica.ui.layout.SettingsDialogBody
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.flow.first
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.scanFolderLabels
import io.github.anastaci1a.localica.scanner.ScanPreferences

private data class SettingsDestination(val title: String, val parent: String?, val icon: AppIcon, val summary: String)
private data class SettingsPageRoute(val title: String, val folderUri: String?, val iconPackId: String?)
private val settingsDestinations = listOf(
    SettingsDestination("Interface", null, AppIcon.SettingsInterface, "Navigation, player, icons, glass, and motion"),
    SettingsDestination("Appearance", "Interface", AppIcon.SettingsAppearance, "Typography and interface gray"),
    SettingsDestination("Icon packs", "Interface", AppIcon.SettingsIconPacks, "Import, switch, and view icons"),
    SettingsDestination("Glass", "Interface", AppIcon.SettingsGlass, "Blur, white tint, and floating shadows"),
    SettingsDestination("Navigation", "Interface", AppIcon.SettingsNavigation, "Main tabs, Back history, and exit confirmation"),
    SettingsDestination("Player", "Interface", AppIcon.SettingsPlayer, "Artwork, gestures, and player actions"),
    SettingsDestination("Motion", "Interface", AppIcon.SettingsMotion, "Animation speed and reduced motion"),
    SettingsDestination("Last.fm", null, AppIcon.MenuStation, "Read-only listening statistics"),
    SettingsDestination("Playback and queues", null, AppIcon.TransportPlay, "Preloading, queue switching, and retention"),
    SettingsDestination("Song tap behavior", "Playback and queues", AppIcon.TabSongs, "Default action and per-list overrides"),
    SettingsDestination("Library and tags", null, AppIcon.TabLibrary, "Folders, scanner configuration, tag jobs and recovery"),
    SettingsDestination("Library filters", "Library and tags", AppIcon.SettingsLibraryFilters, "Visible filters, their order, and unfiltered collections"),
    SettingsDestination("Folders to scan", "Library and tags", AppIcon.TabFolders, "Choose music folders and excluded subfolders"),
    SettingsDestination("Scanner", "Library and tags", AppIcon.ActionSearch, "Quick Scan, full scan, startup scans, subfolders and short files"),
    SettingsDestination("Metadata read failures", "Scanner", AppIcon.SettingsMetadataFailures, "Files with incomplete metadata"),
    SettingsDestination("Tag jobs and recovery", "Library and tags", AppIcon.SettingsTagJobs, "Background jobs, outcomes and interrupted tag edits"),
    SettingsDestination("About and licenses", null, AppIcon.ActionInfo, "Version, source code, help, diagnostics export, and open-source licenses"),
)
/** Destination title to row icon; the #655 audit keeps every settings row icon distinct. */
internal val settingsDestinationIcons: Map<String, AppIcon> get() = settingsDestinations.associate { it.title to it.icon }
private fun destinationIcon(title: String): AppIcon = settingsDestinations.first { it.title == title }.icon
/** Pages that link to child destinations (or list folders) show row icons, so every row there keeps the icon slot. */
private fun reservesIconSlot(page: String?): Boolean =
    page == null || page == "Folders to scan" || settingsDestinations.any { it.parent == page }

private fun settingsTitle(title: String): String = when (title) {
    "Interface and gestures" -> "Interface"
    "Scanner configuration" -> "Scanner"
    else -> title
}

// Keep the existing saved List representation, migrating every restored ancestor too.
private val settingsPathSaver = Saver<List<String>, List<String>>(
    save = { migrateSettingsPath(it) }, restore = { migrateSettingsPath(it) })

private fun migrateSettingsPath(path: List<String>): List<String> {
    val titles = path.map(::settingsTitle)
    return when (titles.firstOrNull()) {
        "Icon packs" -> listOf("Interface") + titles
        "Pack icons" -> listOf("Interface", "Icon packs") + titles
        else -> titles
    }
}

internal fun settingsPath(title: String?): List<String> {
    if (title == null) return emptyList()
    val name = settingsTitle(title)
    if (name == "Pack icons") return settingsPath("Icon packs") + name
    val destination = settingsDestinations.firstOrNull { it.title == name } ?: return if (name in SettingsCatalog.parents) SettingsCatalog.path(name) else emptyList()
    return settingsPath(destination.parent) + name
}

@Composable
fun FolderSettings(state: LibraryUiState, onDismiss: () -> Unit, onChooseFolder: () -> Unit,
    onRemove: (String) -> Unit, extraContent: @Composable () -> Unit = {},
    interfaceContent: @Composable (String) -> Unit = {}, playbackContent: @Composable (String) -> Unit = {},
    aboutContent: @Composable () -> Unit = {}, initialCategory: String? = null, onFullScan: () -> Unit = {}, onQuickScan: () -> Unit = {}, libraryFiltersContent: @Composable () -> Unit = {}, searchValues: Map<String, String> = emptyMap(), lastFmContent: @Composable () -> Unit = {}) {
    var path by rememberSaveable(stateSaver = settingsPathSaver) { mutableStateOf(settingsPath(initialCategory)) }
    var iconPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<SettingSpec?>(null) }
    val glass = LocalGlassAppearance.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var search by rememberSaveable { mutableStateOf("") }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<FolderSelection?>(null) }
    val removeOrigin = rememberGlassAnchor()
    val folderLabels = remember(state.snapshot.folders) { scanFolderLabels(state.snapshot.folders) }
    val page = path.lastOrNull()
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val preferences = remember(context) { ScanPreferences(context) }
    fun back() { if (path.isEmpty()) onDismiss() else path = path.dropLast(1) }
    val route = SettingsPageRoute(page ?: "Settings", folderUri.takeIf { page == "Folder options" }, iconPackId.takeIf { page == "Pack icons" })
    SettingsNavigationPage(route, { it.title }, ::back, onDismiss, navigationDepth = path.size,
        onSystemBack = {
            if (initialCategory != null && initialCategory !in setOf("Icon packs", "Pack icons") &&
                path == settingsPath(initialCategory)) onDismiss() else back()
        }) { shown ->
        val page = shown.title.takeUnless { it == "Settings" }
        val pageKey = (page ?: "root") + shown.folderUri.orEmpty() + shown.iconPackId.orEmpty()
        GlassSearchField(search, { search = it }, "Search settings",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        key(pageKey) { holder.SaveableStateProvider(pageKey) {
            val scroll = rememberScrollState()
            val focus = remember(pageKey, scroll) { SettingsSearchFocus(page.orEmpty(), scroll) }
            val reduced = LocalReducedMotion.current
            val searchScroll = rememberSettingsScroll(scroll)
            LaunchedEffect(search.isNotBlank()) { if (search.isNotBlank()) scroll.scrollTo(0) }
            LaunchedEffect(pending, pageKey) {
                val target = pending ?: return@LaunchedEffect
                if (target.page != page) return@LaunchedEffect
                val id = snapshotFlow {
                    when {
                        target.id in focus.anchors -> target.id
                        target.revealedBy in focus.anchors -> target.revealedBy
                        else -> null
                    }
                }.first { it != null }!!
                val y = ((focus.anchors.getValue(id) - focus.viewportTop) - focus.viewportHeight / 3f)
                    .toInt().coerceIn(0, scroll.maxValue)
                if (reduced) scroll.scrollTo(y) else searchScroll.scrollTo(y)
                focus.highlighted = id
                pending = null
            }
            if (page == "Metadata read failures" && search.isBlank()) MetadataReadFailures(state, Modifier.weight(1f))
            else CompositionLocalProvider(LocalSettingsSearchFocus provides focus.takeIf { search.isBlank() },
                LocalSettingsNavigate provides { destination -> path = settingsPath(destination) }, LocalSettingsRowMetrics provides
                LocalSettingsRowMetrics.current.copy(iconSlot = reservesIconSlot(page))) { Column(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { focus.viewportTop = it.positionInWindow().y; focus.viewportHeight = it.size.height }
                .controlVerticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (search.isNotBlank()) {
                    val scanOptions = preferences.read("")
                    val values = searchValues + mapOf(
                        "Include subfolders" to if (scanOptions.includeSubfolders) "On" else "Off",
                        "Exclude short files" to if (scanOptions.minimumDurationSeconds == 0) "Off" else "${scanOptions.minimumDurationSeconds} seconds",
                        "Quick Scan on launch" to if (preferences.scanOnLaunch) "On" else "Off",
                        "Full scan on launch" to if (preferences.fullScanOnLaunch) "On" else "Off")
                    SettingsSearchResults(search, values) { result ->
                        var target = result.setting
                        var destination = result.page
                        if (glass.renderer == GlassRendererMode.None &&
                            "Glass" in SettingsCatalog.path(destination) && destination != "Glass") {
                            target = SettingsCatalog.entries.first { it.id == "Glass renderer" }
                            destination = "Glass"
                        }
                        focusManager.clearFocus()
                        keyboard?.hide()
                        search = ""
                        focus.highlighted = null
                        pending = target
                        path = settingsPath(destination)
                    }
                } else if (page == null) {
                    settingsDestinations.filter { it.parent == null }.forEach { destination ->
                        SettingsLink(destination.title, destination.summary, destination.icon) { path = settingsPath(destination.title) }
                    }
                } else when (page) {
                    "Interface", "Library and tags" -> settingsDestinations.filter { it.parent == page }.forEach { child ->
                        SettingsLink(child.title, child.summary, child.icon) { path = path + child.title }
                    }
                    "Navigation", "Player", "Motion", "Glass", "Glass appearance", "Glass advanced", "Navigation selector", "Appearance" -> interfaceContent(page)
                    "Icon packs" -> IconPackSettings { id -> iconPackId = id; path = path + "Pack icons" }
                    "Pack icons" -> IconPackViewer(shown.iconPackId)
                    "Playback and queues" -> {
                        playbackContent(page)
                        SettingsLink("Song tap behavior", "Default action and per-list overrides", destinationIcon("Song tap behavior")) { path = path + "Song tap behavior" }
                    }
                    "Song tap behavior", "Per-list overrides" -> playbackContent(page)
                    "About and licenses" -> aboutContent()
                    "Library filters" -> libraryFiltersContent()
                    "Last.fm" -> lastFmContent()
                    "Tag jobs and recovery" -> { Text("Interrupted edits keep a recovery copy until you resolve them."); extraContent() }
                    "Folders to scan" -> {
                        Text("Your files stay where they are. Start scanning from More → Scan music.", style = MaterialTheme.typography.bodyMedium)
                        LibraryNotice(state)
                        SettingsEntry("Music folders") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.snapshot.folders.forEach { folder ->
                            val count = state.snapshot.tracks.count { track -> track.locations.any { it.folderUri == folder.uri } }
                            SettingsLink(folderLabels.getValue(folder.uri), if (folder.uri in state.unavailableFolders) "Access lost · select this folder again" else "$count songs", AppIcon.TabFolders) {
                                folderUri = folder.uri; path = path + "Folder options"
                            }
                        }
                        if (state.updatingFolders) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ActionButton(onClick = onChooseFolder, enabled = !state.loading && !state.scanning && !state.updatingFolders,
                            modifier = Modifier.fillMaxWidth()) { Text(if (state.snapshot.folders.isEmpty()) "Choose folder" else "Add folder") }
                        } }
                    }
                    "Folder options" -> state.snapshot.folders.firstOrNull { it.uri == shown.folderUri }?.let { folder ->
                        Text(folderLabels.getValue(folder.uri), style = MaterialTheme.typography.titleMedium)
                        Text("Removing a folder only removes it from Localica. Audio files are kept.")
                        ExcludedFoldersPreference(folder, preferences, !state.scanning && !state.updatingFolders)
                        SettingsLink("Remove folder", anchor = removeOrigin, enabled = !state.scanning && !state.updatingFolders) { removing = folder }
                    }
                    "Scanner" -> ScannerConfiguration(preferences, state, onFullScan, onQuickScan) { path = path + "Metadata read failures" }
                }
            } }
        } }
    }
    removing?.let { folder -> CompositionLocalProvider(LocalGlassOrigin provides removeOrigin) { GlassAlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${folderLabels[folder.uri] ?: folder.name}?") },
        text = { Text("Audio files are kept. Queues and playlists keep their references to these songs.") },
        confirmButton = { ActionTextButton(onClick = { onRemove(folder.uri); removing = null; back() }) { Text("Remove") } },
        dismissButton = { ActionTextButton(onClick = { removing = null }) { Text("Cancel") } }) } }
}

@Composable
private fun ScannerConfiguration(preferences: ScanPreferences, state: LibraryUiState, onFullScan: () -> Unit,
    onQuickScan: () -> Unit, failures: () -> Unit) {
    var options by remember { mutableStateOf(preferences.read("")) }
    var onLaunch by remember { mutableStateOf(preferences.scanOnLaunch) }
    var fullOnLaunch by remember { mutableStateOf(preferences.fullScanOnLaunch) }
    var durationOpen by remember { mutableStateOf(false) }
    val durationOrigin = rememberGlassAnchor()
    ScannerActions(state, onFullScan, onQuickScan)
    SettingsLink("Metadata read failures", "${state.snapshot.metadataFailures.map { it.trackId }.distinct().size} songs",
        destinationIcon("Metadata read failures"), onClick = failures)
    SettingsHeading("Music library")
    BooleanPreference("Include subfolders", options.includeSubfolders) { options = options.copy(includeSubfolders = it); preferences.write(options) }
    Text("Off scans only each chosen folder; on includes nested folders. Both scans skip excluded paths. Set exclusions in Library and tags → Folders to scan → choose a folder → Excluded subfolders.", style = MaterialTheme.typography.bodySmall)
    SettingsLink("Exclude short files", if (options.minimumDurationSeconds == 0) "Off" else "Shorter than ${options.minimumDurationSeconds} seconds", anchor = durationOrigin) { durationOpen = true }
    Text("Unknown durations are kept. Quick Scan keeps indexed songs. Run Full scan to apply changed rules to them.", style = MaterialTheme.typography.bodySmall)
    SettingsHeading("On launch")
    SettingsEntry("Quick Scan on launch") { BooleanPreference("Quick Scan", onLaunch) { onLaunch = it; preferences.scanOnLaunch = it } }
    SettingsEntry("Full scan on launch") { BooleanPreference("Full scan", fullOnLaunch) { fullOnLaunch = it; preferences.fullScanOnLaunch = it } }
    Text("Both are off by default. Quick Scan finds new songs; Full scan also refreshes tags and removes missing files. Enable both to run Quick Scan first, then Full scan.", style = MaterialTheme.typography.bodySmall)
    if (durationOpen) CompositionLocalProvider(LocalGlassOrigin provides durationOrigin) {
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
    val exclusionsOrigin = rememberGlassAnchor()
    SettingsLink("Excluded subfolders", if (paths.isEmpty()) "None" else paths.joinToString(", "), enabled = enabled, anchor = exclusionsOrigin) { open = true }
    if (open) CompositionLocalProvider(LocalGlassOrigin provides exclusionsOrigin) {
        var input by remember { mutableStateOf(paths.joinToString("\n")) }
        val lines = input.lineSequence().map { it.trim().replace('\\', '/').trimEnd('/') }.filter { it.isNotBlank() }.toSet()
        val valid = lines.all { !it.startsWith('/') && it.split('/').none { part -> part in setOf("", ".", "..") } }
        GlassAlertDialog(onDismissRequest = { open = false }, title = { Text("Excluded subfolders") }, text = {
            SettingsDialogBody {
                Text("One path per line, relative to ${folder.name}. For example: Podcasts or Albums/Demos. No wildcards. Both scans skip these paths when Include subfolders is on. Run Full scan to remove indexed songs from excluded paths.")
                GlassTextField(input, { input = it }, label = { Text("Subfolder paths") }, minLines = 3, maxLines = 6, isError = !valid)
            }
        }, confirmButton = { ActionTextButton(enabled = valid, onClick = { paths = lines; preferences.excluded(folder.uri, paths); open = false }) { Text("Save") } },
            dismissButton = { ActionTextButton(onClick = { open = false }) { Text("Cancel") } })
    }
}


