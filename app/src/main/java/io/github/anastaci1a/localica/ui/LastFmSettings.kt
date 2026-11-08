package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.ui.layout.SettingsReviewStack
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
    var shown by rememberSaveable { mutableIntStateOf(20) }
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
    val lastSync = saved.snapshot?.let { DateFormat.getDateTimeInstance().format(Date(it.syncedAt)) }
    Text(if (lastSync == null) "Last sync: never" else "Last sync: $lastSync · ${saved.snapshot?.username}", Modifier.testTag("lastfm-last-sync"))
    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm-problem")) }
    if (controller.enabled && saved.snapshot != null) BooleanPreference("Use Last.fm counts", saved.useCounts, controller::useCounts)
    else Text("Use Last.fm counts: off")
    Text("Uses Last.fm totals for all-time rankings and play-based sorts. Songs with no Last.fm plays use local counts. Recent ranges use local listening history.",
        style = MaterialTheme.typography.bodyMedium)
    if (saved.username.isNotBlank() || saved.snapshot != null) ActionTextButton(onClick = controller::disconnect,
        modifier = Modifier.testTag("lastfm-disconnect")) { Text("Disconnect and delete Last.fm snapshot") }
    SettingsHeading("Review matches")
    Text("${matches.counts.size} matched · ${matches.review.size} to review", Modifier.testTag("lastfm-review-summary"))
    matches.review.take(shown).forEach { review ->
        SettingsReviewStack {
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
    if (saved.mappings.isNotEmpty()) ActionTextButton(onClick = controller::resetMappings) { Text("Reset Last.fm match choices") }
}
