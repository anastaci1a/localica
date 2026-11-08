package io.github.anastaci1a.localica

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LastFmMetadataStorageTest {
    @Test fun migrationFromEightRetainsReleaseTagsAndPersistsRecordingMbid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val name = "lastfm-mbid-migration.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val schema = instrumentation.context.assets.open("io.github.anastaci1a.localica.library.LibraryDatabase/8.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO folders VALUES ('root','Music')")
            old.execSQL("INSERT INTO tracks (id,title,fileName,addedAt,releaseType,compilation) VALUES ('song','Song','song.mp3',42,'album; live',1)")
            old.execSQL("INSERT INTO folder_tracks VALUES ('root','song','uri','song.mp3',0)")
            old.version = 8
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val store = RoomLibraryStore(db.libraryDao()); val track = store.current().tracks.single()
            assertEquals("album; live", track.releaseType); assertEquals(true, track.compilation); assertEquals(42L, track.addedAt)
            assertNull(track.musicBrainzId)
            store.updateMetadata(track.copy(musicBrainzId = "11111111-1111-1111-1111-111111111111"))
            val saved = store.current().tracks.single()
            assertEquals("11111111-1111-1111-1111-111111111111", saved.musicBrainzId)
            assertEquals(saved, TrackSnapshot.decode(TrackSnapshot.encode(saved)))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun recordingMbidImportsFromId3AndVorbisTags() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        ContainerTags.configure()
        for (extension in listOf("mp3", "flac")) {
            val file = File(context.cacheDir, "lastfm-mbid.$extension")
            try {
                instrumentation.context.assets.open("tagged.$extension").use { input -> file.outputStream().use(input::copyTo) }
                val audio = AudioFileIO.read(file)
                audio.tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, "11111111-1111-1111-1111-111111111111"); audio.commit()
                assertEquals(extension, "11111111-1111-1111-1111-111111111111",
                    AndroidDocuments(context).metadata(Uri.fromFile(file).toString(), file.name).musicBrainzId)
            } finally { file.delete() }
        }
    }
}
