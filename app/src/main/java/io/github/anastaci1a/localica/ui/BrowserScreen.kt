package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollByOffset
import androidx.compose.ui.semantics.scrollToIndex
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun BrowserScreen(library: LibraryUiState, playlists: PlaylistState, route: BrowseRoute,
    onRoute: (BrowseRoute) -> Unit, onOpen: (BrowserItem) -> Unit, onSettings: () -> Unit,
    onPlay: (List<SongRow>, Int, SongListView) -> Unit, queues: QueueController?, playlistController: PlaylistController?,
    enabled: Boolean, playerOpen: Boolean, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {},
    sorting: Boolean = false, onSortDismiss: () -> Unit = {}, queueState: QueueUiState = QueueUiState(),
    playingTrackId: String? = null, playingEntryId: String? = null,
    header: @Composable () -> Unit = {}, presentationOnly: Boolean = false, selectionVisible: Boolean = true,
    onSearchEdit: (BrowseRoute) -> Unit = onRoute, onSearchCommit: () -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false,
    listBackEnabled: Boolean = false,
    libraryFilters: LibraryFilters = LibraryFilters(), onFilterSettings: () -> Unit = {},
    popupDismiss: (() -> Unit)? = null, scopedEditor: ScopedSearchEditorSession? = null, focusRequest: Int = 0, sortOrigin: GlassAnchor? = null,
    queryDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default, onBack: () -> Unit = {},
    onAppearance: (androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color) -> Unit = { _, _ -> }) {
    val searchEditTrace = LocalSearchEditTrace.current
    if (searchEditTrace != null && route.search) {
        DisposableEffect(route.key) {
            searchEditTrace("editor composition attach key=${route.key.hashCode()} length=${route.text.length} committed=${route.queryCommitted}")
            onDispose { searchEditTrace("editor composition dispose key=${route.key.hashCode()}") }
        }
        SideEffect { searchEditTrace("editor composition render key=${route.key.hashCode()} length=${route.text.length} committed=${route.queryCommitted}") }
    }
    var localSearchOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val searchAnchor = rememberGlassAnchor()
    val modalBackdrop = LocalModalBackdrop.current
    var searchVacancy by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0f) }
    var searchOriginalOffset by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    fun freshSearch() = NavigationState(route.tab, mapOf(route.tab to listOf(route.copy(search = true,
        text = "", queryCommitted = false, key = java.util.UUID.randomUUID().toString()))))
    var localSearch by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<NavigationState, String>(
        save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
        mutableStateOf(freshSearch())
    }
    var localFocusRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val searchHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var editorGeneration by remember { mutableIntStateOf(0) }
    val searchEditor = remember(editorGeneration) { ScopedSearchEditorSession() }
    DisposableEffect(searchEditor) { onDispose { searchEditor.retire() } }
    fun retireSearchEditor() { searchEditor.retire(); editorGeneration++ }
    val obscuredInset = LocalBrowseObscuredInset.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val editTags = LocalTagEdit.current
    val sorts = rememberBrowseSortSnapshot()
    val preferences = remember(context) { BrowsePreferences(context) }
    var inheritedInformation by remember(preferences) { mutableStateOf(preferences.informationStyles()) }
    DisposableEffect(preferences) {
        val unsubscribe = preferences.observeInformation { inheritedInformation = it }
        onDispose(unsubscribe)
    }
    val currentRoute by rememberUpdatedState(route)
    var style by remember(route.key, route.filter) { mutableStateOf(preferences.read(route).let { it.copy(sort = route.querySort ?: it.sort, ascending = route.queryAscending ?: it.ascending) }) }
    DisposableEffect(preferences, route.key, route.filter) {
        val unsubscribe = preferences.observe(route) { updated ->
            style = updated.copy(sort = currentRoute.querySort ?: updated.sort, ascending = currentRoute.queryAscending ?: updated.ascending)
        }
        onDispose(unsubscribe)
    }
    fun editSearchText(text: String) {
        searchEditTrace?.invoke("editor value key=${route.key.hashCode()} oldLength=${route.text.length} newLength=${text.length} committed=${route.queryCommitted}")
        onSearchEdit(route.copy(text = text, querySort = style.sort, queryAscending = style.ascending))
    }
    val filters = if (route.globalSearch) LibraryFilter.entries else libraryFilters.visible
    val showFilters = route.collection == null && (route.tab == MainTab.Library || route.globalSearch) && filters.isNotEmpty()
    val pillSlotHeight = listPillSlotHeight()
    val showSearchPill = !route.search && route.collection?.kind != MediaKind.Album && style.quickSearch && (route.collection != null || route.tab != MainTab.Library)
    val routeFocus = remember(route.key) { androidx.compose.ui.focus.FocusRequester() }
    val focus = scopedEditor?.focus ?: routeFocus
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val resultsFocus = remember(route.key) { androidx.compose.ui.focus.FocusRequester() }
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(false) }
    var focusedRequest by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableIntStateOf(-1) }
    var inputCompletion by remember(route.key) { mutableIntStateOf(0) }
    fun submitSearch() {
        if (scopedEditor != null && !scopedEditor.active) return
        // Cancel a pending frame-delayed focus request before ending this input session.
        focusedOnce = true; focusedRequest = focusRequest; inputCompletion++
        onSearchCommit(); resultsFocus.requestFocus(); keyboard?.hide()
    }
    val panelReady = LocalGlassPanelReady.current
    val reportSearchOpening = LocalSearchOpeningReady.current
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val keyboardClosing = WindowInsets.imeAnimationTarget.getBottom(density) == 0 &&
        (WindowInsets.ime.getBottom(density) > 0 || WindowInsets.imeAnimationSource.getBottom(density) > 0)
    var restoreResultsFocus by remember(route.key) { mutableStateOf(route.search && route.queryCommitted) }
    LaunchedEffect(route.key, playerOpen, focusRequest, inputCompletion, panelReady, windowFocused, keyboardClosing, reportSearchOpening, scopedEditor?.focused) {
        // Dialog attachment and IME focus happen outside Compose's animation clock.
        // Do not consume an autofocus request while the returning window still owns input.
        if (!panelReady || !windowFocused || keyboardClosing) return@LaunchedEffect
        if (route.search && (!focusedOnce || focusedRequest != focusRequest) && !playerOpen) {
            withFrameNanos { } // Wait until the field is attached.
            if (scopedEditor != null && !scopedEditor.active) return@LaunchedEffect
            focus.requestFocus(); keyboard?.show(); focusedOnce = true; focusedRequest = focusRequest
            restoreResultsFocus = false
            reportSearchOpening?.invoke(true)
        } else if (restoreResultsFocus && !playerOpen) {
            // Replacing a focused field can transfer focus to the restored field even without
            // requestFocus. Back restores results, so explicitly end that input session.
            withFrameNanos { }
            if (scopedEditor != null && !scopedEditor.active) return@LaunchedEffect
            resultsFocus.requestFocus(); keyboard?.hide()
            restoreResultsFocus = false
            reportSearchOpening?.invoke(false)
        } else if (route.search && !playerOpen) reportSearchOpening?.invoke(scopedEditor?.focused == true)
    }
    LaunchedEffect(libraryFilters.visible, route.key, route.filter, selectionVisible) {
        if (selectionVisible && route.tab == MainTab.Library && route.collection == null && !route.globalSearch && route.filter != null && filters.none { it.kind == route.filter }) {
            onRoute(route.copy(filter = null))
        }
    }
    val browse = rememberBrowsePresentation(library.snapshot, playlists.playlists, queueState.library.queues,
        libraryFilters, route, style, reducedMotion, queryDispatcher, sorts,
        sourcesReady = browseCatalogReady(route, library.loading, playlists.loading, queueState.loading, libraryFilters),
        animateArrival = route.search || route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist))
    val presentation = browse.current
    val loadedResult = presentation?.result
    val displayedStyle = presentation?.style ?: style
    val queryPending = browse.pending
    val sourceResult = loadedResult ?: BrowseResult()
    val artDetail = !route.search && route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist)
    val seededCollection = LocalBrowseArtwork.current?.collection?.takeIf { it.ref == route.collection }
    val entrySweep = remember(route.key, route.filter, route.search && route.text.isNotBlank()) {
        androidx.compose.animation.core.Animatable(if (browse.cached && !route.search) 1f else 0f)
    }
    LaunchedEffect(entrySweep, loadedResult != null, queryPending, reducedMotion) {
        if (loadedResult != null && !queryPending && entrySweep.value < 1f) {
            if (reducedMotion) entrySweep.snapTo(1f)
            else entrySweep.animateTo(1f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    val artistDetail = artDetail && route.collection?.kind != MediaKind.Album
    var selectedAlbums by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(emptyList<String>()) }
    val albums = remember(sourceResult.rows, artistDetail, sorts) {
        if (artistDetail) LibraryViews.groups(library.snapshot.copy(tracks = sourceResult.rows.map { it.track }),
            SongListView.Albums, LibraryViews.defaults(SongListView.Albums)).map { album ->
                album.copy(tracks = sorts.rows(MediaRef(MediaKind.Album, album.id), album.tracks.map { SongRow(it.id, it) }).map { it.track })
            } else emptyList()
    }
    val validAlbums = remember(albums, selectedAlbums) { selectedAlbums.filter { id -> albums.any { it.id == id } } }
    val selectedTrackIds = remember(albums, validAlbums) {
        albums.filter { it.id in validAlbums }.flatMap { it.tracks }.map { it.id }.toSet()
    }
    val result = remember(sourceResult, artistDetail, selectedTrackIds) {
        if (artistDetail && selectedTrackIds.isNotEmpty()) sourceResult.copy(items = sourceResult.items.filter { it.row?.track?.id in selectedTrackIds }) else sourceResult
    }
    val itemIndices = remember(result.items) { result.items.withIndex().associate { it.value.key to it.index } }
    val itemKeys = remember(result.items) { result.items.map { it.key } }
    // The artist page displays songs beneath album-filter chips; its hero follows that displayed list.
    // Entry artwork is only a loading seed, never a permanent override of a newly sorted result.
    val heroUri = collectionHeroUri(loadedResult?.let { result }, seededCollection)
    val hero = if (artDetail) rememberCollectionVisual(heroUri) else PlayerVisual()
    val lightCollection = collectionIsLight(hero)
    val collectionInk = if (lightCollection) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White
    SideEffect { if (selectionVisible) onAppearance(if (artDetail) collectionInk else androidx.compose.ui.graphics.Color.Black,
        if (artDetail) { if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom } else androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val collectionSource = rememberFrostedBackdrop(hero, artDetail, dark = !lightCollection)
    var collectionView by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    val collectionMenuAnchor = rememberGlassAnchor()
    val gridState = rememberLazyGridState()
    val touchExploration = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true }
    val playbackFollow = androidx.compose.runtime.saveable.rememberSaveable(route.key, route.collection,
        saver = androidx.compose.runtime.saveable.Saver<QueuePlaybackFollowCoordinator, Boolean>(
            save = { it.attached }, restore = { QueuePlaybackFollowCoordinator(it) })) {
        QueuePlaybackFollowCoordinator(!touchExploration)
    }
    val scrollOwnership = remember { BrowseScrollOwnership() }
    val modalOwner = LocalModalGestures.current
    val modalBlocked = modalOwner?.blocked == true
    var listContact by remember { mutableStateOf(false) }
    val orderScroll = remember { BrowseOrderScrollCoordinator() }
    fun dismissSorting() {
        // Retire pending receipts before visibility changes; disposal can happen later.
        orderScroll.cancelPending()
        collectionMenu = false
        onSortDismiss()
    }
    val orderOwner = remember(route, selectionVisible, playerOpen) { Any() }
    val currentOrderOwner by rememberUpdatedState(orderOwner)
    DisposableEffect(orderOwner) { onDispose { orderScroll.cancel() } }
    val mutableQueue = queueState.library.queues.firstOrNull { route.collection?.kind == MediaKind.Queue && it.id == route.collection.id }
    val mutablePlaylist = playlists.playlists.firstOrNull { route.collection?.kind == MediaKind.Playlist && it.id == route.collection.id }
    val savedOrder = mutableQueue?.order ?: mutablePlaylist?.order
    val physicalOrder = mutableQueue?.entries?.map { it.id } ?: mutablePlaylist?.entries?.map { it.id }
    fun commitStyle(value: BrowseStyle) {
        orderScroll.cancel()
        style = value
        if (route.search) onRoute(route.copy(querySort = value.sort, queryAscending = value.ascending))
        dismissSorting()
    }
    // Each origin owns its opening tab; an inactive origin must not override it.
    @Composable fun sortingPanel(initialView: Boolean) {
        // Closing a pending dialog invalidates its callback, but not an already accepted follow.
        val lifetime = remember { booleanArrayOf(true) }
        DisposableEffect(Unit) {
            orderScroll.cancel()
            onDispose { lifetime[0] = false }
        }
        fun orderAction(followCurrent: Boolean, done: (OrderCommit?) -> Unit,
            action: ((OrderCommit?) -> Unit) -> Unit) {
            val token = orderScroll.begin(orderOwner, route)
            playbackFollow.cancel()
            action { receipt ->
                if (lifetime[0]) orderScroll.complete(token, currentOrderOwner, currentRoute,
                    receipt?.changed == true,
                    receipt?.let { BrowseOrderGeneration(token.route.collection!!, it.revision, it.order) },
                    receipt?.currentEntryId.takeIf { followCurrent }, followCurrent = followCurrent)
                done(receipt)
            }
        }
        val mutableRows = remember(mutableQueue?.entries, mutablePlaylist?.entries) {
            mutableQueue?.entries?.map { SongRow(it.id, it.track) } ?: mutablePlaylist?.entries
        }
        BrowseSortDialog(route, style, preferences.overridden(route), ::dismissSorting, { value, override ->
        preferences.write(route, value, override)
        commitStyle(value)
    }, { preferences.reset(route); commitStyle(preferences.read(route)) }, initialView,
        onRandomize = when {
            mutableQueue != null && queues != null -> ({ done -> orderAction(false, { done(it != null) }) { queues.randomizeOrder(mutableQueue.id, it) } })
            mutablePlaylist != null && playlistController != null -> ({ done -> orderAction(false, { done(it != null) }) { playlistController.randomizeOrder(mutablePlaylist.id, it) } })
            else -> null
        }, onReverseOrder = when {
            mutableQueue != null && queues != null -> ({ done -> orderAction(true, { done(it?.order) }) { queues.reverseOrder(mutableQueue.id, it) } })
            mutablePlaylist != null && playlistController != null -> ({ done -> orderAction(false, { done(it?.order) }) { playlistController.reverseOrder(mutablePlaylist.id, it) } })
            else -> null
        }, mutableOrder = savedOrder, manualNeedsRestore = savedOrder != null && savedOrder.manual != physicalOrder,
        mutableOrderRows = mutableRows, orderSnapshot = library.snapshot,
        mutableOrderIds = physicalOrder, onRevertOrder = when {
            mutableQueue != null && queues != null -> ({ checkpoint, done -> orderAction(true, { done(it != null) }) { queues.revertOrderWithCommit(mutableQueue.id, checkpoint, it) } })
            mutablePlaylist != null && playlistController != null -> ({ checkpoint, done -> orderScroll.cancel(); playlistController.revertOrder(mutablePlaylist.id, checkpoint, done) })
            else -> null
        },
        onApplySort = when {
            mutableQueue != null && queues != null -> ({ method, ascending, done -> orderAction(true, { done(it != null) }) { queues.applySortOrder(mutableQueue.id, method, ascending, it) } })
            mutablePlaylist != null && playlistController != null -> ({ method, ascending, done -> orderScroll.cancel(); playlistController.applySort(mutablePlaylist.id, method, ascending, done) })
            else -> null
        }) }

    val vacancyProgress = remember { androidx.compose.animation.core.Animatable(if (localSearchOpen) 1f else 0f) }
    val motionSpeed = LocalMotionSpeed.current
    LaunchedEffect(localSearchOpen, reducedMotion, motionSpeed) {
        if (reducedMotion) vacancyProgress.snapTo(if (localSearchOpen) 1f else 0f)
        else vacancyProgress.animateTo(if (localSearchOpen) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = .86f, stiffness = 420f * motionSpeed * motionSpeed))
        if (!localSearchOpen && searchOriginalOffset >= 0) {
            scrollOwnership.during { gridState.scrollToItem(0, searchOriginalOffset) }
            searchOriginalOffset = -1; searchVacancy = 0f
        }
        searchAnchor.source?.refresh()
        modalBackdrop?.refresh()
    }
    val searchSlotHeight = (pillSlotHeight - with(density) { (searchVacancy * vacancyProgress.value.coerceIn(0f, 1f)).toDp() }).coerceAtLeast(0.dp)
    val pinchMotion = remember(route.key) { BrowsePinchMotion() }
    var zoomAnchor by remember(route.key) { mutableStateOf<BrowseZoomAnchor?>(null) }
    LaunchedEffect(zoomAnchor, queryPending, displayedStyle) {
        val anchor = zoomAnchor ?: return@LaunchedEffect
        if (anchor.route != route) { zoomAnchor = null; return@LaunchedEffect }
        if (queryPending || displayedStyle != anchor.style) return@LaunchedEffect
        val index = result.items.indexOfFirst { it.key == anchor.key }
        try {
            if (index >= 0) scrollOwnership.during {
                when (anchor.edge) {
                    -1 -> gridState.scrollToItem(0)
                    1 -> gridState.scrollToItem((gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    else -> gridState.scrollToItem(index + 1, -anchor.top)
                }
                gridState.scroll {
                    fun correctAnchor() {
                        val layout = gridState.layoutInfo
                        when (anchor.edge) {
                            -1 -> scrollBy(-gridState.firstVisibleItemScrollOffset.toFloat())
                            1 -> layout.visibleItemsInfo.lastOrNull()?.let {
                                scrollBy((it.offset.y + it.size.height + layout.afterContentPadding - layout.viewportEndOffset).toFloat())
                            }
                            else -> layout.visibleItemsInfo.firstOrNull {
                                it.key == anchor.key && it.index == index + 1 && it.size.height > 0
                            }?.let {
                                scrollBy(it.offset.y + it.size.height * anchor.fraction - anchor.top)
                            }
                        }
                    }
                    // displayedStyle gates the destination; scrollToItem forces its
                    // remeasurement. Read that measured item, never the source bounds,
                    // and correct before yielding a frame of coarse top alignment.
                    // Both this correction and the continuing loop own the same scroll.
                    correctAnchor()
                    val start = withFrameNanos { it }
                    do {
                        val now = withFrameNanos { it }
                        correctAnchor()
                    // The same anchor owns preview AND release settlement. Ending it at
                    // finger-up lets onRelease recreate it and expose the initial coarse
                    // scrollToItem placement before fractional correction on the next frame.
                    } while (pinchMotion.source != null || now - start < if (reducedMotion) 32_000_000L else 450_000_000L)
                }
            }
        } finally { if (zoomAnchor == anchor) zoomAnchor = null }
    }
    val surroundingBackdrop = LocalFrostedBackdrop.current
    val hasSongItems = result.items.any { it.row != null }
    val needsListBackdrop = (hasSongItems || showFilters || showSearchPill || route.collection?.kind == MediaKind.Queue ||
        (route.collection == null && route.tab in setOf(MainTab.Library, MainTab.Playlists)))
    val listBackdrop = rememberFrostedBackdrop(presentation to surroundingBackdrop?.fallback,
        needsListBackdrop, dark = if (artDetail) !lightCollection else surroundingBackdrop?.dark == true,
        // Floating controls sample narrow regions. Let their clips cull rows
        // instead of rasterizing the entire scrolling viewport a second time.
        retainRaster = false)
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
    val artworkTransit = remember { ArtworkTransit() }
    val latestBrowse by rememberUpdatedState(browse)
    val latestReducedMotion by rememberUpdatedState(rememberEffectiveReducedMotion(reducedMotion || LocalReducedMotion.current))
    val latestTopInset by rememberUpdatedState(with(density) {
        (if (showSearchPill || showFilters) pillSlotHeight else 0.dp).toPx()
    })
    var followingOrder by remember { mutableStateOf<BrowseOrderScrollRequest?>(null) }
    fun sourceStillCommitted(request: BrowseOrderScrollRequest): Boolean {
        val source = BrowseSourceRevision(library.snapshot,
            playlistController?.state?.value?.playlists ?: playlists.playlists,
            queues?.state?.value?.library?.queues ?: queueState.library.queues, libraryFilters)
        return request.committed.matches(source)
    }
    // A post-commit prop publication may catch up while readiness waits. It must not
    // restart/cancel the request; only a different actor-current structure does.
    val visibleOrderSource = BrowseSourceRevision(library.snapshot, playlists.playlists, queueState.library.queues, libraryFilters)
    LaunchedEffect(orderScroll.request, visibleOrderSource) {
        orderScroll.request?.let { if (!sourceStillCommitted(it)) orderScroll.finish(it) }
    }
    // Cursor captures replace outer queue snapshots, not the committed entries generation.
    LaunchedEffect(orderScroll.revision, orderScroll.request, orderOwner) {
        val request = orderScroll.request ?: return@LaunchedEffect
        if (!selectionVisible || playerOpen || !orderScroll.isCurrent(request, currentOrderOwner, currentRoute) ||
            !sourceStillCommitted(request)) { orderScroll.finish(request); return@LaunchedEffect }
        fun destination(): Int? {
            if (latestBrowse.pending) return null
            // Resolve the live active occurrence once readiness completes, not the
            // potentially older cursor at commit. Motion itself never chases playback.
            val target = if (request.followCurrent) queues?.state?.value?.library?.let { live ->
                live.active?.takeIf { it.id == request.committed.collection.id }?.currentEntryId
            } else request.targetOccurrenceId
            return request.targetIndex(latestBrowse.current, currentOrderOwner, currentRoute, target)
        }
        try {
            var target: Int
            while (true) {
                snapshotFlow { destination() }.first { it != null }
                // Publication precedes lazy measurement. A cursor-only refresh may
                // temporarily retain old rows again; wait rather than lose the request.
                withFrameNanos { }; withFrameNanos { }
                val resolved = destination()
                if (resolved != null && gridState.layoutInfo.totalItemsCount ==
                    (latestBrowse.current?.result?.items?.size ?: 0) + 1) {
                    target = resolved
                    break
                }
            }
            if (!orderScroll.isCurrent(request, currentOrderOwner, currentRoute) || !sourceStillCommitted(request)) return@LaunchedEffect
            followingOrder = request
            playbackFollow.cancel()
            scrollOwnership.during { artworkTransit.during(!latestReducedMotion &&
                kotlin.math.abs(target - gridState.firstVisibleItemIndex) > gridState.layoutInfo.visibleItemsInfo.size * 2) {
                if (target == 0) gridState.easeToTop(latestReducedMotion)
                else gridState.easeToItem(target, latestReducedMotion, latestTopInset)
            } }
        } finally {
            if (followingOrder === request) followingOrder = null
            orderScroll.finish(request)
        }
    }
    // Accessibility/programmatic user scrolling can supersede a request still awaiting rows.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && followingOrder == null) orderScroll.cancel()
        }
    }
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    OnModalGestureCancellation { playbackFollow.interrupt() }
    val readyToScroll by rememberUpdatedState(!queryPending && loadedResult != null)
    // Cursor checkpoints replace queue snapshots without changing this list. A return
    // owns its query/occurrence structure, not unrelated live playback bookkeeping.
    val returnOwner = remember(route, style, library.snapshot, playlists.playlists,
        mutableQueue?.entries, mutableQueue?.order, libraryFilters, selectedAlbums) { Any() }
    var presentedReturnOwner by remember { mutableStateOf<Any?>(null) }
    SideEffect { if (!queryPending && loadedResult != null) presentedReturnOwner = returnOwner }
    // A cursor-only refresh need not blink a still-valid control. Genuine query or
    // occurrence changes hide it until the corresponding list has been presented.
    val returnListMatches = loadedResult != null && (!queryPending || presentedReturnOwner === returnOwner)
    val clearableFilter = (route.tab == MainTab.Library && route.collection == null && route.filter != null) ||
        (artistDetail && validAlbums.isNotEmpty())
    DisposableEffect(playbackFollow) { onDispose { playbackFollow.cancel() } }
    val returnToList = ListReturnActions(scrollToTopRequest, returnOwner,
        selectionVisible && !playerOpen && !session.active && !localSearchOpen,
        backEnabled = listBackEnabled && !route.search && !localSearchOpen && !playerOpen,
        action = listReturnAction(gridState.canScrollBackward, gridState.isScrollInProgress, clearableFilter),
        onSubmit = { orderScroll.cancel(); playbackFollow.userScroll() },
        ready = {
            snapshotFlow { readyToScroll }.first { it }
            withFrameNanos { }; withFrameNanos { }
        }, execute = { action ->
            localSearchOpen = false
            when (action) {
                ListReturnAction.ClearFilter -> if (artistDetail) selectedAlbums = emptyList()
                    else onRoute(route.copy(filter = null))
                ListReturnAction.ScrollTop -> scrollOwnership.during {
                    artworkTransit.during(!reducedMotion &&
                        gridState.firstVisibleItemIndex > gridState.layoutInfo.visibleItemsInfo.size * 2) {
                        gridState.easeToTop(reducedMotion)
                    }
                }
                ListReturnAction.None -> Unit
            }
        })
    BackHandler(selectionVisible && session.active && route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) {
        session.clear()
    }
    BindSelectionScope(session, route.collection, result.rows, selectionVisible && !localSearchOpen)
    val reorder = remember(route.key) { SongDragState(gridState, scrollScope) }
    val dragHost = LocalSongDragHost.current
    SideEffect {
        if (reorder.drag != null) {
            dragHost?.source = if (needsListBackdrop) listBackdrop else if (artDetail) collectionSource else surroundingBackdrop
            dragHost?.state = reorder; dragHost?.reducedMotion = reducedMotion
        }
        else if (dragHost?.state === reorder) dragHost.state = null
    }
    DisposableEffect(reorder, dragHost) { onDispose { if (dragHost?.state === reorder) dragHost.state = null } }
    val editableOrder = route.text.isBlank() &&
        route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)
    SideEffect { reorder.reconcile(result.items); reorder.obscuredBottom = with(density) { obscuredInset.toPx() } }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
    // Only this collection's canonical occurrence order owns the drag lifetime. A
    // playback checkpoint can refresh all browse presentations without changing it.
    val dragSourceOrder = remember(queueState.library.queues, playlists.playlists, route.collection) { when (route.collection?.kind) {
        MediaKind.Queue -> queueState.library.queues.firstOrNull { it.id == route.collection.id }?.entries?.map { it.id }
        MediaKind.Playlist -> playlists.playlists.firstOrNull { it.id == route.collection.id }?.entries?.map { it.id }
        else -> null
    } }
    // Keep attachment separate from viewport visibility. Takeovers consume their
    // occurrence changes rather than queueing work to surprise the returning owner.
    // Statistics/metadata and unrelated playlist refreshes may arrive during a
    // natural transition; only this route's query and occurrence order own it.
    val followOwner = remember(route, style, physicalOrder) { Any() }
    val followAvailable = route.collection?.kind == MediaKind.Queue &&
        playingEntryId != null && selectionVisible && !playerOpen && !session.active &&
        !localSearchOpen && reorder.drag == null && pinchMotion.source == null && zoomAnchor == null &&
        !sorting && !collectionMenu && orderScroll.request == null
    val followEligible = followAvailable && !listContact
    val latestFollowEligible by rememberUpdatedState(followEligible)
    val latestFollowOwner by rememberUpdatedState(followOwner)
    SideEffect {
        playbackFollow.observe(playingEntryId.takeIf { route.collection?.kind == MediaKind.Queue },
            followOwner, followEligible && !modalBlocked && modalOwner?.blocked != true, modalOwner?.generation,
            automatic = !route.search)
    }
    fun userScroll() {
        orderScroll.cancel()
        returnToList.cancel()
        playbackFollow.userScroll()
    }
    fun accessibleScroll(action: suspend () -> Unit): Boolean {
        if (!selectionVisible || playerOpen || localSearchOpen || modalOwner?.blocked == true) return false
        val generation = modalOwner?.generation
        userScroll()
        scrollScope.launch {
            if (modalOwner?.blocked != true && generation == modalOwner?.generation) action()
        }
        return true
    }
    val latestUserScroll by rememberUpdatedState(::userScroll)
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) latestUserScroll()
                return Offset.Zero
            }
        }
    }
    // Foundation's accessible and key scrolls need not emit a drag interaction.
    // App-owned restoration/order/pinch/follow mutations are explicitly excluded.
    LaunchedEffect(gridState, playbackFollow, reorder, pinchMotion) {
        snapshotFlow { gridState.isScrollInProgress && !scrollOwnership.internal &&
            reorder.drag == null && pinchMotion.source == null && zoomAnchor == null }.collect { unclaimed ->
            if (unclaimed) latestUserScroll()
        }
    }
    LaunchedEffect(playbackFollow.request) {
        val request = playbackFollow.request ?: return@LaunchedEffect
        if (!playbackFollow.bind(request, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)) return@LaunchedEffect
        fun owns() = playbackFollow.request === request && latestFollowEligible &&
            request.owner === latestFollowOwner && modalOwner?.blocked != true &&
            request.modalGeneration == modalOwner?.generation
        try {
            snapshotFlow { !latestBrowse.pending && latestBrowse.current != null }.first { it }
            while (owns()) {
                // Row publication and lazy measurement are separate. Check the
                // measured keys too, including same-size reorder/replacement.
                withFrameNanos { }; withFrameNanos { }
                if (!owns()) return@LaunchedEffect
                val published = latestBrowse.current ?: continue
                if (latestBrowse.pending) continue
                val target = request.targetIndex(published.result.items) ?: return@LaunchedEffect
                val layout = gridState.layoutInfo
                if (published.route != currentRoute || layout.totalItemsCount != published.result.items.size + 1 ||
                    layout.visibleItemsInfo.any { item -> item.index > 0 &&
                        published.result.items.getOrNull(item.index - 1)?.key != item.key }) continue
                if (!playbackFollow.beginMotion(request)) return@LaunchedEffect
                scrollOwnership.during {
                    artworkTransit.during(!latestReducedMotion &&
                        kotlin.math.abs(target - gridState.firstVisibleItemIndex) > layout.visibleItemsInfo.size * 2) {
                        gridState.easeToItem(target, latestReducedMotion, latestTopInset)
                    }
                }
                break
            }
        } finally { playbackFollow.finish(request) }
    }
    val moveSongs: (Set<String>, String?, (Boolean) -> Unit) -> Unit = { ids, before, done ->
        when (route.collection?.kind) {
            MediaKind.Queue -> if (queues != null) queues.moveBefore(route.collection.id, ids, before, dragSourceOrder, done) else done(false)
            MediaKind.Playlist -> if (playlistController != null) playlistController.moveBefore(route.collection.id, ids, before, dragSourceOrder, done) else done(false)
            else -> done(false)
        }
    }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<Track?>(null) }
    val pageColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = if (artDetail) pageColors.copy(onSurface = collectionInk, onSurfaceVariant = collectionInk.copy(alpha = .65f),
        primary = collectionInk, surface = if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom) else pageColors) {
    CompositionLocalProvider(LocalArtworkTransit provides artworkTransit, LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalFrostedBackdrop provides if (artDetail) collectionSource else surroundingBackdrop,
        LocalEmptySearchBack provides popupDismiss?.takeIf { route.text.isBlank() }) {
    Box(Modifier.then(if (popupDismiss != null && route.text.isBlank()) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
    if (artDetail) CollectionBackground(hero, gridState, collectionSource, reducedMotion,
        Modifier.matchParentSize())
    Column(Modifier.then(if (popupDismiss != null && route.text.isBlank()) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
        .then(if (route.search) Modifier.focusRequester(resultsFocus).focusable() else Modifier)
        .then(if (searchAnchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)) {
        header()
        if (route.search && popupDismiss != null) {
            val input = ScopedSearchEditorParameters(route.text, ::editSearchText,
                "Search " + (route.title ?: route.tab.label), focus, ::submitSearch)
            if (scopedEditor != null) scopedEditor.content(input) else ScopedSearchEditor(input)
        }
        else if (route.search) GlassSearchField(route.text, ::editSearchText,
            if (route.globalSearch) "Search library" else "Search ${route.title ?: route.tab.label}", enabled = !playerOpen,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), fieldModifier = Modifier.focusRequester(focus))

        LookaheadScope {
        if (popupDismiss == null || route.text.isNotBlank()) BoxWithConstraints(Modifier.weight(1f).pointerInput(orderScroll, playbackFollow) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                listContact = true
                orderScroll.cancel()
                returnToList.cancel()
                playbackFollow.pointerDown()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally { listContact = false }
            }
        }.onGloballyPositioned { reorder.viewportOrigin = it.positionInRoot() }.onSizeChanged { reorder.viewportHeight = it.height; reorder.viewportWidth = it.width }
            .selectionPanelRelease(session, route.key,
                enabled && !queryPending && route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) { position ->
                val item = reorder.hit(position)
                // Dots and the row share selection-on-hold. Both retain the new
                // action panel until this pointer releases.
                item?.row != null
            }
            .songDrag(reorder, editableOrder && enabled, route.collection, session, reducedMotion,
                displayedStyle.presentation == Presentation.List, acquisitionReady = !queryPending,
                sourceOrder = dragSourceOrder, routeKey = route, commit = moveSongs)) {
            val viewportWidth = constraints.maxWidth
            val columns = galleryColumns(displayedStyle.presentation, with(density) { viewportWidth.toDp().value }, displayedStyle.itemsPerRow)
            val cellGeometry = BrowseCellGeometry(columns)
            val dragColors = MaterialTheme.colorScheme
            val dragInk = LocalContentColor.current
            SideEffect { reorder.style = displayedStyle; reorder.cellGeometry = cellGeometry; reorder.colors = dragColors; reorder.ink = dragInk }
            val loadingReducedMotion = reducedMotion || LocalReducedMotion.current
            val skeletonReveal = !artDetail && !route.search
            val displayedRoute = presentation?.route ?: route
            val artistOnlyLoading = displayedRoute.collection == null && (displayedRoute.filter in setOf(MediaKind.Artist, MediaKind.AlbumArtist) ||
                (displayedRoute.filter == null && displayedRoute.tab in setOf(MainTab.Artists, MainTab.AlbumArtists)))
            val loadingReveal = rememberBrowseLoadingReveal(displayedRoute.key to displayedRoute.filter,
                loadedResult != null, browse.cached, columns,
                with(density) { viewportWidth.toDp().value }, with(density) { constraints.maxHeight.toDp().value }, loadingReducedMotion,
                geometryKey = Triple(displayedStyle.presentation to displayedStyle.listSize, displayedStyle.extraInfo, density.fontScale))
            val loadingActivity = remember { LoadingShimmerActivity() }
            val pendingArtwork by remember(loadingActivity) { derivedStateOf(structuralEqualityPolicy()) { loadingActivity.count > 0 } }
            val shimmer = rememberBrowseLoadingShimmer(loadedResult == null || !loadingReveal.complete || pendingArtwork, loadingReducedMotion)
            val galleryLoadingText = if (displayedStyle.presentation != Presentation.List)
                rememberGalleryLoadingTextMetrics(displayedStyle, columns) else null
            CompositionLocalProvider(LocalLoadingShimmer provides shimmer, LocalLoadingShimmerActivity provides loadingActivity,
                LocalReducedMotion provides loadingReducedMotion, LocalGalleryLoadingTextMetrics provides galleryLoadingText) {
            if (loadedResult == null && !artDetail) BrowseLoadingViewport(displayedStyle, cellGeometry,
                viewportWidth, constraints.maxHeight, if (showFilters || showSearchPill) pillSlotHeight else 0.dp,
                popupDismiss == null && displayedStyle.extraInfo != ExtraInfo.None, artistOnlyLoading)
            else SelectionViewport(Modifier.fillMaxSize()
                // Floating controls sample the finished scrolling scene, including
                // its fade and selection/overscroll transforms, never the raw grid.
                .then(if (needsListBackdrop) Modifier.recordFrostedBackdrop(listBackdrop, if (artDetail) collectionSource else surroundingBackdrop)
                    .then(if (surroundingBackdrop == null && !artDetail) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier) else Modifier)
                .graphicsLayer { alpha = if (artDetail || (skeletonReveal && !queryPending)) 1f else browse.reveal.value }
                .then(if ((showSearchPill || showFilters) && !artDetail) Modifier.listTopFade() else Modifier),
                contentDrawing = Modifier.pinchRetainedOverlay(pinchMotion, gridState, itemKeys)) { overscroll ->
            LazyVerticalGrid(state = gridState, overscrollEffect = overscroll, columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize()
                .controlScrollParent(gridState, ControlScrollAxis.Vertical)
                .nestedScroll(userScrollConnection)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown,
                            Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)) userScroll()
                    false
                }
                .pointerInput(loadingReveal) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        loadingReveal.revealImmediately()
                    }
                }
                .semantics {
                    stateDescription = if (displayedStyle.presentation == Presentation.List) "List, ${displayedStyle.listSize.name}"
                        else if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns"
                    // Explicit semantic entry also catches a no-op request at an
                    // already aligned item, which has no scroll-progress transition.
                    if (route.collection?.kind == MediaKind.Queue) {
                        scrollToIndex { index ->
                            if (index !in 0 until gridState.layoutInfo.totalItemsCount) false
                            else accessibleScroll { gridState.scrollToItem(index) }
                        }
                        scrollBy { _, y -> accessibleScroll { gridState.animateScrollBy(y) } }
                        scrollByOffset { offset ->
                            if (!selectionVisible || playerOpen || localSearchOpen || modalOwner?.blocked == true) Offset.Zero
                            else {
                                userScroll()
                                Offset(0f, gridState.animateScrollBy(offset.y))
                            }
                        }
                    }
                }
                .browsePinch(enabled && !playerOpen && !queryPending && reorder.drag == null,
                    pinchMotion, displayedStyle, route, gridState, viewportWidth, density, scrollScope, reducedMotion,
                    onPreview = { style = it }, onCommit = { style = it; preferences.write(route, it, preferences.overridden(route)) },
                    onAnchor = { zoomAnchor = it })
                .testTag("browser-list"), contentPadding = PaddingValues(start = cellGeometry.gridInset.dp, end = cellGeometry.gridInset.dp,
                    bottom = if (popupDismiss != null) 16.dp else maxOf(80.dp, obscuredInset + 16.dp))) {
                item(key = "browser-notice", contentType = "notice", span = { GridItemSpan(maxLineSpan) }) {
                    // Grid gutters belong to tiles; full-span headers keep their viewport alignment.
                    Column(Modifier.requiredWidth(with(density) { viewportWidth.toDp() }).testTag("browse-full-span")) {
                        if (artDetail) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides collectionSource) {
                                CollectionHeader(route.title ?: seededCollection?.title.orEmpty(), if (artistDetail) null else (sourceResult.rows.firstOrNull()?.track ?: seededCollection?.songs?.firstOrNull()?.track)?.let { it.albumArtist ?: it.artist },
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
                                        localSearch = freshSearch(); localFocusRequest++; localSearchOpen = true
                                    }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
                                }
                            }
                        }
                        if (!artDetail && (showSearchPill || showFilters)) Spacer(Modifier.height(if (showSearchPill) searchSlotHeight else pillSlotHeight))
                        LibraryNotice(library)
                        playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue) ActionTextButton(onClick = onSettings) { Text("Add music folders") }
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
                    val itemIndex = itemIndices[item.key] ?: 0
                    val loadingCell = if (skeletonReveal && !loadingReveal.complete) loadingReveal.cell(itemIndex) else null
                    val current = route.collection?.kind == MediaKind.Queue && item.row != null && item.row.id == playingEntryId
                    val selectedCount = session.selectedCount(item, route.collection)
                    val partiallySelected = item.row == null && selectedCount > 0 && selectedCount < item.songs.size
                    Column(Modifier.then(if (editableOrder && pinchMotion.visualSource == null) Modifier.animateItem(placementSpec = if (reorder.drag?.let { it.dropping && item.key in it.keys } == true) null else androidx.compose.animation.core.tween(if (reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null) else Modifier)
                        .graphicsLayer {
                            val sweep = if (!artDetail && !route.search) 1f else ((entrySweep.value * 600f - itemIndex.coerceAtMost(10) * 30f) / 300f)
                                .coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            alpha = sweep * (reorder.drag?.takeIf { it.dropping && item.key in it.keys }?.handoff?.value ?: 1f)
                            pinchMotion.itemAlpha[item.key] = alpha
                        }) {
                        DisposableEffect(item.key, reorder) { onDispose { reorder.itemCoordinates.remove(item.key) } }
                        val menuAnchor = rememberGlassAnchor()
                        SideEffect { menuAnchor.contextual = true; menuAnchor.bottomClearance = with(density) { obscuredInset.toPx() } }
                        val mutableSong = item.row != null && route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue)
                        val holdLabel = if (mutableSong) "Select song" else if (item.row != null) "Song actions" else "Collection actions"
                        val onHold = {
                            if (mutableSong) session.select(route.collection, listOf(requireNotNull(item.row)))
                            else contextMenu = item.key
                        }
                        val itemMenu: @Composable (Boolean) -> Unit = { showButton ->
                            Box {
                                if (showButton) ActionIcon(R.drawable.ic_ellipsis, "Actions for " + item.title, anchor = menuAnchor,
                                    quiet = true, onLongClickLabel = holdLabel,
                                    onLongClick = onHold.takeIf { !queryPending && (item.row == null || enabled) }) { contextMenu = item.key }
                                CollectionActions(contextMenu == item.key, menuAnchor, item, playlists, playlistController, queues, pins, onPin, { contextMenu = null },
                                    onInformation = { info = it }, containingSource = route.collection, selection = session,
                                    onRemove = if (item.row == null) null else when (route.collection?.kind) {
                                        MediaKind.Queue -> ({ queues?.removeEntries(setOf(item.key), route.collection.id); Unit })
                                        MediaKind.Playlist -> ({ playlistController?.removeEntries(route.collection.id, setOf(item.key)) { }; Unit })
                                        else -> null
                                    })
                            }
                        }
                        item.groupHeading?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                        Column(Modifier.then(if (pinchMotion.visualSource == null) Modifier.animateBounds(this@LookaheadScope,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(displayedStyle).dp),
                            boundsTransform = { _, _ -> androidx.compose.animation.core.tween(if (reducedMotion || editableOrder || loadingCell != null) 0 else 350) })
                            else Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(displayedStyle).dp)
                                .pinchItemPlacement(pinchMotion, gridState, item.key, itemIndex, displayedStyle, cellGeometry)).onGloballyPositioned {
                                reorder.itemCoordinates[item.key] = it
                                val origin = it.positionInRoot() - reorder.viewportOrigin
                                loadingCell?.place(origin.x / density.density,
                                    (origin.y - with(density) { (if (showFilters || showSearchPill) pillSlotHeight else 0.dp).toPx() }) / density.density)
                            }
                            // Frost is redrawn separately from frozen content so its
                            // corners stay identical to selection during pinch.
                            .currentSongHighlight(current, MaterialTheme.colorScheme.primary) { size ->
                                pinchMotion.highlightSize(item.key, itemIndex, size)
                            }.retainPinchItem(item.key, pinchMotion, MaterialTheme.colorScheme.primary.takeIf { current })
                            .selectionLens(selectedCount > 0, partiallySelected) { size ->
                                pinchMotion.highlightSize(item.key, itemIndex, size)
                            }
                            .then(if (pinchMotion.visualSource == null) Modifier.clip(Corners.card) else Modifier)
                            .testTag("media-${item.key}").combinedClickable(
                            enabled = !queryPending && (item.row == null || enabled),
                            onClick = {
                                if (item.row == null) { onSearchCommit(); onOpen(item) }
                                else if (session.active) session.toggle(route.collection, item.row)
                                else { onSearchCommit(); onPlay(result.rows, result.rows.indexOfFirst { it.id == item.row.id }, result.scope) }
                            }, onLongClickLabel = holdLabel, onLongClick = onHold
                        )) {
                            BrowseItemLayout(item, displayedStyle, cellGeometry, this@LookaheadScope, reducedMotion, itemMenu,
                                showInformation = popupDismiss == null, inheritedInformation = inheritedInformation, itemIndex = itemIndex,
                                loadingReveal = loadingCell, artistOnlyLoading = artistOnlyLoading,
                                trackNumber = if (route.collection?.kind == MediaKind.Album) item.row?.track?.trackNumber?.toString() ?: (itemIndex + 1).toString() else null, pinch = pinchMotion, artworkAnchor = menuAnchor)
                        }

                    }
                }
            }
            }
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides
                if (needsListBackdrop) listBackdrop else if (artDetail) collectionSource else surroundingBackdrop) {
            if (showFilters) Box(Modifier.align(Alignment.TopStart)) {
                LibraryFilterBubbles(filters, route.filter, reducedMotion,
                    { onRoute(route.copy(filter = it)) }, onFilterSettings)
            }
            if (showSearchPill && !artDetail) ListSearchPill("Search "+ (route.title ?: route.tab.label), {
                if (gridState.firstVisibleItemIndex == 0) {
                    searchOriginalOffset = gridState.firstVisibleItemScrollOffset
                    searchVacancy = (with(density) { pillSlotHeight.toPx() } - searchOriginalOffset).coerceAtLeast(0f)
                }
                localSearch = freshSearch(); localFocusRequest++; localSearchOpen = true
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
            // Active queues retain their occurrence return. Other current song lists,
            // including inactive queues, return to their own top without changing filters.
            val playbackReturn = route.collection?.kind == MediaKind.Queue && playingEntryId != null
            val showReturn = selectionVisible && !playerOpen && !localSearchOpen && returnListMatches &&
                !session.active && reorder.drag == null &&
                (if (playbackReturn) playingIndex != null && (if (route.search) returnDirection != 0 else !playbackFollow.attached)
                    else hasSongItems && gridState.canScrollBackward)
            // A drag owns this slot exclusively; retire a half-faded return drawing
            // instead of overlapping its replacement X during a quick hold-and-move.
            key(reorder.drag != null) { CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
                FloatingReturnToPositionButton(up = !playbackReturn || returnDirection < 0,
                    label = if (playbackReturn) "Current song" else "Return to top",
                    visible = showReturn, reducedMotion = reducedMotion,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = obscuredInset)) {
                    if (!playbackReturn) returnToList(ListReturnAction.ScrollTop)
                    else {
                        orderScroll.cancel()
                        returnToList.cancel()
                        playbackFollow.returnToCurrent(playingEntryId, followOwner,
                            followAvailable && !listContact && modalOwner?.blocked != true, modalOwner?.generation)
                    }
                }
            } }
            if (loadedResult != null) BrowseScrollbar(gridState, result.items, displayedStyle.sort,
                Modifier.align(Alignment.CenterEnd).padding(bottom = obscuredInset), onUserScroll = ::userScroll)
            if (route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist) && !route.search) {
                val playlist = playlists.playlists.firstOrNull { it.id == route.collection?.id }
                if (playlist != null || artDetail) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset)) {
                    val anchor = rememberGlassAnchor()
                    var open by remember { mutableStateOf(false) }
                    ActionIcon(R.drawable.ic_ellipsis, if (playlist != null) "Playlist actions" else if (artistDetail) "Artist actions" else "Album actions", anchor = anchor) { open = true }
                    CollectionActions(open, anchor, BrowserItem(requireNotNull(route.collection), playlist?.name ?: route.title.orEmpty(), null, sourceResult.rows, coverUri = playlist?.coverUri),
                        playlists, playlistController, queues, pins, onPin, { open = false },
                        onDeleted = { onRoute(route.copy(collection = null, title = null)) },
                        onRenamed = { onRoute(route.copy(title = it)) })
                }
            }
            if (route.tab in setOf(MainTab.Library, MainTab.Playlists) && route.collection == null && !route.search)
                PlaylistCreationButton(library.snapshot, playlists, playlistController, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset).testTag("playlist-add-module"))
            }
        }
        }
        if (LocalSessionSelection.current == null) SelectionPanelVisibility(session.panelVisible && reorder.drag == null, reducedMotion = reducedMotion) {
            SessionSelectionPanel(session, queues, playlistController, playlists)
        }
    }

    if (artDetail) CollectionToolbar(gridState, reorder.viewportWidth, route.title.orEmpty(), collectionMenuAnchor,
        onBack, { view -> collectionView = view; collectionMenu = true }) {
        if (collectionMenu) CompositionLocalProvider(LocalGlassOrigin provides collectionMenuAnchor) { sortingPanel(initialView = collectionView) }
    }
    } } }

    if (showSearchPill || localSearchOpen || searchAnchor.hidden) {
        fun closeSearch() { retireSearchEditor(); localSearchOpen = false }
        fun backSearch() {
            val previous = if (localSearch.route.text.isNotBlank()) localSearch.back(false) else null
            if (previous != null) { retireSearchEditor(); localSearch = previous } else closeSearch()
        }
        LaunchedEffect(localSearchOpen, searchAnchor.modalPresent) {
            if (!localSearchOpen && !searchAnchor.modalPresent) {
                localSearch = freshSearch()
            }
        }
        ListSearchWindow(localSearchOpen && !playerOpen, searchAnchor, localSearch.route.text.isNotBlank(), ::closeSearch, onBack = ::backSearch) {
            CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) {
                val searchRoute = localSearch.route
                key(searchRoute.key) { searchHolder.SaveableStateProvider(searchRoute.key) {
                    BrowserScreen(library, playlists, searchRoute, { localSearch = localSearch.update(it) }, onOpen, onSettings,
                        onPlay, queues, playlistController, enabled, playerOpen, pins, onPin, queueState = queueState,
                        playingTrackId = playingTrackId, playingEntryId = playingEntryId, reducedMotion = reducedMotion,
                        onSearchEdit = { incoming ->
                            val before = localSearch.route
                            // History route keys may change while this editor session continues.
                            // Resolve edits against its current route; retired sessions own nothing.
                            val next = if (searchEditor.active && localSearchOpen) localSearch.editSearch(
                                before.copy(text = incoming.text, querySort = incoming.querySort,
                                    queryAscending = incoming.queryAscending)) else localSearch
                            searchEditTrace?.invoke("route edit currentKey=${before.key.hashCode()} incomingKey=${incoming.key.hashCode()} nextKey=${next.route.key.hashCode()} oldLength=${before.text.length} incomingLength=${incoming.text.length} nextLength=${next.route.text.length} committed=${before.queryCommitted}")
                            localSearch = next
                        },
                        onSearchCommit = { if (searchEditor.active && localSearchOpen) localSearch = localSearch.commitSearch() },
                        libraryFilters = libraryFilters, popupDismiss = ::closeSearch, scopedEditor = searchEditor, focusRequest = localFocusRequest)
                } }
            }
        }
    }
    addingSong?.let { song -> AddToPlaylistDialog(listOf(song), playlists, playlistController, { addingSong = null }, { addingSong = null }) }
    info?.let { song -> SongInformation(song, { info = null }, { editTags?.invoke(listOf(song)) {} }, folders = library.snapshot.folders) { action ->
        val tab = when (action) { PlayerAction.Album -> MainTab.Albums; PlayerAction.Artist -> MainTab.Artists
            PlayerAction.AlbumArtist -> MainTab.AlbumArtists; else -> MainTab.Folders }
        BrowseQueries.evaluate(library.snapshot, playlists.playlists, BrowseRoute(tab), sorts = sorts).items
            .firstOrNull { item -> item.songs.any { it.track.id == song.id &&
                (action != PlayerAction.Folder || io.github.anastaci1a.localica.library.sameSongInformationLocation(it.track, song)) } }?.let { info = null; onOpen(it) }
    } }
    if (sorting) CompositionLocalProvider(LocalGlassOrigin provides sortOrigin) { sortingPanel(initialView = presentationOnly) }
}
