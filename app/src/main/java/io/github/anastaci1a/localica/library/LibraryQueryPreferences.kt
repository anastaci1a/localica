package io.github.anastaci1a.localica.library

import android.content.Context

interface LibraryQueryPreferences {
    fun read(): LibraryQuery
    fun write(query: LibraryQuery)
}

class AndroidLibraryQueryPreferences(context: Context) : LibraryQueryPreferences {
    private val preferences = context.getSharedPreferences("library-query", Context.MODE_PRIVATE)
    override fun read() = LibraryQuery(
        text = preferences.getString("text", "").orEmpty(),
        folderUri = preferences.getString("folder", null),
        sort = TrackSort.entries.firstOrNull { it.name == preferences.getString("sort", null) } ?: TrackSort.Title,
        ascending = preferences.getBoolean("ascending", true),
    )
    override fun write(query: LibraryQuery) {
        preferences.edit().putString("text", query.text).putString("folder", query.folderUri)
            .putString("sort", query.sort.name).putBoolean("ascending", query.ascending).apply()
    }
}
