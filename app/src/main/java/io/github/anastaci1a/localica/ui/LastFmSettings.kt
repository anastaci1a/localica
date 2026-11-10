package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateUtils
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.ui.layout.*
import io.github.anastaci1a.localica.ui.spec.Space

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
    val summary by controller.summary.collectAsState()
    val stats = summary?.stats
    val statsPending = stats == null && (state.loading || saved.snapshot != null)
    val absentStat = if (statsPending) null else "—"
    val counts = summary?.counts ?: LastFmDashboardCounts.from(matches)
    val countsReady = summary?.counts != null || matches.sourceSnapshot != null
    val progress = state.progress ?: LastFmProgress(songs = stats?.songs ?: 0, artists = stats?.artists ?: 0,
        albums = stats?.albums ?: 0, reportedPercent = if (summary == null && saved.snapshot == null) 0 else 100)
    val context = LocalContext.current
    val lastSync = (saved.snapshot?.syncedAt ?: summary?.syncedAt)?.let { lastFmSyncTime(context, it) }
    val focus = LocalSettingsSearchFocus.current
    val highlight = focus?.highlighted
    LaunchedEffect(highlight) {
        when (highlight) {
            "Listening statistics" -> tab = 0
            "Data" -> tab = 2
        }
    }
    SettingsEntry("Listening statistics") {
        SettingsStack(gap = Space.Base) {
            LiquidGlassSurface(Modifier.fillMaxWidth().testTag("lastfm-dashboard-card"), cornerRadius = settingsReviewRadius(), pressFeedback = false) {
                SettingsStack {
                    LastFmDashboardHeader(progress, state.syncing) {
                        AsyncValueText(if (statsPending && !state.syncing) null else progress.countSummary(context.resources), "88888 songs · 8888 artists · 8888 albums",
                            Modifier.testTag(if (state.syncing) "lastfm-progress" else "lastfm-archive-summary"), style = MaterialTheme.typography.bodySmall)
                        Text(if (lastSync == null) "Last sync: never" else "Last sync: $lastSync",
                            Modifier.testTag("lastfm-dashboard-last-sync"), style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsStack(inset = Space.Comfortable) { GlassActionRow {
                        if (state.syncing) GlassActionButton(AppIcon.ActionClose, "Stop sync", onClick = controller::cancelSync)
                        else {
                            GlassActionButton(AppIcon.ActionRefresh, "Sync now", enabled = canSync, modifier = Modifier.testTag("lastfm-dashboard-sync")) { controller.sync(username) }
                            GlassActionButton(AppIcon.ActionRescan, "Full rescan", enabled = canSync && saved.snapshot != null,
                                modifier = Modifier.testTag("lastfm-dashboard-full-rescan")) { controller.sync(username, fullRescan = true) }
                        }
                    } }
                }
            }
            LiquidGlassSurface(Modifier.fillMaxWidth().testTag("lastfm-stats-card"), cornerRadius = settingsReviewRadius(), pressFeedback = false) {
                val share = if (stats == null) absentStat else if (!countsReady) null
                    else if (stats.entries == 0) "—" else "${100L * counts.matched / stats.entries}%"
                LastFmStatsGrid(listOf("Scrobbles" to (stats?.scrobbles?.toString() ?: absentStat), "Songs" to (stats?.songs?.toString() ?: absentStat),
                    "Artists" to (stats?.artists?.toString() ?: absentStat),
                    "Albums" to (if (statsPending) null else stats?.albums?.toString() ?: "—"), "Matched share" to share))
            }
        }
    }
    SettingsEntry("Data") {
        GlassChoices(listOf("Overview", "Matches", "Data"), tab, Modifier.testTag("lastfm-tabs"), horizontal = true) { tab = it }
    }
    if (!controller.enabled) Text("Last.fm is disabled in this build: no bundled API key.")
    if (!onlineAllowed) Text("Online features are off. Enable them to sync.")
    if (!active) Text("Last.fm is off. The saved archive and match choices are kept on this device.")
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm-problem")) }
    when (tab) {
        0 -> {
            if (saved.snapshot != null || summary != null) {
                SettingsHeading("Matching")
                SettingsTextLine(LocalSettingsRowMetrics.current.textStart) { modifier ->
                    AsyncValueText(if (countsReady) "${counts.matched} matched · ${counts.review} to review" else null,
                        "88888 matched · 88888 to review", modifier.testTag("lastfm-review-summary"))
                }
                val segments = listOf(
                    LastFmMatchSegment(LastFmGroup.MATCHED.name, "Matched", counts.clear, Color(0xFF71C9A2)),
                    LastFmMatchSegment(LastFmGroup.JUDGMENTS.name, "Judgment calls", counts.judgments, Color(0xFFE6BC64)),
                    LastFmMatchSegment(LastFmGroup.REVIEW.name, "Needs review", counts.review, Color(0xFFFF7B70)),
                    LastFmMatchSegment(LastFmGroup.ABSENT.name, "Not in your library", counts.absent, Color(0xFF8E91A7)))
                LastFmMatchingBar(segments)
                segments.forEach { segment -> key(segment.id) {
                    val anchor = rememberGlassAnchor()
                    SettingsLink(markerColor = segment.color, title = segment.label,
                        summary = if (countsReady) context.resources.getQuantityString(R.plurals.lastfm_song_count, segment.count, segment.count) else "… songs", anchor = anchor,
                        modifier = Modifier.testTag("lastfm-legend-${segment.id}")) { group = LastFmGroup.valueOf(segment.id); tab = 1 }
                } }
                SettingsLink("Auto-matched editions (${if (countsReady) counts.editions else "…"})", "Same recording on several albums · count on every copy") { group = LastFmGroup.EDITIONS; tab = 1 }
            }
            SettingsLink("Review matches", "Needs review, judgment calls and matched recordings") { tab = 1 }
            LastFmConnection(controller, state, onlineAllowed, active, username, { username = it }, canSync)
        }
        1 -> {
            LastFmReviewList(matches, group, library.tracks, controller::mapAndAwait, { key ->
                if (group == LastFmGroup.ABSENT) controller.resetMapping(key) else controller.undoJudgment(key)
            }, saved.mappings, header = {
                ChoicePreference("Match group", group, LastFmGroup.entries, { it.label }, popup = true) { group = it }
            })
        }
        2 -> {
            SettingsHeading("Account and data")
            LastFmConnection(controller, state, onlineAllowed, active, username, { username = it }, canSync)
            BooleanPreference("Keep syncing in the background", saved.keepSyncingInBackground, controller::keepSyncingInBackground)
            Text("A sync you start can finish after you leave Localica. Its notification shows progress and lets you stop it.", style = MaterialTheme.typography.bodySmall)
            LastFmNotificationPreference()
            SettingsEntry("Use Last.fm counts") {
                if (controller.enabled && onlineAllowed && active && saved.snapshot != null) BooleanPreference("Use Last.fm counts", saved.useCounts, controller::useCounts)
                else Text("Use Last.fm counts: off")
            }
            Text("Local plays add temporarily to the archived counts. A refresh replaces that base and clears the additions. Local listening history is kept. Recent ranges use local history.", style = MaterialTheme.typography.bodyMedium)
            SettingsEntry("Full rescan") { GlassActionButton(AppIcon.ActionRescan, "Full rescan", enabled = canSync) { controller.sync(username, fullRescan = true) } }
            SettingsEntry("Disconnect Last.fm") { GlassActionButton(AppIcon.ActionClose, "Disconnect Last.fm", enabled = saved.username.isNotBlank(),
                modifier = Modifier.testTag("lastfm-disconnect"), onClick = controller::disconnect) }
            Text("Turning Last.fm off or disconnecting keeps the archive and match choices on this device.", style = MaterialTheme.typography.bodySmall)
            SettingsEntry("Delete synced Last.fm data") { GlassActionButton(AppIcon.ActionTrash, "Delete synced Last.fm data",
                enabled = saved.snapshot != null || saved.mappings.isNotEmpty(), modifier = Modifier.testTag("lastfm-delete")) { deleting = true } }
            SettingsEntry("Reset Last.fm match choices") { GlassActionButton(AppIcon.ActionChevronLeft, "Reset Last.fm match choices", enabled = saved.mappings.isNotEmpty(), onClick = controller::resetMappings) }
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
    val context = LocalContext.current
    BooleanPreference("Last.fm", active, controller::setActive)
    Text("Read-only sync sends your username to Last.fm to fetch play counts. It never uploads listening history or audio.", style = MaterialTheme.typography.bodyMedium)
    SettingsEntry("Last.fm username") { GlassTextField(username, onUsername, label = { Text("Last.fm username") }, singleLine = true,
        enabled = controller.enabled && onlineAllowed && active && !state.loading && !state.syncing,
        modifier = Modifier.fillMaxWidth().testTag("lastfm-username")) }
    SettingsEntry("Sync now") { GlassActionButton(AppIcon.ActionRefresh, if (state.syncing) "Syncing…" else if (state.saved.snapshot == null) "Sync now" else "Quick refresh",
        enabled = canSync, modifier = Modifier.testTag("lastfm-sync")) { controller.sync(username) } }
    Text("Wait a minute between syncs. Quick refresh fetches recent changes; Full rescan fetches the complete account.", style = MaterialTheme.typography.bodySmall)
    SettingsEntry("Last sync") { Text(state.saved.snapshot?.let { "Last sync: ${lastFmSyncTime(context, it.syncedAt)} · ${it.username}" }
        ?: "Last sync: never", Modifier.testTag("lastfm-last-sync")) }
}

private fun lastFmSyncTime(context: android.content.Context, timestamp: Long): CharSequence =
    DateUtils.getRelativeDateTimeString(context, timestamp, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_RELATIVE)
