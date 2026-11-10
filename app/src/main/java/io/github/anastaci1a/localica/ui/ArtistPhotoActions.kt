package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowserItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class ArtistPhotoActions(val environment: ArtistPhotoEnvironment, val request: ArtistPhotoRequest, val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var information by mutableStateOf(false)
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
    ActionMenuItem("Artist photo information", icon = AppIcon.TabArtists, onClick = {
        onDismiss(); actions.information = true
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
    if (actions.information) {
        val photo = rememberArtistPhoto(actions.request, actions.environment)?.photo
        val details = photo?.attribution?.let { credit ->
            "${credit.artist} · ${credit.credit} · ${credit.licence}\n${credit.licenceUrl}\n${credit.sourcePage}"
        } ?: photo?.let { "Photo source: ${it.provider.name.lowercase()}" }
            ?: "No artist photo is ready. Album art is used as the fallback."
        GlassAlertDialog({ actions.information = false }, title = { Text("Artist photo information") },
            text = { Text(details, Modifier.controlVerticalScroll(rememberScrollState())) }, confirmButton = {
                ActionTextButton({ actions.information = false }) { Text("Close") }
            })
    }
    if (!actions.open) return
    ArtistPhotoChooser(actions)
}
