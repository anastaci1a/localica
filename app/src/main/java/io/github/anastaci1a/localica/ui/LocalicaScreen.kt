package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
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
        save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it).reconcile(settings.tabs) })) {
        mutableStateOf(NavigationState.fresh(settings.tabs))
    }
    // Count and item keys both read the same live snapshot. A captured List in the
    // key callback can outlive a settings update and disagree with the new count.
    val tabs = { settings.tabs }
    val pager = rememberPagerState(initialPage = settings.tabs.indexOf(navigation.active).coerceAtLeast(0)) { tabs().size }
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
    val presentationCache = remember { BrowsePresentationCache() }
    val warmRoutes = remember(navigation.stacks, settings.tabs, queues.browsedId, queues.library) {
        val pages = settings.tabs.map { navigation.stacks[it]?.lastOrNull() ?: BrowseRoute(it) }
            .filterNot { it.collection == null && !it.search && it.tab in setOf(MainTab.Home, MainTab.Queues) }
        val queue = queues.library.queues.firstOrNull { it.id == queues.browsedId } ?: queues.library.active
        pages + listOfNotNull(queue?.let { BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, it.id), it.name) })
    }
    WarmBrowsePresentations(presentationCache, library.snapshot, playlist.playlists, queues.library.queues, libraryFilters, warmRoutes)
    val artworkStore = remember { BrowseArtworkStore() }
    val retainedRoutes = navigation.stacks.values.mapNotNull { it.lastOrNull()?.key }.toMutableSet()
    var backDestination = navigation
    repeat(2) { backDestination.back(settings.tabHistory)?.let { backDestination = it; retainedRoutes += it.route.key } }
    artworkStore.keep(retainedRoutes, navigation.route.key)
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
    val playerVisible by remember { derivedStateOf { progress.value > 0f } }
    val playerCoversBrowse by remember { derivedStateOf { progress.value >= .5f } }
    val playerFullyOpen by remember { derivedStateOf { progress.value >= .99f } }
    val queueVisible by remember { derivedStateOf { queueProgress.value > 0f } }
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
    // The lyrics page is available even without local sources and stays open
    // across song changes; source discovery belongs to the page itself.
    fun openCollection(item: io.github.anastaci1a.localica.browse.BrowserItem) {
        val route = BrowseRoute(navigation.active, item.ref, item.title)
        artworkStore.seed(route.key, artworkStore.scope(navigation.route.key)?.peek(item.coverUris)
            ?: io.github.anastaci1a.localica.library.ArtworkCache.peek(item.coverUris), item)
        navigation = navigation.open(route)
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
    val neighbors = queueController?.artworkNeighbors().orEmpty()
    val journey = queues.library.active?.journey
    val nextVisual = rememberNeighborArtwork(neighbors.getOrNull(0))
    val previousVisual = rememberNeighborArtwork(neighbors.getOrNull(1))
    val nextArtwork = journey?.next?.let { visit -> nextVisual?.let { ArtworkNeighbor(visit.token, it) } }
    val previousArtwork = journey?.previous?.let { visit -> previousVisual?.let { ArtworkNeighbor(visit.token, it) } }
    // Older Android snapshots must never capture the expanding mini-player's
    // light browsing glass and stretch it into the Queue's dark material.
    val playerBackdrop = rememberFrostedBackdrop(playerVisual to lyricsOpen, expanded && playerFullyOpen, glassBlur = settings.glass.blur)
    var browseInk by remember { mutableStateOf(androidx.compose.ui.graphics.Color.Black) }
    var pageBackground by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val miniInk by androidx.compose.animation.animateColorAsState(browseInk,
        androidx.compose.animation.core.tween(settings.motionDuration(220)), label = "Mini-player foreground")
    val insideCollection = navigation.route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Playlist, MediaKind.Folder)
    val chromeVisibility by androidx.compose.animation.core.animateFloatAsState(if (insideCollection) 0f else 1f,
        androidx.compose.animation.core.tween(settings.motionDuration(300)), label = "Detail navigation")
    SideEffect { if (!insideCollection) { browseInk = androidx.compose.ui.graphics.Color.Black; pageBackground = androidx.compose.ui.graphics.Color(0xFFF9F9FB) } }
    val bottomChrome = 8.dp + 72.dp * chromeVisibility
    val browseBackdrop = rememberFrostedBackdrop(navigation.route to library.snapshot, !expanded, dark = browseInk == androidx.compose.ui.graphics.Color.White, glassBlur = settings.glass.blur)
    val modalBackdrop = rememberFrostedBackdrop(listOf(navigation.route, library.snapshot, expanded, queueOpen, playerVisual),
        dark = expanded, glassBlur = settings.glass.blur)
    val dialogs = LocalGlassDialogs.current
    SideEffect { dialogs?.backdrop = modalBackdrop }
    LaunchedEffect(pager.settledPage) { browseBackdrop.refresh() }
    val playerPalette = animatedPlayerPalette(playerVisual.palette, settings.motionDuration(450))
    PlayerSystemBars(hasPlayer && playerFullyOpen || insideCollection && browseInk == androidx.compose.ui.graphics.Color.White)
    val bottomShadows = remember { GlassShadowGroup() }
    LocalicaTheme(grayTone = settings.grayTone) {
    CompositionLocalProvider(LocalBottomShadows provides bottomShadows, LocalGlassAppearance provides settings.glass, LocalArtworkTransition provides settings.artworkTransition, LocalReducedMotion provides settings.reducedMotion, LocalSessionSelection provides session, LocalSongDragHost provides dragHost,
        LocalBrowsePresentationCache provides presentationCache,
        LocalMotionSpeed provides if (settings.fasterMotion) 2f else 4f / 3f,
        LocalModalBackdrop provides modalBackdrop,
        LocalBackdropRefresh provides browseBackdrop::refresh,
        LocalBrowseObscuredInset provides (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            (if (hasPlayer) CompactControlSize + bottomChrome + 8.dp else bottomChrome) + if (session.active) selectionHeight else 0.dp)) {
    BoxWithConstraints(Modifier.fillMaxSize().recordFrostedBackdrop(modalBackdrop).background(MaterialTheme.colorScheme.background).bottomGlassShadowHost(bottomShadows).testTag("browse-background").windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val queueSelectionMaxHeight = maxHeight / 3
        Column(Modifier.fillMaxSize()
            // The opaque full player covers this entire scene. Keep its composition,
            // scroll state and cached recording, but do not submit hidden pixels.
            .drawWithContent { if (!hasPlayer || progress.value < 1f) drawContent() }
            .recordFrostedBackdrop(browseBackdrop).background(pageBackground)
            .then(if (hasPlayer && playerCoversBrowse) Modifier.clearAndSetSemantics {} else Modifier)) {
            TabPager(pager, tabs, Modifier.weight(1f), userScrollEnabled = !expanded) { page ->
            val tab = settings.tabs[page]
            val pageRoute = navigation.stacks[tab]?.lastOrNull() ?: remember(tab) { BrowseRoute(tab) }
            val route = pageRoute
            val sortAnchor = rememberGlassAnchor()
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .then(if (route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist) && !route.search)
                    Modifier else Modifier.statusBarsPadding())) {
                if (route.search || route.collection?.kind !in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist)) Row(Modifier.zIndex(2f).fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if ((navigation.stacks[tab]?.size ?: 1) > 1) ActionIcon(R.drawable.ic_back, "Back") { back() }
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
            key(route.key) { CompositionLocalProvider(LocalBrowseArtwork provides artworkStore.scope(route.key)) { holder.SaveableStateProvider(route.key) {
                if (route.tab == MainTab.Queues && !route.search && (route.collection == null || route.collection.kind == MediaKind.Queue)) QueueScreen(queues, queueController, library,
                    playlist, playlistController, playback.connected, pins, ::pin, listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage, isPlaying = playback.isPlaying, onOpen = ::openCollection)
                else if (route.tab == MainTab.Home && !route.search && route.collection == null) {
                    PinnedHome(library, playlist, pins, { settingsCategory = "Folders to scan"; settingsOpen = true },
                        { navigation = navigation.open(BrowseRoute(MainTab.Home, globalSearch = true, search = true)) },
                        { item, songs ->
                            if (item.row != null) queueController?.tap(songs.map { it.track }, songs.indexOfFirst { it.id == item.row.id }, sourceName = "Pinned songs")
                            else openCollection(item)
                        }, ::pin, { ref, offset -> pins = PinEdits.move(pins, ref, offset); pinPreferences.write(pins) }, listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, selectionVisible = page == pager.settledPage, playlistController = playlistController, queues = queueController)
                } else BrowserScreen(library, playlist, route, { navigation = navigation.update(it) },
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
                    listBackEnabled = true, scrollToTopRequest = scrollRequests[tab] ?: 0, reducedMotion = settings.reducedMotion, sortOrigin = sortAnchor, onBack = ::back, onAppearance = { ink, background -> browseInk = ink; pageBackground = background })
            } } }
                }
            }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
            .padding(bottom = inset + 8.dp).height(NavigationPillHeight).fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(0, kotlin.math.round((80.dp + inset).toPx() * maxOf(progress.value, 1f - chromeVisibility)).toInt()) }
            .graphicsLayer { alpha = chromeVisibility }
            .then(if (insideCollection || hasPlayer && playerCoversBrowse) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().testTag("tab-pill")) {
                SlidingTabs(settings.tabs, settings.tabs[pager.targetPage.coerceIn(settings.tabs.indices)], { pager.currentPage + pager.currentPageOffsetFraction }, source = browseBackdrop, pageDragging = pager.interactionSource.collectIsDraggedAsState().value, shadowOpacity = { chromeVisibility * (1f - progress.value) }, onReselect = { tab ->
                    navigation = navigation.resetTab(tab, clearLibraryFilter = false)
                    scrollRequests = scrollRequests + (tab to ((scrollRequests[tab] ?: 0) + 1))
                }) { tab ->
                    keyboard?.hide(); focusManager.clearFocus()
                    navigationScope.launch {
                        val index = settings.tabs.indexOf(tab)
                        if (settings.reducedMotion) pager.scrollToPage(index)
                        else pager.animateScrollToPage(index, animationSpec = androidx.compose.animation.core.spring(dampingRatio = .85f, stiffness = 380f))
                    }
                }

            }
            val utilityAnchor = rememberGlassAnchor()
            SideEffect { utilityAnchor.source = browseBackdrop; utilityAnchor.face = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search), null, Modifier.size(28.dp)) }
                    GlassPillDivider()
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_more), null, Modifier.size(28.dp)) }
                }
            } }
            LiquidGlassSurface(Modifier.width(104.dp).fillMaxHeight().glassAnchor(utilityAnchor).testTag("utility-pill").bottomGlassShadow(opacity = { chromeVisibility * (1f - progress.value) }), source = browseBackdrop, shadow = false, pressFeedback = false, depthScale = RaisedGlassDepthScale) {
                utilityAnchor.captureInk()
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_search, "Search library", glass = false, iconSize = 28.dp) {
                    navigation = navigation.commitSearch().open(BrowseRoute(navigation.active, search = true, globalSearch = true))
                }
                GlassPillDivider()
                Box {
                    ActionIcon(R.drawable.ic_more, "More", glass = false, iconSize = 28.dp) { moreOpen = true }
                    GlassMenu(moreOpen, utilityAnchor, { moreOpen = false }) {
                        ActionMenuItem("Scan music", onClick = {
                            moreOpen = false; scannerOpen = true
                        })
                        ActionMenuItem("Settings", onClick = { moreOpen = false; settingsCategory = null; settingsOpen = true })
                    }
                }
                }
            }
        }
        SelectionPanelVisibility(session.active && !expanded && dragHost.state?.drag == null, Modifier.align(Alignment.BottomCenter)
            .padding(horizontal = 8.dp).padding(bottom = inset + if (hasPlayer) CompactControlSize + bottomChrome + 8.dp else bottomChrome)
            .fillMaxWidth().heightIn(max = maxHeight / 2).onSizeChanged { selectionHeight = with(density) { it.height.toDp() } }, settings.reducedMotion) {
            FrostedSurface(shape = Corners.panel) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SessionSelectionPanel(session, queueController, playlistController, playlist) }
        } }
        if (hasPlayer) PlayerSurface(player, expanded, { expanded = it }, settings, progress,
            onToggle, onSeek, onPrevious, onNext, { queueOpen = true }, { expanded = false },
            lyricsOpen, { lyricsOpen = it }, currentTrack?.let { track -> library.snapshot.tracks.firstOrNull { it.id == track.id } ?: track },
            { settings = it; preferences.write(it) }, actionRows = if (playerActions.twoRows) 2 else 1, queueProgress = queueProgress, frostedBackdrop = playerBackdrop, glassBackdrop = browseBackdrop, visual = playerVisual,
            artworkVisit = journey?.current?.token, previousArtwork = previousArtwork, nextArtwork = nextArtwork,
            bottomChrome = bottomChrome, miniForeground = miniInk, onArtworkVisit = queueController?.let { controller -> { token, complete -> controller.swipeTo(token, complete) } }) {
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
        if (hasPlayer && playerVisible) CompositionLocalProvider(LocalFrostedBackdrop provides playerBackdrop) {
        MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color.White,
            onPrimary = androidx.compose.ui.graphics.Color.Black,
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF414249), onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFFDDDEE2), secondaryContainer = androidx.compose.ui.graphics.Color(0xFF414249),
            onSecondaryContainer = androidx.compose.ui.graphics.Color.White, surfaceTint = androidx.compose.ui.graphics.Color.White,
            surface = playerPalette.bottom, surfaceContainer = playerPalette.middle, surfaceContainerLow = playerPalette.bottom,
            onSurface = androidx.compose.ui.graphics.Color.White, onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFDBDFE6))) {
        QueueSurface(queueProgress, settings, { queueOpen = it }, playerProgress = { progress.value }) {
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) { QueueScreen(queues, queueController, library,
                playlist, playlistController, playback.connected, pins, ::pin, reducedMotion = settings.reducedMotion, isPlaying = playback.isPlaying, onOpen = ::openCollection, onMinimize = { queueOpen = false }) } }
            SelectionPanelVisibility(session.active && dragHost.state?.drag == null, reducedMotion = settings.reducedMotion) {
            Column(Modifier.heightIn(max = queueSelectionMaxHeight).verticalScroll(rememberScrollState())) {
                SessionSelectionPanel(session, queueController, playlistController, playlist)
            } }
        }
        } }
        CompositionLocalProvider(LocalFrostedBackdrop provides if (queueVisible) playerBackdrop else browseBackdrop) {
            ForegroundSongDragOverlay(dragHost)
        }
    }
    androidx.activity.compose.BackHandler(expanded) { if (lyricsOpen) lyricsOpen = false else expanded = false }
    androidx.activity.compose.BackHandler(queueOpen || queueVisible) { queueOpen = false; navigationScope.launch { queueProgress.animateTo(0f, androidx.compose.animation.core.tween(settings.motionDuration(400))) } }
    androidx.activity.compose.BackHandler((queueOpen || queueVisible) && session.active) { session.clear() }
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
    if (exitPrompt) GlassAlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Close Localica?") },
        text = { Text("Playback will continue.") }, confirmButton = { ActionTextButton(onClick = { exitPrompt = false; exit() }) { Text("Close") } },
        dismissButton = { ActionTextButton(onClick = { exitPrompt = false }) { Text("Cancel") } })
    if (repeatOptions) RepeatOptionsDialog(queues.settings, { repeatOptions = false }) { queueController?.settings(it) }
    if (customizeActions) PlayerActionCustomizer(playerActions, { playerActions = it; actionPreferences.write(it) }, { customizeActions = false }, settings.reducedMotion)
    if (songInfo && indexedTrack != null) SongInformation(indexedTrack, { songInfo = false }, { playerAction(PlayerAction.Edit) }, ::playerAction)
    if (viewArt && indexedTrack != null) ArtworkViewer(indexedTrack.uri) { viewArt = false }
    if (addPlaying && indexedTrack != null) AddToPlaylistDialog(listOf(indexedTrack), playlist, playlistController, { addPlaying = false }, { addPlaying = false })
    if (playerMore) GlassDialog({ playerMore = false }, GlassPlacement.Sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            PlayerAction.entries.filterNot { it == PlayerAction.More }
                .forEach { action -> ActionTextButton(onClick = { playerMore = false; playerAction(action) }) { Text(action.label) } }
        }
    }
    QueueDialogs(queues, queueController)
    PlaybackFailureDialog(playback, playback.hasNext, onRetryPlayback) { onNext(); onRetryPlayback() }
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
