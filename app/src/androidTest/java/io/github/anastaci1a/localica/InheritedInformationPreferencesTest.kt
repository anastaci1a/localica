package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class InheritedInformationPreferencesTest {
    @Test fun defaultsCompatibilityAndFilteredSearchKeepTheirMeanings() = withCleanStyles { preferences ->
        val mixed = BrowseRoute(MainTab.Library)
        val search = BrowseRoute(MainTab.Library, search = true, globalSearch = true)
        assertEquals(ExtraInfo.Inherited, preferences.read(mixed).extraInfo)
        assertEquals(ExtraInfo.Inherited, preferences.read(search).extraInfo)
        assertEquals(ExtraInfo.Automatic, preferences.read(BrowseRoute(MainTab.Albums)).extraInfo)
        preferences.write(mixed, BrowseStyle(extraInfo = ExtraInfo.Automatic), false)
        assertEquals(ExtraInfo.Automatic, BrowsePreferences(context()).read(mixed).extraInfo)
        preferences.write(search, BrowseStyle(extraInfo = ExtraInfo.Inherited), false)
        assertEquals(ExtraInfo.Automatic, preferences.read(search.copy(filter = MediaKind.Album)).extraInfo)
        assertEquals(ExtraInfo.Inherited, preferences.read(search).extraInfo)
    }

    @Test fun inheritedSnapshotFollowsSharedCatalogSettingsAndOwnSort() = withCleanStyles { preferences ->
        val albums = BrowseRoute(MainTab.Albums)
        val filter = BrowseRoute(MainTab.Library, filter = MediaKind.Album)
        preferences.write(albums, BrowseStyle(sort = TrackSort.Year, extraInfo = ExtraInfo.Automatic), false)
        assertEquals(preferences.read(albums), preferences.read(filter))
        assertEquals(TrackSort.Year, preferences.informationStyles().getValue(MediaKind.Album).sort)
        preferences.write(filter, BrowseStyle(extraInfo = ExtraInfo.Count), false)
        assertEquals(ExtraInfo.Count, preferences.informationStyles().getValue(MediaKind.Album).extraInfo)
        assertEquals(ExtraInfo.Count, BrowsePreferences(context()).informationStyles().getValue(MediaKind.Album).extraInfo)
    }

    @Test fun observersRefreshAndLegacySongSortIsInherited() = withCleanStyles { preferences ->
        val legacy = context().getSharedPreferences("library-query", Context.MODE_PRIVATE)
        val previous = legacy.getString("sort", null)
        val seen = mutableListOf<Map<MediaKind, BrowseStyle>>()
        val stop = preferences.observeInformation { seen.add(it) }
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                legacy.edit().putString("sort", TrackSort.Year.name).commit()
            }
            assertEquals(TrackSort.Year, preferences.informationStyles().getValue(MediaKind.Song).sort)
            assertEquals(TrackSort.Year, seen.last().getValue(MediaKind.Song).sort)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                preferences.write(BrowseRoute(MainTab.Albums), BrowseStyle(extraInfo = ExtraInfo.Year), false)
            }
            assertEquals(ExtraInfo.Year, seen.last().getValue(MediaKind.Album).extraInfo)
        } finally {
            stop()
            legacy.edit().apply { if (previous == null) remove("sort") else putString("sort", previous) }.commit()
        }
    }

    @Test fun everyTypeUsesTheSameCatalogOrderAsArtworkPreferences() = withCleanStyles { preferences ->
        for (kind in MediaKind.entries) {
            val route = if (kind == MediaKind.Queue) BrowseRoute(MainTab.Queues)
                else BrowseRoute(MainTab.Library, filter = kind)
            preferences.write(route, BrowseStyle(sort = TrackSort.Year, ascending = false,
                extraInfo = ExtraInfo.Automatic), false)
            val information = preferences.informationStyles().getValue(kind)
            assertEquals(preferences.read(route).sort, information.sort)
            assertEquals(preferences.sortSnapshot().query(route).sort, information.sort)
            assertEquals(preferences.sortSnapshot().query(route).ascending, information.ascending)
        }
    }

    @Test fun informationOnlyChangesRefreshRowsWithoutInvalidatingArtworkSorts() = withCleanStyles { preferences ->
        val store = context().getSharedPreferences("browse-style", Context.MODE_PRIVATE)
        val before = preferences.sortSnapshot()
        val seen = mutableListOf<Map<MediaKind, BrowseStyle>>()
        var sortChanges = 0
        val stopInformation = preferences.observeInformation { seen.add(it) }
        val stopSorts = preferences.observeSorts { sortChanges++ }
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store.edit().putString("catalog.Album.info", ExtraInfo.Count.name).commit()
            }
            assertEquals(ExtraInfo.Count, seen.last().getValue(MediaKind.Album).extraInfo)
            assertEquals(before, preferences.sortSnapshot())
            assertEquals(0, sortChanges)
            stopInformation()
            val count = seen.size
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store.edit().putString("catalog.Album.info", ExtraInfo.Year.name).commit()
            }
            assertEquals(count, seen.size)
        } finally {
            stopInformation()
            stopSorts()
        }
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun withCleanStyles(block: (BrowsePreferences) -> Unit) {
        val store = context().getSharedPreferences("browse-style", Context.MODE_PRIVATE)
        val before = store.all
        try {
            store.edit().clear().commit()
            block(BrowsePreferences(context()))
        } finally {
            val editor = store.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
}
