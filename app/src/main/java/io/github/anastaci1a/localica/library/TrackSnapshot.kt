package io.github.anastaci1a.localica.library

import org.json.JSONArray
import org.json.JSONObject

/** Saved collections can outlive their library roots. */
internal object TrackSnapshot {
    fun encode(track: Track): String = JSONObject().apply {
        put("id", track.id); put("uri", track.uri); put("title", track.title); put("artist", track.artist)
        put("album", track.album); put("albumArtist", track.albumArtist); put("duration", track.durationMs)
        put("file", track.fileName); put("path", track.relativePath); put("track", track.trackNumber)
        put("disc", track.discNumber); put("year", track.year); put("genre", track.genre); put("comment", track.comment)
        put("locations", JSONArray().apply { track.locations.forEach { place -> put(JSONObject().apply {
            put("root", place.folderUri); put("uri", place.uri); put("path", place.relativePath)
        }) } })
    }.toString()
    fun decode(text: String): Track = JSONObject(text).run {
        fun optional(key: String) = if (isNull(key)) null else getString(key)
        val places = optJSONArray("locations") ?: JSONArray()
        Track(id = getString("id"), uri = getString("uri"), title = getString("title"), artist = optional("artist"),
            album = optional("album"), albumArtist = optional("albumArtist"), durationMs = optional("duration")?.toLong(),
            fileName = getString("file"), relativePath = getString("path"), trackNumber = optional("track")?.toInt(),
            discNumber = optional("disc")?.toInt(), year = optional("year")?.toInt(), genre = optional("genre"),
            comment = optional("comment"), locations = (0 until places.length()).map { places.getJSONObject(it).run {
                TrackLocation(getString("root"), getString("uri"), getString("path")) } })
    }
}
