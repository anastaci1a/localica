package io.github.anastaci1a.localica.library

import android.content.Context

interface LibraryQueryPreferences {
    fun read(view: SongListView = SongListView.Songs): LibraryQuery
    fun write(query: LibraryQuery, view: SongListView = SongListView.Songs)
}

class AndroidLibraryQueryPreferences(context: Context) : LibraryQueryPreferences {
    private val preferences = context.getSharedPreferences("library-query", Context.MODE_PRIVATE)
    private fun key(view: SongListView, name: String) = if (view == SongListView.Songs) name else "${view.name}.$name"
    override fun read(view: SongListView) = LibraryQuery(
        text = preferences.getString(key(view, "text"), "").orEmpty(),
        folderUri = preferences.getString(key(view, "folder"), null),
        sort = preferences.enumValue<TrackSort>(key(view, "sort"))
            ?: LibraryViews.defaults(view).sort,
        ascending = preferences.getBoolean(key(view, "ascending"), true),
    )
    override fun write(query: LibraryQuery, view: SongListView) {
        preferences.edit().putString(key(view, "text"), query.text).putString(key(view, "folder"), query.folderUri)
            .putString(key(view, "sort"), query.sort.name).putBoolean(key(view, "ascending"), query.ascending).apply()
    }
}
