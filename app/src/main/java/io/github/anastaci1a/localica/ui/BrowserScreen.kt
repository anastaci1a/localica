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
internal data class BrowseScrollFrame(val index: Int, val offset: Int, val headerHeight: Int?, val rowHeight: Int?)
internal class BrowseScrollFrameProbe {
    var request by mutableIntStateOf(0)
    @Volatile var serial = 0
    @Volatile var frame: BrowseScrollFrame? = null
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
    BrowseScreenContent(BrowseScreenInputs(
        library, playlists, route, onRoute, onOpen, onSettings, onPlay, queues, playlistController, enabled, playerOpen, pins, onPin, sorting, onSortDismiss, queueState, playingTrackId, playingEntryId, header, presentationOnly, selectionVisible, onSearchEdit, onSearchCommit, scrollToTopRequest, reducedMotion, listBackEnabled, libraryFilters, onFilterSettings, popupDismiss, scopedEditor, focusRequest, sortOrigin, queryDispatcher, onBack, queueOpeningRequest, onAppearance))
}

private class BrowseScreenInputs(
    val library: LibraryUiState,
    val playlists: PlaylistState,
    val route: BrowseRoute,
    val onRoute: (BrowseRoute) -> Unit,
    val onOpen: (BrowserItem) -> Unit,
    val onSettings: () -> Unit,
    val onPlay: (List<SongRow>, Int, SongListView) -> Unit,
    val queues: QueueController?,
    val playlistController: PlaylistController?,
    val enabled: Boolean,
    val playerOpen: Boolean,
    val pins: List<MediaRef>,
    val onPin: (MediaRef) -> Unit,
    val sorting: Boolean,
    val onSortDismiss: () -> Unit,
    val queueState: QueueUiState,
    val playingTrackId: String?,
    val playingEntryId: String?,
    val header: @Composable () -> Unit,
    val presentationOnly: Boolean,
    val selectionVisible: Boolean,
    val onSearchEdit: (BrowseRoute) -> Unit,
    val onSearchCommit: () -> Unit,
    val scrollToTopRequest: Int,
    val reducedMotion: Boolean,
    val listBackEnabled: Boolean,
    val libraryFilters: LibraryFilters,
    val onFilterSettings: () -> Unit,
    val popupDismiss: (() -> Unit)?,
    val scopedEditor: ScopedSearchEditorSession?,
    val focusRequest: Int,
    val sortOrigin: GlassAnchor?,
    val queryDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    val onBack: () -> Unit,
    val queueOpeningRequest: Long,
    val onAppearance: (androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color) -> Unit)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
@NonRestartableComposable
private fun BrowseScreenContent(inputs: BrowseScreenInputs) {

    val searchEditTrace = LocalSearchEditTrace.current
    if (searchEditTrace != null && inputs.route.search) {
        DisposableEffect(inputs.route.key) {
            searchEditTrace("editor composition attach key=${inputs.route.key.hashCode()} length=${inputs.route.text.length} committed=${inputs.route.queryCommitted}")
            onDispose { searchEditTrace("editor composition dispose key=${inputs.route.key.hashCode()}") }
        }
        SideEffect { searchEditTrace("editor composition render key=${inputs.route.key.hashCode()} length=${inputs.route.text.length} committed=${inputs.route.queryCommitted}") }
    }
    val localSearchOpenState = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val searchAnchor = rememberGlassAnchor()
    val modalBackdrop = LocalModalBackdrop.current
    val searchSlot = rememberBrowseSearchSlot()
    val localSearchOwner = rememberBrowseLocalSearch(inputs.route, localSearchOpenState)
    val scrollFrameProbe = LocalBrowseScrollFrameProbe.current
    val screenWork = LocalBrowseCellWorkObserver.current
    if (screenWork != null) SideEffect { screenWork("screen", inputs.route.key) }
    val obscuredInset = LocalBrowseObscuredInset.current
    var returnControlHeight by remember { mutableIntStateOf(0) }
    var actionControlHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val sorts = rememberBrowseSortSnapshot()
    val preferences = remember(context) { BrowsePreferences(context) }
    val inheritedInformation by rememberBrowseInheritedInformation(preferences)
    val currentRouteState = rememberUpdatedState(inputs.route)
    val currentRoute by currentRouteState
    val styleState = remember(inputs.route.key, inputs.route.filter) { mutableStateOf(preferences.read(inputs.route).let { it.copy(sort = inputs.route.querySort ?: it.sort, ascending = inputs.route.queryAscending ?: it.ascending) }) }
    var style by styleState
    BrowseStyleObserver(preferences, inputs.route, currentRouteState, styleState)
    fun editSearchText(text: String) {
        searchEditTrace?.invoke("editor value key=${inputs.route.key.hashCode()} oldLength=${inputs.route.text.length} newLength=${text.length} committed=${inputs.route.queryCommitted}")
        inputs.onSearchEdit(inputs.route.copy(text = text, querySort = style.sort, queryAscending = style.ascending))
    }
    val filters = if (inputs.route.globalSearch) LibraryFilter.entries else inputs.libraryFilters.visible
    val showFilters = inputs.route.collection == null && (inputs.route.tab == MainTab.Library || inputs.route.globalSearch) && filters.isNotEmpty()
    val pillSlotHeight = listPillSlotHeight()
    val pillMaterialTopInset = ListPillVerticalInset + (listPillTargetHeight() - listPillHeight()) / 2
    val showSearchPill = !inputs.route.search && inputs.route.collection?.kind != MediaKind.Album && style.quickSearch && (inputs.route.collection != null || inputs.route.tab != MainTab.Library)
    val routeFocus = remember(inputs.route.key) { androidx.compose.ui.focus.FocusRequester() }
    val focus = inputs.scopedEditor?.focus ?: routeFocus
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val resultsFocus = remember(inputs.route.key) { androidx.compose.ui.focus.FocusRequester() }
    val submitSearch = browseSearchAutofocus(inputs.route, inputs.scopedEditor, inputs.focusRequest, inputs.playerOpen, focus, resultsFocus, keyboard, density,
        inputs.onSearchCommit, searchEditTrace)
    BrowseVisibleFilterEffect(inputs.libraryFilters, inputs.route, inputs.selectionVisible, filters, inputs.onRoute)
    val touchExploration = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager)?.isTouchExplorationEnabled == true }
    val playbackFollow = androidx.compose.runtime.saveable.rememberSaveable(inputs.route.key, inputs.route.collection,
        saver = androidx.compose.runtime.saveable.Saver<QueuePlaybackFollowCoordinator, Any>(
            save = { arrayListOf(it.attached, it.consumedOpeningRequest) },
            restore = { QueuePlaybackFollowCoordinator.restoreState(it) })) {
        QueuePlaybackFollowCoordinator(!touchExploration)
    }
    val unfilteredOpening = !inputs.route.search && !inputs.route.globalSearch && inputs.route.text.isBlank() && inputs.route.filter == null && !localSearchOwner.open
    val catalog = rememberBrowseScreenCatalog(inputs, style, sorts, unfilteredOpening, playbackFollow)
    var selectedAlbums by catalog.selectedAlbumsState
    val lightCollection = collectionIsLight(catalog.hero)
    val collectionInk = if (lightCollection) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White
    SideEffect { if (inputs.selectionVisible) inputs.onAppearance(if (catalog.artDetail) collectionInk else androidx.compose.ui.graphics.Color.Black,
        if (catalog.artDetail) { if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else catalog.hero.palette.bottom } else androidx.compose.ui.graphics.Color(0xFFF9F9FB)) }
    val collectionSource = rememberFrostedBackdrop(catalog.hero, catalog.artDetail, dark = !lightCollection)
    var collectionView by remember { mutableStateOf(false) }
    val collectionMenuState = remember { mutableStateOf(false) }
    var collectionMenu by collectionMenuState
    val collectionMenuAnchor = rememberGlassAnchor()
    val gridState = rememberLazyGridState()
    val scrollScope = rememberCoroutineScope()
    val reorder = remember(inputs.route.key) { SongDragState(gridState, scrollScope) }
    val scrollResetInput = when {
        inputs.route.globalSearch -> Triple(true, inputs.route.filter, inputs.route.text)
        inputs.route.tab == MainTab.Library && inputs.route.collection == null && !inputs.route.search ->
            Triple(false, inputs.route.filter, "")
        else -> null
    }
    // A fresh mount restores its inputs.route's saved viewport. Only an input change
    // within that page must discard LazyGrid's retained item/key anchor.
    BrowseScrollResetEffect(inputs.route, scrollResetInput, gridState)
    var collectionHeaderHeight by androidx.compose.runtime.saveable.rememberSaveable(inputs.route.key) { mutableIntStateOf(0) }
    var collectionActionBottom by androidx.compose.runtime.saveable.rememberSaveable(inputs.route.key) { mutableFloatStateOf(0f) }
    var collectionBackBottom by remember { mutableFloatStateOf(0f) }
    var listViewportTop by remember { mutableFloatStateOf(0f) }
    val collectionHome = ListScrollTarget.header(collectionHeaderHeight,
        (collectionActionBottom - (collectionBackBottom - listViewportTop)).coerceAtLeast(0f))
    val collectionTop = ListScrollTarget.header(collectionHeaderHeight)
    val artistFilterRange = remember(inputs.route.key) { ArtistFilterRange() }
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
    val orderOwner = remember(inputs.route, inputs.selectionVisible, inputs.playerOpen) { Any() }
    val currentOrderOwnerState = rememberUpdatedState(orderOwner)
    val currentOrderOwner by currentOrderOwnerState
    DisposableEffect(orderOwner) { onDispose { orderScroll.cancel() } }
    val mutableQueue = inputs.queueState.library.queues.firstOrNull { inputs.route.collection?.kind == MediaKind.Queue && it.id == inputs.route.collection.id }
    val mutablePlaylist = inputs.playlists.playlists.firstOrNull { inputs.route.collection?.kind == MediaKind.Playlist && it.id == inputs.route.collection.id }
    val savedOrder = mutableQueue?.order ?: mutablePlaylist?.order
    val physicalOrder = remember(mutableQueue?.entries, mutablePlaylist?.entries) {
        mutableQueue?.entries?.map { it.id } ?: mutablePlaylist?.entries?.map { it.id }
    }
    val mutablePositions = remember(physicalOrder) { physicalOrder.orEmpty().withIndex().associate { it.value to it.index + 1 } }
    val pinchMotion = remember(inputs.route.key) { BrowsePinchMotion() }

    val restoredSearchSlotHeight = with(density) { pillSlotHeight.roundToPx() }
    BrowseRestoreSearchSlotEffect(localSearchOwner.openState, searchAnchor, showSearchPill, restoredSearchSlotHeight,
        searchSlot, scrollOwnership, gridState, modalBackdrop)
    val searchSlotLayoutOwner = remember { SearchSlotLayout() }
    // Reconcile the owned inputs.header before LazyGrid measures its children. A later
    // composition request can otherwise let the restored spacer retain a song key
    // as its scroll anchor. The request schedules, never forces, remeasurement.
    val searchSlotGridLayout = Modifier.layout { measurable, constraints ->
        val lookahead = isLookingAhead
        val measure = searchSlotLayoutOwner.measure(lookahead, searchAnchor.searchSlot.fraction,
            searchSlot.originalOffset, searchSlot.vacancy)
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
        val height = (restoredSearchSlotHeight - searchSlot.vacancy * fraction)
            .roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(child.width, height) { child.placeRelative(0, 0) }
    }
    val pinchHeader = if (catalog.artDetail) remember(pinchMotion, gridState) { CollectionPinchHeader(pinchMotion, gridState) } else null
    val headerFrameObserver = LocalCollectionHeaderFrameObserver.current
    SideEffect { pinchMotion.header = pinchHeader; pinchHeader?.observer = headerFrameObserver }
    val zoomAnchorState = remember(inputs.route.key) { mutableStateOf<BrowseZoomAnchor?>(null) }
    var zoomAnchor by zoomAnchorState
    BrowseZoomAnchorEffect(zoomAnchorState, inputs.route, catalog.queryPending, catalog.displayedStyle, catalog.result, pinchMotion, pinchHeader, headerFrameObserver,
        gridState, scrollOwnership, inputs.reducedMotion)
    val surroundingBackdrop = LocalFrostedBackdrop.current
    val hasSongItems = catalog.result.items.any { it.row != null }
    val needsListBackdrop = (catalog.artDetail || hasSongItems || showFilters || showSearchPill || inputs.route.collection?.kind == MediaKind.Queue ||
        (inputs.route.collection == null && inputs.route.tab in setOf(MainTab.Library, MainTab.Playlists)))
    val listBackdrop = rememberFrostedBackdrop(catalog.presentation to surroundingBackdrop?.fallback,
        needsListBackdrop, dark = if (catalog.artDetail) !lightCollection else surroundingBackdrop?.dark == true,
        // Floating controls sample narrow regions. Let their clips cull rows
        // instead of rasterizing the entire scrolling viewport a second time.
        retainRaster = false)
    val backdropBacking = if (catalog.artDetail) collectionSource else surroundingBackdrop
    val backdropScene = rememberBrowseBackdropScene(listBackdrop, backdropBacking,
        listOf(catalog.presentation, catalog.validAlbums, if (catalog.artDetail) catalog.hero else surroundingBackdrop?.fallback))
    val maskedBackdrop = (showSearchPill || showFilters) && !catalog.artDetail
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
            "${pinchMotion.diagnostics()} anchor=$zoomAnchor queryPending=${catalog.queryPending} " +
                "displayed=${catalog.displayedStyle.presentation}/${catalog.displayedStyle.listSize} " +
                "scrolling=${gridState.isScrollInProgress} first=${gridState.firstVisibleItemIndex}/" +
                "${gridState.firstVisibleItemScrollOffset}"
        },
        recoveredReport = { pinchMotion.diagnostics() })
    BrowseScrollSettleBackdropEffect(catalog.presentation, gridState, catalog.artDetail, collectionSource)
    val artworkTransit = remember { ArtworkTransit() }
    val latestBrowseState = rememberUpdatedState(catalog.browse)
    val latestBrowse by latestBrowseState
    val latestReducedMotionState = rememberUpdatedState(rememberEffectiveReducedMotion(inputs.reducedMotion || LocalReducedMotion.current))
    val latestReducedMotion by latestReducedMotionState
    val latestTopInsetState = rememberUpdatedState(with(density) {
        (if (showSearchPill || showFilters) pillSlotHeight else 0.dp).toPx()
    })
    val latestTopInset by latestTopInsetState
    BrowseOrderScrollEffects(inputs.library, inputs.playlists, inputs.queueState, inputs.queues, inputs.playlistController, inputs.libraryFilters, orderScroll, orderOwner,
        currentOrderOwnerState, currentRouteState, inputs.selectionVisible, inputs.playerOpen, latestBrowseState, latestReducedMotionState,
        latestTopInsetState, gridState, scrollOwnership, artworkTransit, playbackFollow)
    val session = LocalSessionSelection.current ?: remember { SessionSelection() }
    OnModalGestureCancellation { playbackFollow.interrupt() }
    val readyToScroll by rememberUpdatedState(!catalog.queryPending && catalog.loadedResult != null)
    // Cursor checkpoints replace queue snapshots without changing this list. A return
    // owns its query/occurrence structure, not unrelated live playback bookkeeping.
    val returnOwner = remember(inputs.route, style, inputs.library.snapshot, inputs.playlists.playlists,
        mutableQueue?.entries, mutableQueue?.order, inputs.libraryFilters, selectedAlbums) { Any() }
    var manualQueueReturn by remember(returnOwner) { mutableStateOf(false) }
    var presentedReturnOwner by remember { mutableStateOf<Any?>(null) }
    SideEffect { if (!catalog.queryPending && catalog.loadedResult != null) presentedReturnOwner = returnOwner }
    // A cursor-only refresh need not blink a still-valid control. Genuine query or
    // occurrence changes hide it until the corresponding list has been presented.
    val returnListMatches = catalog.loadedResult != null && (!catalog.queryPending || presentedReturnOwner === returnOwner)
    val clearableFilter = (inputs.route.tab == MainTab.Library && inputs.route.collection == null && inputs.route.filter != null) ||
        (catalog.artistDetail && catalog.validAlbums.isNotEmpty())
    // Recompose only when the offered action changes, not at every scroll start/stop.
    val returnAction by remember(gridState, clearableFilter) { derivedStateOf {
        listReturnAction(gridState.canScrollBackward, gridState.isScrollInProgress, clearableFilter)
    } }
    DisposableEffect(playbackFollow) { onDispose { playbackFollow.cancel() } }
    var movingReturn by remember { mutableStateOf<ListReturnAction?>(null) }
    val currentReturnTarget by rememberUpdatedState<(ListReturnAction) -> ListScrollTarget> { action ->
        when {
            action == ListReturnAction.ScrollSongs -> collectionHome
            catalog.artDetail && collectionHeaderHeight > 0 && catalog.result.items.isNotEmpty() -> collectionTop
            else -> ListScrollTarget(0)
        }
    }
    val returnToList = ListReturnActions(inputs.scrollToTopRequest, returnOwner,
        inputs.selectionVisible && !inputs.playerOpen && !session.active && !localSearchOwner.open,
        backEnabled = inputs.listBackEnabled && !inputs.route.search && !localSearchOwner.open && !inputs.playerOpen,
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
            localSearchOwner.open = false
            when (action) {
                ListReturnAction.ClearFilter -> if (catalog.artistDetail) selectedAlbums = emptyList()
                    else inputs.onRoute(inputs.route.copy(filter = null))
                ListReturnAction.ScrollTop, ListReturnAction.ScrollSongs -> scrollOwnership.during {
                    artworkTransit.during(!inputs.reducedMotion &&
                        gridState.firstVisibleItemIndex > gridState.layoutInfo.visibleItemsInfo.size * 2) {
                        movingReturn = action
                        try {
                            gridState.easeToTarget({ currentReturnTarget(movingReturn ?: action) }, inputs.reducedMotion)
                        } finally { movingReturn = null }
                    }
                }
                ListReturnAction.None -> Unit
            }
        })
    BackHandler(inputs.selectionVisible && session.active && inputs.route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) {
        session.clear()
    }
    BindSelectionScope(session, inputs.route.collection, catalog.result.rows, inputs.selectionVisible && !localSearchOwner.open)
    // The selection host publishes hover lifts; a drag starts its cards from them (#533).
    val selectionScene = remember(inputs.route.key) { SelectionScene() }
    val dragHost = LocalSongDragHost.current
    val floatingControlClearance = if (inputs.route.collection?.kind == MediaKind.Queue) 12.dp else 0.dp
    BrowseReorderBindings(reorder, selectionScene, backdropBacking, inputs.playingEntryId, inputs.route, dragHost, floatingControlClearance,
        needsListBackdrop, listBackdrop, catalog.artDetail, collectionSource, surroundingBackdrop, inputs.reducedMotion, catalog.result, density, obscuredInset)
    val editableOrder = inputs.route.text.isBlank() &&
        inputs.route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)
    // Only this collection's canonical occurrence order owns the drag lifetime. A
    // playback checkpoint can refresh all catalog.browse presentations without changing it.
    val dragSourceOrder = physicalOrder
    // Keep attachment separate from viewport visibility. Takeovers consume their
    // occurrence changes rather than queueing work to surprise the returning owner.
    // Statistics/metadata and unrelated playlist refreshes may arrive during a
    // natural transition; only this inputs.route's query and occurrence order own it.
    val followOwner = remember(inputs.route, style, physicalOrder) { Any() }
    val followAvailable = inputs.route.collection?.kind == MediaKind.Queue &&
        inputs.playingEntryId != null && inputs.selectionVisible && !inputs.playerOpen && !session.active &&
        !localSearchOwner.open && reorder.drag == null && pinchMotion.source == null && zoomAnchor == null &&
        !inputs.sorting && !collectionMenu && orderScroll.request == null
    val followEligible = followAvailable && !listContact
    val latestFollowEligible by rememberUpdatedState(followEligible)
    val latestFollowOwner by rememberUpdatedState(followOwner)
    val initialOpening = inputs.queueOpeningRequest > playbackFollow.consumedOpeningRequest || playbackFollow.request?.initialPosition == true
    val initialEligible = inputs.route.collection?.kind == MediaKind.Queue && unfilteredOpening &&
        inputs.playingEntryId != null && (inputs.selectionVisible && !inputs.playerOpen || initialOpening) &&
        !session.active && !localSearchOwner.open && reorder.drag == null && pinchMotion.source == null && zoomAnchor == null &&
        !inputs.sorting && !collectionMenu && orderScroll.request == null && !listContact && !modalBlocked && modalOwner?.blocked != true
    val initialTarget = playbackFollow.initialTarget(inputs.playingEntryId, followOwner, initialEligible, inputs.queueOpeningRequest)
    val initialIndex = initialTarget?.takeIf { catalog.presentation?.route == inputs.route }?.let { target ->
        catalog.result.items.indexOfFirst { it.row?.id == target }.takeIf { it >= 0 }?.plus(1)
    }
    SideEffect {
        if (!unfilteredOpening) playbackFollow.consumeOpening(inputs.queueOpeningRequest)
        if (initialTarget != null && initialIndex != null) {
            // Public lazy admission targets the next measurement, before these rows
            // are placed/drawn. Never turn an opening into a frame-delayed ease.
            gridState.requestScrollToItem(initialIndex, -latestTopInset.roundToInt())
            playbackFollow.positionedInitially(initialTarget, followOwner, inputs.queueOpeningRequest)
        }
        playbackFollow.observe(inputs.playingEntryId.takeIf { inputs.route.collection?.kind == MediaKind.Queue },
            followOwner, followEligible && !modalBlocked && modalOwner?.blocked != true, modalOwner?.generation,
            automatic = !inputs.route.search, openingRequest = inputs.queueOpeningRequest, initialEligible = initialEligible)
    }
    fun userScroll() {
        manualQueueReturn = inputs.route.collection?.kind == MediaKind.Queue
        artistFilterRange.interrupt()
        orderScroll.cancel()
        returnToList.cancel()
        playbackFollow.userScroll()
    }
    fun accessibleScroll(action: suspend () -> Unit): Boolean {
        if (!inputs.selectionVisible || inputs.playerOpen || localSearchOwner.open || modalOwner?.blocked == true) return false
        val generation = modalOwner?.generation
        userScroll()
        scrollScope.launch {
            if (modalOwner?.blocked != true && generation == modalOwner?.generation) action()
        }
        return true
    }
    BrowseFilterRangeEffect(artistFilterRange, gridState, listContact, inputs.selectionVisible, inputs.playerOpen, modalBlocked, latestReducedMotionState,
        catalog.displayedStyle, pinchMotion, zoomAnchorState, catalog.result, obscuredInset, density, scrollOwnership)
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
    val boundaryCurrent = remember(catalog.result.items, inputs.playingEntryId) { catalog.result.items.indexOfFirst { it.row?.id == inputs.playingEntryId } }
    val boundaryReached by remember(gridState, boundaryCurrent, catalog.result.items.size, latestTopInset) { derivedStateOf {
        boundaryCurrent >= 0 && (boundaryCurrent == 0 || boundaryCurrent == catalog.result.items.lastIndex) &&
            ListScrollTarget.below(boundaryCurrent + 1, latestTopInset).direction(gridState) == 0
    } }
    val boundaryReturnReady = manualQueueReturn && !returnToList.pending && followEligible && !modalBlocked && !catalog.queryPending &&
        !inputs.route.search && inputs.route.text.isBlank() && !gridState.isScrollInProgress && boundaryReached
    SideEffect {
        if (boundaryReturnReady) {
            playbackFollow.reattachAtBoundary(inputs.playingEntryId, followOwner)
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
        when (inputs.route.collection?.kind) {
            MediaKind.Queue -> if (inputs.queues != null) inputs.queues.moveBefore(inputs.route.collection.id, ids, before, dragSourceOrder, done) else done(false)
            MediaKind.Playlist -> if (inputs.playlistController != null) inputs.playlistController.moveBefore(inputs.route.collection.id, ids, before, dragSourceOrder, done) else done(false)
            else -> done(false)
        }
    }
    var contextMenu by remember { mutableStateOf<String?>(null) }
    var addingSong by remember { mutableStateOf<Track?>(null) }
    var info by remember { mutableStateOf<SongInformationRequest?>(null) }
    val informationLookupOwner = orderOwner
    val currentInformationLookupOwner by rememberUpdatedState(informationLookupOwner)
    val collectionLookup = rememberOwnedCollectionLookup(informationLookupOwner,
        inputs.library.snapshot, inputs.playlists.playlists, sorts, inputs.queryDispatcher) { item ->
        inputs.onOpen(item)
    }
    val pageColors = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = if (catalog.artDetail) pageColors.copy(onSurface = collectionInk, onSurfaceVariant = collectionInk.copy(alpha = .65f),
        primary = collectionInk, surface = if (lightCollection) androidx.compose.ui.graphics.Color(0xFFE5E5E5) else catalog.hero.palette.bottom) else pageColors) {
    CompositionLocalProvider(LocalArtworkTransit provides artworkTransit, LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalLoadingMaterialOpacity provides if (inputs.route.collection?.kind in setOf(MediaKind.Artist,
            MediaKind.AlbumArtist, MediaKind.Album, MediaKind.Playlist, MediaKind.Queue)) .5f else 1f,
        LocalFrostedBackdrop provides if (catalog.artDetail) collectionSource else surroundingBackdrop,
        LocalEmptySearchBack provides inputs.popupDismiss?.takeIf { inputs.route.text.isBlank() }) {
    Box(Modifier.then(if (inputs.popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
    if (catalog.artDetail) CollectionBackground(catalog.hero, gridState, collectionSource, inputs.reducedMotion,
        Modifier.matchParentSize(), pinchHeader)
    Column(Modifier.then(if (inputs.popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
        .then(if (inputs.route.search) Modifier.focusRequester(resultsFocus).focusable() else Modifier)
        .then(if (searchAnchor.hidden) Modifier.clearAndSetSemantics { } else Modifier)) {
        inputs.header()
        if (inputs.route.search && inputs.popupDismiss != null) {
            val input = ScopedSearchEditorParameters(inputs.route.text, ::editSearchText,
                "Search " + (inputs.route.title ?: inputs.route.tab.label), focus, submitSearch)
            if (inputs.scopedEditor != null) inputs.scopedEditor.content(input) else ScopedSearchEditor(input)
        }
        else if (inputs.route.search) GlassSearchField(inputs.route.text, ::editSearchText,
            if (inputs.route.globalSearch) "Search library" else "Search ${inputs.route.title ?: inputs.route.tab.label}", enabled = !inputs.playerOpen,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), fieldModifier = Modifier.focusRequester(focus))

        LookaheadScope {
        if (inputs.popupDismiss == null || inputs.route.text.isNotBlank()) BoxWithConstraints(Modifier.weight(1f, fill = inputs.popupDismiss == null).pointerInput(orderScroll, playbackFollow) {
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
            val columns = galleryColumns(catalog.displayedStyle.presentation, with(density) { viewportWidth.toDp().value }, catalog.displayedStyle.itemsPerRow)
            val cellGeometry = BrowseCellGeometry(columns)
            val dragColors = MaterialTheme.colorScheme
            val dragInk = LocalContentColor.current
            SideEffect { reorder.style = catalog.displayedStyle; reorder.cellGeometry = cellGeometry; reorder.colors = dragColors; reorder.ink = dragInk; reorder.mutablePositions = mutablePositions }
            val loadingReducedMotion = inputs.reducedMotion || LocalReducedMotion.current
            val skeletonReveal = !catalog.artDetail && !inputs.route.search
            val displayedRoute = catalog.presentation?.route ?: inputs.route
            val artistOnlyLoading = displayedRoute.collection == null && (displayedRoute.filter in setOf(MediaKind.Artist, MediaKind.AlbumArtist) ||
                (displayedRoute.filter == null && displayedRoute.tab in setOf(MainTab.Artists, MainTab.AlbumArtists)))
            val loadingReveal = rememberBrowseLoadingReveal(displayedRoute.key to displayedRoute.filter,
                catalog.loadedResult != null, catalog.browse.cached, columns,
                with(density) { viewportWidth.toDp().value }, with(density) { constraints.maxHeight.toDp().value }, loadingReducedMotion,
                geometryKey = Triple(catalog.displayedStyle.presentation to catalog.displayedStyle.listSize, catalog.displayedStyle.extraInfo, density.fontScale))
            val loadingActivity = remember { LoadingShimmerActivity() }
            val pendingArtwork by remember(loadingActivity) { derivedStateOf(structuralEqualityPolicy()) { loadingActivity.count > 0 } }
            val shimmer = rememberBrowseLoadingShimmer(catalog.loadedResult == null || !loadingReveal.complete || pendingArtwork, loadingReducedMotion)
            val galleryLoadingText = if (catalog.displayedStyle.presentation != Presentation.List)
                rememberGalleryLoadingTextMetrics(catalog.displayedStyle, columns) else null
            CompositionLocalProvider(LocalLoadingShimmer provides shimmer, LocalLoadingShimmerActivity provides loadingActivity,
                LocalReducedMotion provides loadingReducedMotion, LocalGalleryLoadingTextMetrics provides galleryLoadingText) {
            if (catalog.loadedResult == null && !catalog.artDetail) BrowseLoadingViewport(catalog.displayedStyle, cellGeometry,
                viewportWidth, constraints.maxHeight, if (showFilters || showSearchPill) pillSlotHeight else 0.dp,
                inputs.popupDismiss == null && catalog.displayedStyle.extraInfo != ExtraInfo.None, artistOnlyLoading)
            else SelectionViewport((if (inputs.popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                // This host shares the viewport's origin and size, but not the hit
                // paths of floating Return/Search/utility controls layered above it.
                // A hold on those controls must never select the obscured song.
                .selectionPanelRelease(session, inputs.route.key,
                    inputs.enabled && !catalog.queryPending && inputs.route.collection?.kind in setOf(MediaKind.Queue, MediaKind.Playlist)) { position ->
                    // Dots and rows share selection-on-hold; the panel waits for release.
                    val item = reorder.hit(position)
                    item?.row != null
                }
                .songDrag(reorder, editableOrder && inputs.enabled, inputs.route.collection, session, inputs.reducedMotion,
                    catalog.displayedStyle.presentation == Presentation.List, acquisitionReady = !catalog.queryPending,
                    sourceOrder = dragSourceOrder, routeKey = inputs.route, commit = moveSongs)
                // Floating controls sample the finished scrolling scene, including
                // its fade and selection/overscroll transforms, never the raw grid.
                // Queue already paints one continuous drawer material beneath its
                // inputs.header and list. Record only list foreground here; the sampling
                // composite adds backdropBacking independently. Repainting it in
                // this displayed viewport would erase inputs.header-shadow spill at its edge.
                .then(if (needsListBackdrop) Modifier.recordBrowseBackdropLayer(backdropScene, requireNotNull(viewportBackdrop),
                    if (inputs.route.collection?.kind == MediaKind.Queue) null else backdropBacking)
                    .then(if (surroundingBackdrop == null && !catalog.artDetail) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier) else Modifier)
                .graphicsLayer { alpha = if (catalog.artDetail || (skeletonReveal && !catalog.queryPending)) 1f else catalog.browse.reveal.value }
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
                    .pinchRetainedOverlay(pinchMotion, gridState, catalog.pinchIndices, selectionScene),
                scene = selectionScene) { overscroll ->
            LazyVerticalGrid(state = gridState, overscrollEffect = overscroll, columns = GridCells.Fixed(columns), modifier = (if (inputs.popupDismiss != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .then(if (showSearchPill) searchSlotGridLayout else Modifier)
                .longScrollFade(gridState)
                .then(if (scrollFrameProbe == null) Modifier else Modifier.drawWithContent {
                    scrollFrameProbe.request // Explicit test readback also redraws settled cached layers.
                    drawContent()
                    val layout = gridState.layoutInfo
                    scrollFrameProbe.frame = BrowseScrollFrame(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
                        layout.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.height,
                        layout.visibleItemsInfo.firstOrNull { it.index > 0 }?.size?.height)
                    scrollFrameProbe.serial++
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
                    stateDescription = if (catalog.displayedStyle.presentation == Presentation.List) "List, ${catalog.displayedStyle.listSize.name}"
                        else if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns"
                    // Explicit semantic entry also catches a no-op request at an
                    // already aligned item, which has no scroll-progress transition.
                    if (inputs.route.collection?.kind == MediaKind.Queue) {
                        scrollToIndex { index ->
                            if (index !in 0 until gridState.layoutInfo.totalItemsCount) false
                            else accessibleScroll { gridState.scrollToItem(index) }
                        }
                        scrollBy { _, y -> accessibleScroll { gridState.animateScrollBy(y) } }
                        scrollByOffset { offset ->
                            if (!inputs.selectionVisible || inputs.playerOpen || localSearchOwner.open || modalOwner?.blocked == true) Offset.Zero
                            else {
                                userScroll()
                                Offset(0f, gridState.animateScrollBy(offset.y))
                            }
                        }
                    }
                }
                .browsePinch(inputs.enabled && !inputs.playerOpen && !catalog.queryPending && reorder.drag == null,
                    pinchMotion, catalog.displayedStyle, inputs.route, gridState, viewportWidth, density, scrollScope, inputs.reducedMotion,
                    onPreview = { style = it }, onCommit = { style = it; preferences.write(inputs.route, it, preferences.overridden(inputs.route)) },
                    onAnchor = { zoomAnchor = it })
                .testTag("browser-list"), contentPadding = PaddingValues(start = cellGeometry.gridInset.dp, end = cellGeometry.gridInset.dp,
                    bottom = obscuredInset +
                        maxOf(with(density) { returnControlHeight.toDp() } + floatingControlClearance,
                            with(density) { actionControlHeight.toDp() }) + 16.dp)) {
                item(key = "browser-notice", contentType = "notice", span = { GridItemSpan(maxLineSpan) }) {
                    // Grid gutters belong to tiles; full-span headers keep their viewport alignment.
                    Column(Modifier.requiredWidth(with(density) { viewportWidth.toDp() }).testTag("browse-full-span")
                        .onSizeChanged { if (catalog.artDetail) collectionHeaderHeight = it.height }
                        .then(if (pinchHeader != null) Modifier.collectionPinchHeader(pinchHeader, catalog.displayedStyle) else Modifier)) {
                        if (catalog.artDetail) {
                            CompositionLocalProvider(LocalFrostedBackdrop provides collectionSource) {
                                CollectionHeader(catalog.detailPlaylist?.name ?: inputs.route.title ?: catalog.seededCollection?.title.orEmpty(), if (catalog.playlistDetail) songCountLabel(catalog.detailPlaylist?.entries?.size ?: 0)
                                    else if (catalog.artistDetail) null else (catalog.sourceResult.rows.firstOrNull()?.track ?: catalog.seededCollection?.songs?.firstOrNull()?.track)?.let { it.albumArtist ?: it.artist },
                                    catalog.hero, catalog.artistDetail, inputs.enabled && catalog.result.rows.isNotEmpty(),
                                    onPlay = { inputs.onPlay(catalog.result.rows, 0, catalog.result.scope) }, onShuffle = {
                                        if (inputs.queues != null) inputs.queues.randomizedQueue(catalog.result.rows.map { it.track }, inputs.route.title ?: "Collection", catalog.result.rows.map { it.sourcePlaylistId })
                                        else inputs.onPlay(catalog.result.rows.shuffled(), 0, catalog.result.scope)
                                    }, playlist = catalog.playlistDetail, pinchHeader = pinchHeader,
                                    onActionBottom = { collectionActionBottom = it })
                                if (catalog.artistDetail) ArtistAlbumChoices(catalog.albums, catalog.validAlbums, ::chooseArtistAlbums)
                                if ((catalog.artistDetail || catalog.playlistDetail) && showSearchPill) Box(Modifier.fillMaxWidth().then(searchSlotLayout)
                                    .onSizeChanged { searchSlot.measuredHeight = it.height }.clipToBounds()) {
                                    ListSearchPill("Search " + (inputs.route.title ?: inputs.route.tab.label), {
                                        if (searchSlot.vacancy == 0f && searchSlot.originalOffset < 0) {
                                            searchSlot.originalOffset = if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset else -1
                                            searchSlot.vacancy = with(density) { searchSlotVacancy(pillSlotHeight.toPx(),
                                                pillMaterialTopInset.toPx(), cellGeometry.cardVerticalInset(catalog.displayedStyle).dp.toPx()) }
                                        }
                                        localSearchOwner.navigation = freshBrowseSearch(inputs.route); localSearchOwner.focusRequest++; localSearchOwner.open = true
                                    }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
                                }
                            }
                        }
                        if (!catalog.artDetail && (showSearchPill || showFilters)) Spacer((if (showSearchPill) searchSlotLayout else Modifier.height(pillSlotHeight))
                            .then(if (showSearchPill) Modifier.onSizeChanged { searchSlot.measuredHeight = it.height } else Modifier))
                        LibraryNotice(inputs.library)
                        inputs.playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (inputs.library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (inputs.library.snapshot.folders.isEmpty() && inputs.route.collection?.kind != MediaKind.Queue) ActionTextButton(onClick = inputs.onSettings) { Text("Add music folders") }
                        if (catalog.result.items.isEmpty() && !inputs.library.loading && !catalog.queryPending) Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            Icon(appIconPainter(AppIcon.TabQueues),
                                if (inputs.route.search) "No matching items" else "Empty list", Modifier.size(72.dp),
                                tint = LocalContentColor.current.copy(alpha = .13f))
                        }
                    }
                }
                items(reorder.rendered(catalog.result.items), key = { it?.key ?: DropGapKey },
                    contentType = { if (it == null) "gap" else it.ref.kind.name }) { entry ->
                    if (entry == null) {
                        val height = with(androidx.compose.ui.platform.LocalDensity.current) { (reorder.drag?.cardSize?.height ?: 0f).toDp() }
                        Box(Modifier.fillMaxWidth().height(height).testTag("drop-gap"))
                        return@items
                    }
                    val item = entry
                    val itemIndex = catalog.itemIndices[item.key] ?: 0
                    val loadingCell = if (skeletonReveal && !loadingReveal.complete) loadingReveal.cell(itemIndex) else null
                    val current = inputs.route.collection?.kind == MediaKind.Queue && item.row != null && item.row.id == inputs.playingEntryId
                    val selectedCount = session.selectedCount(item, inputs.route.collection)
                    val partiallySelected = item.row == null && selectedCount > 0 && selectedCount < item.songs.size
                    // Search already owns the spacer and row displacement. A second
                    // reorder tween would trail that measured placement every frame.
                    Column(Modifier.then(if (catalog.artDetail && itemIndex == 0) Modifier.testTag("browser-song-list-start") else Modifier)
                        .then(if (editableOrder && pinchMotion.visualSource == null) Modifier.animateItem(placementSpec = if (
                        searchSlot.originalOffset >= 0 && searchSlot.vacancy > 0f ||
                        reorder.drag?.let { it.dropping && item.key in it.keys } == true
                    ) null else androidx.compose.animation.core.tween(if (inputs.reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null) else Modifier)
                        .graphicsLayer {
                            val sweep = if (!catalog.artDetail && !inputs.route.search) 1f else ((catalog.entrySweep.value * 600f - itemIndex.coerceAtMost(10) * 30f) / 300f)
                                .coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            alpha = sweep * (reorder.drag?.takeIf { it.dropping && item.key in it.keys }?.handoff?.value ?: 1f)
                            // Write only a change: an identical boxed value still replaces the
                            // map and would invalidate the retained overlay on every layer update.
                            // Do not subscribe this layer to other cells' entries.
                            val previous = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                                pinchMotion.itemAlpha[item.key]
                            }
                            if (previous != alpha) pinchMotion.itemAlpha[item.key] = alpha
                            // The row body, frost and selection share the overlay's departure/arrival fade (#559).
                            alpha *= pinchMotion.pinchFadeAlpha(item.key, gridState, catalog.pinchIndices)
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
                        // A closed menu is not composed until its first opening. It then stays
                        // mounted with this row for its return morph, dialogs and saveable workflow.
                        var actionsMounted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                        // Gallery artwork publishes its menu origin only when the menu opens.
                        val menuAnchor = rememberGlassAnchor(deferContextPlacement = true)
                        SideEffect { menuAnchor.contextual = true; menuAnchor.bottomClearance = with(density) { obscuredInset.toPx() } }
                        val mutableSong = item.row != null && inputs.route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue)
                        val holdLabel = if (mutableSong) "Select song" else if (item.row != null) "Song actions" else "Collection actions"
                        val onHold = {
                            if (mutableSong) session.select(inputs.route.collection, listOf(requireNotNull(item.row)))
                            else { menuAnchor.prepareContextPlacement(); contextMenu = item.key }
                        }
                        val itemMenu: @Composable (Boolean) -> Unit = { showButton ->
                            Box {
                                if (showButton) ActionIcon(AppIcon.ActionEllipsis, "Actions for " + item.title, anchor = menuAnchor,
                                    quietInputEnabled = catalog.displayedStyle.presentation == Presentation.List, quiet = true, onLongClickLabel = holdLabel,
                                    onLongClick = onHold.takeIf { !catalog.queryPending && (item.row == null || inputs.enabled) }) { menuAnchor.prepareContextPlacement(); contextMenu = item.key }
                                val menuOpen = contextMenu == item.key
                                if (menuOpen || actionsMounted) {
                                    if (!actionsMounted) SideEffect { actionsMounted = true }
                                    if (cellWork != null) SideEffect { cellWork("actions", item.key) }
                                    CollectionActions(menuOpen, menuAnchor, item, inputs.playlists, inputs.playlistController, inputs.queues, inputs.pins, inputs.onPin, { contextMenu = null },
                                        onInformationPresented = { song, presentation -> collectionLookup.cancel(); info = SongInformationRequest(song, presentation) }, containingSource = inputs.route.collection, selection = session,
                                        onRemove = if (item.row == null) null else when (inputs.route.collection?.kind) {
                                            MediaKind.Queue -> ({ inputs.queues?.removeEntries(setOf(item.key), inputs.route.collection.id); Unit })
                                            MediaKind.Playlist -> ({ inputs.playlistController?.removeEntries(inputs.route.collection.id, setOf(item.key)) { }; Unit })
                                            else -> null
                                        })
                                }
                            }
                        }
                        item.groupHeading?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                        Column(Modifier.browseBeyondBounds(pinchMotion.beyondBounds)
                            .then(if (pinchMotion.visualSource == null) Modifier.animateBounds(this@LookaheadScope,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(catalog.displayedStyle).dp),
                            boundsTransform = { _, _ -> androidx.compose.animation.core.tween(if (inputs.reducedMotion || editableOrder || loadingCell != null) 0 else 350) })
                            else Modifier.fillMaxWidth().padding(horizontal = cellGeometry.cardInset.dp, vertical = cellGeometry.cardVerticalInset(catalog.displayedStyle).dp)
                                .pinchItemPlacement(pinchMotion, gridState, item.key, itemIndex, catalog.displayedStyle, cellGeometry))
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
                                handle = hover, alpha = { (pinchMotion.itemAlpha[item.key] ?: 1f) * pinchMotion.pinchFadeAlpha(item.key, gridState, catalog.pinchIndices) }) { size ->
                                pinchMotion.highlightSize(item.key, itemIndex, size)
                            }
                            .currentSongHighlight(current, MaterialTheme.colorScheme.primary, { _, _ -> itemOutline.value },
                                visible = { hover.progress() > 0f })
                            .retainPinchItem(item.key, pinchMotion, MaterialTheme.colorScheme.primary.takeIf { current })
                            .then(if (pinchMotion.visualSource == null) Modifier.clip(Corners.card) else Modifier)
                            .testTag("media-${item.key}").combinedClickable(
                            enabled = !catalog.queryPending && (item.row == null || inputs.enabled),
                            onClick = {
                                if (item.row == null) { inputs.onSearchCommit(); inputs.onOpen(item) }
                                else if (session.active) session.toggle(inputs.route.collection, item.row)
                                else { inputs.onSearchCommit(); inputs.onPlay(catalog.result.rows, catalog.result.rows.indexOfFirst { it.id == item.row.id }, catalog.result.scope) }
                            }, onLongClickLabel = holdLabel, onLongClick = onHold
                        )) {
                            BrowseItemLayout(item, catalog.displayedStyle, cellGeometry, this@LookaheadScope, inputs.reducedMotion, itemMenu,
                                showInformation = inputs.popupDismiss == null, inheritedInformation = inheritedInformation, itemIndex = itemIndex,
                                loadingReveal = loadingCell, artistOnlyLoading = artistOnlyLoading, mutablePosition = mutablePositions[item.key],
                                trackNumber = if (inputs.route.collection?.kind == MediaKind.Album) item.row?.track?.trackNumber?.toString() ?: (itemIndex + 1).toString() else null, pinch = pinchMotion, artworkAnchor = menuAnchor,
                                outline = itemOutline)
                        }

                    }
                }
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
                if (needsListBackdrop) listBackdrop else if (catalog.artDetail) collectionSource else surroundingBackdrop) {
            if (showFilters) Box(Modifier.align(Alignment.TopStart)) {
                LibraryFilterBubbles(filters, inputs.route.filter, inputs.reducedMotion,
                    { inputs.onRoute(inputs.route.copy(filter = it)) }, inputs.onFilterSettings)
            }
            if (showSearchPill && !catalog.artDetail) ListSearchPill("Search "+ (inputs.route.title ?: inputs.route.tab.label), {
                // A reversal keeps the same original vacancy/offset until the actual
                // return reaches home; do not capture a partly collapsed replacement.
                if (searchSlot.vacancy == 0f && searchSlot.originalOffset < 0 && gridState.firstVisibleItemIndex == 0) {
                    searchSlot.originalOffset = gridState.firstVisibleItemScrollOffset
                    searchSlot.vacancy = with(density) { searchSlotVacancy(
                        (pillSlotHeight.toPx() - searchSlot.originalOffset).coerceAtLeast(0f),
                        pillMaterialTopInset.toPx(), cellGeometry.cardVerticalInset(catalog.displayedStyle).dp.toPx()) }
                }
                localSearchOwner.navigation = freshBrowseSearch(inputs.route); localSearchOwner.focusRequest++; localSearchOwner.open = true
            }, Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = ListPillVerticalInset), anchor = searchAnchor)
            if (dragHost == null) SongDragOverlay(reorder, inputs.reducedMotion)
            val playingIndex = remember(catalog.result.items, inputs.playingEntryId) {
                catalog.result.items.indexOfFirst { it.row?.id == inputs.playingEntryId }.takeIf { inputs.playingEntryId != null && it >= 0 }?.plus(1)
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
            // Active inputs.queues retain their occurrence return. Other current song lists,
            // including inactive inputs.queues, return to their own top without changing filters.
            val playbackReturn = inputs.route.collection?.kind == MediaKind.Queue && inputs.playingEntryId != null
            val returning = returnToList.pending || (playbackReturn && playbackFollow.request != null)
            val songsPastTop by remember(gridState, collectionHome) { derivedStateOf {
                collectionHome.displacement(gridState) < -1f
            } }
            val showReturn = inputs.selectionVisible && !inputs.playerOpen && !localSearchOwner.open && returnListMatches &&
                !session.active && reorder.drag == null && !returning &&
                (if (playbackReturn) playingIndex != null && (if (inputs.route.search) currentOffscreen else !playbackFollow.attached)
                    else hasSongItems && (if (catalog.artDetail) songsPastTop else gridState.canScrollBackward))
            // A drag owns this slot exclusively; retire a half-faded return drawing
            // instead of overlapping its replacement X during a quick hold-and-move.
            key(reorder.drag != null) { CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
                FloatingReturnToPositionButton(up = !playbackReturn || returnDirection < 0,
                    label = if (playbackReturn) "Current song" else "Return to top",
                    visible = showReturn, reducedMotion = inputs.reducedMotion,
                    returning = returning,
                    onHeightMeasured = { returnControlHeight = it },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = obscuredInset + floatingControlClearance)) {
                    if (!playbackReturn) returnToList(if (catalog.artDetail) ListReturnAction.ScrollSongs else ListReturnAction.ScrollTop)
                    else {
                        orderScroll.cancel()
                        returnToList.cancel()
                        playbackFollow.returnToCurrent(inputs.playingEntryId, followOwner,
                            followAvailable && !listContact && modalOwner?.blocked != true, modalOwner?.generation)
                    }
                }
            } }
            // The enlarged scrollbar hit strip must start below fixed inputs.header controls,
            // otherwise it wins hit testing over right-edge filter/search taps.
            if (catalog.loadedResult != null) BrowseScrollbar(gridState, catalog.result.items, catalog.displayedStyle.sort,
                Modifier.align(Alignment.CenterEnd).padding(
                    top = if (showFilters || (showSearchPill && !catalog.artDetail)) pillSlotHeight else 0.dp,
                    bottom = obscuredInset), onUserScroll = ::userScroll)
            if (inputs.route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Album, MediaKind.Artist, MediaKind.AlbumArtist) && !inputs.route.search) {
                val playlist = inputs.playlists.playlists.firstOrNull { it.id == inputs.route.collection?.id }
                if (playlist != null || catalog.artDetail) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset)
                    .onSizeChanged { actionControlHeight = it.height }) {
                    val anchor = rememberGlassAnchor()
                    var open by remember { mutableStateOf(false) }
                    ActionIcon(AppIcon.ActionEllipsis, if (playlist != null) "Playlist actions" else if (catalog.artistDetail) "Artist actions" else "Album actions", anchor = anchor) { open = true }
                    CollectionActions(open, anchor, collectionActionTarget(requireNotNull(inputs.route.collection), playlist?.name ?: inputs.route.title.orEmpty(), catalog.result, playlist?.coverUri),
                        inputs.playlists, inputs.playlistController, inputs.queues, inputs.pins, inputs.onPin, { open = false },
                        onDeleted = { inputs.onRoute(inputs.route.copy(collection = null, title = null)) },
                        onRenamed = { inputs.onRoute(inputs.route.copy(title = it)) })
                }
            }
            if (inputs.route.tab in setOf(MainTab.Library, MainTab.Playlists) && inputs.route.collection == null && !inputs.route.search)
                PlaylistCreationButton(inputs.library.snapshot, inputs.playlists, inputs.playlistController, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = obscuredInset).testTag("playlist-add-module")
                    .onSizeChanged { actionControlHeight = it.height })
            }
        }
        }
        if (LocalSessionSelection.current == null) CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
            SelectionPanelVisibility(session.panelVisible, reducedMotion = inputs.reducedMotion) {
                SessionSelectionPanel(session, inputs.queues, inputs.playlistController, inputs.playlists, dragging = reorder.drag != null)
            }
        }
    }

    if (catalog.artDetail) CompositionLocalProvider(LocalFrostedBackdrop provides listBackdrop) {
        CollectionToolbar(gridState, reorder.viewportWidth, inputs.route.title.orEmpty(), collectionMenuAnchor,
            inputs.onBack, { view -> collectionView = view; collectionMenu = true }, backdropScene, pinchHeader,
            onBackBottom = { collectionBackBottom = it }) {
            if (collectionMenu) CompositionLocalProvider(LocalGlassOrigin provides collectionMenuAnchor) {
                BrowseSortingPanel(collectionView, inputs.route, styleState, catalog.displayedStyle, preferences, pinchMotion, orderScroll, orderOwner, currentOrderOwnerState,
                currentRouteState, playbackFollow, scrollScope, mutableQueue, mutablePlaylist, savedOrder, physicalOrder, inputs.queues, inputs.playlistController,
                inputs.library, collectionMenuState, inputs.onRoute, inputs.onSortDismiss) }
        }
    }
    if (needsListBackdrop) BrowseBackdropRecording(backdropScene, backdropBacking)
    } } }

    BrowseLocalSearchWindow(inputs, localSearchOwner, showSearchPill, searchAnchor, searchEditTrace)
    addingSong?.let { song -> AddToPlaylistDialog(listOf(song), inputs.playlists, inputs.playlistController, { addingSong = null }, { addingSong = null }) }
    info?.let { request -> TagEditorPresentationScope(request.presentation) {
    val song = request.track
    SongInformation(song, { collectionLookup.cancel(); info = null }, folders = inputs.library.snapshot.folders) { action ->
        // SongInformation intentionally dismisses before invoking this accepted action.
        val owner = informationLookupOwner
        collectionLookup.open(action, song) { info == null && currentInformationLookupOwner === owner }
    } } }
    if (inputs.sorting) CompositionLocalProvider(LocalGlassOrigin provides inputs.sortOrigin) {
        BrowseSortingPanel(inputs.presentationOnly, inputs.route, styleState, catalog.displayedStyle, preferences, pinchMotion, orderScroll, orderOwner, currentOrderOwnerState,
        currentRouteState, playbackFollow, scrollScope, mutableQueue, mutablePlaylist, savedOrder, physicalOrder, inputs.queues, inputs.playlistController,
        inputs.library, collectionMenuState, inputs.onRoute, inputs.onSortDismiss) }
}


// #682: BrowserScreen exceeded ART's compile limit (16383 code units), so it ran interpreted. The hosts below
// are the verbatim effect clusters that used to sit inline, called from the same composition position with
// exactly the values that code read. Effect keys, relative order and updated-state freshness are unchanged.
// They are non-restartable, so their snapshot reads still invalidate BrowserScreen's own scope, as inline
// code did; only the dex code moved. Measure with tools/performance/method-size.sh.

/** Search-field autofocus for this route; owns its per-route focus bookkeeping and returns submitSearch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun browseSearchAutofocus(route: BrowseRoute, scopedEditor: ScopedSearchEditorSession?, focusRequest: Int, playerOpen: Boolean,
    focus: androidx.compose.ui.focus.FocusRequester, resultsFocus: androidx.compose.ui.focus.FocusRequester,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?, density: androidx.compose.ui.unit.Density,
    onSearchCommit: () -> Unit, searchEditTrace: ((String) -> Unit)?): () -> Unit {
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
    return ::submitSearch
}

@Composable @NonRestartableComposable
private fun BrowseZoomAnchorEffect(zoomAnchorState: MutableState<BrowseZoomAnchor?>, route: BrowseRoute, queryPending: Boolean,
    displayedStyle: BrowseStyle, result: BrowseResult, pinchMotion: BrowsePinchMotion, pinchHeader: CollectionPinchHeader?,
    headerFrameObserver: ((CollectionHeaderFrame) -> Unit)?, gridState: LazyGridState, scrollOwnership: BrowseScrollOwnership,
    reducedMotion: Boolean) {
    var zoomAnchor by zoomAnchorState
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
}

@Composable @NonRestartableComposable
private fun BrowseScrollSettleBackdropEffect(presentation: PresentedBrowse?, gridState: LazyGridState, artDetail: Boolean,
    collectionSource: FrostedBackdrop) {
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
}

@Composable @NonRestartableComposable
private fun BrowseOrderScrollEffects(library: LibraryUiState, playlists: PlaylistState, queueState: QueueUiState, queues: QueueController?,
    playlistController: PlaylistController?, libraryFilters: LibraryFilters, orderScroll: BrowseOrderScrollCoordinator, orderOwner: Any,
    currentOrderOwnerState: State<Any>, currentRouteState: State<BrowseRoute>, selectionVisible: Boolean, playerOpen: Boolean,
    latestBrowseState: State<BrowsePresentationState>, latestReducedMotionState: State<Boolean>, latestTopInsetState: State<Float>,
    gridState: LazyGridState, scrollOwnership: BrowseScrollOwnership, artworkTransit: ArtworkTransit,
    playbackFollow: QueuePlaybackFollowCoordinator) {
    val currentOrderOwner by currentOrderOwnerState
    val currentRoute by currentRouteState
    val latestBrowse by latestBrowseState
    val latestReducedMotion by latestReducedMotionState
    val latestTopInset by latestTopInsetState
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
                else gridState.easeToTarget(ListScrollTarget.below(target, latestTopInset), latestReducedMotion)
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
}

@Composable @NonRestartableComposable
private fun BrowseReorderBindings(reorder: SongDragState, selectionScene: SelectionScene, backdropBacking: FrostedBackdrop?,
    playingEntryId: String?, route: BrowseRoute, dragHost: SongDragHost?, floatingControlClearance: androidx.compose.ui.unit.Dp,
    needsListBackdrop: Boolean, listBackdrop: FrostedBackdrop, artDetail: Boolean, collectionSource: FrostedBackdrop,
    surroundingBackdrop: FrostedBackdrop?, reducedMotion: Boolean, result: BrowseResult, density: androidx.compose.ui.unit.Density,
    obscuredInset: androidx.compose.ui.unit.Dp) {
    SideEffect {
        reorder.hoverLift = { key -> selectionScene.hover[key] }; reorder.hoverBacking = backdropBacking
        reorder.currentSongKey = playingEntryId.takeIf { route.collection?.kind == MediaKind.Queue }
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
    SideEffect { reorder.reconcile(result.items); reorder.obscuredBottom = with(density) { obscuredInset.toPx() } }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
}

@Composable @NonRestartableComposable
private fun BrowseFilterRangeEffect(artistFilterRange: ArtistFilterRange, gridState: LazyGridState, listContact: Boolean,
    selectionVisible: Boolean, playerOpen: Boolean, modalBlocked: Boolean, latestReducedMotionState: State<Boolean>,
    displayedStyle: BrowseStyle, pinchMotion: BrowsePinchMotion, zoomAnchorState: State<BrowseZoomAnchor?>, result: BrowseResult,
    obscuredInset: androidx.compose.ui.unit.Dp, density: androidx.compose.ui.unit.Density, scrollOwnership: BrowseScrollOwnership) {
    val latestReducedMotion by latestReducedMotionState
    val zoomAnchor by zoomAnchorState
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
}

/** The sorting panel and its commit helpers; each origin owns its opening tab, an inactive origin must not override it. */
@Composable @NonRestartableComposable
private fun BrowseSortingPanel(initialView: Boolean, route: BrowseRoute, styleState: MutableState<BrowseStyle>, displayedStyle: BrowseStyle,
    preferences: BrowsePreferences, pinchMotion: BrowsePinchMotion, orderScroll: BrowseOrderScrollCoordinator, orderOwner: Any,
    currentOrderOwnerState: State<Any>, currentRouteState: State<BrowseRoute>, playbackFollow: QueuePlaybackFollowCoordinator,
    scrollScope: kotlinx.coroutines.CoroutineScope, mutableQueue: ListeningQueue?, mutablePlaylist: ManualPlaylist?, savedOrder: SavedOrder?,
    physicalOrder: List<String>?, queues: QueueController?, playlistController: PlaylistController?, library: LibraryUiState,
    collectionMenuState: MutableState<Boolean>, onRoute: (BrowseRoute) -> Unit, onSortDismiss: () -> Unit) {
    var style by styleState
    var collectionMenu by collectionMenuState
    val currentOrderOwner by currentOrderOwnerState
    val currentRoute by currentRouteState
    fun dismissSorting() {
        // Retire pending receipts before visibility changes; disposal can happen later.
        orderScroll.cancelPending()
        collectionMenu = false
        onSortDismiss()
    }
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
    })
}

@Composable
@NonRestartableComposable
private fun rememberBrowseInheritedInformation(preferences: BrowsePreferences): State<Map<MediaKind, BrowseStyle>> {
    val informationState = remember(preferences) { mutableStateOf(preferences.informationStyles()) }
    var inheritedInformation by informationState
    DisposableEffect(preferences) {
        val unsubscribe = preferences.observeInformation { inheritedInformation = it }
        onDispose(unsubscribe)
    }
    return informationState
}

@Composable
@NonRestartableComposable
private fun BrowseStyleObserver(preferences: BrowsePreferences, route: BrowseRoute,
    currentRouteState: State<BrowseRoute>, styleState: MutableState<BrowseStyle>) {
    val currentRoute by currentRouteState
    var style by styleState
    DisposableEffect(preferences, route.key, route.filter) {
        val unsubscribe = preferences.observe(route) { updated ->
            style = updated.copy(sort = currentRoute.querySort ?: updated.sort, ascending = currentRoute.queryAscending ?: updated.ascending)
        }
        onDispose(unsubscribe)
    }
}

@Composable
@NonRestartableComposable
private fun rememberBrowseEntrySweep(route: BrowseRoute, cached: Boolean, hasLoadedResult: Boolean,
    queryPending: Boolean, reducedMotion: Boolean): androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val entrySweep = remember(route.key, route.filter, route.search && route.text.isNotBlank()) {
        androidx.compose.animation.core.Animatable(if (cached && !route.search) 1f else 0f)
    }
    LaunchedEffect(entrySweep, hasLoadedResult, queryPending, reducedMotion) {
        if (hasLoadedResult && !queryPending && entrySweep.value < 1f) {
            if (reducedMotion) entrySweep.snapTo(1f)
            else entrySweep.animateTo(1f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    return entrySweep
}

@Composable
@NonRestartableComposable
private fun BrowseSelectedAlbumsEffect(albums: List<LibraryGroup>, selectedAlbumsState: MutableState<List<String>>,
    queryPending: Boolean, hasLoadedResult: Boolean, artistDetail: Boolean, validAlbums: List<String>) {
    var selectedAlbums by selectedAlbumsState
    LaunchedEffect(albums, selectedAlbums, queryPending, hasLoadedResult) {
        // Persist the canonical mode after completed library changes, never a loading placeholder.
        if (artistDetail && hasLoadedResult && !queryPending && selectedAlbums != validAlbums) {
            selectedAlbums = validAlbums
        }
    }
}

@Composable
@NonRestartableComposable
private fun BrowseScrollResetEffect(route: BrowseRoute, scrollResetInput: Triple<Boolean, MediaKind?, String>?, gridState: LazyGridState) {
    var previousScrollResetInput by remember(route.key) { mutableStateOf(scrollResetInput) }
    SideEffect {
        if (previousScrollResetInput != scrollResetInput) {
            previousScrollResetInput = scrollResetInput
            gridState.requestScrollToItem(0)
        }
    }
}

@Composable
@NonRestartableComposable
private fun BrowseVisibleFilterEffect(libraryFilters: LibraryFilters, route: BrowseRoute, selectionVisible: Boolean,
    filters: List<LibraryFilter>, onRoute: (BrowseRoute) -> Unit) {
    LaunchedEffect(libraryFilters.visible, route.key, route.filter, selectionVisible) {
        if (selectionVisible && route.tab == MainTab.Library && route.collection == null && !route.globalSearch && route.filter != null && filters.none { it.kind == route.filter }) {
            onRoute(route.copy(filter = null))
        }
    }
}

private fun freshBrowseSearch(route: BrowseRoute) = NavigationState(route.tab, mapOf(route.tab to listOf(route.copy(search = true,
    text = "", queryCommitted = false, key = java.util.UUID.randomUUID().toString()))))

private class BrowseLocalSearchOwner(val openState: MutableState<Boolean>, navigationState: MutableState<NavigationState>,
    focusRequestState: MutableIntState, private val generationState: MutableIntState,
    val holder: androidx.compose.runtime.saveable.SaveableStateHolder, val editor: ScopedSearchEditorSession) {
    var open by openState
    var navigation by navigationState
    var focusRequest by focusRequestState
    fun retireEditor() { editor.retire(); generationState.intValue++ }
}

@Composable
@NonRestartableComposable
private fun rememberBrowseLocalSearch(route: BrowseRoute, openState: MutableState<Boolean>): BrowseLocalSearchOwner {
    val navigationState = androidx.compose.runtime.saveable.rememberSaveable(saver = navigationStateSaver { state -> state.takeIf { it.active == route.tab } }) {
        mutableStateOf(freshBrowseSearch(route))
    }
    val focusRequestState = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val searchHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val generationState = remember { mutableIntStateOf(0) }
    val generation by generationState
    val searchEditor = remember(generation) { ScopedSearchEditorSession() }
    DisposableEffect(searchEditor) { onDispose { searchEditor.retire() } }
    return BrowseLocalSearchOwner(openState, navigationState, focusRequestState, generationState, searchHolder, searchEditor)
}

@Composable
@NonRestartableComposable
private fun BrowseLocalSearchWindow(inputs: BrowseScreenInputs, localSearchOwner: BrowseLocalSearchOwner,
    showSearchPill: Boolean, searchAnchor: GlassAnchor, searchEditTrace: ((String) -> Unit)?) {
    val route = inputs.route
    val playerOpen = inputs.playerOpen
    val library = inputs.library
    val playlists = inputs.playlists
    val onOpen = inputs.onOpen
    val onSettings = inputs.onSettings
    val onPlay = inputs.onPlay
    val queues = inputs.queues
    val playlistController = inputs.playlistController
    val enabled = inputs.enabled
    val pins = inputs.pins
    val onPin = inputs.onPin
    val queueState = inputs.queueState
    val playingTrackId = inputs.playingTrackId
    val playingEntryId = inputs.playingEntryId
    val reducedMotion = inputs.reducedMotion
    val libraryFilters = inputs.libraryFilters
    if (showSearchPill || localSearchOwner.open || searchAnchor.hidden) {
        fun closeSearch() { localSearchOwner.retireEditor(); localSearchOwner.open = false }
        fun backSearch() {
            val previous = if (localSearchOwner.navigation.route.text.isNotBlank()) localSearchOwner.navigation.back(false) else null
            if (previous != null) { localSearchOwner.retireEditor(); localSearchOwner.navigation = previous } else closeSearch()
        }
        LaunchedEffect(localSearchOwner.open, searchAnchor.modalPresent) {
            if (!localSearchOwner.open && !searchAnchor.modalPresent) {
                localSearchOwner.navigation = freshBrowseSearch(route)
            }
        }
        ListSearchWindow(localSearchOwner.open && !playerOpen, searchAnchor, localSearchOwner.navigation.route.text.isNotBlank(), ::closeSearch, onBack = ::backSearch) {
            CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) {
                val searchRoute = localSearchOwner.navigation.route
                key(searchRoute.key) { localSearchOwner.holder.SaveableStateProvider(searchRoute.key) {
                    BrowserScreen(library, playlists, searchRoute, { localSearchOwner.navigation = localSearchOwner.navigation.update(it) }, onOpen, onSettings,
                        onPlay, queues, playlistController, enabled, playerOpen, pins, onPin, queueState = queueState,
                        playingTrackId = playingTrackId, playingEntryId = playingEntryId, reducedMotion = reducedMotion,
                        onSearchEdit = { incoming ->
                            val before = localSearchOwner.navigation.route
                            // History route keys may change while this editor session continues.
                            // Resolve edits against its current route; retired sessions own nothing.
                            val next = if (localSearchOwner.editor.active && localSearchOwner.open) localSearchOwner.navigation.editSearch(
                                before.copy(text = incoming.text, querySort = incoming.querySort,
                                    queryAscending = incoming.queryAscending)) else localSearchOwner.navigation
                            searchEditTrace?.invoke("route edit currentKey=${before.key.hashCode()} incomingKey=${incoming.key.hashCode()} nextKey=${next.route.key.hashCode()} oldLength=${before.text.length} incomingLength=${incoming.text.length} nextLength=${next.route.text.length} committed=${before.queryCommitted}")
                            localSearchOwner.navigation = next
                        },
                        onSearchCommit = { if (localSearchOwner.editor.active && localSearchOwner.open) localSearchOwner.navigation = localSearchOwner.navigation.commitSearch() },
                        libraryFilters = libraryFilters, popupDismiss = ::closeSearch, scopedEditor = localSearchOwner.editor, focusRequest = localSearchOwner.focusRequest)
                } }
            }
        }
    }
}

internal class BrowseSearchSlot(vacancyState: MutableFloatState, originalOffsetState: MutableIntState,
    measuredHeightState: MutableIntState) {
    var vacancy by vacancyState
    var originalOffset by originalOffsetState
    var measuredHeight by measuredHeightState
}

@Composable
@NonRestartableComposable
private fun rememberBrowseSearchSlot(): BrowseSearchSlot {
    val vacancyState = androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0f) }
    val originalOffsetState = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    val measuredHeightState = remember { mutableIntStateOf(-1) }
    return BrowseSearchSlot(vacancyState, originalOffsetState, measuredHeightState)
}

@Composable
@NonRestartableComposable
internal fun BrowseRestoreSearchSlotEffect(openState: State<Boolean>, searchAnchor: GlassAnchor,
    showSearchPill: Boolean, restoredSearchSlotHeight: Int, searchSlot: BrowseSearchSlot,
    scrollOwnership: BrowseScrollOwnership, gridState: LazyGridState, modalBackdrop: FrostedBackdrop?) {
    LaunchedEffect(openState.value, searchAnchor, showSearchPill, restoredSearchSlotHeight) {
        snapshotFlow { searchAnchor.searchSlot.fraction }.first { it == if (openState.value) 1f else 0f }
        if (!openState.value) {
            if (searchSlot.originalOffset >= 0) {
                if (showSearchPill && searchSlot.vacancy > 0f) {
                    // A zero fraction can precede the root's restored measurement.
                    // Keep the owned header request alive until that layout exists;
                    // scrolling a still-zero-height header would anchor the first song.
                    snapshotFlow { searchSlot.measuredHeight }.first { it == restoredSearchSlotHeight }
                    // Reopen can precede recomposition/cancellation of this close.
                    if (openState.value) return@LaunchedEffect
                }
                // The measured-height acknowledgment can resume this coroutine
                // during layout. Queue the exact anchor for the next measure;
                // scrollToItem would synchronously force a reentrant layout.
                scrollOwnership.during { gridState.requestScrollToItem(0, searchSlot.originalOffset) }
            }
            searchSlot.originalOffset = -1; searchSlot.vacancy = 0f
        }
        searchAnchor.source?.refresh()
        modalBackdrop?.refresh()
    }
}

private class BrowseScreenCatalog(
    val browse: BrowsePresentationState,
    val presentation: PresentedBrowse?,
    val loadedResult: BrowseResult?,
    val displayedStyle: BrowseStyle,
    val queryPending: Boolean,
    val sourceResult: BrowseResult,
    val artDetail: Boolean,
    val playlistDetail: Boolean,
    val detailPlaylist: ManualPlaylist?,
    val seededCollection: BrowserItem?,
    val entrySweep: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val artistDetail: Boolean,
    val selectedAlbumsState: MutableState<List<String>>,
    val albums: List<LibraryGroup>,
    val validAlbums: List<String>,
    val result: BrowseResult,
    val itemIndices: Map<String, Int>,
    val pinchIndices: Map<String, Int>,
    val hero: PlayerVisual)

@Composable
@NonRestartableComposable
private fun rememberBrowseScreenCatalog(inputs: BrowseScreenInputs, style: BrowseStyle, sorts: BrowseSortSnapshot,
    unfilteredOpening: Boolean, playbackFollow: QueuePlaybackFollowCoordinator): BrowseScreenCatalog {
    val library = inputs.library
    val playlists = inputs.playlists
    val queueState = inputs.queueState
    val libraryFilters = inputs.libraryFilters
    val route = inputs.route
    val reducedMotion = inputs.reducedMotion
    val queryDispatcher = inputs.queryDispatcher
    val queueOpeningRequest = inputs.queueOpeningRequest
    val playingEntryId = inputs.playingEntryId
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
    val entrySweep = rememberBrowseEntrySweep(route, browse.cached, loadedResult != null, queryPending, reducedMotion)
    val artistDetail = artDetail && route.collection?.kind in setOf(MediaKind.Artist, MediaKind.AlbumArtist)
    val selectedAlbumsState = androidx.compose.runtime.saveable.rememberSaveable(route.key) { mutableStateOf(emptyList<String>()) }
    var selectedAlbums by selectedAlbumsState
    // The completed query owns both orders. Per-album view overrides must not
    // reverse inner tracks or reorder this carousel independently of its rows.
    val albums = if (artistDetail) sourceResult.artistAlbums else emptyList()
    val validAlbums = remember(albums, selectedAlbums) {
        ArtistAlbumSelection.reconcile(selectedAlbums, albums.map { it.id })
    }
    BrowseSelectedAlbumsEffect(albums, selectedAlbumsState, queryPending, loadedResult != null, artistDetail, validAlbums)
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
    val hero = if (artDetail) rememberCollectionVisual(heroUri) else PlayerVisual()
    return BrowseScreenCatalog(browse, presentation, loadedResult, displayedStyle, queryPending, sourceResult, artDetail, playlistDetail, detailPlaylist, seededCollection, entrySweep, artistDetail, selectedAlbumsState, albums, validAlbums, result, itemIndices, pinchIndices, hero)
}
