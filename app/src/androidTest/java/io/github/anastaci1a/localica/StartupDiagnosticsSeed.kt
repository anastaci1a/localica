package io.github.anastaci1a.localica

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.editing.ArtworkChange
import io.github.anastaci1a.localica.editing.CoverImage
import io.github.anastaci1a.localica.editing.TagCodec
import io.github.anastaci1a.localica.editing.TagPatch
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File

/** Destructive fixture preparation only on an explicitly admitted disposable emulator. */
class StartupDiagnosticsSeed : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(arguments.getString("startupDiagnostics") == "disposable-emulator")
            check(targetContext.packageName == "io.github.anastaci1a.localica.debug")
            val columns = arguments.getString("columns", "5").toInt().also { require(it in 1..5) }
            val artwork = arguments.getString("artwork", "embedded").also { require(it in listOf("embedded", "none")) }
            val context = targetContext
            val directory = File(context.filesDir, "startup-diagnostics").apply { mkdirs() }
            val tone = this.context.assets.open("queue-tone.mp3").use { it.readBytes() }
            val albums = (13_313 + 11) / 12
            val files = List(albums) { index ->
                File(directory, "$artwork-$index.mp3").also { file ->
                    if (!file.exists()) {
                        file.writeBytes(tone)
                        if (artwork == "embedded") {
                            // Deterministic synthetic JPEGs, not a realistic personal artwork corpus.
                            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bitmap)
                                val paint = Paint()
                                for (y in 0 until 32) for (x in 0 until 32) {
                                    paint.color = Color.rgb((x * 31 + index * 17) % 256,
                                        (y * 47 + index * 29) % 256, (x * y * 7 + index * 43) % 256)
                                    canvas.drawRect(x * 32f, y * 32f, (x + 1) * 32f, (y + 1) * 32f, paint)
                                }
                                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                                TagCodec.edit(file, TagPatch(artwork = ArtworkChange.Replace(CoverImage(bytes, "image/jpeg", 1024, 1024))))
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            if (artwork == "embedded") runBlocking {
                check(ArtworkReader(context).read(Uri.fromFile(files.first()).toString(), 192) != null)
            }
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(*LibraryDatabase.MIGRATIONS).build()
            try {
                runBlocking {
                    val store = RoomLibraryStore(database.libraryDao())
                    store.replaceRoot(ScanResult(
                        FolderSelection(Uri.fromFile(directory).toString(), "Synthetic startup diagnostics"),
                        List(13_313) { index ->
                            val album = index / 12
                            val artist = album / 4
                            Track(Uri.fromFile(files[album]).toString(), "Diagnostic song %05d".format(index),
                                artist = "Diagnostic artist %04d".format(artist),
                                album = "Diagnostic album %04d".format(album),
                                albumArtist = "Diagnostic artist %04d".format(artist),
                                durationMs = 20_000, fileName = "$index.mp3", relativePath = "$album/$index.mp3",
                                id = "startup-diagnostics:$index", trackNumber = index % 12 + 1)
                        }, 0))
                    check(store.current().tracks.size == 13_313) { "Fixture row count changed" }
                }
            } finally { database.close() }
            context.getSharedPreferences("interface", Context.MODE_PRIVATE).edit()
                .putString("tabs", "Albums,Artists").commit()
            context.getSharedPreferences("scanner", Context.MODE_PRIVATE).edit()
                .putBoolean("on-launch", false).putBoolean("full-on-launch", false).commit()
            val preferences = BrowsePreferences(context)
            for (tab in listOf(MainTab.Albums, MainTab.Artists)) {
                preferences.write(BrowseRoute(tab), BrowseStyle(presentation = Presentation.Compact, itemsPerRow = columns), false)
            }
            // apply() writes above must finish before the host force-stops this process.
            context.getSharedPreferences("browse-style", Context.MODE_PRIVATE).edit().putBoolean("diagnostic-seeded", true).commit()
            result.putString("stream", "Seeded 13313 tracks, $albums albums; artwork=$artwork, columns=$columns; synthetic control only.\n")
            finish(0, result)
        } catch (failure: Throwable) {
            result.putString("stream", failure.stackTraceToString())
            finish(1, result)
        }
    }
}
