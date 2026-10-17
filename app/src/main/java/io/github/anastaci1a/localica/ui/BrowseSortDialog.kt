package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.browse.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.library.OrderCheckpoint
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun BrowseSortDialog(route: BrowseRoute, value: BrowseStyle, overridden: Boolean,
    onDismiss: () -> Unit, onSave: (BrowseStyle, Boolean) -> Unit, onReset: () -> Unit, presentationOnly: Boolean = false, onRandomize: ((((Boolean) -> Unit)) -> Unit)? = null,
    mutableOrder: io.github.anastaci1a.localica.library.SavedOrder? = null,
    manualNeedsRestore: Boolean = false,
    mutableOrderRows: List<SongRow>? = null,
    orderSnapshot: LibrarySnapshot? = null,
    mutableOrderIds: List<String>? = null,
    onRevertOrder: ((OrderCheckpoint, (Boolean) -> Unit) -> Unit)? = null,
    onApplySort: ((TrackSort, Boolean, (Boolean) -> Unit) -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(if (presentationOnly) 1 else 0) }
    val shadowInset = glassShadowInset().coerceAtLeast(12.dp)
    val sortScroll = rememberScrollState()
    val viewScroll = rememberScrollState()
    var edited by remember { mutableStateOf(if (mutableOrder != null)
        value.copy(sort = mutableOrder.sort, ascending = mutableOrder.ascending) else value) }
    // This call leaves composition on dismissal even while its host retains the
    // closing drawing. A new opening always gets a new checkpoint and draft.
    val checkpoint = remember {
        if (mutableOrder != null && mutableOrderIds != null) OrderCheckpoint.capture(mutableOrderIds, mutableOrder) else null
    }
    var canRevert by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val applying = mutableOrder != null && tab == 0
    val evaluatedApplied = if (mutableOrder != null && mutableOrderRows != null && orderSnapshot != null) {
        var result by remember(mutableOrder, mutableOrderRows, orderSnapshot, edited.sort, edited.ascending) {
            mutableStateOf<Boolean?>(null)
        }
        LaunchedEffect(mutableOrder, mutableOrderRows, orderSnapshot, edited.sort, edited.ascending) {
            result = withContext(Dispatchers.Default) {
                LiveOrder(orderSnapshot).isApplied(mutableOrder, mutableOrderRows, edited.sort, edited.ascending)
            }
        }
        // Pending/obsolete evaluations cannot enable an actionable stale Apply.
        result ?: true
    } else null
    val applied = evaluatedApplied ?: (mutableOrder != null && edited.sort == mutableOrder.sort &&
        (edited.sort == TrackSort.Manual || edited.ascending == mutableOrder.ascending) &&
        !(edited.sort == TrackSort.Manual && manualNeedsRestore))
    var override by remember { mutableStateOf(overridden) }
    @Composable fun scopeFields() {
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = .12f))
        BooleanPreference("Save only for this list", override) { override = it }
        if (overridden) ActionTextButton(onClick = onReset) { Text("Use list-type defaults") }
    }
    val songs = route.collection != null || route.tab in setOf(MainTab.Songs, MainTab.MostPlayed) || route.filter == MediaKind.Song
    val fields = if (songs) TrackSort.entries.filter { it != TrackSort.Count && (it != TrackSort.Manual || route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue)) }
        else TrackSort.entries.filterNot { it in setOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder) }
    GlassAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("browse-options-dialog"), title = {
        Column {
            GlassChoices(listOf("Sort", "View"), tab, horizontal = true,
                icons = listOf(R.drawable.ic_sort, R.drawable.ic_eye), role = Role.Tab,
                modifier = Modifier.testTag("browse-options-tabs")) { tab = it }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = LocalContentColor.current.copy(alpha = .12f))
        }
    }, text = {
        val pageProgress by animateFloatAsState(tab.toFloat(), tween(if (LocalReducedMotion.current) 0 else 140), label = "Browse options page")
        StablePopupBody(Modifier.testTag("browse-options-body"), measurement = {
            BrowseOptionsMeasurement(route, fields, mutableOrder != null, onRandomize != null, overridden, shadowInset)
        }) {
        Layout(modifier = Modifier.fillMaxWidth(), content = {
        repeat(2) { page ->
            val scroll = if (page == 0) sortScroll else viewScroll
            Column(Modifier.fillMaxWidth().then(if (page != tab) Modifier.clearAndSetSemantics { }
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                else Modifier)
                .scrollEdgeFade(scroll).controlVerticalScroll(scroll).padding(horizontal = shadowInset, vertical = shadowInset),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page == 1) {
                    ListViewFields(route, edited) { edited = it }
                    if (mutableOrder != null) scopeFields()
                } else {
                    if (onRandomize != null) {
                        GlassActionRow {
                            GlassActionButton(R.drawable.ic_shuffle, "Randomize", enabled = !busy, onClick = {
                                busy = true; failure = false
                                onRandomize { ok -> busy = false; failure = !ok
                                    if (ok) {
                                        edited = edited.copy(sort = TrackSort.Manual, ascending = true)
                                        canRevert = true
                                    }
                                }
                            })
                            if (checkpoint != null && onRevertOrder != null) GlassPresence(canRevert) {
                                GlassActionButton(R.drawable.ic_revert, "Revert", enabled = !busy, onClick = {
                                    busy = true; failure = false
                                    onRevertOrder(checkpoint) { ok -> busy = false; failure = !ok
                                        if (ok) {
                                            edited = edited.copy(sort = checkpoint.saved.sort, ascending = checkpoint.saved.ascending)
                                            canRevert = false
                                        }
                                    }
                                })
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalContentColor.current.copy(alpha = .12f))
                    }
                    ChoicePreference("Sort by", edited.sort, fields, ::sortLabel, popup = true) { field ->
                        edited = edited.copy(sort = field, ascending = if (field != edited.sort && field in setOf(TrackSort.Added, TrackSort.Modified, TrackSort.MostPlayed)) false else edited.ascending)
                    }
                    if (edited.sort != TrackSort.Manual) SortDirection(edited.ascending) { edited = edited.copy(ascending = it) }
                    if (failure) Text("Couldn’t save this order. Try again.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }) { children, constraints ->
            val pages = children.map { it.measure(constraints.copy(minHeight = 0)) }
            layout(constraints.maxWidth, pages.maxOf { it.height }) {
                pages.forEachIndexed { index, page ->
                    val alpha = if (index == 0) 1f - pageProgress else pageProgress
                    if (alpha > 0f) page.placeWithLayer(0, 0, zIndex = if (index == tab) 1f else 0f) { this.alpha = alpha }
                }
            }
        }
        }
    }, footer = {
        if (mutableOrder == null && (route.collection != null || route.search)) Column { scopeFields() }
    }, confirmButton = {
        ActionTextButton(enabled = !busy && (!applying || !applied && onApplySort != null), onClick = {
            if (applying) {
                busy = true; failure = false
                onApplySort?.invoke(edited.sort, edited.ascending) { ok -> busy = false; failure = !ok }
            } else onSave(edited, override)
        }) { Text(if (applying) "Apply" else "Save") }
    }, dismissButton = { ActionTextButton(enabled = !busy, onClick = onDismiss) { Text(if (applying) "Close" else "Cancel") } },
        actionsMeasurement = if (mutableOrder != null) ({ BrowseOptionsActionsMeasurement() }) else null,
        widthMeasurement = {
            BrowseOptionsWidthMeasurement(route, fields, mutableOrder != null, onRandomize != null, overridden, shadowInset)
        })
}

internal fun sortLabel(sort: TrackSort) = when (sort) {
    TrackSort.Album -> "Album / disc / track"
    TrackSort.AlbumArtist -> "Album Artist"
    TrackSort.Added -> "Date added"
    TrackSort.Modified -> "Date modified"
    TrackSort.MostPlayed -> "Most played"
    TrackSort.Count -> "Number of songs"
    else -> sort.name
}

/** A single source surface for both destinations and their return transition. */
@Composable
internal fun BrowseOptionsPill(anchor: GlassAnchor, onChoose: (Boolean) -> Unit) {
    val source = LocalFrostedBackdrop.current
    SideEffect {
        anchor.source = source
        anchor.face = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_sort), null) }
                GlassPillDivider()
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_eye), null) }
            }
        }
    }
    LiquidGlassSurface(Modifier.height(CompactControlSize).glassAnchor(anchor).testTag("browse-options-trigger")) {
        anchor.captureInk()
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(R.drawable.ic_sort, "Sort", glass = false) { onChoose(false) }
            GlassPillDivider()
            ActionIcon(R.drawable.ic_eye, "View options", glass = false) { onChoose(true) }
        }
    }
}
