package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ListeningIdentityStorageTest {
    @Test fun reopenedIdentityDeduplicatesQualificationAndRestartCreatesANewPlay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "listening-identity-reopen"
        context.deleteDatabase(name)
        context.deleteSharedPreferences(name)
        var database = Room.databaseBuilder(context, StatisticsDatabase::class.java, name).build()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var clock = 0L
        val source = ListeningSource("entry", "track", "playlist")
        fun repository() = StatisticsRepository(database.statistics(), scope,
            ListeningIdentityStore(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        fun session(repo: StatisticsRepository) = ListeningSession({ clock }, repo.restoredIdentity(), repo::checkpoint, repo::record)
        try {
            var repo = repository()
            val first = session(repo)
            first.update(source, "durable-queue-visit", 180_000, true)
            clock = 30_000
            first.detach()
            withTimeout(5000) { repo.counts.first { it.tracks["track"] == 1L } }
            val saved = repo.restoredIdentity()
            assertNotNull(saved)
            scope.cancel(); database.close()
            database = Room.databaseBuilder(context, StatisticsDatabase::class.java, name).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo = repository()
            assertEquals(saved, repo.restoredIdentity())
            val reopened = session(repo)
            clock = 130_000
            reopened.update(source, "durable-queue-visit", 180_000, true)
            clock = 160_000
            reopened.update(source, "durable-queue-visit", 180_000, true)
            // The next distinct event is a FIFO barrier for the replayed qualification write.
            reopened.update(source, "durable-queue-visit", 180_000, true, restart = true)
            clock = 190_000
            reopened.detach()
            withTimeout(5000) { repo.counts.first { (it.tracks["track"] ?: 0) >= 2L } }
            val counts = repo.counts.first()
            assertEquals(2L, counts.tracks["track"])
            assertEquals(2L, counts.playlists["playlist"])
            assertNotEquals(saved, repo.restoredIdentity())
        } finally {
            scope.cancel(); database.close()
            context.deleteDatabase(name)
            context.deleteSharedPreferences(name)
        }
    }
}
