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
    var bytes by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision, busy) { bytes = photos.cacheBytes() }
    SettingsEntry("Photo sources") {
        Text("Deezer → Wikimedia → album art", style = MaterialTheme.typography.titleMedium)
    }
    Text("Photos are matched by artist name and releases in your library. Album art appears immediately while a photo loads. Photos refresh after 30 days and stay cached for offline use.")
    SettingsEntry("Photo cache size") { Text("Photo cache size: ${String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))} MiB / 64 MiB") }
    fun run(refresh: Boolean) {
        scope.launch {
            busy = true; status = null
            try {
                if (refresh) {
                    val requests = withContext(Dispatchers.Default) { libraryArtistPhotoRequests(snapshot) }
                    photos.refreshAll(requests)
                    status = if (consent.allowed.value) "Artist photos refreshed." else "Refresh stopped: Online features are off."
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
    if (!allowed) Text("Enable Online features to fetch photos. Artist images use album art while it is off.")
    if (busy) Text("Updating artist photos…")
    status?.let { Text(it) }
    Text("Choose a different photo or clear a wrong match from an artist’s ⋯ menu. Clearing a match keeps album art until you choose a photo again.")
}
