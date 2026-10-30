package io.github.anastaci1a.localica.home

import android.content.Context
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.library.enumNameOrNull
import org.json.JSONArray
import org.json.JSONObject

object PinEdits {
    val supported = setOf(MediaKind.Song, MediaKind.Album, MediaKind.Playlist)
    fun toggle(pins: List<MediaRef>, ref: MediaRef): List<MediaRef> {
        require(ref.kind in supported)
        return if (ref in pins) pins - ref else pins + ref
    }
    fun move(pins: List<MediaRef>, ref: MediaRef, offset: Int): List<MediaRef> {
        val index = pins.indexOf(ref)
        if (index < 0) return pins
        return io.github.anastaci1a.localica.library.OccurrenceMoves.byOffset(pins, index, offset)
    }
}

class PinPreferences(context: Context) {
    private val store = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    fun read(): List<MediaRef> {
        val array = JSONArray(store.getString("items", "[]"))
        return (0 until array.length()).mapNotNull { index -> array.getJSONObject(index).run {
            enumNameOrNull<MediaKind>(optString("kind"))?.takeIf { it in PinEdits.supported }
                ?.let { MediaRef(it, getString("id")) }
        } }.distinct()
    }
    fun write(pins: List<MediaRef>) {
        require(pins.distinct() == pins && pins.all { it.kind in PinEdits.supported })
        store.edit().putString("items", JSONArray().apply { pins.forEach { ref ->
            put(JSONObject().put("kind", ref.kind.name).put("id", ref.id))
        } }.toString()).apply()
    }
}
