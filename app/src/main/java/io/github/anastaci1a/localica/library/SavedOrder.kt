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

/** A single database column commits the baseline and applied method with the physical order. */
internal object SavedOrderCodec {
    fun encode(value: SavedOrder): String = org.json.JSONObject().put("manual", org.json.JSONArray(value.manual))
        .put("sort", value.sort.name).put("ascending", value.ascending).toString()
    fun decode(value: String, ids: List<String>): SavedOrder = if (value.isBlank()) SavedOrder(ids) else {
        val json = org.json.JSONObject(value)
        val manual = json.getJSONArray("manual")
        SavedOrder((0 until manual.length()).map { manual.getString(it) },
            TrackSort.entries.firstOrNull { it.name == json.optString("sort") } ?: TrackSort.Manual,
            json.optBoolean("ascending", true)).reconcile(ids)
    }
}
