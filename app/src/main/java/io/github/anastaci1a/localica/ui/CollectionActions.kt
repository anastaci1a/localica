package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*

/** One collection menu for tiles, rows, Home pins and the collection itself. */
@Composable internal fun CollectionActions(visible: Boolean, anchor: GlassAnchor, item: BrowserItem,
    playlists: PlaylistState, controller: PlaylistController?, queues: QueueController?, pins: List<MediaRef>,
    onPin: (MediaRef) -> Unit, onDismiss: () -> Unit, onDeleted: () -> Unit = {}, onRenamed: (String) -> Unit = {},
    onInformation: ((io.github.anastaci1a.localica.library.Track) -> Unit)? = null,
    containingSource: MediaRef? = null, onRemove: (() -> Unit)? = null,
    selection: SessionSelection? = LocalSessionSelection.current,
    extra: @Composable () -> Unit = {}) {
    val editTags = LocalTagEdit.current
    val selectionTarget = item.selectionTarget(containingSource)
    var adding by remember { mutableStateOf(false) }
    var queuePicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun act(action: SongTap) { onDismiss(); queues?.act(item.songs.map { it.track }, action, item.songs.map { it.sourcePlaylistId }) }
    GlassMenu(visible, anchor, onDismiss) {
        item.row?.let { row ->
            ActionMenuItem("Information", enabled = onInformation != null, onClick = { onDismiss(); onInformation?.invoke(row.track) })
            if (editTags != null) ActionMenuItem("Edit tags", onClick = { onDismiss(); editTags(listOf(row.track)) {} })
        }
        if (item.row == null && editTags != null) ActionMenuItem("Edit tags", enabled = item.songs.isNotEmpty(),
            onClick = { onDismiss(); editTags(item.songs.map { it.track }.distinctBy { it.id }) {} })
        ActionMenuItem(if (item.row == null) "Select all songs" else "Select song", enabled = selectionTarget.rows.isNotEmpty(),
            onClick = { selection?.select(selectionTarget.source, selectionTarget.rows); onDismiss() })
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (item.row != null) {
            ActionMenuItem("New queue", enabled = queues != null, onClick = { act(SongTap.NewQueue) })
            ActionMenuItem("Play now", enabled = queues != null, onClick = { act(SongTap.PlayNow) })
        }
        ActionMenuItem("Play next", enabled = item.songs.isNotEmpty() && queues != null, onClick = { act(SongTap.PlayNext) })
        ActionMenuItem("Add to current queue", enabled = item.songs.isNotEmpty() && queues != null, onClick = { act(SongTap.Append) })
        ActionMenuItem("Add to a queue", enabled = item.songs.isNotEmpty() && queues != null, onClick = { onDismiss(); queuePicker = true })
        ActionMenuItem("Add to playlist", enabled = item.songs.isNotEmpty() && controller != null, onClick = { onDismiss(); adding = true })
        if (item.ref.kind in io.github.anastaci1a.localica.home.PinEdits.supported) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ActionMenuItem(if (item.ref in pins) "Unpin from Home" else "Pin to Home", onClick = { onPin(item.ref); onDismiss() })
        }
        if (item.ref.kind == MediaKind.Playlist) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ActionMenuItem("Set playlist image", enabled = controller != null, onClick = { onDismiss(); image = true })
            ActionMenuItem("Rename playlist", enabled = controller != null, onClick = { onDismiss(); renaming = true })
            ActionMenuItem("Delete playlist", enabled = controller != null, onClick = { onDismiss(); deleting = true })
        }
        extra()
        if (onRemove != null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ActionMenuItem(if (selectionTarget.source?.kind == MediaKind.Queue) "Remove from queue" else "Remove from playlist",
                onClick = { onDismiss(); onRemove() })
        }
    }
    if (adding) AddToPlaylistDialog(item.songs.map { it.track }, playlists, controller, { adding = false }, { adding = false })
    if (queuePicker && queues != null) {
        val state by queues.state.collectAsState()
        ChoiceDialog("Add to a queue", state.library.queues, state.library.active ?: state.library.queues.firstOrNull(),
            { it?.name.orEmpty() }, { queuePicker = false }) { target ->
            if (target != null) queues.appendTo(target.id, item.songs) { if (!it) failure = "Couldn’t add these songs to the queue." }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(item.title) }
        val available = PlaylistEdits.nameAvailable(playlists.playlists, name, item.ref.id)
        GlassAlertDialog({ if (!busy) renaming = false }, title = { Text("Rename playlist") }, text = {
            Column {
                GlassTextField(name, { name = it }, singleLine = true, label = { Text("Playlist name") }, enabled = !busy)
                if (name.isNotBlank() && !available) Text("A playlist with this name already exists.", color = MaterialTheme.colorScheme.error)
                playlists.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { ActionTextButton(enabled = available && !busy, onClick = {
            busy = true; controller?.rename(item.ref.id, name) { ok -> busy = false; if (ok) { renaming = false; onRenamed(name.trim()) } }
        }) { Text("Save") } }, dismissButton = { ActionTextButton(enabled = !busy, onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (deleting) GlassAlertDialog({ if (!busy) deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("Delete “${item.title}”? Your music files stay on the device.") },
        confirmButton = { ActionTextButton(enabled = !busy, onClick = {
            busy = true; controller?.delete(item.ref.id) { ok -> busy = false; if (ok) { deleting = false; onDeleted() } }
        }) { Text("Delete") } }, dismissButton = { ActionTextButton(enabled = !busy, onClick = { deleting = false }) { Text("Cancel") } })
    if (image) ImageChooser({ image = false }) { cropped ->
        image = false
        scope.launch {
            var stored: java.io.File? = null
            try {
                stored = withContext(Dispatchers.IO) {
                    val bytes = io.github.anastaci1a.localica.editing.CoverImages(context).read(cropped).bytes
                    val directory = java.io.File(context.filesDir, "playlist-covers").apply { mkdirs() }
                    java.io.File(directory, "${java.util.UUID.randomUUID()}.png").apply { writeBytes(bytes) }
                }
                val output = stored
                controller?.cover(item.ref.id, android.net.Uri.fromFile(output).toString()) { ok ->
                    if (!ok) { output?.delete(); failure = "Couldn’t save the playlist image." }
                }
            } catch (cancelled: CancellationException) { stored?.delete(); throw cancelled }
            catch (_: Exception) { stored?.delete(); failure = "Couldn’t save the playlist image." }
        }
    }
    failure?.let { message -> GlassAlertDialog({ failure = null }, title = { Text("Collection") }, text = { Text(message) },
        confirmButton = { ActionTextButton({ failure = null }) { Text("Close") } }) }
}
