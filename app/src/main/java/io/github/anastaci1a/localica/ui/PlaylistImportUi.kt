package io.github.anastaci1a.localica.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.playlist.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

private class LocalPlaylistPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun PlaylistCreationButton(library: LibrarySnapshot, state: PlaylistState, controller: PlaylistController?,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<PlaylistImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val menuAnchor = rememberGlassAnchor()
    val menuSource = LocalFrostedBackdrop.current
    SideEffect {
        menuAnchor.source = menuSource
        menuAnchor.face = { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_plus), null, Modifier.size(24.dp)) }
    }
    val picker = rememberLauncherForActivityResult(LocalPlaylistPicker()) { uri ->
        if (uri != null) loadJob = scope.launch {
            loading = true; error = null
            try {
                preview = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty()
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer); if (read < 0) break
                            require(output.size() + read <= PlaylistImport.MAX_BYTES) { "Playlist exceeds the 5 MB limit." }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: error("Could not open playlist")
                    PlaylistImport.parse(bytes, name, library, PlaylistImport.documentPath(uri.toString())?.substringBeforeLast('/', ""))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: IllegalArgumentException) { error = problem.message ?: "This playlist could not be imported." }
            catch (_: Exception) { error = "Couldn’t read the playlist. Select a local M3U, M3U8, or PLS file and try again." }
            finally { loading = false }
        }
    }
    Box(modifier) {
        LiquidGlassSurface(Modifier.size(CompactControlSize).glassAnchor(menuAnchor).clickable { menu = true }) {
            menuAnchor.captureInk()
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_plus), "Add playlist", Modifier.size(24.dp))
            }
        }
        GlassMorphPopup(menu, menuAnchor, { menu = false }) {
            Column(Modifier.padding(vertical = 8.dp).controlVerticalScroll(rememberScrollState())) {
            ActionMenuItem("Create playlist", enabled = !state.loading && !loading, onClick = { menu = false; naming = true })
            ActionMenuItem("Import playlist from file", enabled = !state.loading && !loading,
                onClick = { menu = false; picker.launch(arrayOf("*/*")) })
            }
        }
    }
    if (loading) GlassAlertDialog(onDismissRequest = { loadJob?.cancel() }, title = { Text("Reading playlist") },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {
            ActionTextButton(onClick = { loadJob?.cancel() }) { Text("Cancel") }
        })
    if (naming) PlaylistNameDialog(controller, { naming = false })
    preview?.let { found -> PlaylistImportDialog(found, controller, { preview = null }) }
    error?.let { message -> GlassAlertDialog(onDismissRequest = { error = null }, title = { Text("Playlist import") }, text = { Text(message) },
        confirmButton = { ActionTextButton(onClick = { error = null }) { Text("Close") } }) }
}

@Composable
private fun PlaylistNameDialog(controller: PlaylistController?, onDismiss: () -> Unit) {
    val playlists = controller?.state?.collectAsState()?.value ?: PlaylistState()
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    GlassAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("New playlist") }, text = {
        Column {
            GlassTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, enabled = !saving)
            if (name.isNotBlank() && !PlaylistEdits.nameAvailable(playlists.playlists, name)) Text("A playlist with this name already exists.", color = MaterialTheme.colorScheme.error)
            if (failed) Text("Couldn’t save the playlist. Try again.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { ActionTextButton(enabled = PlaylistEdits.nameAvailable(playlists.playlists, name) && !saving && controller != null, onClick = {
        saving = true; controller?.create(name) { success -> saving = false; failed = !success; if (success) onDismiss() }
    }) { Text("Create") } }, dismissButton = { ActionTextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun PlaylistImportDialog(preview: PlaylistImportPreview, controller: PlaylistController?, onDismiss: () -> Unit) {
    val playlists = controller?.state?.collectAsState()?.value ?: PlaylistState()
    var name by remember(preview) { mutableStateOf(preview.name) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val found = preview.tracks
    val missing = preview.entries.size - found.size
    GlassAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Import playlist") }, text = {
        Column(Modifier.controlVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, enabled = !saving)
            Text("${found.size} matched · $missing unresolved. Song order and repeated entries are preserved.")
            if (missing > 0) Text("Unresolved entries will be skipped. Scan missing folders first if you want to include them.")
            if (preview.entries.any { it.nameOnly }) Text("Some songs matched by unique filename. Review them below.")
            val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).scrollEdgeFade(scroll), state = scroll,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(preview.entries) { index, entry ->
                    Column {
                        Text("${index + 1}. ${entry.track?.title ?: entry.path}", style = MaterialTheme.typography.titleSmall)
                        Text(entry.path, style = MaterialTheme.typography.bodySmall)
                        entry.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (entry.nameOnly) Text("Matched by filename", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (name.isNotBlank() && !PlaylistEdits.nameAvailable(playlists.playlists, name)) Text("A playlist with this name already exists.", color = MaterialTheme.colorScheme.error)
            if (failed) Text("Couldn’t save the playlist. Try again.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { ActionTextButton(enabled = PlaylistEdits.nameAvailable(playlists.playlists, name) && found.isNotEmpty() && !saving && controller != null, onClick = {
        saving = true; controller?.create(name, found) { success -> saving = false; failed = !success; if (success) onDismiss() }
    }) { Text("Import ${found.size} songs") } }, dismissButton = { ActionTextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
