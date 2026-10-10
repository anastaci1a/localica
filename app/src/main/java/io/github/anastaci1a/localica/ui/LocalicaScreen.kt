package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.home.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.LibraryQuery
import io.github.anastaci1a.localica.library.SongListView
import io.github.anastaci1a.localica.playlist.PlaylistController
import io.github.anastaci1a.localica.playback.PlaybackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalicaScreen(
    library: LibraryUiState,
    playback: PlaybackState,
    onPlay: (Track) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRemoveFolder: (String) -> Unit = {},
    queues: QueueUiState = QueueUiState(loading = false),
    queueController: QueueController? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    playlist: io.github.anastaci1a.localica.playlist.PlaylistState = io.github.anastaci1a.localica.playlist.PlaylistState(),
    playlistController: PlaylistController? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val editTags = LocalTagEdit.current
    val preferences = remember(context) { io.github.anastaci1a.localica.navigation.InterfacePreferences(context) }
    var settings by remember { mutableStateOf(preferences.read()) }
    val pinPreferences = remember(context) { PinPreferences(context) }
    var pins by remember { mutableStateOf(pinPreferences.read()) }
    fun pin(ref: MediaRef) { pins = PinEdits.toggle(pins, ref); pinPreferences.write(pins) }
    var navigation by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<NavigationState, String>(
        save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
        mutableStateOf(NavigationState.fresh(settings.tabs))
    }
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var consumedRequest by rememberSaveable { mutableLongStateOf(queues.playerRequest) }
    val progress = remember { androidx.compose.animation.core.Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(queues.playerRequest) {
        if (queues.playerRequest != consumedRequest) {
            consumedRequest = queues.playerRequest
            if (settings.expandOnPlay) expanded = true
        }
    }
    fun exit() {
        var owner: android.content.Context? = context
        while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
        (owner as? android.app.Activity)?.finish()
    }
    fun back() {
        val previous = navigation.back(settings.tabHistory)
        if (previous != null) navigation = previous
        else if (settings.confirmExit) exitPrompt = true else exit()
    }
    androidx.activity.compose.BackHandler { back() }
    val currentTrack = queues.library.active?.let { queue ->
        queue.entries.firstOrNull { it.id == queue.currentEntryId }?.track
    }
    val player = if (currentTrack != null && currentTrack.id != playback.trackId) playback.copy(
        trackId = currentTrack.id, trackUri = currentTrack.uri, title = currentTrack.title, artist = currentTrack.artist,
        albumArtist = currentTrack.albumArtist, album = currentTrack.album, fileName = currentTrack.fileName,
        durationMs = currentTrack.durationMs, positionMs = 0) else playback
    val hasPlayer = currentTrack != null || playback.trackUri != null
    LaunchedEffect(hasPlayer) { if (!hasPlayer) { expanded = false; queueOpen = false; progress.snapTo(0f) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = inset + if (hasPlayer) 144.dp else 64.dp)
            .then(if (hasPlayer && progress.value >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier)) {
            val route = navigation.route
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (navigation.stacks.getValue(navigation.active).size > 1) ActionIcon(R.drawable.ic_back, "Back") { back() }
                Text(if (route.search) "Search" else route.title ?: route.tab.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (route.tab != MainTab.Queues && (route.tab != MainTab.Home || route.search || route.collection != null))
                    ActionIcon(R.drawable.ic_sort, "Sort and presentation") { sortingRoute = route.key }
                if (!route.search && route.tab != MainTab.Queues) ActionIcon(R.drawable.ic_search, "Search this view") {
                    navigation = navigation.open(route.copy(search = true, globalSearch = route.tab == MainTab.Home,
                        text = "", key = java.util.UUID.randomUUID().toString()))
                }
                ActionIcon(R.drawable.ic_settings, "Settings") { settingsOpen = true }
            }
            holder.SaveableStateProvider(route.key) {
                if (route.tab == MainTab.Queues && !route.search) QueueScreen(queues, queueController, library,
                    playlist, playlistController, playback.connected, pins, ::pin)
                else if (route.tab == MainTab.Home && !route.search && route.collection == null) {
                    PinnedHome(library, playlist, pins, { settingsOpen = true },
                        { navigation = navigation.open(BrowseRoute(MainTab.Home, globalSearch = true, search = true)) },
                        { item, songs ->
                            if (item.row != null) queueController?.tap(songs.map { it.track }, songs.indexOfFirst { it.id == item.row.id })
                            else navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title))
                        }, ::pin, { ref, offset -> pins = PinEdits.move(pins, ref, offset); pinPreferences.write(pins) })
                } else BrowserScreen(library, playlist, route, { navigation = navigation.update(it) },
                    { item -> navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title)) },
                    { settingsOpen = true }, { rows, index, scope ->
                        if (index >= 0) {
                            if (queueController != null) queueController.tap(rows.map { it.track }, index, scope)
                            else onPlay(rows[index].track)
                        }
                    }, queueController, playlistController, playback.connected, expanded, pins, ::pin,
                    sortingRoute == route.key, { sortingRoute = null }, playingTrackId = playback.trackId)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).offset(y = (64.dp + inset) * progress.value)
            .then(if (hasPlayer && progress.value >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier), tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val fits = maxWidth >= 56.dp * settings.tabs.size
                    Row(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
                        settings.tabs.forEach { tab ->
                            Column((if (fits) Modifier.weight(1f) else Modifier.width(56.dp)).fillMaxHeight()
                                .selectable(navigation.active == tab, role = androidx.compose.ui.semantics.Role.Tab,
                                    onClick = { navigation = navigation.switch(tab) }),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(painterResource(tab.icon()), tab.label, Modifier.size(24.dp),
                                    tint = if (navigation.active == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(tab.label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                ActionIcon(R.drawable.ic_search, "Search library") {
                    navigation = navigation.open(BrowseRoute(navigation.active, search = true, globalSearch = true))
                }
            }
        }
        if (hasPlayer) PlayerSurface(player, expanded, { expanded = it }, settings, progress,
            onToggle, onSeek, onPrevious, onNext, { queueOpen = true }, { expanded = false },
            if (currentTrack != null && editTags != null) ({ editTags(listOf(library.snapshot.tracks.firstOrNull { it.id == currentTrack.id } ?: currentTrack)) {} }) else null)
    }
    androidx.activity.compose.BackHandler(expanded) { expanded = false }
    if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxHeight(0.9f)) { QueueScreen(queues, queueController, library,
            playlist, playlistController, playback.connected, pins, ::pin) }
    }
    if (settingsOpen) FolderSettings(library,
        onDismiss = { settingsOpen = false }, onChooseFolder, onRescan, onCancelScan, onRemoveFolder,
        extraContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let { TagRecoverySettings(it.tagEditing) }
            InterfaceSettingsControls(settings) {
                settings = it; preferences.write(it); navigation = navigation.reconcile(it.tabs)
            }
            QueueSettingsControls(queues.settings) { queueController?.settings(it) }
            LibraryLicenses()
        })
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Close Localica?") },
        text = { Text("Playback will continue.") }, confirmButton = { TextButton(onClick = { exitPrompt = false; exit() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Cancel") } })
    QueueDialogs(queues, queueController)
}

@Composable
internal fun LibraryNotice(state: LibraryUiState) {
    val message = when (state.problem) {
        LibraryProblem.RemoveFailed -> R.string.remove_failed
        LibraryProblem.ScanFailed -> R.string.scan_failed
        LibraryProblem.LoadFailed -> R.string.load_failed
        null -> if (state.unavailableFolders.isNotEmpty()) R.string.access_lost
            else if (state.metadataFailures > 0) R.string.metadata_missing else null
    }
    message?.let {
        Text(stringResource(it), color = if (state.problem != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
