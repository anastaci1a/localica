package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ArtistPhotosSettings(photos: ArtistPhotos, consent: NetworkConsent, snapshot: LibrarySnapshot) {
    val allowed by consent.allowed.collectAsState()
    val revision by photos.changes.collectAsState()
    val work = artistPhotoEnvironment()?.takeIf { it.photos === photos }?.work
    val progress = work?.status?.collectAsState()?.value
    var bytes by remember { mutableLongStateOf(0) }
    var chosenBytes by remember { mutableLongStateOf(0) }
    var budget by remember { mutableLongStateOf(512L * 1024 * 1024) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision, busy, progress) {
        bytes = photos.cacheBytes(); chosenBytes = photos.chosenBytes(); budget = photos.cacheBudget()
    }
    SettingsEntry("Photo sources") {
        Text("Deezer → Wikimedia → album art", style = MaterialTheme.typography.titleMedium)
    }
    SettingsDescription("Photos are matched by artist name and releases in your library. Album art appears immediately while a photo loads. Photos refresh after 30 days and stay cached for offline use.")
    SettingsEntry("Photo cache size") {
        SettingsDescription("Photo cache size: ${String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))} MiB / ${budget / (1024 * 1024)} MiB")
    }
    ChoicePreference("Photo cache budget", budget, listOf(128L, 512L, 1024L, 2048L).map { it * 1024 * 1024 },
        label = { "${it / (1024 * 1024)} MiB" }, popup = true) { selected -> scope.launch { photos.setCacheBudget(selected) } }
    SettingsDescription("Chosen photos: ${String.format(Locale.ROOT, "%.1f", chosenBytes / (1024.0 * 1024.0))} MiB, kept separately. Automatic photos use least-recently-displayed eviction. Visible photos are protected.")
    fun run(refresh: Boolean) {
        scope.launch {
            busy = true; status = null
            try {
                if (refresh) {
                    val requests = withContext(Dispatchers.Default) { libraryArtistPhotoRequests(snapshot) }
                    if (work != null) {
                        work.refresh(requests)
                        status = "Photo refresh queued. You can leave this page."
                    } else {
                        photos.refreshAll(requests)
                        status = if (consent.allowed.value) "Artist photos refreshed." else "Refresh stopped: Online features are off."
                    }
                } else { photos.clearCache(); status = "Photo cache cleared. Match choices are kept." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Could not update artist photos. Try again." }
            finally { busy = false }
        }
    }
    SettingsEntry("Clear photo cache") {
        ActionButton(onClick = { run(false) }, enabled = !busy) { Text("Clear photo cache") }
    }
    SettingsEntry("Refresh all photos") {
        ActionButton(onClick = { run(true) }, enabled = allowed && !busy) { Text("Refresh all photos") }
    }
    if (!allowed) SettingsDescription("Enable Online features to fetch photos. Artist images use album art while it is off.")
    if (busy) SettingsDescription("Updating artist photos…")
    progress?.let { current ->
        if (current.pending > 0) SettingsDescription("${current.pending} artists waiting${current.artist?.let { " · Fetching $it" }.orEmpty()}")
        if (current.failed.isNotEmpty()) {
            SettingsDescription("${current.failed.size} artists couldn't be fetched.")
            ActionTextButton({ scope.launch { work?.retry() } }, enabled = allowed) { Text("Retry") }
        }
    }
    status?.let { SettingsDescription(it) }
    SettingsDescription("Choose a different photo or clear a wrong match from an artist’s ⋯ menu. Clearing a match keeps album art until you choose a photo again.")
}
