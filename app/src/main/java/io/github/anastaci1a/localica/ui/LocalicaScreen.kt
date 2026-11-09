package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import io.github.anastaci1a.localica.toast.ToastCommand
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
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
    onQuickScan: () -> Unit = {},
    scannerRequest: Int = 0,
    onRetryPlayback: () -> Unit = onToggle,
    onSeekRequest: ((Long, String?) -> Long)? = null,
    playbackSample: (() -> PlaybackState)? = null,
    lastFmSettingsRequest: Int = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session: SessionSelection = androidx.lifecycle.viewmodel.compose.viewModel()
    val browseSorts = rememberBrowseSortSnapshot()
    val preferences = remember(context) { io.github.anastaci1a.localica.navigation.InterfacePreferences(context) }
    var settings by remember { mutableStateOf(preferences.read()) }
    val glassRenderer = LocalGlassRendererRuntime.current
    SideEffect { glassRenderer?.request(settings.glass.renderer) }
    val libraryFilterPreferences = remember(context) { io.github.anastaci1a.localica.browse.LibraryFilterPreferences(context) }
    var libraryFilters by remember { mutableStateOf(libraryFilterPreferences.read()) }
    val pinPreferences = remember(context) { PinPreferences(context) }
    var pins by remember { mutableStateOf(pinPreferences.read()) }
    fun pin(ref: MediaRef) { pins = PinEdits.toggle(pins, ref); pinPreferences.write(pins) }
    var navigation by rememberSaveable(saver = navigationStateSaver { state -> state.reconcile(settings.tabs) }) {
        mutableStateOf(NavigationState.fresh(settings.tabs))
    }
    // Count and item keys both read the same live snapshot. A captured List in the
    // key callback can outlive a settings update and disagree with the new count.
    val tabs = { settings.tabs }
    val pager = rememberPagerState(initialPage = settings.tabs.indexOf(navigation.active).coerceAtLeast(0)) { tabs().size }
    val pagePopupHomes = remember { mutableStateMapOf<MainTab, GlassPopupHome>() }
    var requestedTab by remember { mutableStateOf<MainTab?>(null) }
    val navigationScope = rememberCoroutineScope()
    var scrollRequests by rememberSaveable { mutableStateOf(emptyMap<MainTab, Int>()) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(navigation.active, settings.tabs) {
        val target = settings.tabs.indexOf(navigation.active).coerceAtLeast(0)
        if (pager.currentPage != target) {
            pagePopupHomes[settings.tabs[pager.currentPage]]?.awaitHandoff()
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
    val presentationCache = remember { BrowsePresentationCache() }
    val warmRoutes = remember(navigation.stacks, settings.tabs, queues.browsedId, queues.library) {
        val pages = settings.tabs.map { navigation.stacks[it]?.lastOrNull() ?: BrowseRoute(it) }
            .filterNot { it.collection == null && !it.search && it.tab in setOf(MainTab.Home, MainTab.Queues) }
        val queue = queues.library.queues.firstOrNull { it.id == queues.browsedId } ?: queues.library.active
        pages + listOfNotNull(queue?.let { BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, it.id), it.name) })
    }
    WarmBrowsePresentations(presentationCache, library.snapshot, playlist.playlists, queues.library.queues, libraryFilters, warmRoutes, browseSorts,
        libraryLoading = library.loading, playlistsLoading = playlist.loading, queuesLoading = queues.loading)
    val artworkStore = remember { BrowseArtworkStore() }
    val retainedRoutes = remember(navigation, settings.tabHistory) {
        val retained = navigation.stacks.values.mapNotNull { it.lastOrNull()?.key }.toMutableSet()
        var backDestination = navigation
        repeat(2) { backDestination.back(settings.tabHistory)?.let { backDestination = it; retained += it.route.key } }
        retained
    }
    artworkStore.keep(retainedRoutes, navigation.route.key)
    val actionPreferences = remember(context) { PlayerActionPreferences(context) }
    var playerActions by remember { mutableStateOf(actionPreferences.read()) }
    var customizeActions by remember { mutableStateOf(false) }
    var customizeAppearance by remember { mutableStateOf<GlassDialogAppearance?>(null) }
    var repeatOptions by remember { mutableStateOf(false) }
    var songInfo by remember { mutableStateOf(false) }
    var viewArt by remember { mutableStateOf(false) }
    var addPlaying by remember { mutableStateOf(false) }
    var playerMore by remember { mutableStateOf(false) }
    var playerDialogAppearance by remember { mutableStateOf<GlassDialogAppearance?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var viewOptions by remember { mutableStateOf(false) }
    var settingsCategory by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var returnToScanner by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scannerRequest) { if (scannerRequest > 0) scannerOpen = true }
    LaunchedEffect(lastFmSettingsRequest) {
        if (lastFmSettingsRequest > 0) { settingsCategory = "Last.fm"; settingsOpen = true }
    }
    var sortingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val displayLease = rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<QueueDisplayLease, String>(
        save = { it.queueId.orEmpty() }, restore = { QueueDisplayLease(it.takeIf(String::isNotEmpty)) })) { QueueDisplayLease() }
    var toastNavigationEpoch by remember { mutableLongStateOf(0L) }
    var toastExpectedRoute by remember { mutableStateOf<String?>(null) }
    var toastScroll by remember { mutableStateOf<ToastScrollRequest?>(null) }
    val activityWindow = androidx.compose.ui.platform.LocalView.current.rootView
    val rootModal = LocalModalGestures.current
    val livePlaylists by rememberUpdatedState(playlist)
    fun cancelToastNavigation() {
        toastNavigationEpoch++
        displayLease.invalidate()
        toastScroll?.cancel(); toastScroll = null
        toastExpectedRoute = null
    }
    fun requestPlayer(open: Boolean) {
        cancelToastNavigation()
        expanded = open
        if (!open && displayLease.queueId != null) queueOpen = false
    }
    fun requestQueue(open: Boolean) { cancelToastNavigation(); queueOpen = open }
    DisposableEffect(displayLease) { onDispose { displayLease.dispose(); toastNavigationEpoch++; toastScroll?.cancel() } }
    OnModalGestureCancellation { cancelToastNavigation() }
    LaunchedEffect(navigation.route.key) {
        if (toastExpectedRoute != null && toastExpectedRoute != navigation.route.key) cancelToastNavigation()
    }
    LaunchedEffect(expanded) { if (expanded) { focusManager.clearFocus(); keyboard?.hide() } }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var consumedRequest by rememberSaveable { mutableLongStateOf(queues.playerRequest) }
    val browsePopupHome = rememberGlassPopupHome(expanded || settingsOpen)
    val utilityPopupHome = rememberGlassPopupHome(!moreOpen || expanded || settingsOpen, browsePopupHome)
    var settingsPresented by remember { mutableStateOf(false) }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) browsePopupHome.awaitHandoff()
        settingsPresented = settingsOpen
    }
    val playerPopupHome = rememberGlassPopupHome(!expanded)
    val queuePopupHome = rememberGlassPopupHome(!queueOpen || !expanded, playerPopupHome)
    val insideCollection = navigation.route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Playlist, MediaKind.Folder)
    val chromeVisibility by androidx.compose.animation.core.animateFloatAsState(if (insideCollection) 0f else 1f,
        androidx.compose.animation.core.tween(settings.motionDuration(300)), label = "Detail navigation")
    val bottomChrome = 8.dp + 72.dp * chromeVisibility
    // U11.00: one root record publishes the full player's reference travel, display interval and admitted geometry.
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Endpoint bases follow the measured root layout (the window until first measure); Rebase keeps changes continuous.
    var rootSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val window = rootSize.takeIf { it.height > 0 } ?: androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val refreshHz = rememberPeakRefreshHz()
    val referenceTravel = playerBasis(density, window, navigationInset, StockUiSpec.clearance.getValue(Clearance.BottomChrome)).edgeTravelPx
    val resolvedSpec = remember(settings.glass, settings.reducedMotion, settings.fasterMotion, referenceTravel, window, density) {
        uiSpec(settings, ExpansionGeometryDomain(referenceTravel, maxOf(1f, kotlin.math.hypot(window.width.toFloat(), window.height.toFloat())), 1f / 24), density) }
    CompositionLocalProvider(LocalEngineContext provides remember(refreshHz, referenceTravel, resolvedSpec) { EngineContext(1f / refreshHz, referenceTravel, resolvedSpec.spec.access) }, LocalUiSpec provides resolvedSpec) {
    val sheet = rememberSheetState("player", expanded, playerBasis(density, window, navigationInset, bottomChrome))
    val phase = sheet.expansion.phase
    val queueSheet = rememberSheetState("queue", queueOpen, queueBasis(density, window, navigationInset, WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))
    LaunchedEffect(queueOpen) { if (!queueOpen) queuePopupHome.awaitHandoff(); if (queueOpen) queueSheet.expand() else queueSheet.collapse() }
    LaunchedEffect(expanded) { if (expanded) browsePopupHome.awaitHandoff() else playerPopupHome.awaitHandoff(); if (expanded) sheet.expand() else sheet.collapse() }
    // Browse yields semantics to an opening or open player and stops drawing only beneath a player resting open.
    val playerRest by rememberExpansionRest(sheet.expansion)
    val playerVisible = playerRest != ExpansionRest.Closed
    val playerCoversBrowse = expanded && playerVisible
    val playerFullyOpen = playerRest == ExpansionRest.Open
    val queueClosed by rememberExpansionClosed(queueSheet.expansion)
    val queueRest by rememberExpansionRest(queueSheet.expansion)
    val queueVisible = !queueClosed
    var queueOpeningRequest by rememberSaveable { mutableLongStateOf(0) }
    // QueueSurface removes its body at the closed endpoint. Keep intent/viewport
    // outside that conditional lifetime; a later explicit epoch owns repositioning.
    val queueDrawerState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun openQueue() {
        // User events own openings; restoring/cancelling an animation never does.
        if (!queueOpen) queueOpeningRequest++
        queueOpen = true
    }
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
        cancelToastNavigation()
        val previous = navigation.back(settings.tabHistory)
        if (previous != null) navigation = previous
        else if (settings.confirmExit) exitPrompt = true else exit()
    }
    androidx.activity.compose.BackHandler { back() }
    val playerTracks = rememberPlayerTrackProjection(queues.library, library.snapshot)
    val currentTrack = playerTracks.current
    val indexedTrack = playerTracks.indexed
    // The lyrics page is available even without local sources and stays open
    // across song changes; source discovery belongs to the page itself.
    fun openCollection(item: io.github.anastaci1a.localica.browse.BrowserItem) {
        cancelToastNavigation()
        val route = BrowseRoute(navigation.active, item.ref, item.title)
        artworkStore.seed(route.key, artworkStore.scope(navigation.route.key)?.peek(item.coverUris)
            ?: io.github.anastaci1a.localica.library.ArtworkCache.peek(item.coverUris), item)
        navigation = navigation.open(route)
        queueOpen = false; expanded = false; songInfo = false
        navigationScope.launch { queuePopupHome.awaitHandoff(); queueSheet.collapse() }
    }
    val livePlayerOccurrence by rememberUpdatedState(queues.library.activeId to queues.library.active?.currentEntryId)
    val playerCollectionLookup = rememberCollectionLookup(library.snapshot, playlist.playlists, browseSorts, onOpen = ::openCollection)
    fun playerAction(action: PlayerAction, editTags: ((List<Track>, (Boolean) -> Unit) -> Unit)? = null) {
        playerCollectionLookup.cancel()
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
            PlayerAction.BackTen -> onSeek(((playbackSample?.invoke() ?: playback).positionMs - 10000).coerceAtLeast(0))
            PlayerAction.ForwardTen -> onSeek((playbackSample?.invoke() ?: playback).positionMs + 10000)
            PlayerAction.More -> playerMore = true
            else -> {
                val routeKey = navigation.route.key
                val occurrence = livePlayerOccurrence
                val informationOpen = songInfo
                val playerExpanded = expanded
                val drawerOpen = queueOpen
                // More intentionally closes before invoking this action; root navigation owns the accepted request.
                playerCollectionLookup.open(action, track) {
                    navigation.route.key == routeKey && livePlayerOccurrence == occurrence &&
                        songInfo == informationOpen && expanded == playerExpanded && queueOpen == drawerOpen
                }
            }
        }
    }
    val player = playerDisplayState(playback, currentTrack)
    val playerSample = remember(playbackSample, currentTrack) {
        playbackSample?.let { sample -> { playerDisplayState(sample(), currentTrack) } }
    }
    val playbackPresent = currentTrack != null || playback.trackUri != null
    // UI display admission never changes activeId, decoder, queue persistence or play intent.
    val hasPlayer = playbackPresent || displayLease.queueId != null &&
        (expanded || queueOpen || playerRest != ExpansionRest.Closed || !queueClosed)
    LaunchedEffect(playbackPresent, displayLease.queueId, expanded, queueOpen, playerRest, queueClosed) {
        displayLease.reconcile(playbackPresent, expanded, queueOpen, playerRest == ExpansionRest.Closed, queueClosed)
        if (!playbackPresent && displayLease.queueId == null) { expanded = false; queueOpen = false }
    }
    LaunchedEffect(queues.loading, queues.library.queues, displayLease.queueId) {
        if (displayLease.destinationMissing(queues.loading, queues.library.queues.any { it.id == displayLease.queueId })) {
            cancelToastNavigation(); expanded = false; queueOpen = false
        }
    }
    val toastNavigation = ToastNavigation { command, presenterCurrent ->
        cancelToastNavigation()
        val epoch = toastNavigationEpoch
        toastExpectedRoute = navigation.route.key
        fun current() = epoch == toastNavigationEpoch && presenterCurrent() &&
            toastExpectedRoute == navigation.route.key && rootModal?.blocked != true &&
            (rootModal?.coordinator?.presentedModalCount ?: 0) == 0
        when (command) {
            is ToastCommand.OpenQueue -> {
                val ticket = displayLease.request(command.queueId, navigation.route.key)
                if (ticket != null) queueController?.browse(command.queueId) { success ->
                    if (displayLease.admit(ticket, navigation.route.key,
                            success && queueController.state.value.library.queues.any { it.id == command.queueId }, current())) {
                        val target = ToastScrollRequest(MediaRef(MediaKind.Queue, command.queueId),
                            command.firstAddedEntryId.takeUnless { command.playNext }, true, activityWindow, null, ::current)
                        toastScroll = target
                        expanded = true
                        navigationScope.launch {
                            snapshotFlow { playerRest == ExpansionRest.Open || !current() }.first { it }
                            if (!current()) return@launch
                            openQueue()
                            snapshotFlow { queueRest == ExpansionRest.Open || !current() }.first { it }
                            if (current()) target.ready = true
                        }
                    }
                }
            }
            is ToastCommand.OpenList -> {
                val list = (playlistController?.state?.value?.playlists ?: livePlaylists.playlists).firstOrNull { it.id == command.listId }
                if (list != null && current()) {
                    val route = BrowseRoute(navigation.active, MediaRef(MediaKind.Playlist, list.id), list.name)
                    toastExpectedRoute = route.key
                    navigation = navigation.open(route)
                    queueOpen = false; expanded = false; songInfo = false
                    toastScroll = ToastScrollRequest(requireNotNull(route.collection), command.firstAddedEntryId,
                        false, activityWindow, route.key, ::current)
                }
            }
            is ToastCommand.Operation -> Unit
        }
    }
    val dragHost = remember { SongDragHost() }
    val playerVisual = rememberPlayerVisual(player.trackUri)
    val artworkQueue = queues.library.active
    val preloadUris = remember(queueController, artworkQueue?.id, artworkQueue?.entries, artworkQueue?.journey) {
        queueController?.artworkPreloadUris().orEmpty()
    }
    PreloadPlayerArtwork(preloadUris)
    val neighbors = remember(queueController, artworkQueue?.id, artworkQueue?.entries, artworkQueue?.journey) {
        queueController?.artworkNeighbors().orEmpty()
    }
    val journey = queues.library.active?.journey
    val nextVisual = rememberNeighborArtwork(neighbors.getOrNull(0))
    val previousVisual = rememberNeighborArtwork(neighbors.getOrNull(1))
    val nextArtwork = journey?.next?.let { visit -> nextVisual?.let { ArtworkNeighbor(visit.token, it) } }
    val previousArtwork = journey?.previous?.let { visit -> previousVisual?.let { ArtworkNeighbor(visit.token, it) } }
    // Older Android snapshots must never capture the expanding mini-player's
    // browsing glass and stretch it into the Queue’s artwork material.
    val playerBackgroundPolarity = remember { PlayerBackgroundPolarityState() }
    // The eased polarity (foreground and materials share it); the discrete decision only seeds it.
    val playerLightMix = playerBackgroundPolarity.lightMix
    val playerLightInk = playerLightMix >= .5f
    val playerBackdrop = rememberFrostedBackdrop(listOf(playerVisual, lyricsOpen, settings.glass), expanded && playerFullyOpen, dark = playerLightInk, lightMix = playerLightMix)
    var browseInk by remember { mutableStateOf(androidx.compose.ui.graphics.Color.Black) }
    var pageBackground by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val miniInk by androidx.compose.animation.animateColorAsState(browseInk,
        androidx.compose.animation.core.tween(settings.motionDuration(220)), label = "Mini-player foreground")
    SideEffect { if (!insideCollection) { browseInk = androidx.compose.ui.graphics.Color.Black; pageBackground = androidx.compose.ui.graphics.Color(0xFFF9F9FB) } }
    val selectionPanel = rememberFloatingSelectionPanel(
        session.panelVisible && !expanded, settings.reducedMotion)
    // The Queue drawer's pill floats above its list (#552); its occupancy also pads the list end.
    val queueSelectionPanel = rememberFloatingSelectionPanel(session.panelVisible, settings.reducedMotion)
    val browseBottomChrome = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        if (hasPlayer) CompactControlSize + bottomChrome + 8.dp else bottomChrome
    val browseBackdrop = rememberFrostedBackdrop(navigation.route to library.snapshot, !expanded, dark = browseInk == androidx.compose.ui.graphics.Color.White)
    val modalBackdrop = rememberFrostedBackdrop(listOf(navigation.route, library.snapshot, expanded, queueOpen, playerVisual, settings.glass),
        dark = if (expanded) playerLightInk else browseInk == androidx.compose.ui.graphics.Color.White,
        lightMix = if (expanded) playerLightMix else null)
    val dialogs = LocalGlassDialogs.current
    SideEffect { dialogs?.backdrop = modalBackdrop }
    LaunchedEffect(pager.settledPage) { browseBackdrop.refresh() }
    PlayerSystemBars(hasPlayer && playerFullyOpen && playerLightInk || !playerFullyOpen && insideCollection && browseInk == androidx.compose.ui.graphics.Color.White)
    val rowFaces = remember(activityWindow) { FloatingRowFaces(activityWindow) }
    val rowScenes = remember { mutableMapOf<String, FloatingRowScene>() }
    fun rowScene(key: String) = rowScenes.getOrPut(key) { FloatingRowScene(ToastRowOwner(key)) }
    val browseRow = rowScene(navigation.route.key)
    val queueRow = remember { FloatingRowScene(ToastRowOwner("player-queue"), queue = true) }
    val rowViewport = remember { FloatingRowViewport() }
    val rowPresentation = remember { ToastRowPresentation() }
    val currentBrowseRow by rememberUpdatedState(browseRow)
    val browseBottomFallback by rememberUpdatedState(with(density) { (browseBottomChrome + selectionPanel.clearance).toPx() })
    val playerBottomInset by rememberUpdatedState(with(density) { (navigationInset + StockUiSpec.clearance.getValue(Clearance.FloatingGap)).toPx() })
    val rowSeat = rememberFloatingRowSeat(
        browse = { rowFaces.originals(currentBrowseRow).maxOfOrNull { requireNotNull(it.original).bottom }
            ?: ((rowViewport.bounds?.bottom ?: window.height.toFloat()) - browseBottomFallback) },
        player = { (rowViewport.bounds?.bottom ?: window.height.toFloat()) - playerBottomInset },
        progress = { phase.value() },
        queue = { rowFaces.originals(queueRow).maxOfOrNull { requireNotNull(it.original).bottom } },
        queueShown = { hasPlayer && playerVisible && queueVisible })
    val rowRoot = remember(rowViewport, rowSeat, rowPresentation) { ToastRowRoot(rowViewport, rowSeat, rowPresentation) }
    val bottomShadows = rememberShadowPlane()
    SideEffect { LongScroll.style = settings.longScroll }
    LocalicaTheme(grayTone = settings.grayTone) {
    CompositionLocalProvider(LocalPageShadows provides bottomShadows, LocalGlassAppearance provides settings.glass, LocalArtworkTransition provides settings.artworkTransition, LocalReducedMotion provides rememberEffectiveReducedMotion(settings.reducedMotion), LocalSessionSelection provides session, LocalSongDragHost provides dragHost,
        LocalBrowsePresentationCache provides presentationCache, LocalBrowseSortSnapshot provides browseSorts,
        LocalToastNavigation provides toastNavigation, LocalToastScroll provides toastScroll,
        LocalFloatingRowFaces provides rowFaces, LocalToastRowRoot provides rowRoot,
        LocalMotionSpeed provides if (settings.fasterMotion) 2f else 4f / 3f,
        LocalModalBackdrop provides modalBackdrop,
        LocalBackdropRefresh provides browseBackdrop::refresh,
        LocalBrowseObscuredInset provides (browseBottomChrome + selectionPanel.clearance)) {
    ShadowPlaneViewport(bottomShadows, settings.glass.shadowStrength, settings.glass.shadowSpread,
        Modifier.fillMaxSize().recordFrostedBackdrop(modalBackdrop).background(MaterialTheme.colorScheme.background)
            .observeToastNavigationContact(::cancelToastNavigation),
        Modifier.testTag("browse-background"), WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) {
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val screenScope = this
        SideEffect { rootSize = androidx.compose.ui.unit.IntSize(constraints.maxWidth, constraints.maxHeight) }
        val queueSelectionMaxHeight = maxHeight / 3
        SongDragScene(dragHost, Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGlassPopupHome provides browsePopupHome) {
        Box(Modifier.fillMaxSize().glassPopupHome(browsePopupHome)) {
        Column(Modifier.fillMaxSize().glassPopupProjection(utilityPopupHome, browseBackdrop)
            // The opaque full player covers this entire scene. Keep its composition,
            // scroll state and cached recording, but do not submit hidden pixels.
            .drawWithContent { if (!hasPlayer || !playerFullyOpen) drawContent() }
            .recordFrostedBackdrop(browseBackdrop).background(pageBackground)
            .then(if (hasPlayer && playerCoversBrowse) Modifier.clearAndSetSemantics {} else Modifier)) {
            TabPager(pager, tabs, Modifier.weight(1f), userScrollEnabled = !expanded) { page ->
            val tab = settings.tabs[page]
            val pageRoute = navigation.stacks[tab]?.lastOrNull() ?: remember(tab) { BrowseRoute(tab) }
            val route = pageRoute
            val routeArtwork = artworkStore.scope(route.key)
            GlassPopupNavigation(route to routeArtwork, Modifier.fillMaxSize(), departing = page != pager.targetPage || tab != (requestedTab ?: navigation.active),
                onHome = { pagePopupHomes[tab] = it },
                // Query history changes results inside one continuing editor host.
                contentKey = { if (it.first.search) Triple(it.first.tab, it.first.collection, it.first.globalSearch) else it.first.key }) { (route, routeArtwork) ->
            val sortAnchor = rememberGlassAnchor()
            // Collection pages own their hero, navigation controls and status-bar inset.
            // Playlists use that same presentation, not the legacy shell heading.
            val collectionChrome = !route.search && route.collection?.kind in setOf(
                MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Playlist)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .then(if (collectionChrome)
                    Modifier else Modifier.statusBarsPadding())) {
                if (!collectionChrome) Row(Modifier.zIndex(2f).fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if ((navigation.stacks[tab]?.size ?: 1) > 1) ActionIcon(AppIcon.ActionChevronLeft, "Back") { back() }
                    androidx.compose.animation.Crossfade(if (route.search) "Search" else route.title ?: route.tab.label,
                        modifier = Modifier.weight(1f), animationSpec = androidx.compose.animation.core.tween(settings.motionDuration(220)), label = "Page heading") { title ->
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("page-heading"))
                    }
                    if (route.tab != MainTab.Queues && (route.tab != MainTab.Home || route.search || route.collection != null)) {
                        BrowseOptionsPill(sortAnchor) { view -> viewOptions = view; sortingRoute = route.key }
                    }
                }
                Box(Modifier.weight(1f)) {
            // Routes retain saved state, but must not recycle live drawing/focus nodes from
            // a different grid or text field through SaveableStateProvider's ReusableContent.
            key(route.key) { CompositionLocalProvider(LocalBrowseArtwork provides routeArtwork, LocalFloatingRowScene provides rowScene(route.key)) { holder.SaveableStateProvider(route.key) {
                if (route.tab == MainTab.Queues && !route.search && (route.collection == null || route.collection.kind == MediaKind.Queue)) QueueScreen(queues, queueController, library,
                    playlist, playlistController, playback.connected, pins, ::pin, listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage && !queueVisible, isPlaying = playback.isPlaying,
                    onOpen = ::openCollection, playerOpen = expanded || playerVisible)
                else if (route.tab == MainTab.Home && !route.search && route.collection == null) {
                    PinnedHome(library, playlist, pins, { settingsCategory = "Folders to scan"; settingsOpen = true },
                        { navigation = navigation.open(BrowseRoute(MainTab.Home, globalSearch = true, search = true)) },
                        { item, songs ->
                            if (item.row != null) queueController?.tap(songs.map { it.track }, songs.indexOfFirst { it.id == item.row.id }, sourceName = "Pinned songs")
                            else openCollection(item)
                        }, ::pin, { ref, offset -> pins = PinEdits.move(pins, ref, offset); pinPreferences.write(pins) }, listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage, playlistController = playlistController, queues = queueController, playerOpen = expanded)
                } else BrowserScreen(library, playlist, route, {
                    if (navigation.active == tab && navigation.stacks[tab]?.lastOrNull()?.key == route.key) navigation = navigation.update(it)
                },
                    ::openCollection,
                    { settingsCategory = "Folders to scan"; settingsOpen = true }, { rows, index, scope ->
                        if (index >= 0) {
                            if (queueController != null) queueController.tap(rows.map { it.track }, index, scope, io.github.anastaci1a.localica.browse.queueSourceName(route, rows.map { it.track }), rows.map { it.sourcePlaylistId })
                            else onPlay(rows[index].track)
                        }
                    }, queueController, playlistController, playback.connected, expanded, pins, ::pin,
                    sortingRoute == route.key, { sortingRoute = null }, playingTrackId = playback.trackId, presentationOnly = viewOptions, selectionVisible = page == pager.settledPage,
                    onSearchEdit = { navigation = navigation.editSearch(it) }, onSearchCommit = { navigation = navigation.commitSearch() },
                    libraryFilters = libraryFilters, onFilterSettings = { settingsCategory = "Library filters"; settingsOpen = true },
                    listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, sortOrigin = sortAnchor, onBack = ::back, onAppearance = { ink, background ->
                        if (navigation.route.key == route.key) { browseInk = ink; pageBackground = background }
                    })
            } } }
                }
            }
            }
            }
        }
        val tabActivity = rememberSelectorTrackActivity()
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
            .padding(bottom = inset + 8.dp).height(NavigationPillHeight).fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(0, kotlin.math.round((80.dp + inset).toPx() * maxOf(phase.value(), 1f - chromeVisibility)).toInt()) }
            .graphicsLayer { alpha = chromeVisibility }
            .then(if (insideCollection || hasPlayer && playerCoversBrowse) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().selectorTrackStacking(tabActivity).testTag("tab-pill")) {
                SlidingTabs(settings.tabs, settings.tabs[pager.targetPage.coerceIn(settings.tabs.indices)], { pager.currentPage + pager.currentPageOffsetFraction }, source = browseBackdrop, pageDragging = pager.interactionSource.collectIsDraggedAsState().value, activity = tabActivity, shadowOpacity = { chromeVisibility * (1f - phase.value()) }, onReselect = { tab ->
                    navigation = navigation.resetTab(tab, clearLibraryFilter = false)
                    scrollRequests = scrollRequests + (tab to ((scrollRequests[tab] ?: 0) + 1))
                }) { tab ->
                    keyboard?.hide(); focusManager.clearFocus()
                    requestedTab = tab
                    navigationScope.launch {
                        try {
                            pagePopupHomes[settings.tabs[pager.currentPage]]?.awaitHandoff()
                            val index = settings.tabs.indexOf(tab)
                            if (settings.reducedMotion) pager.scrollToPage(index)
                            else pager.animateScrollToPage(index, animationSpec = androidx.compose.animation.core.spring(dampingRatio = .85f, stiffness = 380f))
                        } finally { requestedTab = null }
                    }
                }

            }
            CompositionLocalProvider(LocalGlassPopupHome provides utilityPopupHome) {
            val utilityAnchor = rememberGlassAnchor()
            NavigationUtilityPill(utilityAnchor, browseBackdrop,
                Modifier.width(104.dp).fillMaxHeight(),
                shadowOpacity = { chromeVisibility * (1f - phase.value()) },
                onSearch = { navigation = navigation.commitSearch().open(BrowseRoute(navigation.active, search = true, globalSearch = true)) },
                onMore = { moreOpen = true })
            GlassMenu(moreOpen, utilityAnchor, { moreOpen = false }) {
                ActionMenuItem("Scan music", icon = AppIcon.TabFolders, onClick = { moreOpen = false; scannerOpen = true })
                ActionMenuItem("Settings", icon = AppIcon.ActionSettings, onClick = { moreOpen = false; settingsCategory = null; settingsOpen = true })
            }
            }
        }
        // The pill is a standard Liquid Glass pill over the page: it samples the browse backdrop and takes the
        // page's ink for its material, icons and menu, like the mini-player and utility pill (#552).
        CompositionLocalProvider(LocalFrostedBackdrop provides browseBackdrop) {
        GlassPanelTheme(browseInk == androidx.compose.ui.graphics.Color.White) {
        FloatingSelectionPanel(selectionPanel, Modifier.align(Alignment.BottomCenter)
            .padding(horizontal = 8.dp).padding(bottom = browseBottomChrome)
            .fillMaxWidth().heightIn(max = screenScope.maxHeight / 2)) {
            Column(Modifier.controlVerticalScroll(rememberScrollState())) {
                SessionSelectionPanel(session, queueController, playlistController, playlist, dragging = dragHost.state?.drag != null)
            }
        }
        } }
        } }
        Sheet(sheet) { if (hasPlayer) screenScope.PlayerSurface(player, expanded, ::requestPlayer, settings, sheet,
            onToggle, onSeek, onPrevious, onNext, { cancelToastNavigation(); openQueue() }, { requestPlayer(false) },
            lyricsOpen, { lyricsOpen = it }, playerTracks.lyrics,
            { settings = it; preferences.write(it) }, actionRows = if (playerActions.twoRows) 2 else 1, queue = queueSheet, frostedBackdrop = playerBackdrop, glassBackdrop = browseBackdrop, visual = playerVisual,
            onSeekRequest = onSeekRequest, sampleState = playerSample, backgroundPolarity = playerBackgroundPolarity, artworkVisit = journey?.current?.token, previousArtwork = previousArtwork, nextArtwork = nextArtwork,
            providedPopupHome = playerPopupHome, openingPopupHome = browsePopupHome,
            bottomChrome = bottomChrome, miniForeground = miniInk, onArtworkVisit = queueController?.let { controller -> { token, complete -> controller.swipeTo(token, complete) } }) {
            val editTags = presentedTagEdit()
            val actionAppearance = currentGlassDialogAppearance()
            PlayerActionStrip(playerActions, { action -> when (action) {
                PlayerAction.Shuffle -> "Shuffle: " + if (queues.settings.shuffle) "on" else "off"
                PlayerAction.Repeat -> queues.settings.advancedRepeat?.let { "Repeat: ${it.songEnd.label}; queue end: ${it.queueEnd.label}" } ?: ("Repeat: " + queues.settings.repeat.name)
                PlayerAction.Count -> "Track " + ((queues.library.active?.entries?.indexOfFirst { it.id == queues.library.active?.currentEntryId } ?: -1) + 1) + " of " + (queues.library.active?.entries?.size ?: 0)
                PlayerAction.Lyrics -> if (lyricsOpen) "Hide lyrics" else "Show lyrics"
                PlayerAction.Pin -> if (currentTrack?.let { MediaRef(MediaKind.Song, it.id) in pins } == true) "Unpin from Home" else "Pin to Home"
                else -> action.label
            } }, { playerDialogAppearance = actionAppearance; playerAction(it, editTags) }, { customizeAppearance = actionAppearance; customizeActions = true }, shuffle = queues.settings.shuffle, repeat = queues.settings.repeat,
                lyricsOpen = lyricsOpen, pinned = currentTrack?.let { MediaRef(MediaKind.Song, it.id) in pins } == true,
                onRepeatOptions = { playerDialogAppearance = actionAppearance; repeatOptions = true }, advancedRepeat = queues.settings.advancedRepeat,
                count = "${(queues.library.active?.entries?.indexOfFirst { it.id == queues.library.active?.currentEntryId } ?: -1) + 1} / ${queues.library.active?.entries?.size ?: 0}")
        } }
        if (hasPlayer && playerVisible) CompositionLocalProvider(LocalFrostedBackdrop provides playerBackdrop) {
        GlassPanelTheme(playerLightMix) {
        screenScope.PlayerClippedOverlay({ phase.value() }, bottomChrome) {
        screenScope.QueueSurface(queueSheet, ::requestQueue, playerProgress = { phase.value() },
            onOpeningContact = { cancelToastNavigation(); queueOpeningRequest++ }, providedPopupHome = queuePopupHome) {
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalBrowseObscuredInset provides queueSelectionPanel.clearance) {
            CompositionLocalProvider(LocalFloatingRowScene provides queueRow) {
            queueDrawerState.SaveableStateProvider("player-queue") { QueueScreen(queues, queueController, library,
                playlist, playlistController, playback.connected, pins, ::pin, reducedMotion = settings.reducedMotion, isPlaying = playback.isPlaying, onOpen = ::openCollection, onMinimize = { requestQueue(false) },
                selectionVisible = queueOpen && expanded, playerOpen = !queueOpen || !expanded,
                openingRequest = queueOpeningRequest) } } }
            // Floats over the list: it never shortens the viewport, so the items gain no new bottom clip edge.
            FloatingSelectionPanel(queueSelectionPanel, Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.heightIn(max = queueSelectionMaxHeight).controlVerticalScroll(rememberScrollState())) {
                SessionSelectionPanel(session, queueController, playlistController, playlist, dragging = dragHost.state?.drag != null)
            } } }
        }
        } } }
        }
        // Siblings of SongDragScene: outside browse/player/Queue recordings and clips.
        val rowOutset = with(density) { glassShadowInset().toPx() }
        val rowDownward = with(density) { FloatingControlRise.toPx() }
        val rowInput = rootModal?.blocked != true && (rootModal?.coordinator?.presentedModalCount ?: 0) == 0
        screenScope.PlayerOccludedOverlay({ phase.value() }, bottomChrome, occluding = { hasPlayer }) {
        RootFloatingFaces(rowFaces, queue = false, scene = browseRow.takeUnless { hasPlayer && playerFullyOpen },
            toast = rowPresentation::body, downwardPx = rowDownward, fieldOutset = rowOutset,
            interactive = rowInput && !playerCoversBrowse, modifier = Modifier.floatingRowViewport(rowViewport))
        }
        screenScope.PlayerClippedOverlay({ phase.value() }, bottomChrome) {
            RootFloatingFaces(rowFaces, queue = true, scene = queueRow, admitted = { rowSeat.queueAdmitted },
                toast = rowPresentation::body, downwardPx = rowDownward, fieldOutset = rowOutset,
                sourceOpacity = { queuePlayerOpacity(phase.value()) }, interactive = rowInput && rowSeat.queueInteractive)
        }
        ToastGlassPresenter(rowRoot, if (playerVisible) playerBackdrop else browseBackdrop, modal = !rowInput)
    }
    androidx.activity.compose.BackHandler(expanded) { if (lyricsOpen) lyricsOpen = false else requestPlayer(false) }
    androidx.activity.compose.BackHandler(queueOpen || queueVisible) { requestQueue(false); navigationScope.launch { queuePopupHome.awaitHandoff(); queueSheet.collapse() } }
    androidx.activity.compose.BackHandler(session.active && (!expanded || queueOpen || queueVisible)) { session.clear() }
    GlassPanelTheme(if (playerVisible) playerLightMix else if (browseInk == androidx.compose.ui.graphics.Color.White) 1f else 0f) {
    if (settingsPresented) FolderSettings(library,
        onDismiss = { settingsOpen = false; if (returnToScanner) { scannerOpen = true; returnToScanner = false } }, onChooseFolder, onRemoveFolder,
        extraContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let { TagRecoverySettings(it.tagEditing) }
        }, interfaceContent = { section ->
            if (section == "Player") {
                val appearance = currentGlassDialogAppearance()
                SettingsLink("Customize player actions", "Choose controls and their order") {
                    customizeAppearance = appearance; customizeActions = true
                }
            }
            InterfaceSettingsControls(settings, section) {
                settings = it; preferences.write(it); navigation = navigation.reconcile(it.tabs)
            }
        }, playbackContent = { section ->
            QueueSettingsControls(queues.settings, section) { queueController?.settings(it) }
        }, aboutContent = {
            Text("Localica", style = MaterialTheme.typography.headlineSmall)
            Text("Offline music, organized your way.")
            DiagnosticsExportAction()
            LibraryLicenses()
        }, lastFmContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let {
                LastFmSettings(it.lastfm)
            }
        }, onlineFeaturesContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let {
                OnlineFeaturesSettings(it.onlineMetadataConsent)
            }
        }, artistPhotosContent = {
            (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.let {
                ArtistPhotosSettings(it.artistPhotos, it.onlineMetadataConsent, library.snapshot)
            }
        }, lastFmSettingsRequest = if (settingsCategory == "Last.fm") lastFmSettingsRequest else 0, searchValues = settingsSearchValues(settings, queues.settings, libraryFilters), initialCategory = settingsCategory, onFullScan = onScanAll, onQuickScan = onQuickScan, libraryFiltersContent = {
            LibraryFilterSettings(libraryFilters) { libraryFilters = it; libraryFilterPreferences.write(it) }
        })
    // The app-owned scanner is the live count source; LibraryUiState carries scan milestones only.
    if (scannerOpen) ScannerPopup(library, { scannerOpen = false }, onScanAll, onCancelScan, onQuickScan,
        remember(context) { (context.applicationContext as? io.github.anastaci1a.localica.LocalicaApplication)?.scanner?.state }) { destination ->
        scannerOpen = false; returnToScanner = true; settingsCategory = destination; settingsOpen = true
    }
    if (exitPrompt) GlassAlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Close Localica?") },
        text = { Text("Playback will continue.") }, confirmButton = { ActionTextButton(onClick = { exitPrompt = false; exit() }) { Text("Close") } },
        dismissButton = { ActionTextButton(onClick = { exitPrompt = false }) { Text("Cancel") } })
    CompositionLocalProvider(LocalGlassDialogAppearance provides playerDialogAppearance) {
    if (repeatOptions) RepeatOptionsDialog(queues.settings, { repeatOptions = false }) { queueController?.settings(it) }
    if (customizeActions) CompositionLocalProvider(LocalGlassDialogAppearance provides customizeAppearance) {
        PlayerActionCustomizer(playerActions, { playerActions = it; actionPreferences.write(it) }, { customizeActions = false; customizeAppearance = null }, settings.reducedMotion)
    }
    if (songInfo && indexedTrack != null) SongInformation(indexedTrack, { playerCollectionLookup.cancel(); songInfo = false }, folders = library.snapshot.folders, onAction = { playerAction(it) })
    if (viewArt && indexedTrack != null) ArtworkViewer(indexedTrack.uri, indexedTrack.albumArtist ?: indexedTrack.artist, indexedTrack.album) { viewArt = false }
    if (addPlaying && indexedTrack != null) AddToPlaylistDialog(listOf(indexedTrack), playlist, playlistController, { addPlaying = false }, { addPlaying = false })
    if (playerMore) GlassDialog({ playerMore = false }, GlassPlacement.Sheet) {
        val editTags = presentedTagEdit()
        Column(Modifier.fillMaxWidth().controlVerticalScroll(rememberScrollState()).padding(16.dp)) {
            PlayerAction.entries.filterNot { it == PlayerAction.More }
                .forEach { action -> ActionTextButton(onClick = { playerMore = false; playerAction(action, editTags) }) { Text(action.label) } }
        }
    }
    }
    QueueDialogs(queues, queueController)
    PlaybackFailureDialog(playback, playback.hasNext, onRetryPlayback) { onNext(); onRetryPlayback() }
    }
    }
    }
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



/** U11.00: peak display refresh, re-published when the display changes (the engine's two-frame floor). */
@Composable private fun rememberPeakRefreshHz(): Float {
    val view = androidx.compose.ui.platform.LocalView.current
    fun peak() = (view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f).coerceAtLeast(1f)
    var refreshHz by remember(view) { mutableFloatStateOf(peak()) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { refreshHz = peak() } }
        displays.registerDisplayListener(listener, null); onDispose { displays.unregisterDisplayListener(listener) }
    }
    return refreshHz
}
/** U11.00: the player's unclipped endpoint frames from the same solver PlayerSurface lays out with. */
private fun playerBasis(density: androidx.compose.ui.unit.Density, window: androidx.compose.ui.unit.IntSize, navigationInset: androidx.compose.ui.unit.Dp,
    chrome: androidx.compose.ui.unit.Dp) = with(density) { listOf(0f, 1f).map { p -> playerSurfaceFrame(window.width.toDp(), window.height.toDp(), navigationInset, chrome, p, DeviceCorners())
        .let { androidx.compose.ui.geometry.Rect(it.x.toPx(), it.y.toPx(), (it.x + it.width).toPx(), (it.y + it.height).toPx()) } } }.let { ExpansionBasis(it[0], it[1], density.density) }
/** U10.02: the Queue drawer's lip top travels from its collapsed shelf to the status inset (operator, 2026-11-06). */
private fun queueBasis(density: androidx.compose.ui.unit.Density, window: androidx.compose.ui.unit.IntSize, navigationInset: androidx.compose.ui.unit.Dp,
    status: androidx.compose.ui.unit.Dp) = with(density) { val open = status.toPx(); val closed = window.height - (navigationInset + CollapsedQueueHeight).toPx()
    ExpansionBasis(androidx.compose.ui.geometry.Rect(0f, closed, window.width.toFloat(), closed + window.height - open), androidx.compose.ui.geometry.Rect(0f, open, window.width.toFloat(), window.height.toFloat()), density.density) }
