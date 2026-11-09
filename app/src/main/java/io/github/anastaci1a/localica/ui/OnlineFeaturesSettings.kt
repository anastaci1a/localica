package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OnlineFeaturesSettings(consent: NetworkConsent) {
    val allowed by consent.allowed.collectAsState()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf(false) }
    BooleanPreference("Online features", allowed, { value -> scope.launch {
        try { consent.setAllowed(value); problem = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem = true }
    } })
    SettingsDescription("Allow artist and release names to reach Deezer, MusicBrainz and Wikimedia for photos, and your username to reach Last.fm for statistics sync. Audio and local listening history stay on this device.",
        style = MaterialTheme.typography.bodySmall)
    if (problem) SettingsDescription("Could not save online features preference. Try again.", color = MaterialTheme.colorScheme.error)
}
