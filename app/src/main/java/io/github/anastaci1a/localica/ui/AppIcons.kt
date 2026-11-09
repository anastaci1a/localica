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
import androidx.compose.ui.res.vectorResource
import io.github.anastaci1a.localica.icons.BuiltinIcons
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.icons.IconPacks

/** Every icon the app draws. [id] is the stable pack key; [builtin] is the fallback vector. */
internal enum class AppIcon(val id: String, @DrawableRes val builtin: Int) {
    TabHome("nav.home", R.drawable.ic_home),
    TabLibrary("nav.library", R.drawable.ic_library),
    TabSongs("nav.songs", R.drawable.ic_music),
    TabAlbums("nav.albums", R.drawable.ic_albums),
    TabArtists("nav.artists", R.drawable.ic_artist),
    TabFolders("nav.folders", R.drawable.ic_folder),
    TabPlaylists("nav.playlists", R.drawable.ic_playlist),
    TabQueues("nav.queues", R.drawable.ic_queue),
    TransportPlay("transport.play", R.drawable.ic_play),
    TransportPause("transport.pause", R.drawable.ic_pause),
    TransportPrevious("transport.previous", R.drawable.ic_previous),
    TransportNext("transport.next", R.drawable.ic_next),
    MenuPin("action.pin", R.drawable.ic_menu_pin),
    MenuTrash("action.remove", R.drawable.ic_menu_trash),
    MenuListCreate("action.create_list", R.drawable.ic_menu_playlist_add),
    MenuListAddNext("action.add_next", R.drawable.ic_menu_play_next),
    MenuListAddLast("action.add_to_queue", R.drawable.ic_menu_queue_add),
    MenuInfo("action.show_info", R.drawable.ic_menu_info),
    MenuDownload("action.download", R.drawable.ic_download),
    MenuShare("action.share", R.drawable.ic_share),
    MenuStation("action.start_station", R.drawable.ic_station),
    MenuFavorite("action.favorite", R.drawable.ic_star),
    MenuSuggestLess("action.suggest_less", R.drawable.ic_thumb_down),
    ActionEdit("action.edit", R.drawable.ic_edit),
    ActionCheck("selection.confirm", R.drawable.ic_check),
    ActionRefresh("action.refresh", R.drawable.ic_refresh),
    ActionRescan("action.rescan", R.drawable.ic_rescan),
    ActionClose("action.close", R.drawable.ic_close),
    ActionChevronUp("nav.chevron_up", R.drawable.ic_chevron_up),
    ActionChevronDown("nav.chevron_down", R.drawable.ic_down),
    ActionChevronLeft("nav.chevron_left", R.drawable.ic_back),
    ActionChevronRight("nav.chevron_right", R.drawable.ic_chevron_right),
    ActionArrowUp("nav.arrow_up", R.drawable.ic_arrow_up),
    ActionArrowDown("nav.arrow_down", R.drawable.ic_arrow_down),
    ActionArrowLeft("nav.arrow_left", R.drawable.ic_arrow_left),
    ActionArrowRight("nav.arrow_right", R.drawable.ic_arrow_right),
    ActionSearch("action.search", R.drawable.ic_search),
    ActionEllipsis("action.more", R.drawable.ic_ellipsis),
    ActionPlus("action.add", R.drawable.ic_plus),
    ActionSort("sort.choose", R.drawable.ic_sort),
    ActionEye("view.show", R.drawable.ic_eye),
    ActionEyeOff("view.hide", R.drawable.ic_eye_off),
    ActionDrag("action.reorder", R.drawable.ic_drag),
    ActionRevert("sort.revert", R.drawable.ic_revert),
    ActionReverse("sort.reverse", R.drawable.ic_reverse),
    ActionSave("action.save", R.drawable.ic_download),
    ActionSettings("nav.settings", R.drawable.ic_settings),
    ActionShuffle("transport.shuffle", R.drawable.ic_shuffle),
    ActionRepeat("transport.repeat", R.drawable.ic_repeat),
    ActionRepeatOne("transport.repeat_one", R.drawable.ic_repeat_one),
    ActionSeekBack("transport.seek_back", R.drawable.ic_seek_back),
    ActionSeekForward("transport.seek_forward", R.drawable.ic_seek_forward),
    ActionLyrics("view.lyrics", R.drawable.ic_lyrics),
    ActionInfo("view.info", R.drawable.ic_info),
    ActionTrash("action.delete", R.drawable.ic_trash),
    ActionListAddLast("action.add_to_playlist", R.drawable.ic_playlist_add),
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
            "tab.home" to "nav.home",
            "tab.library" to "nav.library",
            "tab.songs" to "nav.songs",
            "tab.albums" to "nav.albums",
            "tab.artists" to "nav.artists",
            "tab.folders" to "nav.folders",
            "tab.playlists" to "nav.playlists",
            "tab.queues" to "nav.queues",
            "menu.pin" to "action.pin",
            "menu.trash" to "action.remove",
            "menu.list_create" to "action.create_list",
            "menu.list_add_next" to "action.add_next",
            "menu.list_add_last" to "action.add_to_queue",
            "menu.info" to "action.show_info",
            "menu.download" to "action.download",
            "menu.share" to "action.share",
            "menu.station" to "action.start_station",
            "menu.favorite" to "action.favorite",
            "menu.suggest_less" to "action.suggest_less",
            "action.check" to "selection.confirm",
            "action.chevron_up" to "nav.chevron_up",
            "action.chevron_down" to "nav.chevron_down",
            "action.chevron_left" to "nav.chevron_left",
            "action.chevron_right" to "nav.chevron_right",
            "action.arrow_up" to "nav.arrow_up",
            "action.arrow_down" to "nav.arrow_down",
            "action.arrow_left" to "nav.arrow_left",
            "action.arrow_right" to "nav.arrow_right",
            "action.ellipsis" to "action.more",
            "action.plus" to "action.add",
            "action.sort" to "sort.choose",
            "action.eye" to "view.show",
            "action.eye_off" to "view.hide",
            "action.drag" to "action.reorder",
            "action.revert" to "sort.revert",
            "action.reverse" to "sort.reverse",
            "action.settings" to "nav.settings",
            "action.shuffle" to "transport.shuffle",
            "action.repeat" to "transport.repeat",
            "action.repeat_one" to "transport.repeat_one",
            "action.seek_back" to "transport.seek_back",
            "action.seek_forward" to "transport.seek_forward",
            "action.lyrics" to "view.lyrics",
            "action.info" to "view.info",
            "action.trash" to "action.delete",
            "action.list_add_last" to "action.add_to_playlist",
            "action.back" to "nav.chevron_left",
            "action.forward" to "nav.arrow_right",
            "action.down" to "nav.chevron_down",
            "action.save_artwork" to "action.save",
            "menu.playlist_add" to "action.create_list",
            "menu.play_next" to "action.add_next",
            "menu.queue_add" to "action.add_to_queue",
            "action.playlist_add" to "action.add_to_playlist",
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
    if (mask != null) return remember(mask) { MaskPainter(mask) }
    val source = ImageVector.vectorResource(icon.builtin)
    val builtin = remember(source, icon) { BuiltinIcons.vector(source, icon) }
    return rememberVectorPainter(builtin)
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
