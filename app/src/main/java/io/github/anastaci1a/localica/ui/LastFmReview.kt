package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.launch

@Composable
internal fun LastFmReviewList(matches: LastFmMatches, group: LastFmGroup, library: List<Track>,
    onMap: suspend (String, String) -> Boolean, onUndo: (String) -> Unit) {
    var query by rememberSaveable(group) { mutableStateOf("") }
    var page by rememberSaveable(group, query) { mutableIntStateOf(0) }
    var skipped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val rows = remember(matches, group, query, skipped) {
        matches.rows(group).filter { it.contains(query) }.sortedBy { it.remote.key in skipped }
    }
    val currentPage = page.coerceAtMost((rows.size - 1).coerceAtLeast(0) / 20)
    SettingsHeading("${group.label} (${rows.size})")
    GlassTextField(query, { query = it }, label = { Text("Search ${group.label.lowercase()}") },
        modifier = Modifier.fillMaxWidth().testTag("lastfm-list-search"), singleLine = true)
    if (rows.isEmpty()) Text(if (query.isBlank()) "No songs in this group." else "No matching songs.")
    rows.drop(currentPage * 20).take(20).forEach { row -> key(row.remote.key) {
        var open by remember { mutableStateOf(false) }
        val anchor = rememberGlassAnchor()
        SettingsLink("${row.remote.artist} — ${row.remote.title}",
            "${row.remote.plays} scrobbles · ${row.reason}" + if (row.remote.key in skipped) " · Skipped for now" else "",
            anchor = anchor, modifier = Modifier.testTag("lastfm-match:${row.remote.key}")) { open = true }
        if (group == LastFmGroup.JUDGMENTS) GlassActionButton(AppIcon.ActionBack, "Undo match for ${row.remote.title}") { onUndo(row.remote.key) }
        if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
            LastFmDecision(row, library, onDismiss = { open = false }, onChoose = {
                val saved = onMap(row.remote.key, it); if (saved) open = false; saved
            }, onSkip = {
                skipped = skipped.filterNot { it == row.remote.key } + row.remote.key; open = false
            })
        }
    } }
    if (currentPage > 0) GlassActionButton(AppIcon.ActionBack, "Previous matches") { page = currentPage - 1 }
    if ((currentPage + 1) * 20 < rows.size) GlassActionButton(AppIcon.ActionForward, "Show more matches") { page = currentPage + 1 }
}

/** One shared popup remains mounted while None of these switches it to the complete library. */
@Composable
internal fun LastFmDecision(row: LastFmMatchRow, library: List<Track>, onDismiss: () -> Unit,
    onChoose: suspend (String) -> Boolean, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picking by rememberSaveable(row.remote.key) { mutableStateOf(row.candidates.isEmpty()) }
    var query by rememberSaveable(row.remote.key) { mutableStateOf("") }
    var page by rememberSaveable(query) { mutableIntStateOf(0) }
    var selected by rememberSaveable(row.remote.key) { mutableStateOf<String?>(null) }
    val filtered = remember(library, query) { libraryMatches(library, query) }
    val currentPage = page.coerceAtMost((filtered.size - 1).coerceAtLeast(0) / 20)
    val candidates = if (picking) filtered.drop(currentPage * 20).take(20) else row.candidates
    val choices = candidates.map { it.id } + if (picking) emptyList() else listOf(LastFmMatcher.NONE)
    ChoiceDialog(ChoiceDialogActions("Use selection", "Skip", enabled = !saving), "Choose a match", choices, selected,
        label = { id -> candidates.firstOrNull { it.id == id }?.let {
            "${it.title} · ${it.artist ?: "Unknown artist"} · ${it.album ?: "Unknown album"}"
        } ?: "None of these" }, onDismiss = onSkip,
        onSelect = { id -> if (id == LastFmMatcher.NONE) { picking = true; selected = null } else scope.launch {
            saving = true
            try { if (!onChoose(id)) problem = "Could not save the match. Try again." }
            finally { saving = false }
        } },
        onDraft = { id -> if (id == LastFmMatcher.NONE) { picking = true; selected = null } else selected = id },
        header = {
            Text("${row.remote.artist} — ${row.remote.title}", style = MaterialTheme.typography.titleSmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("${row.remote.plays} scrobbles · ${row.reason}", style = MaterialTheme.typography.bodyMedium)
            if (picking) {
                GlassTextField(query, { query = it; selected = null }, label = { Text("Search your entire library") },
                    modifier = Modifier.fillMaxWidth().testTag("lastfm-library-search"), singleLine = true)
                Text("${filtered.size} library songs", style = MaterialTheme.typography.bodySmall)
                if (filtered.isEmpty()) Text("No matching library songs. Try another search.")
            }
        }, footer = {
            if (picking && currentPage > 0) GlassActionButton(AppIcon.ActionBack, "Previous library songs") { page = currentPage - 1; selected = null }
            if (picking && (currentPage + 1) * 20 < filtered.size) GlassActionButton(AppIcon.ActionForward, "More library songs") { page = currentPage + 1; selected = null }
            if (picking && row.candidates.isNotEmpty()) GlassActionButton(AppIcon.ActionBack, "Suggested matches") { picking = false; selected = null }
        })
}
