package io.github.anastaci1a.localica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPreferencesTest {
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
