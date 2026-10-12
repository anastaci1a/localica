package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPreferencesTest {
    @Test fun storedEnumNamesPreserveFallbackAndRejectUnknownValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("enum-reader-test", Context.MODE_PRIVATE)
        try {
            store.edit().clear().commit()
            assertEquals(TrackSort.Album, store.enumValue<TrackSort>("sort", "Album"))
            store.edit().putString("sort", "Year").commit()
            assertEquals(TrackSort.Year, store.enumValue<TrackSort>("sort", "Album"))
            store.edit().putString("sort", "UnknownFutureName").commit()
            assertEquals(null, store.enumValue<TrackSort>("sort", "Album"))
            store.edit().putString("sort", "year").commit()
            assertEquals(null, store.enumValue<TrackSort>("sort"))
        } finally { store.edit().clear().commit() }
    }

    @Test fun viewQueriesAndTapOverridesPersistIndependentlyAndResetToInheritance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AndroidLibraryQueryPreferences(context)
        val previous = SongListView.entries.associateWith { preferences.read(it) }
        val queuePreferences = QueuePreferences(context)
        val oldSettings = queuePreferences.read()
        try {
            val songs = LibraryQuery("song", sort = TrackSort.Year)
            val albums = LibraryQuery("album", sort = TrackSort.Album, ascending = false)
            preferences.write(songs)
            preferences.write(albums, SongListView.Albums)
            assertEquals(songs, AndroidLibraryQueryPreferences(context).read())
            assertEquals(albums, AndroidLibraryQueryPreferences(context).read(SongListView.Albums))
            preferences.write(LibraryViews.defaults(SongListView.Albums), SongListView.Albums)
            assertEquals(songs, preferences.read())
            val settings = QueueSettings(songTap = SongTap.Append, tapOverrides = mapOf(SongListView.Playlists to SongTap.NewQueue))
            queuePreferences.write(settings)
            assertEquals(settings, QueuePreferences(context).read())
            queuePreferences.write(settings.copy(tapOverrides = emptyMap()))
            assertEquals(SongTap.Append, QueuePreferences(context).read().tapFor(SongListView.Playlists))
        } finally {
            previous.forEach { (view, query) -> preferences.write(query, view) }
            queuePreferences.write(oldSettings)
        }
    }
    @Test fun querySurvivesPreferencesRecreationAndReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AndroidLibraryQueryPreferences(context)
        val previous = preferences.read()
        try {
            val chosen = LibraryQuery("Live jazz", "content://music/root", TrackSort.AlbumArtist, false)
            preferences.write(chosen)
            assertEquals(chosen, AndroidLibraryQueryPreferences(context).read())
            preferences.write(LibraryQuery())
            assertEquals(LibraryQuery(), AndroidLibraryQueryPreferences(context).read())
        } finally { preferences.write(previous) }
    }
}
