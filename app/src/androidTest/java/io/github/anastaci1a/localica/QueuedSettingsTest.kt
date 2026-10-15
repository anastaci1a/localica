package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongListView
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class QueuedSettingsTest {
    @Test fun independentUpdatesComposeBehindRestoreInEitherOrder() = verifyQueuedUpdates(restoring = true)
    @Test fun independentUpdatesComposeBehindSaveInEitherOrder() = verifyQueuedUpdates(restoring = false)

    private fun verifyQueuedUpdates(restoring: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        try {
            for (reverse in listOf(false, true)) {
                val initial = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Repeat), repeat = QueueRepeat.One)
                preferences.write(initial)
                val seed = QueueEdits.create(listOf(Track("missing", "Track", fileName = "missing.mp3", relativePath = "missing.mp3")), 0, "Original")
                val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
                db.queues().save(QueueLibrary(), QueueLibrary(listOf(seed), seed.id))
                val dao = SettingsGateDao(db.queues(), restoring)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                lateinit var queues: QueueController
                try {
                    instrumentation.runOnMainSync {
                        queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                    }
                    if (!restoring) {
                        withTimeout(10000) { queues.state.first { !it.loading } }
                        queues.rename(seed.id, "Blocked")
                    }
                    withTimeout(10000) { dao.entered.await() }
                    // Restore can load preferences newer than the UI's initial state as well.
                    val restoredSettings = if (restoring) initial.copy(maximum = 7, switching = QueueSwitch.Paused) else initial
                    if (restoring) preferences.write(restoredSettings)
                    // These are field edits, not settings snapshots captured while the actor is blocked.
                    val edits: List<(QueueSettings) -> QueueSettings> = listOf(
                        { it.copy(shuffle = true) },
                        { it.copy(advancedRepeat = null, repeat = QueueRepeat.All) },
                        { it.copy(preloadNext = false) },
                        { it.copy(previousBehavior = PreviousBehavior.PreviousSong) },
                        { it.copy(previousThresholdSeconds = 12) },
                        { it.copy(tapOverrides = it.tapOverrides + (SongListView.Songs to SongTap.Append)) },
                        { it.copy(tapOverrides = it.tapOverrides + (SongListView.Albums to SongTap.PlayNext)) },
                    )
                    (if (reverse) edits.reversed() else edits).forEach { queues.settings(it) }
                    val done = CompletableDeferred<Unit>()
                    queues.settings { done.complete(Unit); it }
                    dao.release.complete(Unit)
                    withTimeout(10000) { done.await() }
                    val expected = edits.fold(restoredSettings) { settings, edit -> edit(settings) }
                    assertEquals(expected, queues.state.value.settings)
                    assertEquals(expected, QueuePreferences(context).read())
                    assertNull(queues.state.value.problem)

                    // Advanced policy still normalizes simple repeat; explicit full replacement still resets all fields.
                    val policy = AdvancedRepeat(songEnd = SongEnd.Repeat)
                    queues.settings { it.copy(repeat = QueueRepeat.Off, advancedRepeat = policy) }
                    withTimeout(10000) { queues.state.first { it.settings.advancedRepeat == policy } }
                    assertEquals(QueueRepeat.One, queues.state.value.settings.repeat)
                    assertEquals(QueueRepeat.One, QueuePreferences(context).read().repeat)
                    queues.settings(QueueSettings())
                    withTimeout(10000) { queues.state.first { it.settings == QueueSettings() } }
                    assertEquals(QueueSettings(), QueuePreferences(context).read())
                } finally {
                    dao.release.complete(Unit)
                    instrumentation.runOnMainSync { scope.cancel() }
                    db.close()
                }
            }
        } finally { preferences.write(original) }
    }
}

private class SettingsGateDao(private val delegate: QueueDao, private val restoring: Boolean) : QueueDao() {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override suspend fun read(): QueueLibrary {
        if (restoring) { entered.complete(Unit); release.await() }
        return delegate.read()
    }
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        if (!restoring && after.active?.name == "Blocked" && before.active?.name != "Blocked") {
            entered.complete(Unit); release.await()
        }
        delegate.save(before, after)
    }
    override suspend fun readQueues() = delegate.readQueues()
    override suspend fun readActive() = delegate.readActive()
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Use save")
    override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("Use save")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Use save")
    override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
}
