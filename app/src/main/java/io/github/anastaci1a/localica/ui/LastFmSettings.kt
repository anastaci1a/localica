package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.ui.layout.*
import io.github.anastaci1a.localica.ui.spec.Space
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LastFmSettings(controller: LastFmController) {
    val state by controller.state.collectAsState()
    val onlineAllowed by controller.networkConsent.allowed.collectAsState()
    val matches by controller.matches.collectAsState()
    val library by controller.catalog.collectAsState()
    val active by controller.active.collectAsState()
    val saved = state.saved
    var username by rememberSaveable(saved.username) { mutableStateOf(saved.username) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableStateOf(LastFmGroup.REVIEW) }
    var deleting by remember { mutableStateOf(false) }
    val canSync = controller.enabled && onlineAllowed && active && username.isNotBlank() && !state.loading && !state.syncing
    val stats = remember(saved.snapshot) { saved.snapshot?.accountStats() }
    val progress = state.progress ?: LastFmProgress(songs = stats?.songs ?: 0, artists = stats?.artists ?: 0,
        albums = stats?.albums ?: 0, reportedPercent = if (saved.snapshot == null) 0 else 100)
    val focus = LocalSettingsSearchFocus.current
    val highlight = focus?.highlighted
    LaunchedEffect(highlight) { if (highlight == "Data") tab = 2 }
    SettingsEntry("Listening statistics") {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = settingsReviewRadius(), pressFeedback = false) {
            LastFmDashboardHeader(progress, state.syncing) {
                Text("${progress.songs} songs · ${progress.artists} artists · ${progress.albums} known albums",
                    Modifier.testTag(if (state.syncing) "lastfm-progress" else "lastfm-archive-summary"), style = MaterialTheme.typography.bodySmall)
                if (state.syncing) GlassActionButton(AppIcon.ActionClose, "Stop sync", onClick = controller::cancelSync)
            }
        }
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = settingsReviewRadius(), pressFeedback = false) {
            val share = if (saved.snapshot?.tracks.isNullOrEmpty()) "—" else "${100L * matches.matched / saved.snapshot!!.tracks.size}%"
            LastFmStatsGrid(listOf("Scrobbles" to (stats?.scrobbles?.toString() ?: "—"), "Songs" to (stats?.songs?.toString() ?: "—"),
                "Artists" to (stats?.artists?.toString() ?: "—"), "Albums" to (stats?.albums?.toString() ?: "—"), "Matched share" to share))
        }
    }
    SettingsEntry("Data") {
        GlassChoices(listOf("Overview", "Matches", "Data"), tab, horizontal = true) { tab = it }
    }
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm-problem")) }
    when (tab) {
        0 -> {
            SettingsEntry("Review matches") { SettingsHeading("Matching") }
            Text("${matches.matched} matched · ${matches.review.size} to review", Modifier.testTag("lastfm-review-summary"))
            val segments = listOf(
                LastFmMatchSegment(LastFmGroup.MATCHED.name, "Matched", matches.clearCount, Color(0xFF71C9A2)),
                LastFmMatchSegment(LastFmGroup.JUDGMENTS.name, "Judgment calls", matches.judgments.size, Color(0xFFE6BC64)),
                LastFmMatchSegment(LastFmGroup.REVIEW.name, "Needs review", matches.review.size, Color(0xFFFF7B70)),
                LastFmMatchSegment(LastFmGroup.ABSENT.name, "Not in your library", matches.notInLibrary, Color(0xFF8E91A7)))
            LastFmMatchingBar(segments)
            segments.forEach { segment -> key(segment.id) {
                val anchor = rememberGlassAnchor()
                SettingsLink(segment.label, "${segment.count} songs", anchor = anchor) { group = LastFmGroup.valueOf(segment.id); tab = 1 }
            } }
            SettingsLink("Auto-matched editions (${matches.editions.size})", "Same recording on several albums · count on every copy") { group = LastFmGroup.EDITIONS; tab = 1 }
            LastFmConnection(controller, state, onlineAllowed, active, username, { username = it }, canSync)
        }
        1 -> {
            ChoicePreference("Match group", group, LastFmGroup.entries, { it.label }, popup = true) { group = it }
            LastFmReviewList(matches, group, library.tracks, controller::mapAndAwait, controller::undoJudgment)
        }
        2 -> {
            SettingsHeading("Account and data")
            LastFmConnection(controller, state, onlineAllowed, active, username, { username = it }, canSync)
            BooleanPreference("Keep syncing in the background", saved.keepSyncingInBackground, controller::keepSyncingInBackground)
            Text("A sync you start can finish after you leave Localica. Its notification shows progress and lets you stop it.", style = MaterialTheme.typography.bodySmall)
            SettingsEntry("Use Last.fm counts") {
                if (controller.enabled && onlineAllowed && active && saved.snapshot != null) BooleanPreference("Use Last.fm counts", saved.useCounts, controller::useCounts)
                else Text("Use Last.fm counts: off")
            }
            Text("Local plays add temporarily to the archived counts. A refresh replaces that base and clears the additions. Local listening history is kept. Recent ranges use local history.", style = MaterialTheme.typography.bodyMedium)
            SettingsEntry("Full rescan") { GlassActionButton(AppIcon.MenuDownload, "Full rescan", enabled = canSync) { controller.sync(username, fullRescan = true) } }
            SettingsEntry("Disconnect Last.fm") { GlassActionButton(AppIcon.ActionClose, "Disconnect Last.fm", enabled = saved.username.isNotBlank(),
                modifier = Modifier.testTag("lastfm-disconnect"), onClick = controller::disconnect) }
            Text("Turning Last.fm off or disconnecting keeps the archive and match choices on this device.", style = MaterialTheme.typography.bodySmall)
            SettingsEntry("Delete synced Last.fm data") { GlassActionButton(AppIcon.ActionTrash, "Delete synced Last.fm data",
                enabled = saved.snapshot != null || saved.mappings.isNotEmpty(), modifier = Modifier.testTag("lastfm-delete")) { deleting = true } }
            SettingsEntry("Reset Last.fm match choices") { GlassActionButton(AppIcon.ActionBack, "Reset Last.fm match choices", enabled = saved.mappings.isNotEmpty(), onClick = controller::resetMappings) }
        }
    }
    if (deleting) GlassAlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete synced Last.fm data?") },
        text = { Text("This deletes only the Last.fm archive, match choices and judgment calls. Your local listening history is kept.") },
        confirmButton = { GlassActionButton(AppIcon.ActionTrash, "Delete synced data") { controller.deleteSyncedData(); deleting = false } },
        dismissButton = { GlassActionButton(AppIcon.ActionClose, "Cancel") { deleting = false } })
}

@Composable
private fun LastFmConnection(controller: LastFmController, state: LastFmState, onlineAllowed: Boolean, active: Boolean,
    username: String, onUsername: (String) -> Unit, canSync: Boolean) {
    BooleanPreference("Last.fm", active, controller::setActive)
    if (!active) Text("Last.fm is off. The saved archive and match choices are kept on this device.")
    Text("Read-only sync sends your username to Last.fm to fetch play counts. It never uploads listening history or audio.", style = MaterialTheme.typography.bodyMedium)
    if (!controller.enabled) Text("Last.fm is disabled in this build: no bundled API key.")
    if (!onlineAllowed) Text("Online features are off. Enable them to sync.")
    SettingsEntry("Last.fm username") { GlassTextField(username, onUsername, label = { Text("Last.fm username") }, singleLine = true,
        enabled = controller.enabled && onlineAllowed && active && !state.loading && !state.syncing,
        modifier = Modifier.fillMaxWidth().testTag("lastfm-username")) }
    SettingsEntry("Sync now") { GlassActionButton(AppIcon.MenuDownload, if (state.syncing) "Syncing…" else if (state.saved.snapshot == null) "Sync now" else "Quick refresh",
        enabled = canSync, modifier = Modifier.testTag("lastfm-sync")) { controller.sync(username) } }
    Text("Wait a minute between syncs. Quick refresh fetches recent changes; Full rescan fetches the complete account.", style = MaterialTheme.typography.bodySmall)
    SettingsEntry("Last sync") { Text(state.saved.snapshot?.let { "Last sync: ${DateFormat.getDateTimeInstance().format(Date(it.syncedAt))} · ${it.username}" }
        ?: "Last sync: never", Modifier.testTag("lastfm-last-sync")) }
}
