package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test

class CollectionArtworkPreferencesTest {
    @Test fun observedSortSnapshotsMatchStyleReadsAcrossOverrideResetAndRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("browse-style", 0)
        val legacy = context.getSharedPreferences("library-query", 0)
        store.edit().clear().commit(); legacy.edit().clear().commit()
        val preferences = BrowsePreferences(context)
        val album = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "album:with:separators"))
        val another = album.copy(collection = MediaRef(MediaKind.Album, "another"))
        var observed = preferences.sortSnapshot()
        val stop = preferences.observeSorts { observed = it }
        fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun matches(route: BrowseRoute) {
            assertEquals(preferences.read(route).query(), observed.query(route))
            assertEquals(BrowsePreferences(context).sortSnapshot(), observed)
        }
        try {
            onMain { preferences.write(album, BrowseStyle(sort = TrackSort.Year, ascending = false), false) }
            matches(album); matches(another)
            assertEquals(TrackSort.Year, observed.collectionQuery(another.collection!!).sort)
            onMain { preferences.write(album, BrowseStyle(sort = TrackSort.Title), true) }
            matches(album); matches(another)
            assertEquals(TrackSort.Title, observed.collectionQuery(album.collection!!).sort)
            assertEquals(TrackSort.Year, observed.collectionQuery(another.collection!!).sort)
            val overridden = observed
            onMain { preferences.write(another, BrowseStyle(sort = TrackSort.Duration), false) }
            matches(album); matches(another)
            assertEquals(TrackSort.Title, observed.collectionQuery(album.collection!!).sort)
            onMain { preferences.reset(album) }
            matches(album)
            assertEquals(TrackSort.Duration, observed.collectionQuery(album.collection!!).sort)
            assertEquals("Older captured values remain unchanged", TrackSort.Title, overridden.collectionQuery(album.collection!!).sort)
            val beforeLayout = observed
            onMain { preferences.write(another, BrowseStyle(sort = TrackSort.Duration, listSize = ListSize.Large), false) }
            assertEquals("Layout changes retain completed result identities", beforeLayout, observed)
        } finally { stop(); store.edit().clear().commit(); legacy.edit().clear().commit() }
    }
}
