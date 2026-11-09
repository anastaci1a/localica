package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.MutatePriority
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

/** Optional screen and per-cell work evidence (kind, route or item key); no behavior depends on it. */
internal data class BrowseScrollFrame(val index: Int, val offset: Int, val headerHeight: Int?, val rowHeight: Int?,
    val viewportWidth: Int = 0, val noticeWidth: Int? = null, val noticeLookahead: Boolean? = null, val noticeContent: Int? = null,
    val headerTop: Int? = null, val searchPill: Boolean = false, val serial: Int = 0, val frameTime: Long = 0)
internal class BrowseScrollFrameProbe {
    var request by mutableIntStateOf(0)
    @Volatile var serial = 0
    /** Test draw invalidation: the last composed quick-search pill mode, read in the grid draw hook so
     *  the retained grid draws once in the frame the mode flips and its actual placement is sampled. */
    var searchPill by mutableStateOf(false)
    @Volatile var frame: BrowseScrollFrame? = null
    /** Test fixtures record every actual draw through this; production never sets it. */
    @Volatile var onFrame: ((BrowseScrollFrame) -> Unit)? = null
}
internal val LocalBrowseScrollFrameProbe = staticCompositionLocalOf<BrowseScrollFrameProbe?> { null }

internal val LocalBrowseCellWorkObserver = staticCompositionLocalOf<((String, Any) -> Unit)?> { null }

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
    queueOpeningRequest: Long = 0,
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
    var measuredSearchSlotHeight by remember { mutableIntStateOf(-1) }
    fun freshSearch() = NavigationState(route.tab, mapOf(route.tab to listOf(route.copy(search = true,
        text = "", queryCommitted = false, key = java.util.UUID.randomUUID().toString()))))
    var localSearch by androidx.compose.runtime.saveable.rememberSaveable(saver = navigationStateSaver { state -> state.takeIf { it.active == route.tab } }) {
        mutableStateOf(freshSearch())
    }
    var localFocusRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val searchHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var editorGeneration by remember { mutableIntStateOf(0) }
    val searchEditor = remember(editorGeneration) { ScopedSearchEditorSession() }
    DisposableEffect(searchEditor) { onDispose { searchEditor.retire() } }
    fun retireSearchEditor() { searchEditor.retire(); editorGeneration++ }
    val scrollFrameProbe = LocalBrowseScrollFrameProbe.current
    val screenWork = LocalBrowseCellWorkObserver.current
    if (screenWork != null) SideEffect { screenWork("screen", route.key) }
    val obscuredInset = LocalBrowseObscuredInset.current
    var returnControlHeight by remember { mutableIntStateOf(0) }
    var actionControlHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val pillMaterialTopInset = ListPillVerticalInset + (listPillTargetHeight() - listPillHeight()) / 2
    val showSearchPill = !route.search && route.collection?.kind != MediaKind.Album && style.quickSearch && (route.collection != null || route.tab != MainTab.Library)
    if (scrollFrameProbe != null) SideEffect { scrollFrameProbe.searchPill = showSearchPill }
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
    val keyboardClosing by rememberImeClosing(WindowInsets.ime,
        WindowInsets.imeAnimationSource, WindowInsets.imeAnimationTarget, density)
    var restoreResultsFocus by remember(route.key) { mutableStateOf(route.search && route.queryCommitted) }
    LaunchedEffect(route.key, playerOpen, focusRequest, inputCompletion, panelReady, windowFocused, keyboardClosing, reportSearchOpening, scopedEditor?.focused) {
        searchEditTrace?.invoke("autofocus decision key=${route.key.hashCode()} request=$focusRequest consumed=$focusedRequest once=$focusedOnce ready=$panelReady window=$windowFocused closing=$keyboardClosing player=$playerOpen editor=${scopedEditor?.let { System.identityHashCode(it) }} active=${scopedEditor?.active} focused=${scopedEditor?.focused}")
        // Dialog attachment and IME focus happen outside Compose's animation clock.
        // Do not consume an autofocus request while the returning window still owns input.
        if (!panelReady || !windowFocused || keyboardClosing) return@LaunchedEffect
        if (route.search && (!focusedOnce || focusedRequest != focusRequest) && !playerOpen) {
            withFrameNanos { } // Wait until the field is attached.
            if (scopedEditor != null && !scopedEditor.active) return@LaunchedEffect
            focus.requestFocus(); keyboard?.show(); focusedOnce = true; focusedRequest = focusRequest
            searchEditTrace?.invoke("autofocus requested key=${route.key.hashCode()} request=$focusRequest editor=${scopedEditor?.let { System.identityHashCode(it) }}")
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
    val touchExploration = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true }
    val playbackFollow = androidx.compose.runtime.saveable.rememberSaveable(route.key, route.collection,
        saver = androidx.compose.runtime.saveable.Saver<QueuePlaybackFollowCoordinator, Any>(
            save = { arrayListOf(it.attached, it.consumedOpeningRequest) },
            restore = { QueuePlaybackFollowCoordinator.restoreState(it) })) {
        QueuePlaybackFollowCoordinator(!touchExploration)
    }
    val unfilteredOpening = !route.search && !route.globalSearch && route.text.isBlank() && route.filter == null && !localSearchOpen
    val browse = rememberBrowsePresentation(library.snapshot, playlists.playlists, queueState.library.queues,
        libraryFilters, route, style, reducedMotion, queryDispatcher, sorts,
        sourcesReady = browseCatalogReady(route, library.loading, playlists.loading, queueState.loading, libraryFilters),
        animateArrival = route.search || route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist),
        queueOpeningRequest = queueOpeningRequest.takeIf { unfilteredOpening &&
            (it > playbackFollow.consumedOpeningRequest || it > 0 && playbackFollow.request?.initialPosition == true) } ?: 0,
        queueOpeningOccurrence = playingEntryId)
    val presentation = browse.current
    val loadedResult = presentation?.result
    val displayedStyle = presentation?.style ?: style
    val queryPending = browse.pending
    val sourceResult = loadedResult ?: BrowseResult()
    val artDetail = !route.search && route.collection?.kind in setOf(MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist, MediaKind.Playlist)
    val playlistDetail = artDetail && route.collection?.kind == MediaKind.Playlist
    val detailPlaylist = if (playlistDetail) playlists.playlists.firstOrNull { it.id == route.collection?.id } else null
    val seededCollection = LocalBrowseArtwork.current?.collection?.takeIf { it.ref == route.collection }
    val artistDetail = artDetail && route.collection?.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)
    var selectedAlbums by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(emptyList<String>()) }
    // The completed query owns both orders. Per-album view overrides must not
    // reverse inner tracks or reorder this carousel independently of its rows.
    val albums = if (artistDetail) sourceResult.artistAlbums else emptyList()
    val validAlbums = remember(albums, selectedAlbums) {
        ArtistAlbumSelection.reconcile(selectedAlbums, albums.map { it.id })
    }
    LaunchedEffect(albums, selectedAlbums, queryPending, loadedResult != null) {
        // Persist the canonical mode after completed library changes, never a loading placeholder.
        if (artistDetail && loadedResult != null && !queryPending && selectedAlbums != validAlbums) {
            selectedAlbums = validAlbums
        }
    }
    val selectedTrackIds = remember(albums, validAlbums) {
        albums.filter { it.id in validAlbums }.flatMap { it.tracks }.map { it.id }.toSet()
    }
    val result = remember(sourceResult, artistDetail, selectedTrackIds) {
        if (artistDetail && selectedTrackIds.isNotEmpty()) sourceResult.copy(items = sourceResult.items.filter { it.row?.track?.id in selectedTrackIds }) else sourceResult
    }
    // Prepared with the query off Main; only an artist album filter derives a new result here.
    val itemIndices = remember(result.items) { result.keyIndices }
    val pinchIndices = remember(result.items, itemIndices) {
        retainedPinchItemIndices(result.items, itemIndices) { it.key }
    }
    // The artist page displays songs beneath album-filter chips; its hero follows that displayed list.
    // Entry artwork is only a loading seed, never a permanent override of a newly sorted result.
    val heroUri = if (playlistDetail) remember(detailPlaylist, library.snapshot, playlists.loading, seededCollection) {
        playlistHeroUri(detailPlaylist, library.snapshot)
            ?: seededCollection?.coverUris?.firstOrNull().takeIf { detailPlaylist == null && playlists.loading }
    } else collectionHeroUri(loadedResult?.let { result }, seededCollection)
    val albumHero = if (artDetail) rememberCollectionVisual(heroUri) else PlayerVisual()
    val photoEnvironment = artistPhotoEnvironment()
    val artistPhotoConsentAllowed = if (artistDetail) photoEnvironment?.consent?.allowed?.collectAsState()?.value == true else false
    val hero = rememberArtistCollectionVisual(route.collection, route.title, sourceResult, heroUri, albumHero)
    val lightCollection = collectionIsLight(hero)
    val collectionInk = if (lightCollection) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White
    SideEffect { if (selectionVisible) onAppearance(if (artDetail) collectionInk else androidx.compose.ui.graphics.Color.Black,
        if (artDetail) { if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom } else androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val collectionSource = rememberFrostedBackdrop(hero, artDetail, dark = !lightCollection)
    var collectionView by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    val collectionMenuAnchor = rememberGlassAnchor()
    val gridState = rememberLazyGridState()
    val scrollScope = rememberCoroutineScope()
    val reorder = remember(route.key) { SongDragState(gridState, scrollScope) }
    val scrollResetInput = when {
        route.globalSearch -> Triple(true, route.filter, route.text)
        route.tab == MainTab.Library && route.collection == null && !route.search ->
            Triple(false, route.filter, "")
        else -> null
    }
    // A fresh mount restores its route's saved viewport. Only an input change
    // within that page must discard LazyGrid's retained item/key anchor.
    var previousScrollResetInput by remember(route.key) { mutableStateOf(scrollResetInput) }
    SideEffect {
        if (previousScrollResetInput != scrollResetInput) {
            previousScrollResetInput = scrollResetInput
            gridState.requestScrollToItem(0)
        }
    }
    var collectionHeaderHeight by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableIntStateOf(0) }
    var collectionActionBottom by androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableFloatStateOf(0f) }
    var collectionBackBottom by remember { mutableFloatStateOf(0f) }
    var listViewportTop by remember { mutableFloatStateOf(0f) }
    val collectionHome = ListScrollTarget.header(collectionHeaderHeight,
        (collectionActionBottom - (collectionBackBottom - listViewportTop)).coerceAtLeast(0f))
    val collectionTop = ListScrollTarget.header(collectionHeaderHeight)
    val artistFilterRange = remember(route.key) { ArtistFilterRange() }
    fun chooseArtistAlbums(ids: List<String>) {
        if (ids == selectedAlbums) return
        val layout = gridState.layoutInfo
        val header = layout.visibleItemsInfo.firstOrNull { it.key == "browser-notice" }
        val request = artistFilterRange.begin(header?.offset?.y, layout.viewportSize.height, layout)
        // Request the same absolute offset before changed keys can auto-anchor/clamp.
        if (request != null) gridState.requestScrollToItem(0, request.scrollOffset)
        selectedAlbums = ids
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
    val physicalOrder = remember(mutableQueue?.entries, mutablePlaylist?.entries) {
        mutableQueue?.entries?.map { it.id } ?: mutablePlaylist?.entries?.map { it.id }
    }
    val mutablePositions = remember(physicalOrder) { physicalOrder.orEmpty().withIndex().associate { it.value to it.index + 1 } }
    val pinchMotion = remember(route.key) { BrowsePinchMotion() }
    /** A saved geometry change (View popup) takes the pinch transaction, not a reflow (#627).
     * Its geometry commits like a pinch; the saved style (sort, information and so on) is
     * written once the transaction ends, so a rollback, failure or disposal still saves it. */
    fun changeViewInPlace(value: BrowseStyle, persist: () -> Unit = {}): Boolean {
        if (route.search) return false
        val view = displayedStyle.copy(presentation = value.presentation, itemsPerRow = value.itemsPerRow, listSize = value.listSize)
        val finished = pinchMotion.viewChange?.invoke(view) { style = it } ?: return false
        orderScroll.cancel()
        scrollScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { finished.await() } finally {
                persist()
                style = value
            }
        }
        dismissSorting()
        return true
    }
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
        if (!changeViewInPlace(value) { preferences.write(route, value, override) }) {
            preferences.write(route, value, override)
            commitStyle(value)
        }
    }, {
        val shown = style
        preferences.reset(route)
        val value = preferences.read(route)
        // The observer reloads synchronously; restore the shown view before any frame so the
        // defaults' geometry still enters the pinch transaction from the presented source.
        style = shown
        if (!changeViewInPlace(value)) commitStyle(value)
    }, initialView,
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

    val restoredSearchSlotHeight = with(density) { pillSlotHeight.roundToPx() }
    LaunchedEffect(localSearchOpen, searchAnchor, showSearchPill, restoredSearchSlotHeight) {
        snapshotFlow { searchAnchor.searchSlot.fraction }.first { it == if (localSearchOpen) 1f else 0f }
        if (!localSearchOpen) {
            if (searchOriginalOffset >= 0) {
                if (showSearchPill && searchVacancy > 0f) {
                    // A zero fraction can precede the root's restored measurement.
                    // Keep the owned header request alive until that layout exists;
                    // scrolling a still-zero-height header would anchor the first song.
                    snapshotFlow { measuredSearchSlotHeight }.first { it == restoredSearchSlotHeight }
                }
                // The measured-height acknowledgment can resume this coroutine
                // during layout. Queue the exact anchor for the next measure;
                // scrollToItem would synchronously force a reentrant layout.
                scrollOwnership.during { gridState.requestScrollToItem(0, searchOriginalOffset) }
            }
            searchOriginalOffset = -1; searchVacancy = 0f
        }
        searchAnchor.source?.refresh()
        modalBackdrop?.refresh()
    }
    val searchSlotLayoutOwner = remember { SearchSlotLayout() }
    // Reconcile the owned header before LazyGrid measures its children. A later
    // composition request can otherwise let the restored spacer retain a song key
    // as its scroll anchor. The request schedules, never forces, remeasurement.
    val searchSlotGridLayout = Modifier.layout { measurable, constraints ->
        val lookahead = isLookingAhead
        val measure = searchSlotLayoutOwner.measure(lookahead, searchAnchor.searchSlot.fraction,
            searchOriginalOffset, searchVacancy)
        measure.anchorOffset?.let { gridState.requestScrollToItem(0, it) }
        val child = measurable.measure(constraints)
        layout(child.width, child.height) {
            searchSlotLayoutOwner.place(lookahead, measure)
            child.placeRelative(0, 0)
        }
    }
    val searchSlotLayoutObserver = LocalSearchSlotLayoutObserver.current
    val searchSlotLayout = Modifier.layout { measurable, constraints ->
        // Dirty parent and cached spacer from the same animation mutation, before
        // traversal; a state write during parent measurement would notify too late.
        searchAnchor.searchSlot.fraction
        // Use this pass's captured owner, not a speculative lookahead value or a
        // separately sampled animation frame after the anchor request.
        val fraction = searchSlotLayoutOwner.fraction(isLookingAhead)
        val height = (restoredSearchSlotHeight - searchVacancy * fraction)
            .roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(child.width, height) { child.placeRelative(0, 0) }
    }
    val pinchHeader = if (artDetail) remember(pinchMotion, gridState) { CollectionPinchHeader(pinchMotion, gridState) } else null
    val headerFrameObserver = LocalCollectionHeaderFrameObserver.current
    SideEffect { pinchMotion.header = pinchHeader; pinchHeader?.observer = headerFrameObserver }
    var zoomAnchor by remember(route.key) { mutableStateOf<BrowseZoomAnchor?>(null) }
    LaunchedEffect(zoomAnchor, queryPending, displayedStyle) {
        fun headerPhase(phase: String) {
            if (pinchHeader != null) io.github.anastaci1a.localica.diagnostics.Diagnostics.info("CollectionHeaderAnchor",
                "phase=$phase anchor=${zoomAnchor != null} pending=$queryPending " +
                    "styleMatches=${zoomAnchor?.style == displayedStyle} rows=${result.items.size} " +
                    "capturing=${pinchMotion.capturing} held=${pinchMotion.held}", alsoLog = headerFrameObserver != null)
        }
        headerPhase("admission")
        val anchor = zoomAnchor ?: return@LaunchedEffect
        if (anchor.route != route) { zoomAnchor = null; return@LaunchedEffect }
        if (queryPending || displayedStyle != anchor.style) return@LaunchedEffect
        val index = result.items.indexOfFirst { it.key == anchor.key }
        try {
            if (index >= 0) scrollOwnership.during {
                headerPhase("position-request")
                // Recognition already owns and consumes the two-finger contact.
                // Retire the grid's earlier drag/fling mutation before requesting
                // this pinch's measured anchor. Unheld restoration keeps normal priority.
                if (pinchMotion.held) gridState.stopScroll(MutatePriority.UserInput)
                when (anchor.edge) {
                    -1 -> gridState.scrollToItem(0)
                    1 -> gridState.scrollToItem((gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    else -> gridState.scrollToItem(index + 1, -anchor.top)
                }
                headerPhase("position-measured")
                gridState.scroll(if (pinchMotion.held) MutatePriority.UserInput else MutatePriority.Default) {
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
                                scrollBy(anchor.correction(it.offset.y, it.size.height))
                            }
                        }
                    }
                    // displayedStyle gates the destination; scrollToItem forces its
                    // remeasurement. Read that measured item, never the source bounds,
                    // and correct before yielding a frame of coarse top alignment.
                    // Both this correction and the continuing loop own the same scroll.
                    correctAnchor()
                    headerPhase("anchor-corrected")
                    pinchHeader?.anchorCorrected(displayedStyle)
                    val start = withFrameNanos { it }
                    do {
                        val now = withFrameNanos { it }
                        correctAnchor()
                        pinchHeader?.anchorCorrected(displayedStyle)
                    // The same anchor owns preview AND release settlement. Ending it at
                    // finger-up lets onRelease recreate it and expose the initial coarse
                    // scrollToItem placement before fractional correction on the next frame.
                    } while (pinchMotion.source != null || now - start < if (reducedMotion) 32_000_000L else 450_000_000L)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            headerPhase("mutation-cancelled type=${cancelled.javaClass.simpleName}")
            throw cancelled
        } finally {
            val active = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true
            headerPhase("retired active=$active")
            // A relaunch (a library refresh flips queryPending, or the style changes) cancels this
            // run. While the pinch still owns its anchor, keep it: the relaunched run verifies it.
            if (zoomAnchor == anchor && (active || pinchMotion.source == null)) {
                zoomAnchor = null
                // Still active, nothing will run this anchor again: a user scroll took its mutation
                // (#535), or it ended without verifying because the anchor row left the result.
                // Hand the header back instead of leaving the settle, and every later pinch, waiting.
                // A verified handoff has already completed, so this is then a no-op (#542).
                if (active) pinchHeader?.abandonHandoff()
            }
        }
    }
    val surroundingBackdrop = LocalFrostedBackdrop.current
    val hasSongItems = result.items.any { it.row != null }
    val needsListBackdrop = (artDetail || hasSongItems || showFilters || showSearchPill || route.collection?.kind == MediaKind.Queue ||
        (route.collection == null && route.tab in setOf(MainTab.Library, MainTab.Playlists)))
    val listBackdrop = rememberFrostedBackdrop(presentation to surroundingBackdrop?.fallback,
        needsListBackdrop, dark = if (artDetail) !lightCollection else surroundingBackdrop?.dark == true,
        // Floating controls sample narrow regions. Let their clips cull rows
        // instead of rasterizing the entire scrolling viewport a second time.
        retainRaster = false)
    val backdropBacking = if (artDetail) collectionSource else surroundingBackdrop
    val backdropScene = rememberBrowseBackdropScene(listBackdrop, backdropBacking,
        listOf(presentation, validAlbums, if (artDetail) hero else surroundingBackdrop?.fallback))
    val maskedBackdrop = (showSearchPill || showFilters) && !artDetail
    val viewportBackdrop = if (needsListBackdrop) rememberBrowseBackdropLayer(backdropScene, maskedBackdrop) else null
    // #535 (b): the always-on stuck-state watchdog and phase trail feed the in-app diagnostics buffer.
    // Both observe state transitions through snapshot flows, so a healthy run does no per-frame work.
    PinchBreadcrumbs { pinchMotion.breadcrumb() }
    StuckStateWatchdog(
        lingering = {
            pinchMotion.source != null && !pinchMotion.held || pinchMotion.readbackPending ||
                zoomAnchor != null && pinchMotion.source == null
        },
        lingeringReport = {
            "${pinchMotion.diagnostics()} anchor=$zoomAnchor queryPending=$queryPending " +
                "displayed=${displayedStyle.presentation}/${displayedStyle.listSize} " +
                "scrolling=${gridState.isScrollInProgress} first=${gridState.firstVisibleItemIndex}/" +
                "${gridState.firstVisibleItemScrollOffset}"
        },
        recoveredReport = { pinchMotion.diagnostics() })
    val refreshBackdrop = LocalBackdropRefresh.current
    // Observe scroll start/stop in the effect, not this screen's composition.
    val currentRefreshBackdrop by rememberUpdatedState(refreshBackdrop)
    val currentArtDetail by rememberUpdatedState(artDetail)
    val currentCollectionSource by rememberUpdatedState(collectionSource)
    LaunchedEffect(presentation) {
        snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) {
                kotlinx.coroutines.delay(150)
                currentRefreshBackdrop()
                if (currentArtDetail) currentCollectionSource.refresh()
            }
        }
    }
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
    BrowseEffectScope {
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
                else gridState.easeToTarget(ListScrollTarget.below(target, latestTopInset), latestReducedMotion)
            } }
        } finally {
            if (followingOrder === request) followingOrder = null
            orderScroll.finish(request)
        }
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
    var manualQueueReturn by remember(returnOwner) { mutableStateOf(false) }
    var presentedReturnOwner by remember { mutableStateOf<Any?>(null) }
    SideEffect { if (!queryPending && loadedResult != null) presentedReturnOwner = returnOwner }
    // A cursor-only refresh need not blink a still-valid control. Genuine query or
    // occurrence changes hide it until the corresponding list has been presented.
    val returnListMatches = loadedResult != null && (!queryPending || presentedReturnOwner === returnOwner)
    // A zero-height notice is canonicalized to item 1 at offset 0 by the grid.
    // Its index alone cannot distinguish that visual home from a scrolled list.
    val noticeMeasurement = remember { BrowseNoticeMeasurement() }
    val noticeContent = remember(library.problem, library.unavailableFolders.isNotEmpty(),
        playlists.problem, library.loading,
        library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue,
        result.items.isEmpty() && !library.loading && !queryPending) { Any() }
    val measuresNotice = !artDetail && !showSearchPill && !showFilters
    val noticeDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val scrolledFromTop by remember(gridState, noticeMeasurement, noticeContent, measuresNotice,
        density, noticeDirection) { derivedStateOf {
        val height = noticeMeasurement.height(noticeContent, gridState.layoutInfo.viewportSize.width,
            density, noticeDirection)
        gridState.canScrollBackward && !(measuresNotice && height == 0 &&
            gridState.firstVisibleItemIndex == 1 && gridState.firstVisibleItemScrollOffset == 0)
    } }
    // A quick-search pill grows the header above the grid's retained item 1 / offset 0 anchor.
    // Shown at the visual top it must be revealed; a scrolled list keeps its place. The grid is
    // read without observation: scroll position must not recompose this screen.
    val hadSearchPill = remember(route.key) { booleanArrayOf(showSearchPill) }
    val revealSearchHeader = showSearchPill && !hadSearchPill[0] && androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
        gridState.firstVisibleItemIndex == 1 && gridState.firstVisibleItemScrollOffset == 0 &&
            noticeMeasurement.height(noticeContent, gridState.layoutInfo.viewportSize.width, density, noticeDirection) == 0
    }
    SideEffect {
        hadSearchPill[0] = showSearchPill
        if (revealSearchHeader) gridState.requestScrollToItem(0)
    }
    val clearableFilter = (route.tab == MainTab.Library && route.collection == null && route.filter != null) ||
        (artistDetail && validAlbums.isNotEmpty())
    // Recompose only when the offered action changes, not at every scroll start/stop.
    val returnAction by remember(gridState, clearableFilter, noticeContent, measuresNotice, density, noticeDirection) { derivedStateOf {
        listReturnAction(scrolledFromTop, gridState.isScrollInProgress, clearableFilter)
    } }
    DisposableEffect(playbackFollow) { onDispose { playbackFollow.cancel() } }
    var movingReturn by remember { mutableStateOf<ListReturnAction?>(null) }
    val currentReturnTarget by rememberUpdatedState<(ListReturnAction) -> ListScrollTarget> { action ->
        when {
            action == ListReturnAction.ScrollSongs -> collectionHome
            artDetail && collectionHeaderHeight > 0 && result.items.isNotEmpty() -> collectionTop
            else -> ListScrollTarget(0)
        }
    }
    val returnToList = ListReturnActions(scrollToTopRequest, returnOwner,
        selectionVisible && !playerOpen && !session.active && !localSearchOpen,
        backEnabled = listBackEnabled && !route.search && !localSearchOpen && !playerOpen,
        action = returnAction,
        onSubmit = { orderScroll.cancel(); playbackFollow.userScroll() },
        onRetarget = { action ->
            if (movingReturn != null && action in setOf(ListReturnAction.ScrollTop, ListReturnAction.ScrollSongs)) {
                movingReturn = action
                true
            } else false
        },
        ready = {
            snapshotFlow { readyToScroll }.first { it }
            withFrameNanos { }; withFrameNanos { }
        }, execute = { action ->
            manualQueueReturn = false
            localSearchOpen = false
            when (action) {
                ListReturnAction.ClearFilter -> if (artistDetail) selectedAlbums = emptyList()
                    else onRoute(route.copy(filter = null))
                ListReturnAction.ScrollTop, ListReturnAction.ScrollSongs -> scrollOwnership.during {
                    artworkTransit.during(!reducedMotion &&
                        gridState.firstVisibleItemIndex > gridState.layoutInfo.visibleItemsInfo.size * 2) {
                        movingReturn = action
                        try {
                            gridState.easeToTarget({ currentReturnTarget(movingReturn ?: action) }, reducedMotion)
                        } finally { movingReturn = null }
                    }
                }
                ListReturnAction.None -> Unit
            }
        })
    BackHandler(selectionVisible && session.active && route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) {
        session.clear()
    }
    BindSelectionScope(session, route.collection, result.rows, selectionVisible && !localSearchOpen)
    // The selection host publishes hover lifts; a drag starts its cards from them (#533).
    val selectionScene = remember(route.key) { SelectionScene() }
    SideEffect {
        reorder.hoverLift = { key -> selectionScene.hover[key] }; reorder.hoverBacking = backdropBacking
        reorder.currentSongKey = playingEntryId.takeIf { route.collection?.kind == MediaKind.Queue }
    }
    val dragHost = LocalSongDragHost.current
    val floatingControlClearance = when {
        route.collection?.kind == MediaKind.Queue -> 12.dp
        popupDismiss != null -> FloatingSurfaceGap
        else -> 0.dp
    }
    SideEffect {
        reorder.onDragStarted = {
            dragHost?.cancelBottomMargin = floatingControlClearance
            dragHost?.source = if (needsListBackdrop) listBackdrop else if (artDetail) collectionSource else surroundingBackdrop
            dragHost?.state = reorder; dragHost?.reducedMotion = reducedMotion
        }
        if (reorder.drag != null) reorder.onDragStarted()
        // Keep the foreground owner attached through the return to the resting rows.
        // Its shared overlay observes drag == null directly; a later SideEffect must
        // not remove and rebuild that owner across the pickup/landing frame boundary.
    }
    DisposableEffect(reorder, dragHost) { onDispose {
        reorder.onDragStarted = {}
        if (dragHost?.state === reorder) {
        dragHost.state = null
        dragHost.cancelBottomMargin = 0.dp
    } } }
    val editableOrder = route.text.isBlank() &&
        route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)
    // #694: artist sort and Group by album changes reorder the same keyed rows; reuse the
    // queue/playlist placement tween instead of jumping.
    val orderMotion = editableOrder || artistDetail && route.text.isBlank()
    SideEffect { reorder.reconcile(result.items); reorder.obscuredBottom = with(density) { obscuredInset.toPx() } }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
    // Only this collection's canonical occurrence order owns the drag lifetime. A
    // playback checkpoint can refresh all browse presentations without changing it.
    val dragSourceOrder = physicalOrder
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
    val initialOpening = queueOpeningRequest > playbackFollow.consumedOpeningRequest || playbackFollow.request?.initialPosition == true
    val initialEligible = route.collection?.kind == MediaKind.Queue && unfilteredOpening &&
        playingEntryId != null && (selectionVisible && !playerOpen || initialOpening) &&
        !session.active && !localSearchOpen && reorder.drag == null && pinchMotion.source == null && zoomAnchor == null &&
        !sorting && !collectionMenu && orderScroll.request == null && !listContact && !modalBlocked && modalOwner?.blocked != true
    val initialTarget = playbackFollow.initialTarget(playingEntryId, followOwner, initialEligible, queueOpeningRequest)
    val initialIndex = initialTarget?.takeIf { presentation?.route == route }?.let { target ->
        result.items.indexOfFirst { it.row?.id == target }.takeIf { it >= 0 }?.plus(1)
    }
    SideEffect {
        if (!unfilteredOpening) playbackFollow.consumeOpening(queueOpeningRequest)
        if (initialTarget != null && initialIndex != null) {
            // Public lazy admission targets the next measurement, before these rows
            // are placed/drawn. Never turn an opening into a frame-delayed ease.
            gridState.requestScrollToItem(initialIndex, -latestTopInset.roundToInt())
            playbackFollow.positionedInitially(initialTarget, followOwner, queueOpeningRequest)
        }
        playbackFollow.observe(playingEntryId.takeIf { route.collection?.kind == MediaKind.Queue },
            followOwner, followEligible && !modalBlocked && modalOwner?.blocked != true, modalOwner?.generation,
            automatic = !route.search, openingRequest = queueOpeningRequest, initialEligible = initialEligible)
    }
    fun userScroll() {
        manualQueueReturn = route.collection?.kind == MediaKind.Queue
        artistFilterRange.interrupt()
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
    val filterRangeRequest = artistFilterRange.request
    val filterRangeViewport by remember(gridState) { derivedStateOf { gridState.layoutInfo.viewportSize } }
    LaunchedEffect(filterRangeRequest, artistFilterRange.interruption, listContact, selectionVisible,
        playerOpen, modalBlocked, latestReducedMotion, displayedStyle, pinchMotion.source, zoomAnchor,
        result.items, filterRangeViewport, obscuredInset, density.density, density.fontScale) {
        val request = filterRangeRequest ?: return@LaunchedEffect
        if (listContact || !selectionVisible || playerOpen || modalBlocked || pinchMotion.source != null || zoomAnchor != null) return@LaunchedEffect
        // A touch/fling/key scroll owns its full mutation before any range reconciliation.
        withFrameNanos { }
        snapshotFlow { gridState.isScrollInProgress }.first { !it }
        val layout = snapshotFlow { gridState.layoutInfo }.first { measured ->
            measured !== request.previousLayout && measured.totalItemsCount == result.items.size + 2 &&
                measured.visibleItemsInfo.all { item -> item.key == "browser-notice" ||
                    item.key == ArtistFilterRange.GuardKey || result.items.getOrNull(item.index - 1)?.key == item.key }
        }
        if (!artistFilterRange.owns(request)) return@LaunchedEffect
        val guard = layout.visibleItemsInfo.firstOrNull { it.key == ArtistFilterRange.GuardKey }
        val excess = ArtistFilterRange.excess(guard?.offset?.y, layout.viewportEndOffset, layout.afterContentPadding)
        if (excess < 0f) {
            artistFilterRange.correct(request, gridState, scrollOwnership, excess, latestReducedMotion)
        }
        // Removing the reserve now exposes only the new real range; valid offsets never moved.
        artistFilterRange.finish(request)
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
    // Bind only the request that keyed this effect. The observation SideEffect can
    // publish the first request before a previously scheduled null-key effect runs.
    // Letting that effect borrow it would retire the same request on key replacement.
    // A completed manual trip to the current occurrence's physical end is a return.
    // Never replay a deferred target, move under a contact, or attach a scoped search.
    val boundaryCurrent = remember(result.items, playingEntryId) { result.items.indexOfFirst { it.row?.id == playingEntryId } }
    val boundaryReached by remember(gridState, boundaryCurrent, result.items.size, latestTopInset) { derivedStateOf {
        boundaryCurrent >= 0 && (boundaryCurrent == 0 || boundaryCurrent == result.items.lastIndex) &&
            ListScrollTarget.below(boundaryCurrent + 1, latestTopInset).direction(gridState) == 0
    } }
    val boundaryReturnReady = manualQueueReturn && !returnToList.pending && followEligible && !modalBlocked && !queryPending &&
        !route.search && route.text.isBlank() && !gridState.isScrollInProgress && boundaryReached
    SideEffect {
        if (boundaryReturnReady) {
            playbackFollow.reattachAtBoundary(playingEntryId, followOwner)
            manualQueueReturn = false
        }
    }
    val playbackRequest = playbackFollow.request
    LaunchedEffect(playbackRequest) {
        val request = playbackRequest ?: return@LaunchedEffect
        if (request.initialPosition) return@LaunchedEffect // Owned by pre-measure opening placement above.
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
                        gridState.easeToTarget(ListScrollTarget.below(target, latestTopInset), latestReducedMotion)
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
    val endpointMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val actionPainter = appIconPainter(AppIcon.ActionEllipsis)
    var contextMenu by remember { mutableStateOf<String?>(null) }
    // Rows whose action menu opened once keep it mounted (return morph, dialogs, saveable
    // workflow): one saveable set for the list rather than one registry entry per row.
    val mountedActions = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            { it.toList() }, { it.toMutableStateList() })) { mutableStateListOf<String>() }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<SongInformationRequest?>(null) }
    val informationLookupOwner = orderOwner
    val currentInformationLookupOwner by rememberUpdatedState(informationLookupOwner)
    val collectionLookup = rememberOwnedCollectionLookup(informationLookupOwner,
        library.snapshot, playlists.playlists, sorts, queryDispatcher) { item ->
        onOpen(item)
    }
    val currentCollectionLookup = rememberUpdatedState(collectionLookup)
    val pageColors = MaterialTheme.colorScheme
    val collectionColors = remember(pageColors, artDetail, collectionInk, lightCollection, hero.palette.bottom) {
        if (artDetail) pageColors.copy(onSurface = collectionInk, onSurfaceVariant = collectionInk.copy(alpha = .65f),
            primary = collectionInk, surface = if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else hero.palette.bottom)
        else pageColors
    }
    MaterialTheme(colorScheme = collectionColors) {
    CompositionLocalProvider(LocalArtworkTransit provides artworkTransit, LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        // Artwork pages shade loading material toward their ink like Library (#705: 4%, #F0F0F3
        // on #F9F9FB), so it keeps Library's contrast over any backdrop. Queue/search keep 50%.
        LocalLoadingMaterialColor provides collectionInk.copy(alpha = .04f).takeIf { artDetail },
        LocalLoadingMaterialOpacity provides if (!artDetail && route.collection?.kind in setOf(MediaKind.Artist,
            MediaKind.AlbumArtist, MediaKind.Album, MediaKind.Playlist, MediaKind.Queue)) .5f else 1f,
        LocalFrostedBackdrop provides if (artDetail) collectionSource else surroundingBackdrop,
        LocalEmptySearchBack provides popupDismiss?.takeIf { route.text.isBlank() }) {
    Box(Modifier.then(if (popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
    if (artDetail) CollectionBackground(hero, gridState, collectionSource, reducedMotion,
        Modifier.matchParentSize(), pinchHeader, artworkResetKey = if (artistDetail) route.collection to artistPhotoConsentAllowed else null)
    Column(Modifier.then(if (popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
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
        if (popupDismiss == null || route.text.isNotBlank()) BoxWithConstraints(Modifier.weight(1f, fill = popupDismiss == null).pointerInput(orderScroll, playbackFollow) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Retire automatic range motion before LazyGrid can acquire this
                // contact as a stop-fling drag and swallow an album-chip click.
                artistFilterRange.interrupt()
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
        }.onGloballyPositioned { reorder.viewportOrigin = it.positionInRoot(); listViewportTop = it.positionInRoot().y }.onSizeChanged {
            reorder.viewportHeight = it.height; reorder.viewportWidth = it.width
        }) {
            val viewportWidth = constraints.maxWidth
            val columns = galleryColumns(displayedStyle.presentation, with(density) { viewportWidth.toDp().value }, displayedStyle.itemsPerRow)
            val cellGeometry = BrowseCellGeometry(columns)
            val dragColors = MaterialTheme.colorScheme
            val dragInk = LocalContentColor.current
            SideEffect { reorder.style = displayedStyle; reorder.cellGeometry = cellGeometry; reorder.colors = dragColors; reorder.ink = dragInk; reorder.mutablePositions = mutablePositions }
            val loadingReducedMotion = reducedMotion || LocalReducedMotion.current
            val skeletonReveal = true
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
            if (loadedResult == null) BrowseLoadingViewport(displayedStyle, cellGeometry,
                viewportWidth, constraints.maxHeight, if (showFilters || showSearchPill) pillSlotHeight else 0.dp,
                popupDismiss == null && displayedStyle.extraInfo != ExtraInfo.None, artistOnlyLoading)
            else SelectionViewport((if (popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                // This host shares the viewport's origin and size, but not the hit
                // paths of floating Return/Search/utility controls layered above it.
                // A hold on those controls must never select the obscured song.
                .selectionPanelRelease(session, route.key,
                    enabled && !queryPending && route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) { position ->
                    // Dots and rows share selection-on-hold; the panel waits for release.
                    val item = reorder.hit(position)
                    item?.row != null
                }
                .songDrag(reorder, editableOrder && enabled, route.collection, session, reducedMotion,
                    displayedStyle.presentation == Presentation.List, acquisitionReady = !queryPending,
                    sourceOrder = dragSourceOrder, routeKey = route, commit = moveSongs)
                // Floating controls sample the finished scrolling scene, including
                // its fade and selection/overscroll transforms, never the raw grid.
                // Queue already paints one continuous drawer material beneath its
                // header and list. Record only list foreground here; the sampling
                // composite adds backdropBacking independently. Repainting it in
                // this displayed viewport would erase header-shadow spill at its edge.
                .then(if (needsListBackdrop) Modifier.recordBrowseBackdropLayer(backdropScene, requireNotNull(viewportBackdrop),
                    if (route.collection?.kind == MediaKind.Queue) null else backdropBacking)
                    .then(if (surroundingBackdrop == null && !artDetail) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier) else Modifier)
                .graphicsLayer { alpha = if (artDetail || (skeletonReveal && !queryPending)) 1f else browse.reveal.value }
                .then(if (maskedBackdrop) Modifier.listTopFade {
                    if (showSearchPill) {
                        // Invalidate with the same motion, then draw the fraction
                        // consumed by actual placement rather than lookahead.
                        searchAnchor.searchSlot.fraction
                        1f - searchSlotLayoutOwner.placedFraction
                    } else 1f
                } else Modifier)
                .then(if (maskedBackdrop && viewportBackdrop != null) Modifier.browseBackdropMaskWitness(backdropScene, viewportBackdrop) else Modifier),
                contentDrawing = Modifier.onPlaced { pinchHeader?.viewport = it }
                    .collectionPinchOverlay(pinchHeader).graphicsLayer()
                    .pinchRetainedOverlay(pinchMotion, gridState, pinchIndices, selectionScene),
                scene = selectionScene) { overscroll ->
            val lookahead = this@LookaheadScope
            val gridItemContent: @Composable androidx.compose.foundation.lazy.grid.LazyGridItemScope.(BrowserItem?) -> Unit = remember(
                itemIndices, skeletonReveal, loadingReveal, route, playingEntryId, session, artDetail,
                orderMotion, pinchMotion, reorder, reducedMotion, entrySweep, gridState, pinchIndices,
                mountedActions, density, obscuredInset, displayedStyle, actionPainter, queryPending, enabled,
                playlists, playlistController, queues, pins, onPin, currentCollectionLookup, cellGeometry, editableOrder,
                searchSlotLayoutObserver, searchSlotLayoutOwner, showFilters, showSearchPill, pillSlotHeight,
                onSearchCommit, onOpen, onPlay, result, popupDismiss, inheritedInformation, artistOnlyLoading,
                mutablePositions, endpointMeasurer, lookahead) { row@{ entry ->
                    if (entry == null) {
                        val height = with(androidx.compose.ui.platform.LocalDensity.current) { (reorder.drag?.cardSize?.height ?: 0f).toDp() }
                        Box(Modifier.fillMaxWidth().height(height).testTag("drop-gap"))
                        return@row
                    }
                    val item = entry
                    val itemIndex = itemIndices[item.key] ?: 0
                    val loadingCell = if (skeletonReveal && !loadingReveal.complete) loadingReveal.cell(itemIndex) else null
                    val current = route.collection?.kind == MediaKind.Queue && item.row != null && item.row.id == playingEntryId
                    val selectedCount = session.selectedCount(item, route.collection)
                    val partiallySelected = item.row == null && selectedCount > 0 && selectedCount < item.songs.size
                    // Search already owns the spacer and row displacement. A second
                    // reorder tween would trail that measured placement every frame.
                    Column(Modifier.then(if (artDetail && itemIndex == 0) Modifier.testTag("browser-song-list-start") else Modifier)
                        .then(if (orderMotion && pinchMotion.visualSource == null) Modifier.animateItem(placementSpec = if (
                        searchOriginalOffset >= 0 && searchVacancy > 0f ||
                        reorder.drag?.let { it.dropping && item.key in it.keys } == true
                    ) null else androidx.compose.animation.core.tween(if (reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null) else Modifier)
                        .graphicsLayer {
                            val sweep = 1f
                            alpha = sweep * (reorder.drag?.takeIf { it.dropping && item.key in it.keys }?.handoff?.value ?: 1f)
                            // Write only a change: an identical boxed value still replaces the
                            // map and would invalidate the retained overlay on every layer update.
                            // Do not subscribe this layer to other cells' entries.
                            val previous = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                                pinchMotion.itemAlpha[item.key]
                            }
                            if (previous != alpha) pinchMotion.itemAlpha[item.key] = alpha
                            // The row body, frost and selection share the overlay's departure/arrival fade (#559).
                            alpha *= pinchMotion.pinchFadeAlpha(item.key, gridState, pinchIndices)
                        }) {
                        // Placement callbacks need not repeat for a re-keyed owner; register the
                        // last placed coordinates whenever this row's key or drag owner changes.
                        val cellCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                        // One measured outline (#532) for this cell's frost and selection.
                        val itemOutline = remember { MediaItemOutlineHolder() }
                        val hover = remember { SelectionHoverHandle() }
                        DisposableEffect(item.key, reorder) {
                            cellCoordinates[0]?.let { reorder.itemCoordinates[item.key] = it }
                            onDispose { reorder.itemCoordinates.remove(item.key) }
                        }
                        val cellWork = LocalBrowseCellWorkObserver.current
                        if (cellWork != null) SideEffect { cellWork("row", item.key) }
                        if (cellWork != null) {
                            val scheme = MaterialTheme.colorScheme
                            val backdrop = LocalFrostedBackdrop.current
                            SideEffect { cellWork("row-scheme-owner", scheme);cellWork("row-backdrop-owner", backdrop ?: Unit) }
                        }
                        // A closed menu is not composed until its first opening. It then stays
                        // mounted with this row for its return morph, dialogs and saveable workflow.
                        val actionsMounted = item.key in mountedActions
                        // Gallery artwork publishes its menu origin only when the menu opens.
                        val menuAnchor = rememberGlassAnchor(deferContextPlacement = true)
                        SideEffect { menuAnchor.contextual = true; menuAnchor.bottomClearance = with(density) { obscuredInset.toPx() } }
                        val mutableSong = item.row != null && (route.collection?.kind == MediaKind.Playlist || route.collection?.kind == MediaKind.Queue)
                        val holdLabel = if (mutableSong) "Select song" else if (item.row != null) "Song actions" else "Collection actions"
                        val onHold = {
                            if (mutableSong) session.select(route.collection, listOf(requireNotNull(item.row)))
                            else { menuAnchor.prepareContextPlacement(); contextMenu = item.key }
                        }
                        val itemMenu: @Composable (Boolean) -> Unit = { showButton ->
                            Box {
                                if (showButton) ActionIcon(AppIcon.ActionEllipsis, "Actions for " + item.title, anchor = menuAnchor,
                                    quietInputEnabled = displayedStyle.presentation == Presentation.List, quiet = true, painter = actionPainter, onLongClickLabel = holdLabel,
                                    onLongClick = onHold.takeIf { !queryPending && (item.row == null || enabled) }) { menuAnchor.prepareContextPlacement(); contextMenu = item.key }
                                val menuOpen = contextMenu == item.key
                                if (menuOpen || actionsMounted) {
                                    if (!actionsMounted) SideEffect { if (item.key !in mountedActions) mountedActions.add(item.key) }
                                    if (cellWork != null) SideEffect { cellWork("actions", item.key) }
                                    CollectionActions(menuOpen, menuAnchor, item, playlists, playlistController, queues, pins, onPin, { contextMenu = null },
                                        onInformationPresented = { song, presentation -> currentCollectionLookup.value.cancel(); info = SongInformationRequest(song, presentation) }, containingSource = route.collection, selection = session,
                                        onRemove = if (item.row == null) null else when (route.collection?.kind) {
                                            MediaKind.Queue -> ({ queues?.removeEntries(setOf(item.key), route.collection.id); Unit })
                                            MediaKind.Playlist -> ({ playlistController?.removeEntries(route.collection.id, setOf(item.key)) { }; Unit })
                                            else -> null
                                        })
                                }
                            }
                        }
                        item.groupHeading?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                        Column(Modifier.browseBeyondBounds(pinchMotion.beyondBounds)
                            .then(if (pinchMotion.visualSource == null) Modifier.animateBounds(lookahead,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(displayedStyle).dp),
                            boundsTransform = { _, _ -> androidx.compose.animation.core.tween(if (reducedMotion || editableOrder || loadingCell != null) 0 else 350) })
                            else Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(displayedStyle).dp)
                                .pinchItemPlacement(pinchMotion, gridState, item.key, itemIndex, displayedStyle, cellGeometry))
                            // Drag hit testing reads these live coordinates when needed; keeping the
                            // reference does not require a callback for every scrolled frame.
                            .onPlaced { cellCoordinates[0] = it; reorder.itemCoordinates[item.key] = it }
                            // Only the loading reveal and the first-row search-slot witness need the
                            // per-frame global position. A completed reveal leaves scrolling alone.
                            .then(if (loadingCell == null && (itemIndex != 0 || searchSlotLayoutObserver == null)) Modifier
                            else Modifier.onGloballyPositioned {
                                if (cellWork != null) cellWork("global-position", item.key)
                                if (itemIndex == 0) searchSlotLayoutObserver?.invoke(
                                    SearchSlotLayoutSample(searchSlotLayoutOwner.placedFraction, it.positionInRoot().y))
                                val origin = it.positionInRoot() - reorder.viewportOrigin
                                loadingCell?.place(origin.x / density.density,
                                    (origin.y - with(density) { (if (showFilters || showSearchPill) pillSlotHeight else 0.dp).toPx() }) / density.density)
                            })
                            // Frost is redrawn separately from frozen content so its
                            // corners stay identical to selection during pinch. While the
                            // row hovers (#533) the same frost is drawn inside its lifted layer.
                            .currentSongHighlight(current, MaterialTheme.colorScheme.primary, { _, _ -> itemOutline.value },
                                visible = { hover.progress() <= 0f }) { size ->
                                pinchMotion.highlightSize(item.key, itemIndex, size)
                            }
                            .selectionLens(selectedCount > 0, partiallySelected, itemKey = item.key, anchor = menuAnchor, outline = { _, _ -> itemOutline.value },
                                handle = hover, alpha = { (pinchMotion.itemAlpha[item.key] ?: 1f) * pinchMotion.pinchFadeAlpha(item.key, gridState, pinchIndices) }) { size ->
                                pinchMotion.highlightSize(item.key, itemIndex, size)
                            }
                            .currentSongHighlight(current, MaterialTheme.colorScheme.primary, { _, _ -> itemOutline.value },
                                visible = { hover.progress() > 0f })
                            .retainPinchItem(item.key, pinchMotion, MaterialTheme.colorScheme.primary.takeIf { current })
                            .then(if (pinchMotion.visualSource == null) Modifier.clip(Corners.card) else Modifier)
                            .testTag("media-${item.key}").combinedClickable(
                            enabled = !queryPending && (item.row == null || enabled),
                            onClick = {
                                if (item.row == null) { onSearchCommit(); onOpen(item) }
                                else if (session.active) session.toggle(route.collection, item.row)
                                else { onSearchCommit(); onPlay(result.rows, result.rows.indexOfFirst { it.id == item.row.id }, result.scope) }
                            }, onLongClickLabel = holdLabel, onLongClick = onHold
                        )) {
                            BrowseItemLayout(item, displayedStyle, cellGeometry, lookahead, reducedMotion, itemMenu,
                                showInformation = popupDismiss == null, inheritedInformation = inheritedInformation, itemIndex = itemIndex,
                                loadingReveal = loadingCell, artistOnlyLoading = artistOnlyLoading, mutablePosition = mutablePositions[item.key],
                                trackNumber = if (route.collection?.kind == MediaKind.Album) item.row?.track?.trackNumber?.toString() ?: (itemIndex + 1).toString() else null, pinch = pinchMotion, artworkAnchor = menuAnchor,
                                outline = itemOutline, endpointMeasurer = endpointMeasurer)
                        }

                    }

            } }
            if (screenWork != null) {
                val inputs = arrayOf<Any?>(itemIndices, skeletonReveal, loadingReveal, route, playingEntryId, session, artDetail,
                orderMotion, pinchMotion, reorder, reducedMotion, entrySweep, gridState, pinchIndices,
                mountedActions, density, obscuredInset, displayedStyle, actionPainter, queryPending, enabled,
                playlists, playlistController, queues, pins, onPin, currentCollectionLookup, cellGeometry, editableOrder,
                searchSlotLayoutObserver, searchSlotLayoutOwner, showFilters, showSearchPill, pillSlotHeight,
                onSearchCommit, onOpen, onPlay, result, popupDismiss, inheritedInformation, artistOnlyLoading,
                mutablePositions, endpointMeasurer, lookahead)
                val previous = remember { arrayOfNulls<Array<Any?>>(1) }
                SideEffect {
                    previous[0]?.let { old ->
                        val names = arrayOf("itemIndices", "skeletonReveal", "loadingReveal", "route", "playingEntryId", "session", "artDetail", "orderMotion", "pinchMotion", "reorder", "reducedMotion", "entrySweep", "gridState", "pinchIndices", "mountedActions", "density", "obscuredInset", "displayedStyle", "actionPainter", "queryPending", "enabled", "playlists", "playlistController", "queues", "pins", "onPin", "currentCollectionLookup", "cellGeometry", "editableOrder", "searchSlotLayoutObserver", "searchSlotLayoutOwner", "showFilters", "showSearchPill", "pillSlotHeight", "onSearchCommit", "onOpen", "onPlay", "result", "popupDismiss", "inheritedInformation", "artistOnlyLoading", "mutablePositions", "endpointMeasurer", "lookahead")
                        inputs.forEachIndexed { index, value -> if (old[index] != value) screenWork("row-key:" + names[index], route.key) }
                    }
                    previous[0] = inputs
                    screenWork("row-content-owner", gridItemContent)
                }
            }
            LazyVerticalGrid(state = gridState, overscrollEffect = overscroll, columns = GridCells.Fixed(columns), modifier = (if (popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .then(if (showSearchPill) searchSlotGridLayout else Modifier)
                .longScrollFade(gridState)
                .then(if (scrollFrameProbe == null) Modifier else Modifier.drawWithContent {
                    scrollFrameProbe.request // Explicit test readback also redraws settled cached layers.
                    val pillMode = scrollFrameProbe.searchPill // Test draw invalidation on the mode flip.
                    drawContent()
                    val layout = gridState.layoutInfo
                    scrollFrameProbe.frame = BrowseScrollFrame(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
                        layout.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.height,
                        layout.visibleItemsInfo.firstOrNull { it.index > 0 }?.size?.height,
                        layout.viewportSize.width, noticeMeasurement.width, noticeMeasurement.lookahead, noticeMeasurement.contentIdentity,
                        layout.visibleItemsInfo.firstOrNull { it.index == 0 }?.offset?.y, pillMode, scrollFrameProbe.serial + 1,
                        android.view.animation.AnimationUtils.currentAnimationTimeMillis())
                    scrollFrameProbe.serial++
                    scrollFrameProbe.onFrame?.invoke(scrollFrameProbe.frame!!)
                })
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
                    bottom = obscuredInset +
                        maxOf(with(density) { returnControlHeight.toDp() } + floatingControlClearance,
                            with(density) { actionControlHeight.toDp() }) + 16.dp)) {
                item(key = "browser-notice", contentType = "notice", span = { GridItemSpan(maxLineSpan) }) {
                    if (measuresNotice) {
                        // The grid canonicalizes a zero-height notice to item 1 / offset 0 and then
                        // deactivates item 0. Pinning keeps the actual notice composed and measured
                        // there, so its content changes are observed instead of inferred.
                        val pinnable = androidx.compose.ui.layout.LocalPinnableContainer.current
                        DisposableEffect(pinnable) { val pin = pinnable?.pin(); onDispose { pin?.release() } }
                    }
                    // Grid gutters belong to tiles; full-span headers keep their viewport alignment.
                    Column(Modifier.requiredWidth(with(density) { viewportWidth.toDp() }).testTag("browse-full-span")
                        .then(if (measuresNotice) Modifier.measureBrowseNotice(noticeMeasurement, noticeContent) else Modifier)
                        .onSizeChanged { if (artDetail) collectionHeaderHeight = it.height }
                        .then(if (pinchHeader != null) Modifier.collectionPinchHeader(pinchHeader, displayedStyle) else Modifier)) {
                        if (artDetail) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides collectionSource) {
                                CollectionHeader(detailPlaylist?.name ?: route.title ?: seededCollection?.title.orEmpty(), if (playlistDetail) songCountLabel(detailPlaylist?.entries?.size ?: 0)
                                    else if (artistDetail) null else (sourceResult.rows.firstOrNull()?.track ?: seededCollection?.songs?.firstOrNull()?.track)?.let { it.albumArtist ?: it.artist },
                                    hero, artistDetail, enabled && result.rows.isNotEmpty(),
                                    onPlay = { onPlay(result.rows, 0, result.scope) }, onShuffle = {
                                        if (queues != null) queues.randomizedQueue(result.rows.map { it.track }, route.title ?: "Collection", result.rows.map { it.sourcePlaylistId })
                                        else onPlay(result.rows.shuffled(), 0, result.scope)
                                    }, playlist = playlistDetail, pinchHeader = pinchHeader,
                                    onActionBottom = { collectionActionBottom = it })
                                if (artistDetail) ArtistAlbumChoices(albums, validAlbums, ::chooseArtistAlbums)
                                if ((artistDetail || playlistDetail) && showSearchPill) Box(Modifier.fillMaxWidth().then(searchSlotLayout)
                                    .onSizeChanged { measuredSearchSlotHeight = it.height }.clipToBounds()) {
                                    ListSearchPill("Search " + (route.title ?: route.tab.label), {
                                        if (searchVacancy == 0f && searchOriginalOffset < 0) {
                                            searchOriginalOffset = if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset else -1
                                            searchVacancy = with(density) { searchSlotVacancy(pillSlotHeight.toPx(),
                                                pillMaterialTopInset.toPx(), cellGeometry.cardVerticalInset(displayedStyle).dp.toPx()) }
                                        }
                                        localSearch = freshSearch(); localFocusRequest++; localSearchOpen = true
                                    }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
                                }
                            }
                        }
                        if (!artDetail && (showSearchPill || showFilters)) Spacer((if (showSearchPill) searchSlotLayout else Modifier.height(pillSlotHeight))
                            .then(if (showSearchPill) Modifier.onSizeChanged { measuredSearchSlotHeight = it.height } else Modifier))
                        LibraryNotice(library)
                        playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (library.snapshot.folders.isEmpty() && route.collection?.kind != MediaKind.Queue) ActionTextButton(onClick = onSettings) { Text("Add music folders") }
                        if (result.items.isEmpty() && !library.loading && !queryPending) Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            Icon(appIconPainter(AppIcon.TabQueues),
                                if (route.search) "No matching items" else "Empty list", Modifier.size(72.dp),
                                tint = LocalContentColor.current.copy(alpha = .13f))
                        }
                    }
                }
                items(reorder.rendered(result.items), key = { it?.key ?: DropGapKey },
                    contentType = { if (it == null) "gap" else it.ref.kind.name }, itemContent = gridItemContent)
                artistFilterRange.request?.let { request ->
                    item(key = ArtistFilterRange.GuardKey, span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(with(density) { request.reservePx.toDp() })
                            .testTag("artist-filter-range-reserve"))
                    }
                }
            }
            // Passive draw producer only: do not wrap the grid or install a new
            // hit path. SelectionViewport supplies the shared clip/overscroll.
            if (pinchHeader != null) CollectionHeaderActionPresentation(pinchHeader, collectionSource)
            }
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides
                if (needsListBackdrop) listBackdrop else if (artDetail) collectionSource else surroundingBackdrop) {
            if (showFilters) Box(Modifier.align(Alignment.TopStart)) {
                LibraryFilterBubbles(filters, route.filter, reducedMotion,
                    { onRoute(route.copy(filter = it)) }, onFilterSettings)
            }
            if (showSearchPill && !artDetail) ListSearchPill("Search "+ (route.title ?: route.tab.label), {
                // A reversal keeps the same original vacancy/offset until the actual
                // return reaches home; do not capture a partly collapsed replacement.
                if (searchVacancy == 0f && searchOriginalOffset < 0 && gridState.firstVisibleItemIndex == 0) {
                    searchOriginalOffset = gridState.firstVisibleItemScrollOffset
                    searchVacancy = with(density) { searchSlotVacancy(
                        (pillSlotHeight.toPx() - searchOriginalOffset).coerceAtLeast(0f),
                        pillMaterialTopInset.toPx(), cellGeometry.cardVerticalInset(displayedStyle).dp.toPx()) }
                }
                localSearch = freshSearch(); localFocusRequest++; localSearchOpen = true
            }, Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
            if (dragHost == null) SongDragOverlay(reorder, reducedMotion)
            val playingIndex = remember(result.items, playingEntryId) {
                result.items.indexOfFirst { it.row?.id == playingEntryId }.takeIf { playingEntryId != null && it >= 0 }?.plus(1)
            }
            val returnTarget = playingIndex?.let { ListScrollTarget.below(it, latestTopInset) }
            val returnDirection by remember(returnTarget, gridState) { derivedStateOf {
                returnTarget?.direction(gridState) ?: 0
            } }
            // Scoped search retains its explicit offscreen-only offer; alignment
            // and visibility are distinct, but every offered arrow uses the target.
            val hiddenBottom = with(density) { obscuredInset.toPx() }
            val currentOffscreen by remember(playingIndex, latestTopInset, hiddenBottom) { derivedStateOf {
                val layout = gridState.layoutInfo
                val current = layout.visibleItemsInfo.firstOrNull { it.index == playingIndex }
                playingIndex != null && (current == null ||
                    current.offset.y + current.size.height <= latestTopInset ||
                    current.offset.y >= layout.viewportEndOffset - hiddenBottom)
            } }
            // Active queues retain their occurrence return. Other current song lists,
            // including inactive queues, return to their own top without changing filters.
            val playbackReturn = route.collection?.kind == MediaKind.Queue && playingEntryId != null
            val returning = returnToList.pending || (playbackReturn && playbackFollow.request != null)
            val songsPastTop by remember(gridState, collectionHome) { derivedStateOf {
                collectionHome.displacement(gridState) < -1f
            } }
            val showReturn = selectionVisible && !playerOpen && !localSearchOpen && returnListMatches &&
                !session.active && reorder.drag == null && !(playbackReturn && returning) &&
                (if (playbackReturn) playingIndex != null && (if (route.search) currentOffscreen else !playbackFollow.attached)
                    else hasSongItems && (if (artDetail) songsPastTop else scrolledFromTop))
            // A drag owns this slot exclusively; retire a half-faded return drawing
            // instead of overlapping its replacement X during a quick hold-and-move.
            key(reorder.drag != null) { CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
                FloatingReturnToPositionButton(up = !playbackReturn || returnDirection < 0,
                    label = if (playbackReturn) "Current song" else "Return to top",
                    visible = showReturn, reducedMotion = reducedMotion,
                    returning = playbackReturn && returning,
                    onHeightMeasured = { returnControlHeight = it },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = obscuredInset + floatingControlClearance)) {
                    if (!playbackReturn) returnToList(if (artDetail) ListReturnAction.ScrollSongs else ListReturnAction.ScrollTop)
                    else {
                        orderScroll.cancel()
                        returnToList.cancel()
                        playbackFollow.returnToCurrent(playingEntryId, followOwner,
                            followAvailable && !listContact && modalOwner?.blocked != true, modalOwner?.generation)
                    }
                }
            } }
            // The enlarged scrollbar hit strip must start below fixed header controls,
            // otherwise it wins hit testing over right-edge filter/search taps.
            if (loadedResult != null) BrowseScrollbar(gridState, result.items, displayedStyle.sort,
                Modifier.align(Alignment.CenterEnd).padding(
                    top = if (showFilters || (showSearchPill && !artDetail)) pillSlotHeight else 0.dp,
                    bottom = obscuredInset), onUserScroll = ::userScroll)
            if (route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist) && !route.search) {
                val playlist = playlists.playlists.firstOrNull { it.id == route.collection?.id }
                if (playlist != null || artDetail) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset)
                    .onSizeChanged { actionControlHeight = it.height }) {
                    val anchor = rememberGlassAnchor()
                    var open by remember { mutableStateOf(false) }
                    ActionIcon(AppIcon.ActionEllipsis, if (playlist != null) "Playlist actions" else if (artistDetail) "Artist actions" else "Album actions", anchor = anchor) { open = true }
                    CollectionActions(open, anchor, collectionActionTarget(requireNotNull(route.collection), playlist?.name ?: route.title.orEmpty(), result, playlist?.coverUri),
                        playlists, playlistController, queues, pins, onPin, { open = false },
                        onDeleted = { onRoute(route.copy(collection = null, title = null)) },
                        onRenamed = { onRoute(route.copy(title = it)) })
                }
            }
            if (route.tab in setOf(MainTab.Library, MainTab.Playlists) && route.collection == null && !route.search)
                PlaylistCreationButton(library.snapshot, playlists, playlistController, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset).testTag("playlist-add-module")
                    .onSizeChanged { actionControlHeight = it.height })
            }
        }
        }
        if (LocalSessionSelection.current == null) CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
            SelectionPanelVisibility(session.panelVisible, reducedMotion = reducedMotion) {
                SessionSelectionPanel(session, queues, playlistController, playlists, dragging = reorder.drag != null)
            }
        }
    }

    if (artDetail) CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
        CollectionToolbar(gridState, reorder.viewportWidth, route.title.orEmpty(), collectionMenuAnchor,
            onBack, { view -> collectionView = view; collectionMenu = true }, backdropScene, pinchHeader,
            onBackBottom = { collectionBackBottom = it }) {
            if (collectionMenu) CompositionLocalProvider(LocalGlassOrigin provides collectionMenuAnchor) { sortingPanel(initialView = collectionView) }
        }
    }
    if (needsListBackdrop) BrowseBackdropRecording(backdropScene, backdropBacking)
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
    info?.let { request -> TagEditorPresentationScope(request.presentation) {
    val song = request.track
    SongInformation(song, { collectionLookup.cancel(); info = null }, folders = library.snapshot.folders) { action ->
        // SongInformation intentionally dismisses before invoking this accepted action.
        val owner = informationLookupOwner
        collectionLookup.open(action, song) { info == null && currentInformationLookupOwner === owner }
    } } }
    if (sorting) CompositionLocalProvider(LocalGlassOrigin provides sortOrigin) { sortingPanel(initialView = presentationOnly) }
}


/** Effect-only state changes must not recreate the browse screen or grid callbacks. */
@Composable
private fun BrowseEffectScope(content: @Composable () -> Unit) { content() }
