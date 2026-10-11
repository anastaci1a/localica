package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
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
    onScanAll: () -> Unit = { library.snapshot.folders.forEach { onRescan(it.uri) } },
    scannerRequest: Int = 0,
    onRetryPlayback: () -> Unit = onToggle,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session: SessionSelection = androidx.lifecycle.viewmodel.compose.viewModel()
    var selectionHeight by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val editTags = LocalTagEdit.current
    val preferences = remember(context) { io.github.anastaci1a.localica.navigation.InterfacePreferences(context) }
    var settings by remember { mutableStateOf(preferences.read()) }
    val libraryFilterPreferences = remember(context) { io.github.anastaci1a.localica.browse.LibraryFilterPreferences(context) }
    var libraryFilters by remember { mutableStateOf(libraryFilterPreferences.read()) }
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
    var repeatOptions by remember { mutableStateOf(false) }
    var songInfo by remember { mutableStateOf(false) }
    var viewArt by remember { mutableStateOf(false) }
    var addPlaying by remember { mutableStateOf(false) }
    var playerMore by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var viewOptions by remember { mutableStateOf(false) }
    var settingsCategory by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var returnToScanner by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scannerRequest) { if (scannerRequest > 0) scannerOpen = true }
    var sortingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded) { focusManager.clearFocus(); keyboard?.hide() } }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
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
    val indexedTrack = currentTrack?.let { saved -> library.snapshot.tracks.firstOrNull { it.id == saved.id }
        ?: saved.copy(playCount = library.snapshot.trackPlays[saved.id] ?: 0) }
    fun openCollection(item: io.github.anastaci1a.localica.browse.BrowserItem) {
        navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title))
        queueOpen = false; expanded = false; songInfo = false
        navigationScope.launch { queueProgress.animateTo(0f, androidx.compose.animation.core.tween(settings.motionDuration(400))) }
    }
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
                if (item != null) openCollection(item)
            }
        }
    }
    val player = if (currentTrack != null && currentTrack.id != playback.trackId) playback.copy(
        trackId = currentTrack.id, trackUri = currentTrack.uri, title = currentTrack.title, artist = currentTrack.artist,
        albumArtist = currentTrack.albumArtist, album = currentTrack.album, fileName = currentTrack.fileName,
        durationMs = currentTrack.durationMs, positionMs = 0) else playback
    val hasPlayer = currentTrack != null || playback.trackUri != null
    LaunchedEffect(hasPlayer) { if (!hasPlayer) { expanded = false; queueOpen = false; progress.snapTo(0f) } }
    val dragHost = remember { SongDragHost() }
    val playerVisual = rememberPlayerVisual(player.trackUri)
    val playerBackdrop = rememberFrostedBackdrop(playerVisual to lyricsOpen, expanded)
    val browseBackdrop = rememberFrostedBackdrop(navigation.route to library.snapshot, !expanded, dark = false)
    LaunchedEffect(pager.settledPage) { browseBackdrop.refresh() }
    val playerPalette = animatedPlayerPalette(playerVisual.palette, settings.motionDuration(450))
    PlayerSystemBars(hasPlayer && progress.value >= .99f)
    CompositionLocalProvider(LocalReducedMotion provides settings.reducedMotion, LocalSessionSelection provides session, LocalSongDragHost provides dragHost,
        LocalBackdropRefresh provides browseBackdrop::refresh,
        LocalBrowseObscuredInset provides (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            (if (hasPlayer) 152.dp else 80.dp) + if (session.active) selectionHeight else 0.dp)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("browse-background").windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val queueSelectionMaxHeight = maxHeight / 3
        Column(Modifier.fillMaxSize().recordFrostedBackdrop(browseBackdrop).background(MaterialTheme.colorScheme.background).statusBarsPadding()
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
            // Routes retain saved state, but must not recycle live drawing/focus nodes from
            // a different grid or text field through SaveableStateProvider's ReusableContent.
            key(route.key) { holder.SaveableStateProvider(route.key) {
                if (route.tab == MainTab.Queues && !route.search && (route.collection == null || route.collection.kind == MediaKind.Queue)) QueueScreen(queues, queueController, library,
                    playlist, playlistController, playback.connected, pins, ::pin, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage, isPlaying = playback.isPlaying, onOpen = ::openCollection)
                else if (route.tab == MainTab.Home && !route.search && route.collection == null) {
                    PinnedHome(library, playlist, pins, { settingsCategory = "Folders to scan"; settingsOpen = true },
                        { navigation = navigation.open(BrowseRoute(MainTab.Home, globalSearch = true, search = true)) },
                        { item, songs ->
                            if (item.row != null) queueController?.tap(songs.map { it.track }, songs.indexOfFirst { it.id == item.row.id }, sourceName = "Pinned songs")
                            else navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title))
                        }, ::pin, { ref, offset -> pins = PinEdits.move(pins, ref, offset); pinPreferences.write(pins) }, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion)
                } else BrowserScreen(library, playlist, route, { navigation = navigation.update(it) },
                    { item -> navigation = navigation.open(BrowseRoute(navigation.active, item.ref, item.title)) },
                    { settingsCategory = "Folders to scan"; settingsOpen = true }, { rows, index, scope ->
                        if (index >= 0) {
                            if (queueController != null) queueController.tap(rows.map { it.track }, index, scope, io.github.anastaci1a.localica.browse.queueSourceName(route, rows.map { it.track }), rows.map { it.sourcePlaylistId })
                            else onPlay(rows[index].track)
                        }
                    }, queueController, playlistController, playback.connected, expanded, pins, ::pin,
                    sortingRoute == route.key, { sortingRoute = null }, playingTrackId = playback.trackId, presentationOnly = viewOptions, selectionVisible = page == pager.settledPage,
                    onSearchEdit = { navigation = navigation.editSearch(it) }, onSearchCommit = { navigation = navigation.commitSearch() },
                    libraryFilters = libraryFilters, onFilterSettings = { settingsCategory = "Library filters"; settingsOpen = true },
                    scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion)
            } }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
            .padding(bottom = inset + 8.dp).height(64.dp).fillMaxWidth()
            .offset(y = (80.dp + inset) * progress.value)
            .then(if (hasPlayer && progress.value >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LiquidGlassSurface(Modifier.weight(1f).fillMaxHeight().testTag("tab-pill"), source = browseBackdrop) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val fits = maxWidth >= 56.dp * settings.tabs.size
                    Row(if (fits) Modifier.fillMaxWidth() else Modifier.nestedScroll(HorizontalGestureOwner).horizontalScroll(rememberScrollState())) {
                        settings.tabs.forEach { tab ->
                            val selectedColor by androidx.compose.animation.animateColorAsState(
                                if (navigation.active == tab) androidx.compose.ui.graphics.Color.Black.copy(alpha = .09f) else androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.animation.core.tween(settings.motionDuration(220)), label = "Selected tab")
                            Column((if (fits) Modifier.weight(1f) else Modifier.width(56.dp)).padding(4.dp).fillMaxHeight()
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(selectedColor, androidx.compose.foundation.shape.CircleShape)
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

            }
            LiquidGlassSurface(Modifier.width(104.dp).fillMaxHeight().testTag("utility-pill"), source = browseBackdrop) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_search, "Search library") {
                    navigation = navigation.commitSearch().open(BrowseRoute(navigation.active, search = true, globalSearch = true))
                }
                Box {
                    ActionIcon(R.drawable.ic_more, "More") { moreOpen = true }
                    DropdownMenu(moreOpen, { moreOpen = false }) {
                        ActionMenuItem("Scan music", onClick = {
                            moreOpen = false; scannerOpen = true
                        })
                        ActionMenuItem("Settings", onClick = { moreOpen = false; settingsCategory = null; settingsOpen = true })
                        ActionMenuItem("About and licenses", onClick = { moreOpen = false; settingsCategory = "About and licenses"; settingsOpen = true })
                        ActionMenuItem("Close Localica", onClick = { moreOpen = false; if (settings.confirmExit) exitPrompt = true else exit() })
                    }
                }
                }
            }
        }
        SelectionPanelVisibility(session.active && !expanded && dragHost.state?.drag == null, Modifier.align(Alignment.BottomCenter)
            .padding(horizontal = 8.dp).padding(bottom = inset + if (hasPlayer) 152.dp else 80.dp)
            .fillMaxWidth().heightIn(max = maxHeight / 2).onSizeChanged { selectionHeight = with(density) { it.height.toDp() } }, settings.reducedMotion) {
            Surface(shape = Corners.panel, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SessionSelectionPanel(session, queueController, playlistController, playlist) }
        } }
        if (hasPlayer) PlayerSurface(player, expanded, { expanded = it }, settings, progress,
            onToggle, onSeek, onPrevious, onNext, { queueOpen = true }, { expanded = false },
            if (currentTrack != null && editTags != null) ({ editTags(listOf(library.snapshot.tracks.firstOrNull { it.id == currentTrack.id } ?: currentTrack)) {} }) else null,
            lyricsOpen, { lyricsOpen = it }, currentTrack?.let { track -> library.snapshot.tracks.firstOrNull { it.id == track.id } ?: track },
            { settings = it; preferences.write(it) }, actionRows = if (playerActions.twoRows) 2 else 1, queueProgress = queueProgress, frostedBackdrop = playerBackdrop, glassBackdrop = browseBackdrop, visual = playerVisual) {
            PlayerActionStrip(playerActions, { action -> when (action) {
                PlayerAction.Shuffle -> "Shuffle: " + if (queues.settings.shuffle) "on" else "off"
                PlayerAction.Repeat -> queues.settings.advancedRepeat?.let { "Repeat: ${it.songEnd.label}; queue end: ${it.queueEnd.label}" } ?: ("Repeat: " + queues.settings.repeat.name)
                PlayerAction.Count -> "Track " + ((queues.library.active?.entries?.indexOfFirst { it.id == queues.library.active?.currentEntryId } ?: -1) + 1) + " of " + (queues.library.active?.entries?.size ?: 0)
                PlayerAction.Lyrics -> if (lyricsOpen) "Hide lyrics" else "Show lyrics"
                PlayerAction.Pin -> if (currentTrack?.let { MediaRef(MediaKind.Song, it.id) in pins } == true) "Unpin from Home" else "Pin to Home"
                else -> action.label
            } }, ::playerAction, { customizeActions = true }, shuffle = queues.settings.shuffle, repeat = queues.settings.repeat,
                lyricsOpen = lyricsOpen, pinned = currentTrack?.let { MediaRef(MediaKind.Song, it.id) in pins } == true,
                onRepeatOptions = { repeatOptions = true }, advancedRepeat = queues.settings.advancedRepeat,
                count = "${(queues.library.active?.entries?.indexOfFirst { it.id == queues.library.active?.currentEntryId } ?: -1) + 1} / ${queues.library.active?.entries?.size ?: 0}")
        }
        if (hasPlayer && progress.value > 0f) CompositionLocalProvider(LocalFrostedBackdrop provides playerBackdrop) {
        MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color.White,
            onPrimary = androidx.compose.ui.graphics.Color.Black,
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF414249), onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFFDDDEE2), secondaryContainer = androidx.compose.ui.graphics.Color(0xFF414249),
            onSecondaryContainer = androidx.compose.ui.graphics.Color.White, surfaceTint = androidx.compose.ui.graphics.Color.White,
            surface = playerPalette.bottom, surfaceContainer = playerPalette.middle, surfaceContainerLow = playerPalette.bottom,
            onSurface = androidx.compose.ui.graphics.Color.White, onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFDBDFE6))) {
        QueueSurface(queueProgress, settings, { queueOpen = it }, playerProgress = progress.value) {
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) { QueueScreen(queues, queueController, library,
                playlist, playlistController, playback.connected, pins, ::pin, reducedMotion = settings.reducedMotion, isPlaying = playback.isPlaying, onOpen = ::openCollection) } }
            SelectionPanelVisibility(session.active && dragHost.state?.drag == null, reducedMotion = settings.reducedMotion) {
            Column(Modifier.heightIn(max = queueSelectionMaxHeight).verticalScroll(rememberScrollState())) {
                SessionSelectionPanel(session, queueController, playlistController, playlist)
            } }
        }
        } }
        CompositionLocalProvider(LocalFrostedBackdrop provides if (queueProgress.value > 0f) playerBackdrop else browseBackdrop) {
            ForegroundSongDragOverlay(dragHost)
        }
    }
    androidx.activity.compose.BackHandler(expanded) { if (lyricsOpen) lyricsOpen = false else expanded = false }
    androidx.activity.compose.BackHandler(queueOpen || queueProgress.value > 0f) { queueOpen = false; navigationScope.launch { queueProgress.animateTo(0f, androidx.compose.animation.core.tween(settings.motionDuration(400))) } }
    androidx.activity.compose.BackHandler((queueOpen || queueProgress.value > 0f) && session.active) { session.clear() }
    if (settingsOpen) FolderSettings(library,
        onDismiss = { settingsOpen = false; if (returnToScanner) { scannerOpen = true; returnToScanner = false } }, onChooseFolder, onRemoveFolder,
        extraContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let { TagRecoverySettings(it.tagEditing) }
        }, interfaceContent = { section ->
            if (section == "Player") SettingsLink("Customize player actions", "Choose controls and their order") { customizeActions = true }
            InterfaceSettingsControls(settings, section) {
                settings = it; preferences.write(it); navigation = navigation.reconcile(it.tabs)
            }
        }, playbackContent = { section ->
            QueueSettingsControls(queues.settings, section) { queueController?.settings(it) }
        }, aboutContent = {
            Text("Localica", style = MaterialTheme.typography.headlineSmall)
            Text("Offline music, organized your way.")
            LibraryLicenses()
        }, initialCategory = settingsCategory, libraryFiltersContent = {
            LibraryFilterSettings(libraryFilters) { libraryFilters = it; libraryFilterPreferences.write(it) }
        })
    if (scannerOpen) ScannerPopup(library, { scannerOpen = false }, onScanAll, onCancelScan) { destination ->
        scannerOpen = false; returnToScanner = true; settingsCategory = destination; settingsOpen = true
    }
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Close Localica?") },
        text = { Text("Playback will continue.") }, confirmButton = { TextButton(onClick = { exitPrompt = false; exit() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Cancel") } })
    if (repeatOptions) RepeatOptionsDialog(queues.settings, { repeatOptions = false }) { queueController?.settings(it) }
    if (customizeActions) PlayerActionCustomizer(playerActions, { playerActions = it; actionPreferences.write(it) }, { customizeActions = false }, settings.reducedMotion)
    if (songInfo && indexedTrack != null) SongInformation(indexedTrack, { songInfo = false }, { playerAction(PlayerAction.Edit) }, ::playerAction)
    if (viewArt && indexedTrack != null) ArtworkViewer(indexedTrack.uri) { viewArt = false }
    if (addPlaying && indexedTrack != null) AddToPlaylistDialog(listOf(indexedTrack), playlist, playlistController, { addPlaying = false }, { addPlaying = false })
    if (playerMore) ModalBottomSheet(onDismissRequest = { playerMore = false }, shape = Corners.panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            PlayerAction.entries.filterNot { it == PlayerAction.More }.forEach { action -> TextButton(onClick = { playerMore = false; playerAction(action) }) { Text(action.label) } }
        }
    }
    QueueDialogs(queues, queueController)
    PlaybackFailureDialog(playback, playback.hasNext, onRetryPlayback) { onNext(); onRetryPlayback() }
    }
}

@Composable
internal fun LibraryNotice(state: LibraryUiState) {
    val message = when (state.problem) {
        LibraryProblem.RemoveFailed -> R.string.remove_failed
        LibraryProblem.AddFailed -> R.string.add_folder_failed
        LibraryProblem.ScanFailed -> R.string.scan_failed
        LibraryProblem.LoadFailed -> R.string.load_failed
        null -> if (state.unavailableFolders.isNotEmpty()) R.string.access_lost else null
    }
    message?.let {
        Text(stringResource(it), color = if (state.problem != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
