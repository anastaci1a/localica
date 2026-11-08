package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.lastfm.LastFmController
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LastFmSettings(controller: LastFmController) {
    val state by controller.state.collectAsState()
    val onlineAllowed by controller.networkConsent.allowed.collectAsState()
    val matches by controller.matches.collectAsState()
    val saved = state.saved
    var username by rememberSaveable(saved.username) { mutableStateOf(saved.username) }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    SettingsHeading("Last.fm statistics")
    Text("Read-only sync sends your username to Last.fm to fetch play counts. It never uploads listening history or audio.",
        style = MaterialTheme.typography.bodyMedium)
    if (!controller.enabled) Text("Last.fm is disabled in this build: no bundled API key.")
    if (!onlineAllowed) Text("Online features are off. Enable them to sync.")
    GlassTextField(username, { username = it }, label = { Text("Last.fm username") }, singleLine = true,
        enabled = controller.enabled && onlineAllowed && !state.loading && !state.syncing,
        modifier = Modifier.fillMaxWidth().testTag("lastfm-username"))
    Text("Changing username clears the previous Last.fm snapshot. Sync is manual and limited to once a minute.", style = MaterialTheme.typography.bodySmall)
    ActionButton(onClick = { controller.sync(username) }, enabled = controller.enabled && onlineAllowed && username.isNotBlank() && !state.loading && !state.syncing,
        modifier = Modifier.testTag("lastfm-sync")) { Text(if (state.syncing) "Syncing…" else "Sync now") }
    if (state.syncing) {
        Text(state.progress?.label ?: "Connecting to Last.fm…", Modifier.testTag("lastfm-sync-progress"))
        GlassActionButton(AppIcon.ActionClose, "Cancel sync", onClick = controller::cancelSync)
    }
    BooleanPreference("Keep syncing in the background", saved.keepSyncingInBackground, controller::keepSyncingInBackground)
    Text("Started syncs can finish after you leave the app. Syncs are never scheduled automatically.", style = MaterialTheme.typography.bodySmall)
    val lastSync = saved.snapshot?.let { DateFormat.getDateTimeInstance().format(Date(it.syncedAt)) }
    Text(if (lastSync == null) "Last sync: never" else "Last sync: $lastSync · ${saved.snapshot?.username}", Modifier.testTag("lastfm-last-sync"))
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm-problem")) }
    if (controller.enabled && saved.snapshot != null) BooleanPreference("Use Last.fm counts", saved.useCounts, controller::useCounts)
    else Text("Use Last.fm counts: off")
    Text("Uses Last.fm totals for all-time rankings and play-based sorts. Songs with no Last.fm plays use local counts. Recent ranges use local listening history.",
        style = MaterialTheme.typography.bodyMedium)
    if (saved.username.isNotBlank() || saved.snapshot != null) ActionTextButton(onClick = controller::disconnect,
        modifier = Modifier.testTag("lastfm-disconnect")) { Text("Disconnect and delete Last.fm snapshot") }
    LastFmSummary(state, matches)
    SettingsLink("Review matches", "${matches.review.size} need review · ${matches.editions.size} auto-matched editions") { reviewOpen = true }
    if (saved.mappings.isNotEmpty()) ActionTextButton(onClick = controller::resetMappings) { Text("Reset Last.fm match choices") }
    if (reviewOpen) SettingsPage("Review matches", onBack = { reviewOpen = false }, backLabel = "Back from Last.fm review") {
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(Modifier.fillMaxSize().scrollEdgeFade(scroll).controlVerticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LastFmSummary(state, matches, "lastfm-review-page-summary")
            LastFmReviewList(matches, controller::map)
        }
    }
}
