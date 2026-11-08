package io.github.anastaci1a.localica

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtistDataStorageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun migrationFromSevenPreservesMetadataLocationsAndArtworkThenStoresReleaseTags() = runBlocking {
        val name = "artist-release-migration.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val schema = instrumentation.context.assets.open("io.github.anastaci1a.localica.library.LibraryDatabase/7.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO folders VALUES ('root', 'Music')")
            old.execSQL("INSERT INTO tracks (id,title,artist,album,durationMs,fileName,addedAt,modifiedAt) VALUES ('song','Song','Artist','Album',1000,'song.mp3',42,99)")
            old.execSQL("INSERT INTO folder_tracks VALUES ('root','song','uri','song.mp3',0)")
            old.execSQL("INSERT INTO track_artwork VALUES ('song',99,123)")
            old.version = 7
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(*LibraryDatabase.MIGRATIONS).build()
        try {
            val store = RoomLibraryStore(db.libraryDao())
            val migrated = store.current().tracks.single()
            assertEquals(42L, migrated.addedAt); assertEquals(99L, migrated.modifiedAt)
            assertEquals("uri", migrated.uri); assertEquals(1, migrated.locations.size)
            assertNull(migrated.releaseType); assertNull(migrated.compilation)
            assertEquals(123, db.libraryDao().observeArtworkColors().first().single().color)
            store.updateMetadata(migrated.copy(releaseType = "album; live", compilation = true, addedAt = 100))
            val saved = store.current().tracks.single()
            assertEquals("album; live", saved.releaseType); assertEquals(true, saved.compilation); assertEquals(42L, saved.addedAt)
            val restored = TrackSnapshot.decode(TrackSnapshot.encode(saved.copy(playCount = 123)))
            assertEquals(saved.copy(playCount = 0), restored)
            store.replaceRoot(ScanResult(FolderSelection("root", "Music"), listOf(saved.copy(releaseType = "single", compilation = false)), 0))
            assertEquals("single", store.current().tracks.single().releaseType)
            assertEquals(false, store.current().tracks.single().compilation)
            assertEquals(42L, store.current().tracks.single().addedAt)
            val legacy = JSONObject(TrackSnapshot.encode(saved)).apply { remove("releaseType"); remove("compilation") }
            assertNull(TrackSnapshot.decode(legacy.toString()).releaseType)
            assertNull(TrackSnapshot.decode(legacy.toString()).compilation)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun boundedHistoryCountsQualifiedEventsOnceAndExcludesOldAndFuturePlays() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, StatisticsDatabase::class.java).build()
        try {
            val dao = db.statistics()
            for ((id, time) in listOf("old" to 9L, "start" to 10L, "end" to 20L, "future" to 21L))
                dao.record(QualifiedPlay(id, "song", null, time))
            dao.record(QualifiedPlay("start", "song", null, 10))
            dao.record(QualifiedPlay("other", "other", "playlist", 15))
            val source = LocalPlayCountSource(dao)
            assertEquals(mapOf("song" to 2L, "other" to 1L), source.counts(ListeningWindow(10, 20)).first())
            assertEquals(mapOf("song" to 3L, "other" to 1L), source.counts(ListeningWindow(Long.MIN_VALUE, 20)).first())
            assertTrue(source.counts(ListeningWindow(30, 40)).first().isEmpty())
            assertEquals(mapOf("song" to 4L, "other" to 1L), dao.tracks().first().associate { it.id to it.count })
        } finally { db.close() }
    }

    @Test fun importReadsReleaseTypeAndCompilationAcrossSupportedTagContainers() = runBlocking {
        ContainerTags.configure()
        for (extension in listOf("mp3", "m4a", "flac", "ogg")) {
            val file = File(context.cacheDir, "artist-release-tags.$extension")
            try {
                instrumentation.context.assets.open("tagged.$extension").use { input -> file.outputStream().use(input::copyTo) }
                val audio = AudioFileIO.read(file)
                audio.tag.setField(FieldKey.MUSICBRAINZ_RELEASE_TYPE, "album; live")
                audio.tag.setField(FieldKey.IS_COMPILATION, "1")
                audio.commit()
                val tags = AndroidDocuments(context).metadata(Uri.fromFile(file).toString(), file.name)
                assertEquals(extension, "album; live", tags.releaseType)
                assertEquals(extension, true, tags.compilation)
            } finally { file.delete() }
        }
    }
}
