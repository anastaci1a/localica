package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowserItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class ArtistPhotoActions(val environment: ArtistPhotoEnvironment, val request: ArtistPhotoRequest, val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberArtistPhotoActions(item: BrowserItem): ArtistPhotoActions? {
    val environment = artistPhotoEnvironment()
    val scope = rememberCoroutineScope()
    return remember(item, environment) {
        val request = artistPhotoRequest(item.ref, item.title, item.songs.map { it.track }, item.coverUris.firstOrNull())
        if (environment == null || request == null) null else ArtistPhotoActions(environment, request, scope)
    }
}

@Composable
internal fun ArtistPhotoMenu(actions: ArtistPhotoActions?, onDismiss: () -> Unit) {
    if (actions == null) return
    val allowed by actions.environment.consent.allowed.collectAsState()
    val scope = actions.scope
    ActionMenuItem("Choose artist photo", icon = AppIcon.TabArtists, enabled = allowed, onClick = {
        onDismiss(); actions.open = true
    })
    ActionMenuItem("Clear artist photo", icon = AppIcon.MenuTrash, onClick = {
        onDismiss()
        scope.launch {
            try { actions.environment.photos.clear(actions.request.artistKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actions.problem = "Could not clear artist photo. Try again." }
        }
    })
}

@Composable
internal fun ArtistPhotoDialog(actions: ArtistPhotoActions?) {
    if (actions == null) return
    actions.problem?.let { problem ->
        GlassAlertDialog({ actions.problem = null }, title = { Text("Artist photo") }, text = { Text(problem) },
            confirmButton = { ActionTextButton({ actions.problem = null }) { Text("Close") } })
    }
    if (!actions.open) return
    val allowed by actions.environment.consent.allowed.collectAsState()
    var candidates by remember(actions) { mutableStateOf<List<ArtistPhotoCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val photo = rememberArtistPhoto(actions.request, actions.environment)
    val scope = actions.scope
    LaunchedEffect(actions, allowed) {
        candidates = null
        if (allowed) {
            try { candidates = actions.environment.photos.candidates(actions.request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = "Could not look up artist photos. Try again."; candidates = emptyList() }
        }
    }
    GlassAlertDialog({ actions.open = false }, title = { Text("Choose artist photo") }, text = {
        Column(Modifier.controlVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(actions.request.name)
            if (!allowed) Text("Enable Online features before choosing a photo.")
            else if (candidates == null) Text("Finding artist photos…")
            else if (candidates!!.isEmpty()) Text("No artist photos found. Album art is kept.")
            photo?.photo?.attribution?.let { credit ->
                Text("${credit.artist} · ${credit.credit} · ${credit.licence}\n${credit.licenceUrl}\n${credit.sourcePage}")
            }
            candidates.orEmpty().forEach { candidate ->
                ActionTextButton(enabled = allowed && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            actions.environment.photos.pick(actions.request.artistKey, candidate.choice)
                            actions.open = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { problem = "Could not save photo choice. Try again." }
                        finally { busy = false }
                    }
                }) {
                    Text("${candidate.name} · ${candidate.choice.provider.name.lowercase()}\n" +
                        candidate.releases.take(3).joinToString(" · ") + if (!candidate.overlapsLibrary) "\nNo matching local release; choose only if this is the right artist." else "")
                }
            }
            problem?.let { Text(it) }
        }
    }, confirmButton = { ActionTextButton({ actions.open = false }) { Text("Close") } })
}
