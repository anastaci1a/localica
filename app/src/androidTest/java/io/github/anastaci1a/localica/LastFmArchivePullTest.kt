package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in fixture builder: runs the application's own Last.fm sync for `-e lastfmUser <name>` so the
 * archive is persisted exactly as on a phone (`files/lastfm-snapshot.json`). Needs a build whose
 * `LASTFM_API_KEY` is real and a device with network. The archive is copied to the test output
 * directory for `adb pull`; it is private operator data and must never be committed or uploaded.
 */
class LastFmArchivePullTest {
    @Test fun pullArchiveThroughTheAppSync() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: pass -e lastfmPull true", arguments.getString("lastfmPull") == "true")
        val username = requireNotNull(arguments.getString("lastfmUser")?.takeIf { it.isNotBlank() }) { "Pass -e lastfmUser <name>" }
        assumeTrue("This build has no real Last.fm key", BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_API_KEY.any { it != '0' })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as LocalicaApplication
        runBlocking { app.onlineMetadataConsent.setAllowed(true) }
        val controller = app.lastfm
        await(30_000) { !controller.state.value.loading }
        controller.setActive(true)
        controller.keepSyncingInBackground(false)
        controller.sync(username, fullRescan = true)
        await(5_000) { controller.state.value.syncing || controller.state.value.problem != null }
        val deadline = SystemClock.elapsedRealtime() + 40 * 60_000L
        var lastLogged = ""
        while (controller.state.value.syncing && SystemClock.elapsedRealtime() < deadline) {
            val label = controller.state.value.progress?.label.orEmpty()
            if (label != lastLogged) { Log.i("LastFmPull", "progress $label"); lastLogged = label }
            SystemClock.sleep(1000)
        }
        val state = controller.state.value
        assertNull("Sync reported a problem: ${state.problem}", state.problem)
        val snapshot = requireNotNull(state.saved.snapshot) { "No archive after sync" }
        assertEquals(username.lowercase(), snapshot.username.lowercase())
        val scrobbles = snapshot.tracks.sumOf { it.plays }
        Log.i("LastFmPull", "archive songs=${snapshot.tracks.size} scrobbles=$scrobbles matched=${controller.matches.value.matched}")
        val source = File(context.filesDir, "lastfm-snapshot.json")
        val target = File(nativeEvidenceDirectory("lastfm-archive", context.getExternalFilesDir(null)), "lastfm-snapshot.json")
        source.copyTo(target, overwrite = true)
        runBlocking { app.onlineMetadataConsent.setAllowed(false) }
    }
}
