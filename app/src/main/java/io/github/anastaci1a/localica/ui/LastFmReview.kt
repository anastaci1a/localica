package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.lastfm.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LastFmSummary(state: LastFmState, matches: LastFmMatches) {
    val snapshot = state.saved.snapshot
    val stats = remember(snapshot) { snapshot?.accountStats() }
    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, pressFeedback = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Listening statistics", style = MaterialTheme.typography.titleMedium)
            Text("${matches.matched} matched · ${matches.review.size} need review · ${matches.notInLibrary} not in library",
                Modifier.testTag("lastfm-review-summary"), style = MaterialTheme.typography.bodyLarge)
            Text("${matches.editions.size} auto-matched editions" + if (matches.dismissed > 0) " · ${matches.dismissed} excluded by you" else "",
                style = MaterialTheme.typography.bodyMedium)
            if (stats != null && snapshot != null) {
                Text("${stats.entries} track entries · ${stats.scrobbles} scrobbles", Modifier.testTag("lastfm-account-tracks"))
                Text("${stats.songs} distinct songs · ${stats.artists} artists")
                Text(if (stats.entriesWithAlbums == 0) "Albums not supplied by Last.fm" else
                    "${stats.albums} known albums · metadata on ${stats.entriesWithAlbums} of ${stats.entries} entries",
                    style = MaterialTheme.typography.bodySmall)
                Text("Last sync: ${DateFormat.getDateTimeInstance().format(Date(snapshot.syncedAt))}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.syncing) Text(state.progress?.label ?: "Connecting to Last.fm…", Modifier.testTag("lastfm-progress"),
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun LastFmReviewList(matches: LastFmMatches, onMap: (String, String) -> Unit) {
    var shown by rememberSaveable { mutableIntStateOf(20) }
    var editionsOpen by rememberSaveable { mutableStateOf(false) }
    var editionsShown by rememberSaveable { mutableIntStateOf(20) }
    var absentOpen by rememberSaveable { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    SettingsHeading("Needs review (${matches.review.size})")
    if (matches.review.isEmpty()) Text("All available matches are resolved.", style = MaterialTheme.typography.bodyMedium)
    val ordered = matches.review.filter { it.remote.key !in skipped } + matches.review.filter { it.remote.key in skipped }
    ordered.take(shown).forEach { review -> key(review.remote.key) {
        var open by remember { mutableStateOf(false) }
        val anchor = rememberGlassAnchor()
        SettingsLink("${review.remote.artist} — ${review.remote.title}",
            "${review.remote.plays} plays · " + if (review.remote.key in skipped) "Skipped for now" else "Needs review",
            anchor = anchor) { open = true }
        if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
            LastFmDecision(review, onDismiss = { open = false }, onSkip = {
                skipped = skipped.filterNot { it == review.remote.key } + review.remote.key; open = false
            }, onChoose = { onMap(review.remote.key, it); open = false })
        }
    } }
    if (matches.review.size > shown) SettingsLink("Show more matches", "Next 20, ordered by plays") { shown += 20 }
    SettingsLink("Auto-matched editions (${matches.editions.size})", if (editionsOpen) "Hide editions" else "Show editions",
        AppIcon.TabAlbums) { editionsOpen = !editionsOpen }
    if (editionsOpen) {
        matches.editions.take(editionsShown).forEach { edition ->
            LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, pressFeedback = false) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${edition.remote.artist} — ${edition.remote.title}", style = MaterialTheme.typography.titleSmall)
                    Text("${edition.remote.plays} plays on each copy", style = MaterialTheme.typography.bodyMedium)
                    Text(edition.candidates.mapNotNull { it.album }.distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (matches.editions.size > editionsShown) SettingsLink("Show more editions") { editionsShown += 20 }
    }
    SettingsLink("Not in your library (${matches.notInLibrary})", "Excluded from review", AppIcon.TabLibrary) { absentOpen = !absentOpen }
    if (absentOpen) Text("${matches.notInLibrary} Last.fm entries have no local candidate. They need no decision and do not change your library.",
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LastFmDecision(review: LastFmReview, onDismiss: () -> Unit, onSkip: () -> Unit, onChoose: (String) -> Unit) {
    var selected by remember(review.remote.key) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a match") }, text = {
        Column(Modifier.fillMaxWidth().scrollEdgeFade(scroll).controlVerticalScroll(scroll).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${review.remote.artist} — ${review.remote.title}", style = MaterialTheme.typography.titleSmall)
            Text("${review.remote.plays} plays · choose the recording in your library")
            review.candidates.forEach { candidate ->
                LastFmCandidate("${candidate.album ?: "Unknown album"} · ${candidate.title}", selected == candidate.id) { selected = candidate.id }
            }
            LastFmCandidate("None of these", selected == LastFmMatcher.NONE) { selected = LastFmMatcher.NONE }
        }
    }, confirmButton = {
        GlassActionButton(AppIcon.ActionSave, "Use selection", enabled = selected != null) { selected?.let(onChoose) }
    }, dismissButton = { GlassActionButton(AppIcon.ActionForward, "Skip", onClick = onSkip) })
}

/** SettingsLink's existing glass shell and press feedback, with accessible single-choice semantics. */
@Composable
private fun LastFmCandidate(label: String, selected: Boolean, onClick: () -> Unit) {
    LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Row(Modifier.fillMaxWidth().ownButtonDrag().selectable(selected, role = Role.RadioButton,
            onClick = controlActivation(true, onClick)).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (selected) "●" else "○", color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
