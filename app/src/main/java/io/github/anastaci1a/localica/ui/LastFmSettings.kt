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
    val active by controller.active.collectAsState()
    val saved = state.saved
    var username by rememberSaveable(saved.username) { mutableStateOf(saved.username) }
    var shown by rememberSaveable { mutableIntStateOf(20) }
    var deleting by remember { mutableStateOf(false) }
    SettingsHeading("Last.fm statistics")
    BooleanPreference("Last.fm", active, controller::setActive)
    if (!active) Text("Last.fm is off. The saved archive and match choices are kept on this device.")
    Text("Read-only sync sends your username to Last.fm to fetch play counts. It never uploads listening history or audio.",
        style = MaterialTheme.typography.bodyMedium)
    if (!controller.enabled) Text("Last.fm is disabled in this build: no bundled API key.")
    if (!onlineAllowed) Text("Online features are off. Enable them to sync.")
    SettingsEntry("Last.fm username") { GlassTextField(username, { username = it }, label = { Text("Last.fm username") }, singleLine = true,
        enabled = controller.enabled && onlineAllowed && active && !state.loading && !state.syncing,
        modifier = Modifier.fillMaxWidth().testTag("lastfm-username")) }
    Text("Sync is manual and limited to once a minute. A different account replaces the archive only after its scan finishes.", style = MaterialTheme.typography.bodySmall)
    SettingsEntry("Sync now") { ActionButton(onClick = { controller.sync(username) }, enabled = controller.enabled && onlineAllowed && active && username.isNotBlank() && !state.loading && !state.syncing,
        modifier = Modifier.testTag("lastfm-sync")) { Text(if (state.syncing) "Syncing…" else "Sync now") } }
    if (state.syncing) Text(state.progress?.label ?: "Connecting to Last.fm…", Modifier.testTag("lastfm-progress"))
    if (saved.snapshot != null) ActionTextButton(onClick = { controller.sync(username, fullRescan = true) },
        enabled = controller.enabled && onlineAllowed && active && !state.loading && !state.syncing) { Text("Full rescan") }
    val lastSync = saved.snapshot?.let { DateFormat.getDateTimeInstance().format(Date(it.syncedAt)) }
    SettingsEntry("Last sync") { Text(if (lastSync == null) "Last sync: never" else "Last sync: $lastSync · ${saved.snapshot?.username}", Modifier.testTag("lastfm-last-sync")) }
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm-problem")) }
    SettingsEntry("Use Last.fm counts") {
        if (controller.enabled && onlineAllowed && active && saved.snapshot != null) BooleanPreference("Use Last.fm counts", saved.useCounts, controller::useCounts)
        else Text("Use Last.fm counts: off")
    }
    Text("Uses Last.fm totals for all-time rankings and play-based sorts. Local plays after a scan add temporarily to the archive. A refresh replaces that base and clears the additions. Songs absent from the archive use local counts. Recent ranges use local listening history.",
        style = MaterialTheme.typography.bodyMedium)
    if (saved.username.isNotBlank()) SettingsEntry("Disconnect Last.fm") { ActionTextButton(onClick = controller::disconnect,
        modifier = Modifier.testTag("lastfm-disconnect")) { Text("Disconnect Last.fm") } }
    if (saved.snapshot != null || saved.mappings.isNotEmpty()) ActionTextButton(onClick = { deleting = true },
        modifier = Modifier.testTag("lastfm-delete")) { Text("Delete synced Last.fm data") }
    if (deleting) GlassAlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete synced Last.fm data?") },
        text = { Text("This deletes only the Last.fm archive, match choices and judgment calls. Your local listening history is kept.") },
        confirmButton = { ActionButton(onClick = { controller.deleteSyncedData(); deleting = false }) { Text("Delete synced data") } },
        dismissButton = { ActionTextButton(onClick = { deleting = false }) { Text("Cancel") } })
    SettingsEntry("Review matches") { SettingsHeading("Review matches") }
    SettingsEntry("Listening statistics") { Text("${matches.matched} matched · ${matches.review.size} to review", Modifier.testTag("lastfm-review-summary")) }
    matches.review.take(shown).forEach { review ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${review.remote.artist} — ${review.remote.title}", style = MaterialTheme.typography.titleSmall)
            Text("${review.remote.plays} plays · " + if (review.candidates.isEmpty()) "Not found in the local library" else "Several local matches")
            review.candidates.take(10).forEach { candidate ->
                ActionTextButton(onClick = { controller.map(review.remote.key, candidate.id) }) {
                    Text("Use ${candidate.album ?: "Unknown album"} · ${candidate.fileName}")
                }
            }
        }
    }
    if (matches.review.size > shown) ActionTextButton(onClick = { shown += 20 }) { Text("Show more matches") }
    if (saved.mappings.isNotEmpty()) SettingsEntry("Reset Last.fm match choices") { ActionTextButton(onClick = controller::resetMappings) { Text("Reset Last.fm match choices") } }
}
