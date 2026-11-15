package io.github.anastaci1a.localica.library

/** Occurrence IDs, never track IDs: duplicates are independent positions. */
data class SavedOrder(val manual: List<String>, val sort: TrackSort = TrackSort.Manual, val ascending: Boolean = true) {
    fun reconcile(ids: List<String>): SavedOrder {
        val present = ids.toSet()
        val kept = manual.filter { it in present }
        // Links make inserting many new occurrences linear rather than repeatedly
        // searching/copying the complete manual order. IDs remain occurrence IDs.
        val next = HashMap<String, String?>(ids.size)
        kept.forEachIndexed { index, id -> next[id] = kept.getOrNull(index + 1) }
        var first = kept.firstOrNull()
        var previous: String? = null
        ids.forEach { id ->
            if (!next.containsKey(id)) {
                next[id] = if (previous == null) first else next[previous]
                if (previous == null) first = id else next[previous!!] = id
            }
            previous = id
        }
        return copy(manual = buildList(ids.size) {
            var id = first
            while (id != null) { add(id); id = next[id] }
        })
    }
    fun apply(rows: List<SongRow>, method: TrackSort, ascending: Boolean): List<String> =
        if (method == TrackSort.Manual) reconcile(rows.map { it.id }).manual
        else LibraryQueries.rows(rows, LibraryQuery(sort = method, ascending = ascending)).map { it.id }
}

internal object SavedOrderCodec {
    fun encode(value: SavedOrder): String = org.json.JSONObject().put("manual", org.json.JSONArray(value.manual))
        .put("sort", value.sort.name).put("ascending", value.ascending).toString()
    fun decode(value: String, ids: List<String>): SavedOrder = if (value.isBlank()) SavedOrder(ids) else try {
        var manual: List<String>? = null
        decode(value.reader()) { manual = it.toList() }.copy(manual = requireNotNull(manual)).reconcile(ids)
    } catch (error: Exception) { throw org.json.JSONException(error.message) }
    fun decode(input: java.io.Reader, manual: (Sequence<String>) -> Unit): SavedOrder = java.io.PushbackReader(input).use { source ->
        var first = source.read()
        while (first >= 0 && first.toChar().isWhitespace()) first = source.read()
        if (first < 0) return SavedOrder(emptyList())
        source.unread(first); android.util.JsonReader(source).apply { isLenient = true }.use { json ->
            var sort = TrackSort.Manual; var ascending = true; json.beginObject()
            while (json.hasNext()) when (json.nextName()) {
                "manual" -> { json.beginArray(); manual(sequence { while (json.hasNext()) yield(scalar(json) ?: "null") }); json.endArray() }
                "sort" -> sort = enumNameOrNull<TrackSort>(scalar(json)) ?: TrackSort.Manual
                "ascending" -> ascending = !"false".equals(scalar(json), true)
                else -> json.skipValue()
            }
            json.endObject(); SavedOrder(emptyList(), sort, ascending)
        }
    }
    private fun scalar(json: android.util.JsonReader): String? = when (json.peek()) {
        android.util.JsonToken.STRING -> json.nextString()
        android.util.JsonToken.NUMBER -> org.json.JSONTokener(json.nextString()).nextValue().toString()
        android.util.JsonToken.BOOLEAN -> json.nextBoolean().toString()
        else -> { json.skipValue(); null }
    }
}
