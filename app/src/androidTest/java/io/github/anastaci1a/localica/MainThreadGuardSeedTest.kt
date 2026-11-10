package io.github.anastaci1a.localica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File

/** Separate worker invocation prepares the persistent corpus before the cold process launch. */
class MainThreadGuardSeedTest {
    @Test fun seedDisposableEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(android.os.Build.HARDWARE in setOf("goldfish", "ranchu"))
        MainThreadGuardFixture.seed(instrumentation.targetContext, instrumentation)
    }
}

internal object MainThreadGuardFixture {
    fun seed(context: Context, instrumentation: android.app.Instrumentation) {
        val directory = File(context.filesDir, "main-thread-guard-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val tone = instrumentation.context.assets.open("queue-tone.mp3").use { it.readBytes() }
        val files = List(128) { index ->
            File(directory, "$index.mp3").also { file ->
                file.writeBytes(tone)
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.rgb(index * 37 % 256, index * 71 % 256, index * 13 % 256))
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    TagCodec.edit(file, TagPatch(artwork = ArtworkChange.Replace(CoverImage(bytes, "image/jpeg", 256, 256))))
                } finally { bitmap.recycle() }
            }
        }
        val tracks = List(13_400) { index ->
            Track(Uri.fromFile(files[index % files.size]).toString(), "Guard song %05d".format(index),
                artist = "Guard artist ${index / 48}", album = "Guard album ${index / 12}", durationMs = 20_000,
                fileName = "$index.mp3", relativePath = "$index.mp3", id = "guard:$index")
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
            .addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try { runBlocking {
            val store = RoomLibraryStore(database.libraryDao())
            store.replaceRoot(ScanResult(FolderSelection(Uri.fromFile(directory).toString(), "Threading guard"), tracks, 0))
            check(store.current().tracks.count { it.id.startsWith("guard:") } == 13_400)
        } }
        finally { database.close() }
        runBlocking {
            FileLastFmStore(File(context.filesDir, "lastfm-snapshot.json")).write(SavedLastFm(
                username = "threading-guard", keepSyncingInBackground = false,
                snapshot = LastFmSnapshot("threading-guard", 100_000,
                    tracks.map { LastFmTrack(it.artist.orEmpty(), it.title, 10, it.album) })))
        }
        check(context.getSharedPreferences("interface", Context.MODE_PRIVATE).edit().putString("tabs", "Albums,Songs").commit())
        check(context.getSharedPreferences("scanner", Context.MODE_PRIVATE).edit().putBoolean("on-launch", false)
            .putBoolean("full-on-launch", false).commit())
        BrowsePreferences(context).write(BrowseRoute(MainTab.Albums),
            BrowseStyle(presentation = Presentation.Compact, itemsPerRow = 5), false)
        // Drain the prior apply() on this worker before the host stops the seed process.
        check(context.getSharedPreferences("browse-style", Context.MODE_PRIVATE).edit()
            .putBoolean("main-thread-guard-seeded", true).commit())
    }

}
