package io.github.anastaci1a.localica.ui

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.queue.QueueRepeat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

internal enum class PlayerAction(val label: String, val icon: Int) {
    Info("Information", R.drawable.ic_music), Edit("Edit tags", R.drawable.ic_settings), Artwork("Artwork", R.drawable.ic_albums),
    Lyrics("Lyrics", R.drawable.ic_lyrics), Playlist("Add to playlist", R.drawable.ic_playlist), Pin("Pin to Home", R.drawable.ic_home),
    Album("Go to album", R.drawable.ic_albums), Artist("Go to artist", R.drawable.ic_artist),
    AlbumArtist("Go to Album Artist", R.drawable.ic_artist), Folder("Go to folder", R.drawable.ic_folder),
    Shuffle("Shuffle", R.drawable.ic_shuffle), Repeat("Repeat", R.drawable.ic_repeat), Count("Track count", R.drawable.ic_queue),
    BackTen("Back 10 seconds", R.drawable.ic_previous), ForwardTen("Forward 10 seconds", R.drawable.ic_next), More("More actions", R.drawable.ic_more)
}
internal data class PlayerActionSettings(val actions: List<PlayerAction> = listOf(PlayerAction.Info, PlayerAction.Playlist, PlayerAction.Repeat, PlayerAction.Shuffle),
    val centered: Boolean = false, val twoRows: Boolean = false, val small: Boolean = false)
internal class PlayerActionPreferences(context: Context) {
    private val store = context.getSharedPreferences("player-actions", Context.MODE_PRIVATE)
    fun read() = PlayerActionSettings(
        store.getString("actions", null)?.split(',')?.mapNotNull { name -> PlayerAction.entries.firstOrNull { it.name == name } }?.distinct()
            ?: PlayerActionSettings().actions,
        store.getBoolean("centered", false), store.getBoolean("two-rows", false), store.getBoolean("small", false))
    fun write(value: PlayerActionSettings) { store.edit().putString("actions", value.actions.joinToString(",") { it.name })
        .putBoolean("centered", value.centered).putBoolean("two-rows", value.twoRows).putBoolean("small", value.small).apply() }
}

@Composable
internal fun PlayerActionStrip(value: PlayerActionSettings, label: (PlayerAction) -> String,
    onAction: (PlayerAction) -> Unit, onCustomize: () -> Unit, shuffle: Boolean = false, repeat: QueueRepeat = QueueRepeat.Off) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        val halves = value.actions.chunked(((value.actions.size + 1) / 2).coerceAtLeast(1))
        fun section(index: Int) = halves.getOrNull(index).orEmpty()
        @Composable fun RowScope.buttons(actions: List<PlayerAction>, alignment: Alignment.Horizontal) {
            BoxWithConstraints(Modifier.weight(1f)) {
            Column(Modifier.nestedScroll(HorizontalGestureOwner).horizontalScroll(rememberScrollState()).widthIn(min = maxWidth), horizontalAlignment = if (value.centered) Alignment.CenterHorizontally else alignment) {
                val rows = if (value.twoRows) actions.chunked(((actions.size + 1) / 2).coerceAtLeast(1)) else listOf(actions)
                rows.forEach { row -> Row { row.forEach { action ->
                    val mode = action in setOf(PlayerAction.Shuffle, PlayerAction.Repeat)
                    val active = if (action == PlayerAction.Shuffle) shuffle else action == PlayerAction.Repeat && repeat != QueueRepeat.Off
                    val foreground = LocalContentColor.current
                    IconButton(onClick = { onAction(action) }, Modifier.size(48.dp)
                        .then(if (active) Modifier.background(foreground.copy(alpha = .16f), Corners.card) else Modifier)
                        .semantics { if (mode) { selected = active; stateDescription = label(action) } }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(action.icon), label(action), Modifier.size(if (value.small) 20.dp else 24.dp),
                            tint = foreground.copy(alpha = if (mode && !active) .5f else 1f))
                        if (action == PlayerAction.Repeat && repeat == QueueRepeat.One) Text("1", fontSize = 11.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clearAndSetSemantics {})
                    }
                } } } }
            } }
        }
        buttons(section(0), Alignment.Start)
        Box(Modifier.width(48.dp).fillMaxHeight().combinedClickable(onClick = {}, onLongClickLabel = "Customize player actions",
            onLongClick = onCustomize))
        buttons(section(1), Alignment.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerActionCustomizer(value: PlayerActionSettings, onChange: (PlayerActionSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = Corners.panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Player actions", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.ic_close, "Close customization", onClick = onDismiss)
            }
            BooleanPreference("Centered", value.centered) { onChange(value.copy(centered = it)) }
            BooleanPreference("Two rows", value.twoRows) { onChange(value.copy(twoRows = it)) }
            BooleanPreference("Small icons", value.small) { onChange(value.copy(small = it)) }
            value.actions.forEachIndexed { index, action -> key(action) { Row(verticalAlignment = Alignment.CenterVertically) {
                ReorderHandle("Reorder ${action.label}") { offset ->
                    val to = (index + offset).coerceIn(0, value.actions.lastIndex)
                    onChange(value.copy(actions = value.actions.toMutableList().apply { add(to, removeAt(index)) }))
                }
                Text(action.label, Modifier.weight(1f))
                ActionIcon(R.drawable.ic_previous, "Move ${action.label} earlier", index > 0) {
                    onChange(value.copy(actions = value.actions.toMutableList().apply { add(index - 1, removeAt(index)) }))
                }
                ActionIcon(R.drawable.ic_next, "Move ${action.label} later", index < value.actions.lastIndex) {
                    onChange(value.copy(actions = value.actions.toMutableList().apply { add(index + 1, removeAt(index)) }))
                }
                ActionIcon(R.drawable.ic_close, "Remove ${action.label}") { onChange(value.copy(actions = value.actions - action)) }
            } } }
            Text("Available actions", style = MaterialTheme.typography.titleMedium)
            FlowRow { PlayerAction.entries.filterNot { it in value.actions }.forEach { action ->
                TextButton(onClick = { onChange(value.copy(actions = value.actions + action)) }) { Text(action.label) }
            } }
            TextButton(onClick = { onChange(PlayerActionSettings()) }) { Text("Reset player actions") }
        }
    }
}
