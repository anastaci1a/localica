package io.github.anastaci1a.localica.editing

import io.github.anastaci1a.localica.library.Track
import org.jaudiotagger.tag.FieldKey

enum class EditField(val label: String, val key: FieldKey, val numeric: Boolean = false) {
    Title("Name", FieldKey.TITLE), Album("Album", FieldKey.ALBUM), Artist("Artist", FieldKey.ARTIST),
    AlbumArtist("Album Artist", FieldKey.ALBUM_ARTIST), Year("Year", FieldKey.YEAR, true),
    Disc("Disc number", FieldKey.DISC_NO, true), TrackNumber("Track number", FieldKey.TRACK, true),
    Comment("Comment", FieldKey.COMMENT), Genre("Genre", FieldKey.GENRE),
    Composer("Composer", FieldKey.COMPOSER), Lyricist("Lyricist", FieldKey.LYRICIST);
    fun value(track: Track): String = when (this) {
        Title -> track.title; Album -> track.album; Artist -> track.artist; AlbumArtist -> track.albumArtist
        Year -> track.year?.toString(); Disc -> track.discNumber?.toString()
        TrackNumber -> track.trackNumber?.toString(); Comment -> track.comment
        Genre -> track.genre; Composer -> track.composer; Lyricist -> track.lyricist
    }.orEmpty()
}

data class CoverImage(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)
sealed interface ArtworkChange {
    data object Keep : ArtworkChange
    data object Remove : ArtworkChange
    data class Replace(val image: CoverImage) : ArtworkChange
}
data class TagPatch(val fields: Map<EditField, String> = emptyMap(), val artwork: ArtworkChange = ArtworkChange.Keep) {
    fun validate() {
        require(fields.isNotEmpty() || artwork != ArtworkChange.Keep) { "Choose at least one change." }
        fields.forEach { (field, value) ->
            require(!field.numeric || value.isBlank() || (value.toIntOrNull() ?: 0) > 0) { "${field.label} must be a positive whole number or blank." }
            require(value.length <= 65536) { "${field.label} is too long." }
        }
    }
}

data class EditResult(val track: Track, val saved: Boolean, val message: String)
