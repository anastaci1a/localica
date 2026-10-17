package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.queue.QueueRepeat
import io.github.anastaci1a.localica.queue.AdvancedRepeat
import io.github.anastaci1a.localica.queue.SongEnd
import io.github.anastaci1a.localica.queue.QueueEnd

internal enum class PlayerAction(val label: String, val icon: Int) {
    Info("Information", R.drawable.ic_info), Edit("Edit tags", R.drawable.ic_edit), Artwork("Artwork", R.drawable.ic_albums),
    Lyrics("Lyrics", R.drawable.ic_lyrics), Playlist("Add to playlist", R.drawable.ic_playlist_add), Pin("Pin to Home", R.drawable.ic_home),
    Album("Go to album", R.drawable.ic_albums), Artist("Go to artist", R.drawable.ic_artist),
    AlbumArtist("Go to Album Artist", R.drawable.ic_artist), Folder("Go to folder", R.drawable.ic_folder),
    Shuffle("Shuffle", R.drawable.ic_shuffle), Repeat("Repeat", R.drawable.ic_repeat), Count("Track count", R.drawable.ic_queue),
    BackTen("Back 10 seconds", R.drawable.ic_seek_back), ForwardTen("Forward 10 seconds", R.drawable.ic_seek_forward), More("More actions", R.drawable.ic_more)
}

@Composable
internal fun PlayerActionGlyph(action: PlayerAction, description: String?,
    repeat: QueueRepeat = QueueRepeat.Off, count: String = "1 / 12", advancedRepeat: AdvancedRepeat? = null) {
    Box(contentAlignment = Alignment.Center) {
        if (action == PlayerAction.Count) Text(count.replace(" / ", "/"), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.semantics { if (description != null) contentDescription = description })
        else {
            Icon(painterResource(action.icon), description, Modifier.size(24.dp))
            if (action == PlayerAction.BackTen || action == PlayerAction.ForwardTen) Text("10", fontSize = 8.sp,
                modifier = Modifier.clearAndSetSemantics {}, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (action == PlayerAction.Repeat) {
                val badge = when {
                    repeat == QueueRepeat.One -> "1"
                    advancedRepeat?.songEnd == SongEnd.Stop -> "■"
                    advancedRepeat?.songEnd == SongEnd.NextPaused -> "Ⅱ"
                    advancedRepeat?.queueEnd == QueueEnd.NextQueue -> "Q"
                    else -> null
                }
                // The icon owns the single live description. Decorative variants keep
                // fixed bounds and fade independently, even when reversed mid-transition.
                Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    listOf("1", "■", "Ⅱ", "Q").forEach { variant ->
                        val opacity by animateControlStateFloat(if (badge == variant) 1f else 0f, badge == variant, "Repeat badge $variant")
                        if (opacity > 0f) Text(variant, fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.graphicsLayer { alpha = opacity })
                    }
                }
            }
        }
    }
}

/** Fixed balanced slots or a single horizontal carousel; empty positions are intentional. */
@Composable
internal fun PlayerActionStrip(value: PlayerActionSettings, label: (PlayerAction) -> String,
    onAction: (PlayerAction) -> Unit, onCustomize: () -> Unit, shuffle: Boolean = false, repeat: QueueRepeat = QueueRepeat.Off,
    lyricsOpen: Boolean = false, pinned: Boolean = false, count: String = "0 / 0", onRepeatOptions: (() -> Unit)? = null,
    advancedRepeat: AdvancedRepeat? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnWidth = if (value.centered) (maxWidth / value.columns.size).coerceAtLeast(48.dp) else if (value.small) 48.dp else 64.dp
        Row(Modifier.fillMaxWidth().nestedScroll(HorizontalGestureOwner).controlHorizontalScroll(rememberScrollState()).testTag("player-action-slots")) {
            value.playbackColumns.forEach { column -> Column(Modifier.width(columnWidth)) {
                kotlin.repeat(value.rows) { row ->
                    val slot = ActionSlot(row, column)
                    val action = value.slots[slot]
                    val active = when (action) {
                        PlayerAction.Shuffle -> shuffle
                        PlayerAction.Repeat -> repeat != QueueRepeat.Off || advancedRepeat != null
                        PlayerAction.Lyrics -> lyricsOpen
                        PlayerAction.Pin -> pinned
                        else -> false
                    }
                    val mode = action in setOf(PlayerAction.Shuffle, PlayerAction.Repeat, PlayerAction.Lyrics, PlayerAction.Pin)
                    val opacity by animateControlStateFloat(if (mode && !active) .5f else 1f, !mode || active, "Player action state")
                    Box(Modifier.fillMaxWidth().height(48.dp).clip(Corners.card)
                        .testTag("player-slot-${slot.key}")
                        .semantics {
                            if (action == null) contentDescription = "Empty player action slot, ${slot.label}"
                            if (mode) { selected = active; stateDescription = label(action!!) }
                        }
                        .ownButtonDrag().combinedClickable(interactionSource = null, indication = null, onClick = controlActivation(action != null) { if (action != null) onAction(action) },
                            onLongClickLabel = if (action == PlayerAction.Repeat && onRepeatOptions != null) "Repeat options" else "Customize player actions",
                            onLongClick = if (action == PlayerAction.Repeat && onRepeatOptions != null) onRepeatOptions else onCustomize),
                        contentAlignment = Alignment.Center) {
                        if (action != null) CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = LocalContentColor.current.alpha * opacity)) {
                            PlayerActionGlyph(action, label(action), repeat, count, advancedRepeat)
                        }
                    }
                }
            } }
        }
    }
}
