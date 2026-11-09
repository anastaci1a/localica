package io.github.anastaci1a.localica

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.anastaci1a.localica.statistics.withListeningStatistics

import android.app.Application
import kotlinx.coroutines.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import androidx.room.Room
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.LibraryDatabase
import io.github.anastaci1a.localica.library.LibraryRepository
import io.github.anastaci1a.localica.library.PersistedFolderPermissions
import io.github.anastaci1a.localica.library.RoomLibraryStore
import io.github.anastaci1a.localica.library.TreeScanner

class LocalicaApplication : Application() {
    private companion object {
        val STARTUP_PREFERENCES = listOf("interface", "queue-settings", "scanner", "scan-progress", "browse-style",
            "library-query", "library-filters", "pins", "player-actions", "tag-jobs", "cover-images", "listening-identity")
    }
    override fun onCreate() {
        super.onCreate()
        io.github.anastaci1a.localica.diagnostics.Diagnostics.mirrorToLogcat =
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val startup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startup.launch {
            // The first read of a preferences file blocks its caller until the XML is loaded. Load the
            // files read while the first frames are built (activity, queue, scanner and browse state)
            // here so those Main reads find them in memory. Values and write paths are unchanged.
            for (name in STARTUP_PREFERENCES) runCatching { getSharedPreferences(name, MODE_PRIVATE).contains("") }
        }
        startup.launch { onlineMetadataConsent.load() }
        startup.launch { io.github.anastaci1a.localica.icons.IconPacks.load(this@LocalicaApplication) }
        startup.launch { startArtworkColors(this) }
        startup.launch {
            // The registry starts fail-closed before any service can create/open a source.
            // The writer publishes a useful Unknown state if inspection fails.
            runCatching { tagWriter.initialize() }
            runCatching { io.github.anastaci1a.localica.library.TagViewFiles.sweep(java.io.File(cacheDir, "tag-views")) }
                .onFailure { android.util.Log.w("TagViewFiles", "Could not sweep leftover metadata mirrors", it) }
        }
    }
    /** Colour index, visible-first requests and the bulk backfill (#525); all off Main. */
    private fun startArtworkColors(scope: CoroutineScope) {
        val dao = libraryDatabase.libraryDao()
        val backfill = io.github.anastaci1a.localica.library.ArtworkColorBackfill(
            io.github.anastaci1a.localica.library.RoomArtworkColorStore(dao),
            { uri -> io.github.anastaci1a.localica.library.ArtworkReader(this).averageColor(uri) },
            io.github.anastaci1a.localica.library.ArtworkCache.foregroundDecodes,
            kotlinx.coroutines.flow.combine(scanner.state, io.github.anastaci1a.localica.library.ArtworkColors.foreground) { scan, started ->
                scan.running || !started }.distinctUntilChanged(),
            log = { android.util.Log.i("ArtworkColors", it) })
        io.github.anastaci1a.localica.library.ArtworkColors.requests = backfill
        scope.launch { io.github.anastaci1a.localica.library.publishArtworkColors(dao.observeArtworkColors()) }
        scope.launch { dao.trackChanges().collect { backfill.request() } }
        scope.launch { backfill.run() }
    }
    private val statisticsDatabase by lazy { Room.databaseBuilder(this,
        io.github.anastaci1a.localica.statistics.StatisticsDatabase::class.java, "statistics.db").build() }
    val statistics by lazy {
        io.github.anastaci1a.localica.statistics.StatisticsRepository(statisticsDatabase.statistics(),
            identities = io.github.anastaci1a.localica.statistics.ListeningIdentityStore(
                getSharedPreferences("listening-identity", MODE_PRIVATE)))
    }
    val onlineMetadataConsent by lazy {
        io.github.anastaci1a.localica.network.OnlineMetadataConsent(java.io.File(filesDir, "online-metadata-consent"))
    }
    val artistPhotos by lazy {
        io.github.anastaci1a.localica.artistphoto.ArtistPhotos(onlineMetadataConsent,
            java.io.File(cacheDir, "artist-photos"), java.io.File(filesDir, "artist-photo-matches"))
    }
    val lastfm by lazy {
        io.github.anastaci1a.localica.lastfm.LastFmController(
            io.github.anastaci1a.localica.lastfm.FileLastFmStore(java.io.File(filesDir, "lastfm-snapshot.json")),
            io.github.anastaci1a.localica.lastfm.LastFmSync(io.github.anastaci1a.localica.lastfm.HttpLastFmApi(BuildConfig.LASTFM_API_KEY, onlineMetadataConsent)),
            library.snapshots, BuildConfig.LASTFM_API_KEY.isNotBlank(), onlineMetadataConsent,
            localStatistics = statistics.counts, background = io.github.anastaci1a.localica.lastfm.LastFmWork(this))
    }
    val effectiveStatistics by lazy { lastfm.applyTo(statistics.counts) }
    val playRankings by lazy { io.github.anastaci1a.localica.statistics.PlayRankingSource(
        io.github.anastaci1a.localica.statistics.LocalPlayCountSource(statisticsDatabase.statistics()), lastfm) }
    val listening by lazy {
        io.github.anastaci1a.localica.statistics.ListeningSession(android.os.SystemClock::elapsedRealtime,
            statistics.restoredIdentity(), statistics::checkpoint, statistics::record)
    }
    val scanner by lazy { io.github.anastaci1a.localica.scanner.ScanController(this, library) }
    val lyrics by lazy { io.github.anastaci1a.localica.lyrics.LyricsRepository(this) }
    val tagEditing by lazy { io.github.anastaci1a.localica.editing.TagEditingController(this, library, queues, playlists, tagWriter) }
    val playlists by lazy {
        PlaylistController(Room.databaseBuilder(this, PlaylistDatabase::class.java, "playlists.db").addMigrations(PlaylistDatabase.MIGRATION_1_2, PlaylistDatabase.MIGRATION_2_3, PlaylistDatabase.MIGRATION_3_4).build().playlists(),
            coverDirectory = java.io.File(filesDir, "playlist-covers"),
            sortSnapshot = ::liveSortSnapshot)
    }
    val nativeReaders = io.github.anastaci1a.localica.playback.NativeReaders(
        io.github.anastaci1a.localica.playback.RecoveryAdmission.Checking)
    val tagWriter by lazy {
        io.github.anastaci1a.localica.editing.RecoverableTagWriter(java.io.File(filesDir, "tag-recovery"),
            io.github.anastaci1a.localica.editing.AndroidEditableDocuments(this), nativeReaders)
    }
    val queues by lazy {
        QueueController(Room.databaseBuilder(this, QueueDatabase::class.java, "queues.db").addMigrations(QueueDatabase.MIGRATION_1_2, QueueDatabase.MIGRATION_2_3, QueueDatabase.MIGRATION_3_4, QueueDatabase.MIGRATION_4_5, QueueDatabase.MIGRATION_5_6, QueueDatabase.MIGRATION_6_7).build().queues(),
            QueuePreferences(this), library, readers = nativeReaders, sortSnapshot = ::liveSortSnapshot,
            diagnoseOrders = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            listening = listening)
    }
    private suspend fun liveSortSnapshot() =
        library.sortSnapshots.withListeningStatistics(effectiveStatistics).first()

    private val libraryDatabase by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(*LibraryDatabase.MIGRATIONS).build()
    }
    val library by lazy {
        LibraryRepository(RoomLibraryStore(libraryDatabase.libraryDao()), TreeScanner(AndroidDocuments(this)),
            PersistedFolderPermissions(contentResolver))
    }
}
