package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.home.PinEdits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BrowserScreen(library: LibraryUiState, playlists: PlaylistState, route: BrowseRoute,
    onRoute: (BrowseRoute) -> Unit, onOpen: (BrowserItem) -> Unit, onSettings: () -> Unit,
    onPlay: (List<SongRow>, Int, SongListView) -> Unit, queues: QueueController?, playlistController: PlaylistController?,
    enabled: Boolean, playerOpen: Boolean, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {},
    sorting: Boolean = false, onSortDismiss: () -> Unit = {}, queueState: QueueUiState = QueueUiState(),
    playingTrackId: String? = null, playingEntryId: String? = null,
    header: @Composable () -> Unit = {}, presentationOnly: Boolean = false, selectionVisible: Boolean = true,
    onSearchEdit: (BrowseRoute) -> Unit = onRoute, onSearchCommit: () -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false,
    libraryFilters: LibraryFilters = LibraryFilters(), onFilterSettings: () -> Unit = {},
    popupDismiss: (() -> Unit)? = null, focusRequest: Int = 0, sortOrigin: GlassAnchor? = null,
    queryDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default, onBack: () -> Unit = {},
    onAppearance: (androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color) -> Unit = { _, _ -> }) {
    var localSearchOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val searchAnchor = rememberGlassAnchor()
    val modalBackdrop = LocalModalBackdrop.current
    var searchVacancy by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0f) }
    var searchOriginalOffset by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    var localSearch by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<NavigationState, String>(
        save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
        mutableStateOf(NavigationState(route.tab, mapOf(route.tab to listOf(route.copy(search = true, key = java.util.UUID.randomUUID().toString())))))
    }
    var localFocusRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val searchHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val obscuredInset = LocalBrowseObscuredInset.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val editTags = LocalTagEdit.current
    val preferences = remember(context) { BrowsePreferences(context) }
    var style by remember(route.key, route.filter) { mutableStateOf(preferences.read(route).let { it.copy(sort = route.querySort ?: it.sort, ascending = route.queryAscending ?: it.ascending) }) }
    val filters = if (route.globalSearch) LibraryFilter.entries else libraryFilters.visible
    val showFilters = route.collection == null && (route.tab == MainTab.Library || route.globalSearch) && filters.isNotEmpty()
    val pillSlotHeight = listPillSlotHeight()
    val showSearchPill = !route.search && route.collection?.kind != MediaKind.Album && style.quickSearch && (route.collection != null || route.tab != MainTab.Library)
    val focus = remember(route.key) { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val resultsFocus = remember(route.key) { androidx.compose.ui.focus.FocusRequester() }
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(false) }
    var focusedRequest by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableIntStateOf(-1) }
    var inputCompletion by remember(route.key) { mutableIntStateOf(0) }
    fun submitSearch() {
        // Cancel a pending frame-delayed focus request before ending this input session.
        focusedOnce = true; focusedRequest = focusRequest; inputCompletion++
        onSearchCommit(); resultsFocus.requestFocus(); keyboard?.hide()
    }
    val panelReady = LocalGlassPanelReady.current
    LaunchedEffect(route.key, playerOpen, focusRequest, inputCompletion, panelReady) {
        if (!panelReady) return@LaunchedEffect
        if (route.search && (!focusedOnce || focusedRequest != focusRequest) && !playerOpen) {
            withFrameNanos { } // Wait until the newly keyed field is attached.
            focus.requestFocus(); keyboard?.show(); focusedOnce = true; focusedRequest = focusRequest
        } else if (route.search && route.queryCommitted && !playerOpen) {
            // Replacing a focused field can transfer focus to the restored field even without
            // requestFocus. Back restores results, so explicitly end that input session.
            withFrameNanos { }
            resultsFocus.requestFocus(); keyboard?.hide()
        }
    }
    LaunchedEffect(libraryFilters.visible, route.key, route.filter, selectionVisible) {
        if (selectionVisible && route.tab == MainTab.Library && route.collection == null && !route.globalSearch && route.filter != null && filters.none { it.kind == route.filter }) {
            onRoute(route.copy(filter = null))
        }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // The requested bubble reacts immediately; layout and rows publish together when
    // their query completes. Retain the previous complete list during that query.
    val presentationCache = LocalBrowsePresentationCache.current
    val cachedPresentation = remember(library.snapshot, playlists.playlists, route, style, queueState.library, libraryFilters) {
        presentationCache?.get(library.snapshot, playlists.playlists, queueState.library.queues, libraryFilters, route, style)
    }
    val queriedPresentation by produceState(cachedPresentation, library.snapshot, playlists.playlists, route, style, queueState.library, libraryFilters) {
        if (cachedPresentation != null) { value = cachedPresentation; return@produceState }
        val queried = withContext(queryDispatcher) {
            BrowseQueries.evaluate(library.snapshot, playlists.playlists, route, style.query(), queueState.library.queues, libraryFilters.allCollections).let {
                if (route.collection?.kind == MediaKind.Album) it.groupAlbumSongs(style.songGrouping) else it
            }
        }
        value = PresentedBrowse(route, style, queried).also {
            presentationCache?.put(library.snapshot, playlists.playlists, queueState.library.queues, libraryFilters, it)
        }
    }
    val presentation = cachedPresentation ?: queriedPresentation
    val loadedResult = presentation?.result
    val listReveal by androidx.compose.animation.core.animateFloatAsState(if (loadedResult == null) 0f else 1f,
        androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300), label = "Browse content reveal")
    val displayedStyle = presentation?.style ?: style
    val queryPending = presentation?.route != route || presentation?.style != style
    val sourceResult = loadedResult ?: BrowseResult()
    val artDetail = !route.search && route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist)
    val artistDetail = artDetail && route.collection?.kind != MediaKind.Album
    var selectedAlbums by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(emptyList<String>()) }
    val albums = remember(sourceResult.rows, artistDetail) {
        if (artistDetail) LibraryViews.groups(library.snapshot.copy(tracks = sourceResult.rows.map { it.track }),
            SongListView.Albums, LibraryViews.defaults(SongListView.Albums)) else emptyList()
    }
    val validAlbums = selectedAlbums.filter { id -> albums.any { it.id == id } }
    val selectedTrackIds = remember(albums, validAlbums) {
        albums.filter { it.id in validAlbums }.flatMap { it.tracks }.map { it.id }.toSet()
    }
    val result = if (artistDetail && validAlbums.isNotEmpty()) sourceResult.copy(items = sourceResult.items.filter { it.row?.track?.id in selectedTrackIds }) else sourceResult
    val heroUri = if (artistDetail) albums.sortedWith(compareByDescending<LibraryGroup> { it.tracks.mapNotNull(Track::year).maxOrNull() ?: 0 }
        .thenByDescending { it.tracks.mapNotNull(Track::addedAt).maxOrNull() ?: 0 }.thenBy { it.id }).firstOrNull()?.tracks?.firstOrNull()?.uri
        else sourceResult.rows.firstOrNull()?.track?.uri
    val hero = if (artDetail) rememberPlayerVisual(heroUri) else PlayerVisual()
    val lightCollection = collectionIsLight(hero)
    val collectionInk = if (lightCollection) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White
    SideEffect { if (selectionVisible) onAppearance(if (artDetail) collectionInk else androidx.compose.ui.graphics.Color.Black,
        if (artDetail) { if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom } else androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val collectionSource = rememberFrostedBackdrop(hero, artDetail, dark = !lightCollection)
    var collectionSort by remember { mutableStateOf(false) }
    var collectionView by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    val collectionMenuAnchor = rememberGlassAnchor()
    val gridState = rememberLazyGridState()
    val vacancyProgress = remember { androidx.compose.animation.core.Animatable(if (localSearchOpen) 1f else 0f) }
    val motionSpeed = LocalMotionSpeed.current
    LaunchedEffect(localSearchOpen, reducedMotion, motionSpeed) {
        if (reducedMotion) vacancyProgress.snapTo(if (localSearchOpen) 1f else 0f)
        else vacancyProgress.animateTo(if (localSearchOpen) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = .86f, stiffness = 420f * motionSpeed * motionSpeed))
        if (!localSearchOpen && searchOriginalOffset >= 0) {
            gridState.scrollToItem(0, searchOriginalOffset)
            searchOriginalOffset = -1; searchVacancy = 0f
        }
        searchAnchor.source?.refresh()
        modalBackdrop?.refresh()
    }
    val searchSlotHeight = (pillSlotHeight - with(density) { (searchVacancy * vacancyProgress.value.coerceIn(0f, 1f)).toDp() }).coerceAtLeast(0.dp)
    val pinchMotion = remember(route.key) { BrowsePinchMotion() }
    val pinchGraphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    DisposableEffect(pinchMotion, pinchGraphics) { onDispose {
        pinchMotion.retained.values.forEach(pinchGraphics::releaseGraphicsLayer)
        pinchMotion.retained.clear()
    } }
    var zoomAnchor by remember(route.key) { mutableStateOf<BrowseZoomAnchor?>(null) }
    LaunchedEffect(zoomAnchor, queryPending, displayedStyle) {
        val anchor = zoomAnchor ?: return@LaunchedEffect
        if (anchor.route != route) { zoomAnchor = null; return@LaunchedEffect }
        if (queryPending || displayedStyle != anchor.style) return@LaunchedEffect
        val index = result.items.indexOfFirst { it.key == anchor.key }
        try {
            if (index >= 0) {
                gridState.scrollToItem(index + 1, -anchor.top)
                // Rows above the anchor change height during reflow. Compensate until
                // their bounds settle; a fresh user scroll cancels this mutation.
                gridState.scroll {
                    val start = withFrameNanos { it }
                    do {
                        val now = withFrameNanos { it }
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }?.let {
                            scrollBy((it.offset.y - anchor.top).toFloat())
                        }
                    } while (pinchMotion.held || now - start < if (reducedMotion) 32_000_000L else 450_000_000L)
                }
            }
        } finally { if (zoomAnchor == anchor) zoomAnchor = null }
    }
    val surroundingBackdrop = LocalFrostedBackdrop.current
    val needsListBackdrop = (showFilters || showSearchPill || route.collection?.kind == MediaKind.Queue ||
        (route.collection == null && route.tab in setOf(MainTab.Library, MainTab.Playlists)))
    val listBackdrop = rememberFrostedBackdrop(presentation to surroundingBackdrop?.fallback,
        needsListBackdrop, dark = if (artDetail) !lightCollection else surroundingBackdrop?.dark == true)
    val refreshBackdrop = LocalBackdropRefresh.current
    LaunchedEffect(presentation, gridState.isScrollInProgress) {
        if (!gridState.isScrollInProgress) {
            kotlinx.coroutines.delay(150)
            refreshBackdrop()
            if (needsListBackdrop) listBackdrop.refresh()
            if (artDetail) collectionSource.refresh()
        }
    }
    val scrollScope = rememberCoroutineScope()
    val readyToScroll by rememberUpdatedState(!queryPending && loadedResult != null)
    var consumedScrollRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopRequest) {
        if (consumedScrollRequest != scrollToTopRequest) {
            localSearchOpen = false
            // Keep one job through query publication and the first layout. A readiness key can
            // restart this effect after it consumed the request but before scrolling could begin.
            snapshotFlow { readyToScroll && gridState.layoutInfo.totalItemsCount > 0 }.first { it }
            // Let the newly published rows replace the restored route's empty/old layout.
            withFrameNanos { }; withFrameNanos { }
            consumedScrollRequest = scrollToTopRequest
            gridState.easeToTop(reducedMotion)
        }
    }
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    BackHandler(selectionVisible && session.active && route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) {
        session.clear()
    }
    val selectedTracks = session.entries.map { it.row.track.id }.toSet()
    SideEffect { if (selectionVisible && !localSearchOpen) { session.visible = result.rows; session.source = route.collection } }
    val reorder = remember(route.key) { SongDragState(gridState, scrollScope) }
    val dragHost = LocalSongDragHost.current
    SideEffect {
        if (reorder.drag != null) { dragHost?.state = reorder; dragHost?.reducedMotion = reducedMotion }
        else if (dragHost?.state === reorder) dragHost.state = null
    }
    DisposableEffect(reorder, dragHost) { onDispose { if (dragHost?.state === reorder) dragHost.state = null } }
    val editableOrder = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank() &&
        route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)
    SideEffect { reorder.reconcile(result.items); reorder.obscuredBottom = with(density) { obscuredInset.toPx() } }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
    val moveSongs: (Set<String>, String?, (Boolean) -> Unit) -> Unit = { ids, before, done ->
        when (route.collection?.kind) {
            MediaKind.Queue -> if (queues != null) queues.moveBefore(route.collection.id, ids, before, done) else done(false)
            MediaKind.Playlist -> if (playlistController != null) playlistController.moveBefore(route.collection.id, ids, before, done) else done(false)
            else -> done(false)
        }
    }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<Track?>(null) }
    val pageColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = if (artDetail) pageColors.copy(onSurface = collectionInk, onSurfaceVariant = collectionInk.copy(alpha = .65f),
        primary = collectionInk, surface = if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom) else pageColors) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalFrostedBackdrop provides if (artDetail) collectionSource else surroundingBackdrop) {
    ConnectedSelectionHost(Modifier.then(if (popupDismiss != null && route.text.isBlank()) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
    Box(Modifier.then(if (popupDismiss != null && route.text.isBlank()) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
    if (artDetail) {
        val top = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "browser-notice" }?.offset?.y?.toFloat()
            ?: if (gridState.firstVisibleItemIndex > 0) -reorder.viewportWidth * 2f else 0f
        CollectionBackdrop(hero, top, Modifier.matchParentSize().recordFrostedBackdrop(collectionSource))
    }
    Column(Modifier.then(if (popupDismiss != null && route.text.isBlank()) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
        .then(if (route.search) Modifier.focusRequester(resultsFocus).focusable() else Modifier)
        .then(if (searchAnchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)) {
        if (popupDismiss == null && route.collection?.kind == MediaKind.Playlist) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
        }
        header()
        if (route.search && popupDismiss != null) TextField(route.text, { onSearchEdit(route.copy(text = it, querySort = style.sort, queryAscending = style.ascending)) },
            singleLine = true, placeholder = { Text("Search " + (route.title ?: route.tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search), null) },
            trailingIcon = { ActionIcon(R.drawable.ic_close, "Close list search", onClick = popupDismiss) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch() }),
            colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus))
        else if (route.search) GlassSearchField(route.text, { onSearchEdit(route.copy(text = it, querySort = style.sort, queryAscending = style.ascending)) },
            if (route.globalSearch) "Search library" else "Search ${route.title ?: route.tab.label}", enabled = !playerOpen,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch() }),
            trailingIcon = { ActionIcon(R.drawable.ic_forward, "Submit search", glass = false) { submitSearch() } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), fieldModifier = Modifier.focusRequester(focus))

        LookaheadScope {
        if (popupDismiss == null || route.text.isNotBlank()) Box(Modifier.weight(1f).onGloballyPositioned { reorder.viewportOrigin = it.positionInRoot() }.onSizeChanged { reorder.viewportHeight = it.height; reorder.viewportWidth = it.width }
            .pinchRetainedOverlay(pinchMotion, gridState, result.items.map { it.key })
            .songDrag(reorder, editableOrder && enabled && !queryPending, route.collection, session, reducedMotion, displayedStyle.presentation == Presentation.List, moveSongs)) {
            val columns = galleryColumns(displayedStyle.presentation, with(density) { reorder.viewportWidth.toDp().value }, displayedStyle.itemsPerRow)
            val denseGallery = displayedStyle.presentation != Presentation.List && columns >= 3
            if (loadedResult == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = if (showFilters || showSearchPill) pillSlotHeight else 0.dp).testTag("browse-loading"))
            else LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().graphicsLayer { alpha = listReveal }
                .semantics { stateDescription = if (displayedStyle.presentation == Presentation.List) "List"
                    else if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns" }
                .pinchToChangeView(enabled && !playerOpen && !queryPending && reorder.drag == null && route.collection?.kind != MediaKind.Queue,
                    onProgress = { value -> if (pinchMotion.held && !reducedMotion) scrollScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pinchMotion.progress.snapTo(value) } },
                    onRelease = {
                        pinchMotion.held = false
                        scrollScope.launch {
                            val commit = pinchMotion.progress.value >= .35f
                            val destination = if (commit) pinchMotion.target else pinchMotion.source
                            if (reducedMotion) pinchMotion.progress.snapTo(if (commit) 1f else 0f)
                            else pinchMotion.progress.animateTo(if (commit) 1f else 0f, androidx.compose.animation.core.tween(220))
                            if (destination != null) {
                                style = destination
                                preferences.write(route, destination, preferences.overridden(route))
                                pinchMotion.focusKey?.let { zoomAnchor = BrowseZoomAnchor(route, destination, it, pinchMotion.focusTop) }
                            }
                            // Keep retained drawings through the source-layout handoff.
                            withFrameNanos { }; withFrameNanos { }
                            pinchMotion.source = null
                        }
                    }) { zoomIn, midpoint ->
                    val next = displayedStyle.zoomStep(zoomIn, with(density) { reorder.viewportWidth.toDp().value },
                        albumSongs = route.collection?.kind == MediaKind.Album)
                    if (next != displayedStyle) {
                        val visible = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }
                        pinchMotion.source = displayedStyle
                        pinchMotion.target = next
                        pinchMotion.targetColumns = galleryColumns(next.presentation, with(density) { reorder.viewportWidth.toDp().value }, next.itemsPerRow)
                        pinchMotion.held = true
                        pinchMotion.viewportWidth = reorder.viewportWidth.toFloat()
                        pinchMotion.sourceColumns = galleryColumns(displayedStyle.presentation, with(density) { reorder.viewportWidth.toDp().value }, displayedStyle.itemsPerRow)
                        pinchMotion.bounds = visible.associate { it.key to androidx.compose.ui.geometry.Rect(it.offset.x.toFloat(), it.offset.y.toFloat(),
                            (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()) }
                        visible.firstOrNull()?.let { first ->
                            pinchMotion.anchorIndex = first.index - 1; pinchMotion.anchorTop = first.offset.y.toFloat()
                            pinchMotion.rowHeight = first.size.height.toFloat()
                        }
                        scrollScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pinchMotion.progress.snapTo(if (reducedMotion) 1f else 0f) }
                        val nearest = gridState.layoutInfo.visibleItemsInfo.filter { it.key != "browser-notice" }.minByOrNull {
                            val x = it.offset.x + it.size.width / 2f - midpoint.x
                            val y = it.offset.y + it.size.height / 2f - midpoint.y
                            x * x + y * y
                        }
                        if (nearest != null) {
                            pinchMotion.focusKey = nearest.key; pinchMotion.focusTop = nearest.offset.y
                            zoomAnchor = BrowseZoomAnchor(route, next, nearest.key, nearest.offset.y)
                        }
                        style = next
                    }
                }
                .then(if (needsListBackdrop) Modifier.recordFrostedBackdrop(listBackdrop, if (artDetail) collectionSource else surroundingBackdrop)
                    .then(if (surroundingBackdrop == null && !artDetail) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier) else Modifier)
                .then(if ((showSearchPill || showFilters) && !artDetail) Modifier.listTopFade() else Modifier).testTag("browser-list"), contentPadding = PaddingValues(bottom = if (popupDismiss != null) 16.dp else maxOf(80.dp, obscuredInset + 16.dp))) {
                item(key = "browser-notice", contentType = "notice", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        if (artDetail) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides collectionSource) {
                                CollectionHeader(route.title.orEmpty(), if (artistDetail) null else sourceResult.rows.firstOrNull()?.track?.let { it.albumArtist ?: it.artist },
                                    hero, artistDetail, enabled && result.rows.isNotEmpty(),
                                    onPlay = { onPlay(result.rows, 0, result.scope) }, onShuffle = {
                                        if (queues != null) queues.randomizedQueue(result.rows.map { it.track }, route.title ?: "Collection", result.rows.map { it.sourcePlaylistId })
                                        else onPlay(result.rows.shuffled(), 0, result.scope)
                                    })
                                if (artistDetail) ArtistAlbumChoices(albums, validAlbums) { selectedAlbums = it }
                                if (artistDetail && showSearchPill) Box(Modifier.fillMaxWidth().height(searchSlotHeight).clipToBounds()) {
                                    ListSearchPill("Search " + (route.title ?: route.tab.label), {
                                        searchOriginalOffset = if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset else -1
                                        searchVacancy = with(density) { pillSlotHeight.toPx() }
                                        localFocusRequest++; localSearchOpen = true
                                    }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
                                }
                            }
                        }
                        if (!artDetail && (showSearchPill || showFilters)) Spacer(Modifier.height(if (showSearchPill) searchSlotHeight else pillSlotHeight))
                        LibraryNotice(library)
                        playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue) TextButton(onClick = onSettings) { Text("Add music folders") }
                        if (result.items.isEmpty() && !library.loading && !queryPending) Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_queue),
                                if (route.search) "No matching items" else "Empty list", Modifier.size(72.dp),
                                tint = LocalContentColor.current.copy(alpha = .13f))
                        }
                    }
                }
                items(reorder.rendered(result.items), key = { it?.key ?: DropGapKey },
                    contentType = { if (it == null) "gap" else it.ref.kind.name }) { entry ->
                    if (entry == null) {
                        val height = with(androidx.compose.ui.platform.LocalDensity.current) { (reorder.drag?.cardSize?.height ?: 0f).toDp() }
                        Box(Modifier.fillMaxWidth().height(height).testTag("drop-gap"))
                        return@items
                    }
                    val item = entry
                    val current = route.collection?.kind == MediaKind.Queue && item.row != null && item.row.id == playingEntryId
                    val selectedCount = if (item.row != null) { if (session.contains(route.collection, item.row.id)) 1 else 0 }
                        else item.songs.count { it.track.id in selectedTracks }
                    val partiallySelected = item.row == null && selectedCount > 0 && selectedCount < item.songs.size
                    Column(Modifier.then(if (editableOrder) Modifier.animateItem(placementSpec = androidx.compose.animation.core.tween(if (reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null) else Modifier)
                        .graphicsLayer { alpha = reorder.drag?.takeIf { it.dropping && item.key in it.keys }?.handoff?.value ?: 1f }) {
                        val itemMenu: @Composable (Boolean) -> Unit = { showButton ->
                            val menuAnchor = rememberGlassAnchor()
                            Box {
                                if (showButton) ActionIcon(if (item.row != null) R.drawable.ic_ellipsis else R.drawable.ic_more, "Actions for " + item.title, anchor = menuAnchor, quiet = item.row != null) { contextMenu = item.key }
                                GlassMenu(contextMenu == item.key, menuAnchor, { contextMenu = null }) {
                                    if (item.row != null) {
                                        ActionMenuItem("Information", onClick = { contextMenu = null; info = item.row.track })
                                        if (editTags != null) ActionMenuItem("Edit tags", onClick = { contextMenu = null; editTags(listOf(item.row.track)) {} })
                                        ActionMenuItem("Add to playlist", onClick = { contextMenu = null; addingSong = item.row.track })
                                        SongTap.entries.forEach { action -> ActionMenuItem(action.label(), onClick = {
                                            contextMenu = null; queues?.act(listOf(item.row.track), action, listOf(item.row.sourcePlaylistId))
                                        }) }
                                    }
                                    if (item.row == null) ActionMenuItem("Select all songs", onClick = {
                                        val contents = route.copy(collection = item.ref, globalSearch = false, search = false, text = "")
                                        val songs = BrowseQueries.evaluate(library.snapshot, playlists.playlists, contents,
                                            preferences.read(contents).query(), queueState.library.queues).rows
                                        session.select(item.ref, songs); contextMenu = null
                                    })
                                    if (item.ref.kind in PinEdits.supported) DropdownMenuItem(text = { Text(if (item.ref in pins) "Unpin" else "Pin to Home") },
                                        onClick = { onPin(item.ref); contextMenu = null })
                                    if (route.collection?.kind == MediaKind.Playlist) {
                                        val index = result.rows.indexOfFirst { it.id == item.key }
                                        val manual = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank()
                                        ActionMenuItem("Move up", enabled = manual && index > 0, onClick = {
                                            playlistController?.move(route.collection.id, item.key, -1) { Unit }; contextMenu = null
                                        })
                                        ActionMenuItem("Move down", enabled = manual && index < result.rows.lastIndex, onClick = {
                                            playlistController?.move(route.collection.id, item.key, 1) { if (it) session.clear() }; contextMenu = null
                                        })
                                    }
                                    if (route.collection?.kind == MediaKind.Queue) {
                                        val index = result.rows.indexOfFirst { it.id == item.key }
                                        val manual = style.sort == TrackSort.Manual && style.ascending && route.text.isBlank()
                                        ActionMenuItem("Move up", enabled = manual && index > 0,
                                            onClick = { queues?.moveEntry(item.key, -1, route.collection?.id) { if (it) session.clear() }; contextMenu = null })
                                        ActionMenuItem("Move down", enabled = manual && index < result.rows.lastIndex,
                                            onClick = { queues?.moveEntry(item.key, 1, route.collection?.id) { if (it) session.clear() }; contextMenu = null })
                                    }
                                }
                            }
                        }
                        item.groupHeading?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                        Column(Modifier.then(if (pinchMotion.source == null) Modifier.animateBounds(this@LookaheadScope,
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            boundsTransform = { _, _ -> androidx.compose.animation.core.tween(if (reducedMotion || editableOrder) 0 else 350) })
                            else Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
                                val position = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.key }?.offset
                                if (position != null) {
                                    val old = pinchMotion.sourceBounds(item.key, result.items.indexOf(item))
                                    translationX = (old.left - position.x) * (1f - pinchMotion.progress.value)
                                    translationY = (old.top - position.y) * (1f - pinchMotion.progress.value)
                                }
                            }).then(if (pinchMotion.source == null) Modifier.clip(Corners.card) else Modifier).currentSongHighlight(current, MaterialTheme.colorScheme.primary).selectionOutline(selectedCount > 0, partiallySelected, MaterialTheme.colorScheme.primary)
                            .retainPinchItem(item.key, pinchMotion, pinchGraphics).testTag("media-${item.key}").combinedClickable(
                            enabled = !queryPending && (item.row == null || enabled),
                            onClick = {
                                if (item.row == null) { onSearchCommit(); onOpen(item) }
                                else if (session.active) session.toggle(route.collection, item.row)
                                else { onSearchCommit(); onPlay(result.rows, result.rows.indexOfFirst { it.id == item.row.id }, result.scope) }
                            }, onLongClickLabel = if (item.row != null) "Select song and show actions" else "Collection actions",
                            onLongClick = {
                                if (item.row != null) session.select(route.collection, listOf(item.row))
                                else contextMenu = item.key
                            }
                        ).padding(horizontal = if (denseGallery) 4.dp else 12.dp, vertical = 8.dp)) {
                            BrowseItemLayout(item, displayedStyle, denseGallery, this@LookaheadScope, reducedMotion, itemMenu,
                                trackNumber = if (route.collection?.kind == MediaKind.Album) item.row?.track?.trackNumber?.toString() ?: (result.items.indexOf(item) + 1).toString() else null, pinch = pinchMotion)
                        }

                    }
                }
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
            if (showFilters) Box(Modifier.align(Alignment.TopStart)) {
                LibraryFilterBubbles(filters, route.filter, reducedMotion,
                    { onRoute(route.copy(filter = it)) }, onFilterSettings)
            }
            if (showSearchPill && !artDetail) ListSearchPill("Search "+ (route.title ?: route.tab.label), {
                if (gridState.firstVisibleItemIndex == 0) {
                    searchOriginalOffset = gridState.firstVisibleItemScrollOffset
                    searchVacancy = (with(density) { pillSlotHeight.toPx() } - searchOriginalOffset).coerceAtLeast(0f)
                }
                localFocusRequest++; localSearchOpen = true
            }, Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
            if (dragHost == null) SongDragOverlay(reorder, reducedMotion)
            val playingIndex = result.items.indexOfFirst { it.row?.id == playingEntryId }.takeIf { playingEntryId != null && it >= 0 }?.plus(1)
            val visibleTop = with(density) { (if (showSearchPill || showFilters) pillSlotHeight else 0.dp).toPx() }
            val hiddenBottom = with(density) { obscuredInset.toPx() }
            val returnDirection by remember(playingIndex, visibleTop, hiddenBottom) { derivedStateOf {
                val layout = gridState.layoutInfo
                val current = layout.visibleItemsInfo.firstOrNull { it.index == playingIndex }
                when {
                    playingIndex == null -> 0
                    current == null -> if (playingIndex < gridState.firstVisibleItemIndex) -1 else 1
                    current.offset.y + current.size.height <= visibleTop -> -1
                    current.offset.y >= layout.viewportEndOffset - hiddenBottom -> 1
                    else -> 0
                }
            } }
            val showCurrent = returnDirection != 0
            androidx.compose.animation.AnimatedVisibility(showCurrent && !session.active && reorder.drag == null, Modifier.align(Alignment.BottomCenter).padding(bottom = obscuredInset + 16.dp),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(if (reducedMotion) 0 else 300)) { it }) {
                CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
                    ReturnToPositionButton(up = returnDirection < 0, label = "Current song", visible = showCurrent && !session.active && reorder.drag == null) {
                        playingIndex?.let { scrollScope.launch { gridState.easeToItem(it, reducedMotion, visibleTop) } }
                    }
                }
            }
            if (loadedResult != null) BrowseScrollbar(gridState, result.items, displayedStyle.sort, Modifier.align(Alignment.CenterEnd).padding(bottom = obscuredInset))
            if (route.tab in setOf(MainTab.Library, MainTab.Playlists) && route.collection == null && !route.search)
                PlaylistCreationButton(library.snapshot, playlists, playlistController, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset).testTag("playlist-add-module"))
            }
        }
        }
        if (LocalSessionSelection.current == null) SelectionPanelVisibility(session.active && reorder.drag == null, reducedMotion = reducedMotion) {
            SessionSelectionPanel(session, queues, playlistController, playlists)
        }
    }

    if (artDetail) {
        val header = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "browser-notice" }
        val coverWidth = reorder.viewportWidth.toFloat().coerceAtLeast(1f)
        val scrollFade = if (header == null && gridState.firstVisibleItemIndex > 0) 1f
            else ((-(header?.offset?.y ?: 0) - coverWidth * .65f) / (coverWidth * .35f)).coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(100.dp)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.surface.copy(alpha = .7f * scrollFade), androidx.compose.ui.graphics.Color.Transparent))))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionIcon(R.drawable.ic_back, "Back", onClick = onBack)
            Text(route.title.orEmpty(), Modifier.weight(1f).graphicsLayer { alpha = scrollFade }
                .then(if (scrollFade < .1f) Modifier.clearAndSetSemantics {} else Modifier), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Box {
                ActionIcon(R.drawable.ic_ellipsis, "Collection actions", anchor = collectionMenuAnchor) { collectionMenu = true }
                GlassMenu(collectionMenu, collectionMenuAnchor, { collectionMenu = false }) {
                    ActionMenuItem("Sort") { collectionMenu = false; collectionSort = true }
                    ActionMenuItem("View options") { collectionMenu = false; collectionView = true; collectionSort = true }
                    ActionMenuItem("Select all songs") { collectionMenu = false; session.select(route.collection, result.rows) }
                }
            }
        }
    }
    } } } }

    if (showSearchPill || localSearchOpen || searchAnchor.hidden) {
        fun closeSearch() { localSearchOpen = false }
        fun searchBack() {
            val previous = localSearch.back(false)
            if (previous == null) closeSearch() else localSearch = previous
        }
        ListSearchWindow(localSearchOpen && !playerOpen, searchAnchor, localSearch.route.text.isNotBlank(), ::searchBack) {
            CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) {
                val searchRoute = localSearch.route
                key(searchRoute.key) { searchHolder.SaveableStateProvider(searchRoute.key) {
                    BrowserScreen(library, playlists, searchRoute, { localSearch = localSearch.update(it) }, onOpen, onSettings,
                        onPlay, queues, playlistController, enabled, playerOpen, pins, onPin, queueState = queueState,
                        playingTrackId = playingTrackId, playingEntryId = playingEntryId, reducedMotion = reducedMotion,
                        onSearchEdit = { localSearch = localSearch.editSearch(it) },
                        onSearchCommit = { localSearch = localSearch.commitSearch() },
                        libraryFilters = libraryFilters, popupDismiss = ::closeSearch, focusRequest = localFocusRequest)
                } }
            }
        }
    }
    addingSong?.let { song -> AddToPlaylistDialog(listOf(song), playlists, playlistController, { addingSong = null }, { addingSong = null }) }
    info?.let { song -> SongInformation(song, { info = null }, { editTags?.invoke(listOf(song)) {} }) { action ->
        val tab = when (action) { PlayerAction.Album -> MainTab.Albums; PlayerAction.Artist -> MainTab.Artists
            PlayerAction.AlbumArtist -> MainTab.AlbumArtists; else -> MainTab.Folders }
        BrowseQueries.evaluate(library.snapshot, playlists.playlists, BrowseRoute(tab)).items
            .firstOrNull { item -> item.songs.any { it.track.id == song.id } }?.let { info = null; onOpen(it) }
    } }
    fun dismissSorting() { collectionSort = false; collectionView = false; onSortDismiss() }
    if (sorting || collectionSort) CompositionLocalProvider(LocalGlassOrigin provides sortOrigin) { BrowseSortDialog(route, style, preferences.overridden(route), ::dismissSorting, { value, override ->
        preferences.write(route, value, override); style = value
        if (route.search) onRoute(route.copy(querySort = value.sort, queryAscending = value.ascending))
        dismissSorting()
    }, { preferences.reset(route); style = preferences.read(route); dismissSorting() }, presentationOnly || collectionView,
        onRandomize = if (route.collection?.kind == MediaKind.Queue && queues != null) ({
            queues.randomize(route.collection.id) { saved -> if (saved) {
                style = style.copy(sort = TrackSort.Manual, ascending = true)
                preferences.write(route, style, true)
                dismissSorting()
            } }
        }) else null, reducedMotion = reducedMotion) }
    if (renaming) {
        var name by remember { mutableStateOf(playlists.playlists.firstOrNull { it.id == route.collection?.id }?.name.orEmpty()) }
        GlassAlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename playlist") }, text = {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            route.collection?.let { playlistController?.rename(it.id, name); onRoute(route.copy(title = name.trim())) }; renaming = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (deleting) GlassAlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("Audio files and queues are kept.") }, confirmButton = { TextButton(onClick = {
            route.collection?.let { playlistController?.delete(it.id) }; deleting = false
            onRoute(route.copy(collection = null, title = null))
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
