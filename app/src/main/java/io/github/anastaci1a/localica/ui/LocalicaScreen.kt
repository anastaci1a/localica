package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
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
    val session: SessionSelection = androidx.lifecycle.viewmodel.compose.viewModel()
    var selectionHeight by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
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
    val pager = rememberPagerState(initialPage = settings.tabs.indexOf(navigation.active).coerceAtLeast(0)) { settings.tabs.size }
    val navigationScope = rememberCoroutineScope()
    var scrollRequests by rememberSaveable { mutableStateOf(emptyMap<MainTab, Int>()) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(navigation.active, settings.tabs) {
        val target = settings.tabs.indexOf(navigation.active).coerceAtLeast(0)
        if (pager.currentPage != target) {
            if (settings.reducedMotion) pager.scrollToPage(target)
            else pager.animateScrollToPage(target, animationSpec = androidx.compose.animation.core.tween(settings.motionDuration(400)))
        }
    }
    LaunchedEffect(pager, settings.tabs) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            settings.tabs.getOrNull(index)?.let { navigation = navigation.switch(it) }
        }
    }
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val actionPreferences = remember(context) { PlayerActionPreferences(context) }
    var playerActions by remember { mutableStateOf(actionPreferences.read()) }
    var customizeActions by remember { mutableStateOf(false) }
    var songInfo by remember { mutableStateOf(false) }
    var viewArt by remember { mutableStateOf(false) }
    var addPlaying by remember { mutableStateOf(false) }
    var playerMore by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var viewOptions by remember { mutableStateOf(false) }
    var settingsCategory by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var consumedRequest by rememberSaveable { mutableLongStateOf(queues.playerRequest) }
    val queueProgress = remember { androidx.compose.animation.core.Animatable(if (queueOpen) 1f else 0f) }
    LaunchedEffect(queueOpen) { queueProgress.animateTo(if (queueOpen) 1f else 0f, androidx.compose.animation.core.tween(settings.motionDuration(400))) }
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
    val indexedTrack = currentTrack?.let { saved -> library.snapshot.tracks.firstOrNull { it.id == saved.id } ?: saved }
    fun playerAction(action: PlayerAction) {
        val track = indexedTrack ?: return
        when (action) {
            PlayerAction.Info, PlayerAction.Count -> songInfo = true
            PlayerAction.Edit -> editTags?.invoke(listOf(track)) {}
            PlayerAction.Artwork -> viewArt = true
            PlayerAction.Lyrics -> lyricsOpen = !lyricsOpen
            PlayerAction.Playlist -> addPlaying = true
            PlayerAction.Pin -> pin(MediaRef(MediaKind.Song, track.id))
            PlayerAction.Shuffle -> queueController?.shuffle()
            PlayerAction.Repeat -> queueController?.repeat()
            PlayerAction.BackTen -> onSeek((playback.positionMs - 10000).coerceAtLeast(0))
            PlayerAction.ForwardTen -> onSeek(playback.positionMs + 10000)
            PlayerAction.More -> playerMore = true
            else -> {
                val kind = when (action) { PlayerAction.Album -> MediaKind.Album; PlayerAction.Artist -> MediaKind.Artist
                    PlayerAction.AlbumArtist -> MediaKind.AlbumArtist; else -> MediaKind.Folder }
                val tab = when (kind) { MediaKind.Album -> MainTab.Albums; MediaKind.Artist -> MainTab.Artists
                    MediaKind.AlbumArtist -> MainTab.AlbumArtists; else -> MainTab.Folders }
                val item = io.github.anastaci1a.localica.browse.BrowseQueries.evaluate(library.snapshot, playlist.playlists, BrowseRoute(tab))
                    .items.firstOrNull { item -> item.songs.any { it.track.id == track.id } }
                if (item != null) { navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title)); expanded = false }
            }
        }
    }
    val player = if (currentTrack != null && currentTrack.id != playback.trackId) playback.copy(
        trackId = currentTrack.id, trackUri = currentTrack.uri, title = currentTrack.title, artist = currentTrack.artist,
        albumArtist = currentTrack.albumArtist, album = currentTrack.album, fileName = currentTrack.fileName,
        durationMs = currentTrack.durationMs, positionMs = 0) else playback
    val hasPlayer = currentTrack != null || playback.trackUri != null
    LaunchedEffect(hasPlayer) { if (!hasPlayer) { expanded = false; queueOpen = false; progress.snapTo(0f) } }
    CompositionLocalProvider(LocalSessionSelection provides session) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = inset + (if (hasPlayer) 144.dp else 64.dp) + if (session.active) selectionHeight else 0.dp)
            .then(if (hasPlayer && progress.value >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier)) {
            val route = navigation.route
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (navigation.stacks.getValue(navigation.active).size > 1) ActionIcon(R.drawable.ic_back, "Back") { back() }
                Text(if (route.search) "Search" else route.title ?: route.tab.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (route.tab != MainTab.Queues && (route.tab != MainTab.Home || route.search || route.collection != null)) {
                    ActionIcon(R.drawable.ic_sort, "Sort") { viewOptions = false; sortingRoute = route.key }
                    ActionIcon(R.drawable.ic_settings, "View options") { viewOptions = true; sortingRoute = route.key }
                }

            }
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = !expanded, key = { settings.tabs[it].name }) { page ->
            val tab = settings.tabs[page]
            val pageRoute = navigation.stacks[tab]?.lastOrNull() ?: remember(tab) { BrowseRoute(tab) }
            val route = pageRoute
            holder.SaveableStateProvider(route.key) {
                if (route.tab == MainTab.Queues && !route.search) QueueScreen(queues, queueController, library,
                    playlist, playlistController, playback.connected, pins, ::pin, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage)
                else if (route.tab == MainTab.Home && !route.search && route.collection == null) {
                    PinnedHome(library, playlist, pins, { settingsCategory = "Library and tags"; settingsOpen = true },
                        { navigation = navigation.open(BrowseRoute(MainTab.Home, globalSearch = true, search = true)) },
                        { item, songs ->
                            if (item.row != null) queueController?.tap(songs.map { it.track }, songs.indexOfFirst { it.id == item.row.id }, sourceName = "Pinned songs")
                            else navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title))
                        }, ::pin, { ref, offset -> pins = PinEdits.move(pins, ref, offset); pinPreferences.write(pins) }, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion)
                } else BrowserScreen(library, playlist, route, { navigation = navigation.update(it) },
                    { item -> navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title)) },
                    { settingsCategory = "Library and tags"; settingsOpen = true }, { rows, index, scope ->
                        if (index >= 0) {
                            if (queueController != null) queueController.tap(rows.map { it.track }, index, scope, io.github.anastaci1a.localica.browse.queueSourceName(route, rows.map { it.track }))
                            else onPlay(rows[index].track)
                        }
                    }, queueController, playlistController, playback.connected, expanded, pins, ::pin,
                    sortingRoute == route.key, { sortingRoute = null }, playingTrackId = playback.trackId, presentationOnly = viewOptions, selectionVisible = page == pager.settledPage,
                    onSearchEdit = { navigation = navigation.editSearch(it) }, onSearchCommit = { navigation = navigation.commitSearch() },
                    scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion)
            }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).offset(y = (64.dp + inset) * progress.value)
            .then(if (hasPlayer && progress.value >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier), tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val fits = maxWidth >= 56.dp * settings.tabs.size
                    Row(if (fits) Modifier.fillMaxWidth() else Modifier.nestedScroll(HorizontalGestureOwner).horizontalScroll(rememberScrollState())) {
                        settings.tabs.forEach { tab ->
                            Column((if (fits) Modifier.weight(1f) else Modifier.width(56.dp)).fillMaxHeight()
                                .selectable(navigation.active == tab, role = androidx.compose.ui.semantics.Role.Tab,
                                    onClick = { navigation = navigation.resetTab(tab)
                                        scrollRequests = scrollRequests + (tab to ((scrollRequests[tab] ?: 0) + 1))
                                        keyboard?.hide(); focusManager.clearFocus()
                                        navigationScope.launch {
                                        val index = settings.tabs.indexOf(tab)
                                        if (settings.reducedMotion) pager.scrollToPage(index)
                                        else pager.animateScrollToPage(index, animationSpec = androidx.compose.animation.core.tween(settings.motionDuration(400)))
                                    } }),
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
                    navigation = navigation.commitSearch().open(BrowseRoute(navigation.active, search = true, globalSearch = true))
                }
                Box {
                    ActionIcon(R.drawable.ic_more, "More") { moreOpen = true }
                    DropdownMenu(moreOpen, { moreOpen = false }) {
                        ActionMenuItem("Scan music", enabled = !library.scanning, onClick = {
                            moreOpen = false; library.snapshot.folders.forEach { onRescan(it.uri) }
                        })
                        ActionMenuItem("Settings", onClick = { moreOpen = false; settingsCategory = null; settingsOpen = true })
                        ActionMenuItem("About and licenses", onClick = { moreOpen = false; settingsCategory = "About and licenses"; settingsOpen = true })
                        ActionMenuItem("Close Localica", onClick = { moreOpen = false; if (settings.confirmExit) exitPrompt = true else exit() })
                    }
                }

            }
        }
        if (session.active && !expanded) Surface(Modifier.align(Alignment.BottomCenter)
            .padding(horizontal = 8.dp).padding(bottom = inset + if (hasPlayer) 144.dp else 72.dp)
            .fillMaxWidth().heightIn(max = maxHeight / 2).onSizeChanged { selectionHeight = with(density) { it.height.toDp() } },
            shape = Corners.panel, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SessionSelectionPanel(session, queueController, playlistController, playlist) }
        }
        if (hasPlayer) PlayerSurface(player, expanded, { expanded = it }, settings, progress,
            onToggle, onSeek, onPrevious, onNext, { queueOpen = true }, { expanded = false },
            if (currentTrack != null && editTags != null) ({ editTags(listOf(library.snapshot.tracks.firstOrNull { it.id == currentTrack.id } ?: currentTrack)) {} }) else null,
            lyricsOpen, { lyricsOpen = it }, currentTrack?.let { track -> library.snapshot.tracks.firstOrNull { it.id == track.id } ?: track },
            { settings = it; preferences.write(it) }, actionRows = if (playerActions.twoRows) 2 else 1, queueProgress = queueProgress) {
            PlayerActionStrip(playerActions, { action -> when (action) {
                PlayerAction.Shuffle -> "Shuffle: " + if (queues.library.active?.shuffle == true) "on" else "off"
                PlayerAction.Repeat -> "Repeat: " + queues.library.active?.repeat?.name.orEmpty()
                PlayerAction.Count -> "Track " + ((queues.library.active?.entries?.indexOfFirst { it.id == queues.library.active?.currentEntryId } ?: -1) + 1) + " of " + (queues.library.active?.entries?.size ?: 0)
                else -> action.label
            } }, ::playerAction, { customizeActions = true })
        }
        if (queueProgress.value > 0f) QueueSurface(queueProgress, settings, { queueOpen = it }) {
            Box(Modifier.weight(1f)) { QueueScreen(queues, queueController, library,
                playlist, playlistController, playback.connected, pins, ::pin, reducedMotion = settings.reducedMotion) }
            SessionSelectionPanel(session, queueController, playlistController, playlist)
        }
    }
    androidx.activity.compose.BackHandler(expanded) { if (lyricsOpen) lyricsOpen = false else expanded = false }
    androidx.activity.compose.BackHandler(queueOpen || queueProgress.value > 0f) { queueOpen = false; navigationScope.launch { queueProgress.animateTo(0f, androidx.compose.animation.core.tween(settings.motionDuration(400))) } }
    if (settingsOpen) FolderSettings(library,
        onDismiss = { settingsOpen = false }, onChooseFolder, onRescan, onCancelScan, onRemoveFolder,
        extraContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let { TagRecoverySettings(it.tagEditing) }
        }, interfaceContent = {
            TextButton(onClick = { customizeActions = true }) { Text("Customize player actions") }
            InterfaceSettingsControls(settings) {
                settings = it; preferences.write(it); navigation = navigation.reconcile(it.tabs)
            }
        }, playbackContent = {
            QueueSettingsControls(queues.settings) { queueController?.settings(it) }
        }, aboutContent = {
            Text("Localica", style = MaterialTheme.typography.headlineSmall)
            Text("Offline music, organized your way.")
            LibraryLicenses()
        }, initialCategory = settingsCategory)
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Close Localica?") },
        text = { Text("Playback will continue.") }, confirmButton = { TextButton(onClick = { exitPrompt = false; exit() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Cancel") } })
    if (customizeActions) PlayerActionCustomizer(playerActions, { playerActions = it; actionPreferences.write(it) }, { customizeActions = false })
    if (songInfo && indexedTrack != null) SongInformation(indexedTrack, { songInfo = false }, { playerAction(PlayerAction.Edit) }, ::playerAction)
    if (viewArt && indexedTrack != null) ArtworkViewer(indexedTrack.uri) { viewArt = false }
    if (addPlaying && indexedTrack != null) AddToPlaylistDialog(listOf(indexedTrack), playlist, playlistController, { addPlaying = false }, { addPlaying = false })
    if (playerMore) ModalBottomSheet(onDismissRequest = { playerMore = false }, shape = Corners.panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            PlayerAction.entries.filterNot { it == PlayerAction.More }.forEach { action -> TextButton(onClick = { playerMore = false; playerAction(action) }) { Text(action.label) } }
            TextButton(onClick = { playerMore = false; customizeActions = true }) { Text("Customize player actions") }
        }
    }
    QueueDialogs(queues, queueController)
    }
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
