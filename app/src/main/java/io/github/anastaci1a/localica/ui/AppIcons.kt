package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.icons.IconPacks

/** Every icon the app draws. [id] is the stable pack key; [builtin] is the fallback vector. */
internal enum class AppIcon(val id: String, @DrawableRes val builtin: Int) {
    TabHome("tab.home", R.drawable.ic_home),
    TabLibrary("tab.library", R.drawable.ic_library),
    TabSongs("tab.songs", R.drawable.ic_music),
    TabAlbums("tab.albums", R.drawable.ic_albums),
    TabArtists("tab.artists", R.drawable.ic_artist),
    TabFolders("tab.folders", R.drawable.ic_folder),
    TabPlaylists("tab.playlists", R.drawable.ic_playlist),
    TabQueues("tab.queues", R.drawable.ic_queue),
    TransportPlay("transport.play", R.drawable.ic_play),
    TransportPause("transport.pause", R.drawable.ic_pause),
    TransportPrevious("transport.previous", R.drawable.ic_previous),
    TransportNext("transport.next", R.drawable.ic_next),
    MenuPin("menu.pin", R.drawable.ic_menu_pin),
    MenuTrash("menu.trash", R.drawable.ic_menu_trash),
    MenuListCreate("menu.list_create", R.drawable.ic_menu_playlist_add),
    MenuListAddNext("menu.list_add_next", R.drawable.ic_menu_play_next),
    MenuListAddLast("menu.list_add_last", R.drawable.ic_menu_queue_add),
    MenuInfo("menu.info", R.drawable.ic_menu_info),
    MenuDownload("menu.download", R.drawable.ic_download),
    MenuShare("menu.share", R.drawable.ic_share),
    MenuStation("menu.station", R.drawable.ic_station),
    MenuFavorite("menu.favorite", R.drawable.ic_star),
    MenuSuggestLess("menu.suggest_less", R.drawable.ic_thumb_down),
    ActionEdit("action.edit", R.drawable.ic_edit),
    ActionCheck("action.check", R.drawable.ic_check),
    ActionRefresh("action.refresh", R.drawable.ic_refresh),
    ActionRescan("action.rescan", R.drawable.ic_rescan),
    ActionClose("action.close", R.drawable.ic_close),
    ActionChevronUp("action.chevron_up", R.drawable.ic_chevron_up),
    ActionChevronDown("action.chevron_down", R.drawable.ic_down),
    ActionChevronLeft("action.chevron_left", R.drawable.ic_back),
    ActionChevronRight("action.chevron_right", R.drawable.ic_chevron_right),
    ActionArrowUp("action.arrow_up", R.drawable.ic_arrow_up),
    ActionArrowDown("action.arrow_down", R.drawable.ic_arrow_down),
    ActionArrowLeft("action.arrow_left", R.drawable.ic_arrow_left),
    ActionArrowRight("action.arrow_right", R.drawable.ic_arrow_right),
    ActionSearch("action.search", R.drawable.ic_search),
    ActionEllipsis("action.ellipsis", R.drawable.ic_ellipsis),
    ActionPlus("action.plus", R.drawable.ic_plus),
    ActionSort("action.sort", R.drawable.ic_sort),
    ActionEye("action.eye", R.drawable.ic_eye),
    ActionEyeOff("action.eye_off", R.drawable.ic_eye_off),
    ActionDrag("action.drag", R.drawable.ic_drag),
    ActionRevert("action.revert", R.drawable.ic_revert),
    ActionReverse("action.reverse", R.drawable.ic_reverse),
    ActionSave("action.save", R.drawable.ic_download),
    ActionSettings("action.settings", R.drawable.ic_settings),
    ActionShuffle("action.shuffle", R.drawable.ic_shuffle),
    ActionRepeat("action.repeat", R.drawable.ic_repeat),
    ActionRepeatOne("action.repeat_one", R.drawable.ic_repeat_one),
    ActionSeekBack("action.seek_back", R.drawable.ic_seek_back),
    ActionSeekForward("action.seek_forward", R.drawable.ic_seek_forward),
    ActionLyrics("action.lyrics", R.drawable.ic_lyrics),
    ActionInfo("action.info", R.drawable.ic_info),
    ActionTrash("action.trash", R.drawable.ic_trash),
    ActionListAddLast("action.list_add_last", R.drawable.ic_playlist_add),
    SettingsInterface("settings.interface", R.drawable.ic_settings_interface),
    SettingsAppearance("settings.appearance", R.drawable.ic_settings_appearance),
    SettingsIconPacks("settings.icon_packs", R.drawable.ic_settings_icon_packs),
    SettingsGlass("settings.glass", R.drawable.ic_settings_glass),
    SettingsNavigation("settings.navigation", R.drawable.ic_settings_navigation),
    SettingsPlayer("settings.player", R.drawable.ic_settings_player),
    SettingsMotion("settings.motion", R.drawable.ic_settings_motion),
    SettingsLibraryFilters("settings.library_filters", R.drawable.ic_settings_library_filters),
    SettingsMetadataFailures("settings.metadata_failures", R.drawable.ic_settings_metadata_failures),
    SettingsTagJobs("settings.tag_jobs", R.drawable.ic_settings_tag_jobs);
    companion object {
        val ids: Set<String> = entries.mapTo(linkedSetOf()) { it.id }
        val legacyIds: Map<String, String> = mapOf(
            "action.back" to "action.chevron_left", "action.forward" to "action.arrow_right",
            "action.down" to "action.chevron_down", "action.save_artwork" to "action.save",
            "action.more" to "action.ellipsis", "menu.playlist_add" to "menu.list_create",
            "menu.play_next" to "menu.list_add_next", "menu.queue_add" to "menu.list_add_last",
            "action.playlist_add" to "action.list_add_last",
        )
        fun byId(id: String): AppIcon? = entries.firstOrNull { it.id == (legacyIds[id] ?: id) }
        /** Canonical mappings win over aliases, independent of archive order or image format. */
        fun packIds(icon: AppIcon): List<String> = listOf(icon.id) + legacyIds.filterValues { it == icon.id }.keys
    }
}

/** The one resolver: the active pack's mask for this icon, otherwise the built-in vector. */
@Composable
internal fun appIconPainter(icon: AppIcon): Painter {
    val images by IconPacks.images.collectAsState()
    return packIconPainter(icon, images.masks[icon.id], images.vectors[icon.id])
}

/** Explicit pack preview; never resolves through the currently selected pack. */
@Composable
internal fun packIconPainter(icon: AppIcon, mask: Bitmap?, vector: ImageVector? = null): Painter {
    if (vector != null) return rememberVectorPainter(vector)
    val builtin = painterResource(icon.builtin)
    return if (mask == null) builtin else remember(mask) { MaskPainter(mask) }
}

/** Pack images are alpha masks: the caller's tint decides the colour, and the size follows the host Icon. */
private class MaskPainter(bitmap: Bitmap) : Painter() {
    private val image = bitmap.asImageBitmap()
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            colorFilter = currentFilter, filterQuality = FilterQuality.High)
    }
    private var currentFilter: ColorFilter? = null
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean { currentFilter = colorFilter; return true }
}
