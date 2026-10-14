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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun BrowseSortDialog(route: BrowseRoute, value: BrowseStyle, overridden: Boolean,
    onDismiss: () -> Unit, onSave: (BrowseStyle, Boolean) -> Unit, onReset: () -> Unit, presentationOnly: Boolean = false, onRandomize: (() -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(if (presentationOnly) 1 else 0) }
    val shadowInset = glassShadowInset().coerceAtLeast(12.dp)
    val sortScroll = rememberScrollState()
    val viewScroll = rememberScrollState()
    var edited by remember { mutableStateOf(value) }
    var override by remember { mutableStateOf(overridden) }
    val songs = route.collection != null || route.tab in setOf(MainTab.Songs, MainTab.MostPlayed) || route.filter == MediaKind.Song
    val fields = if (songs) TrackSort.entries.filter { it != TrackSort.Count && (it != TrackSort.Manual || route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue)) }
        else TrackSort.entries.filterNot { it in setOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = {
        Column {
            GlassChoices(listOf("Sort", "View"), tab, horizontal = true,
                icons = listOf(R.drawable.ic_sort, R.drawable.ic_eye), role = Role.Tab,
                modifier = Modifier.testTag("browse-options-tabs")) { tab = it }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = LocalContentColor.current.copy(alpha = .12f))
        }
    }, text = {
        val panelLimit = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .55f).coerceAtMost(420.dp)
        val pageProgress by animateFloatAsState(tab.toFloat(), tween(if (LocalReducedMotion.current) 0 else 140), label = "Browse options page")
        // Measure both drafts under the same height cap. Navigation changes only
        // the visible page, never the panel's size or the footer's position.
        Layout(modifier = Modifier.fillMaxWidth().heightIn(max = panelLimit).testTag("browse-options-body"), content = {
        repeat(2) { page ->
            val scroll = if (page == 0) sortScroll else viewScroll
            Column(Modifier.fillMaxWidth().then(if (page != tab) Modifier.clearAndSetSemantics { }
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                else Modifier)
                .scrollEdgeFade(scroll).verticalScroll(scroll).padding(horizontal = shadowInset, vertical = shadowInset),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page == 1) ListViewFields(route, edited) { edited = it }
                else {
                    if (onRandomize != null) {
                        GlassActionButton(R.drawable.ic_shuffle, "Randomize queue order", onClick = onRandomize)
                        Text("Shuffles the saved order. While playing, the current song stays first and playback continues.", style = MaterialTheme.typography.bodySmall)
                    }
                    ChoicePreference("Sort by", edited.sort, fields, ::sortLabel, popup = true) { field ->
                        edited = edited.copy(sort = field, ascending = if (field != edited.sort && field in setOf(TrackSort.Added, TrackSort.Modified, TrackSort.MostPlayed)) false else edited.ascending)
                    }
                    SortDirection(edited.ascending) { edited = edited.copy(ascending = it) }
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
    }, footer = {
        Column {
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = .12f))
            BooleanPreference("Save only for this list", override) { override = it }
            if (overridden) TextButton(onClick = onReset) { Text("Use list-type defaults") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(edited, override) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun sortLabel(sort: TrackSort) = when (sort) {
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
    LiquidGlassSurface(Modifier.height(CompactControlSize).glassAnchor(anchor).testTag("browse-options-trigger"), pressFeedback = false) {
        anchor.captureInk()
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(R.drawable.ic_sort, "Sort", glass = false) { onChoose(false) }
            GlassPillDivider()
            ActionIcon(R.drawable.ic_eye, "View options", glass = false) { onChoose(true) }
        }
    }
}
