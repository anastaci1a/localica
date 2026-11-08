package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the pre-#661 API, so the same witness runs against the base revision. */
@RunWith(AndroidJUnit4::class)
class ReleaseSnapshotContractTest {
    @Test fun savedCollectionsRetainReleaseTypeAndExplicitCompilationFlag() {
        val song = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        for (compilation in listOf(true, false)) {
            val incoming = JSONObject(TrackSnapshot.encode(song))
                .put("releaseType", "album; live").put("compilation", compilation)
            val restored = TrackSnapshot.decode(incoming.toString())
            val roundTrip = JSONObject(TrackSnapshot.encode(restored))
            assertEquals("Saved metadata must retain the release type", "album; live", roundTrip.optString("releaseType"))
            assertTrue("Saved metadata must distinguish absent and false compilation tags", roundTrip.has("compilation"))
            assertEquals(compilation, roundTrip.getBoolean("compilation"))
        }
    }
}
