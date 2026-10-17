package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.OrderCommit
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShuffleModePlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offAfterShuffledBackUsesOrderedTransportAndNaturalArrivalWithoutJumping() = fixture { f ->
        val anchor = f.queue.journey!!.current!!
        f.queues.shuffle()
        f.check { !f.queues.state.value.settings.shuffle && !f.player.shuffleModeEnabled }
        f.committed()
        f.main {
            assertEquals(anchor.token, f.player.currentMediaItem!!.queueVisit)
            assertEquals(1000L, f.player.currentPosition)
            assertEquals(Player.REPEAT_MODE_ALL, f.player.repeatMode)
            assertFalse(f.player.playWhenReady)
            assertEquals(f.queue.journey!!.history, f.queues.state.value.library.active!!.journey!!.history)
            assertEquals(f.ids[2], f.player.getMediaItemAt(f.player.previousMediaItemIndex).mediaId)
            assertEquals(f.ids[4], f.player.getMediaItemAt(f.player.nextMediaItemIndex).mediaId)
        }
        f.queues.next(); f.at(4)
        f.queues.previous(alwaysSong = true); f.at(3)
        f.main { assertEquals(anchor.token, f.player.currentMediaItem!!.queueVisit) }
        f.queues.previous(alwaysSong = true); f.at(2)
        f.queues.next(); f.at(3)
        val expected = f.queues.state.value.library.active!!.journey!!.next!!
        f.main { f.player.seekTo(f.player.duration - 150); f.player.play() }
        f.check { f.player.currentMediaItem?.queueVisit == expected.token }
        f.main { f.player.pause() }
        f.at(4)
        assertEquals(expected, f.queues.state.value.library.active!!.journey!!.current)
        f.queues.shuffle()
        f.check { f.queues.state.value.settings.shuffle && f.player.shuffleModeEnabled }
        f.committed()
        assertEquals(listOf(expected), f.queues.state.value.library.active!!.journey!!.history)
        f.queues.shuffle()
        f.check { !f.queues.state.value.settings.shuffle && !f.player.shuffleModeEnabled }
        f.committed()
        assertEquals(f.ids[3], f.queues.state.value.library.active!!.journey!!.previous!!.entryId)
        assertEquals(f.ids[5], f.queues.state.value.library.active!!.journey!!.next!!.entryId)
        f.queues.seek(2345)
        f.check { f.queues.state.value.library.active?.positionMs == 2345L }
        f.committed()
        val saved = f.queues.state.value.library
        f.restart()
        f.at(4)
        assertEquals(saved, f.queues.state.value.library)
        f.main {
            assertFalse(f.player.shuffleModeEnabled)
            assertFalse(f.player.playWhenReady)
            assertEquals(2345L, f.player.currentPosition)
            assertEquals(saved.active!!.journey!!.current!!.token, f.player.currentMediaItem!!.queueVisit)
        }
    }

    @Test fun offKeepsSimpleAndAdvancedRepeatPoliciesAndCurrentPosition() = fixture { f ->
        val choices = listOf(
            QueueSettings(shuffle = true),
            QueueSettings(shuffle = true, repeat = QueueRepeat.One),
            QueueSettings(shuffle = true, repeat = QueueRepeat.All),
            QueueSettings(shuffle = true, advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Stop)),
            QueueSettings(shuffle = true, advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused, queueEnd = QueueEnd.Repeat)),
            QueueSettings(shuffle = true, advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Repeat)),
        )
        for (choice in choices) {
            val normalized = choice.copy(repeat = choice.advancedRepeat?.repeat ?: choice.repeat)
            f.queues.settings(choice)
            f.check { f.queues.state.value.settings == normalized }
            f.committed()
            val current = f.queues.state.value.library.active!!.journey!!.current
            f.queues.shuffle()
            f.check { f.queues.state.value.settings == normalized.copy(shuffle = false) && !f.player.shuffleModeEnabled }
            f.committed()
            f.main {
                assertEquals(current!!.token, f.player.currentMediaItem!!.queueVisit)
                assertEquals(1000L, f.player.currentPosition)
                assertFalse(f.player.playWhenReady)
                assertEquals(f.ids[2], f.queues.state.value.library.active!!.journey!!.previous!!.entryId)
                assertEquals(f.ids[4], f.queues.state.value.library.active!!.journey!!.next!!.entryId)
                val nativeRepeat = if (normalized.advancedRepeat?.pausesEachSong == true) Player.REPEAT_MODE_OFF else when (normalized.repeat) {
                    QueueRepeat.Off -> Player.REPEAT_MODE_OFF
                    QueueRepeat.All -> Player.REPEAT_MODE_ALL
                    QueueRepeat.One -> Player.REPEAT_MODE_ONE
                }
                assertEquals(nativeRepeat, f.player.repeatMode)
            }
        }
    }

    @Test fun buttonPublishesNativeModeAndRenderedStateBeforeOneCombinedSaveThenRetriesFailure() = fixture { f ->
        val requestedAt = AtomicLong()
        val returnedAt = AtomicLong()
        val stateAt = AtomicLong()
        val nativeAt = AtomicLong()
        val drawnAt = AtomicLong()
        val gate = f.dao.arm()
        val before = f.dao.committed!!
        f.main {
            f.player.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    if (!enabled) nativeAt.compareAndSet(0, SystemClock.elapsedRealtime())
                }
            })
            f.scope.launch { f.queues.state.collect { if (!it.settings.shuffle) stateAt.compareAndSet(0, SystemClock.elapsedRealtime()) } }
        }
        compose.setContent {
            val state by f.queues.state.collectAsState()
            MaterialTheme {
                Box(Modifier.size(320.dp, 64.dp).drawWithContent {
                    drawContent()
                    if (!state.settings.shuffle) drawnAt.compareAndSet(0, SystemClock.elapsedRealtime())
                }) {
                    PlayerActionStrip(PlayerActionSettings(slots = mapOf(ActionSlot(0, 0) to PlayerAction.Shuffle), centered = false),
                        { "Shuffle: " + if (state.settings.shuffle) "on" else "off" }, {
                            requestedAt.set(SystemClock.elapsedRealtime())
                            f.queues.shuffle()
                            returnedAt.set(SystemClock.elapsedRealtime())
                            // In the same Main turn, before actor execution, make its cursor checkpoint dirty.
                            f.player.seekTo(2345)
                        }, {}, shuffle = state.settings.shuffle)
                }
            }
        }
        compose.onNodeWithTag("player-slot-0:0").assertIsSelected().performTouchInput { click() }
        val pending = runBlocking { withTimeout(10_000) { gate.entered.await() } }
        assertFalse("No preceding cursor-only save may delay the requested mode", pending.after.active!!.journey!!.modes.shuffle)
        assertEquals(2345L, pending.after.active!!.positionMs)
        assertEquals(before, pending.before)
        compose.onNodeWithTag("player-slot-0:0").assertIsNotSelected()
        compose.waitUntil(10_000) { drawnAt.get() > 0 }
        f.main {
            assertFalse(f.player.shuffleModeEnabled)
            assertEquals(2345L, f.player.currentPosition)
            assertEquals(f.queue.journey!!.current!!.token, f.player.currentMediaItem!!.queueVisit)
            assertFalse(f.player.playWhenReady)
        }
        assertEquals(before, runBlocking { f.db.queues().read() })
        assertEquals(before, f.dao.committed)
        assertTrue(stateAt.get() >= requestedAt.get())
        assertTrue(nativeAt.get() >= requestedAt.get())
        Log.i("QueueShuffle", "phase=held-combined-save acceptanceMs=${returnedAt.get() - requestedAt.get()} stateMs=${stateAt.get() - requestedAt.get()} nativeModeMs=${nativeAt.get() - requestedAt.get()} drawCommandMs=${drawnAt.get() - requestedAt.get()} commit=pending injectedGate=true")
        // Failure leaves the durable baseline intact; a subsequent capture retries the published state.
        gate.release.complete(true)
        f.check { f.dao.failed.get() }
        val retryStarted = CompletableDeferred<Unit>()
        f.main { f.queues.settings { retryStarted.complete(Unit); it }; f.player.seekTo(3456) }
        // Native-event capture may commit the retry before this explicit command is dequeued.
        // DAO equality alone is not acknowledgement that the UI error-clear command has run.
        runBlocking { withTimeout(10_000) { retryStarted.await() } }
        f.check { f.queues.state.value.library.active?.positionMs == 3456L && f.queues.state.value.problem == null }
        f.committed()
        val saved = runBlocking { f.db.queues().read() }
        assertFalse(saved.active!!.journey!!.modes.shuffle)
        assertEquals(3456L, saved.active!!.positionMs)
        assertEquals(f.queue.journey!!.current, saved.active!!.journey!!.current)
        assertTrue(f.dao.retryUsedUnchangedBaseline.get())
        assertNull(f.queues.state.value.problem)
        Log.i("QueueShuffle", "phase=retry-complete commandToCommitMs=${f.dao.committedAt - requestedAt.get()} injectedGate=true")
    }

    @Test fun arrivalWhileCombinedSaveWaitsWinsBeforeOppositeToggle() = fixture { f ->
        val gate = f.dao.arm()
        f.queues.shuffle()
        val first = runBlocking { withTimeout(10_000) { gate.entered.await() } }
        assertFalse(first.after.active!!.journey!!.modes.shuffle)
        val expected = first.after.active!!.journey!!.next!!
        // Decoder advancement continues while the actor awaits disk. The next toggle must capture it.
        f.main { f.player.seekTo(f.player.duration - 150); f.player.play() }
        f.check { f.player.currentMediaItem?.queueVisit == expected.token }
        f.main { f.player.pause(); f.queues.shuffle() }
        f.main { assertFalse(f.queues.state.value.settings.shuffle); assertFalse(f.player.shuffleModeEnabled) }
        gate.release.complete(false)
        f.check { f.queues.state.value.settings.shuffle && f.player.shuffleModeEnabled }
        f.committed()
        f.main {
            assertEquals(expected.token, f.player.currentMediaItem!!.queueVisit)
            assertEquals(expected, f.queues.state.value.library.active!!.journey!!.current)
            assertEquals(listOf(expected), f.queues.state.value.library.active!!.journey!!.history)
            assertFalse(f.player.playWhenReady)
            assertEquals(Player.REPEAT_MODE_ALL, f.player.repeatMode)
        }
    }

    @Test fun rejectedQueuedArrivalPreservesPositionAndDoesNotDiscardLaterValidArrival() = fixture(probed = true) { f ->
        val probe = f.probe!!
        lateinit var stale: MediaItem
        f.main { stale = f.player.getMediaItemAt(f.player.currentMediaItemIndex + 1) }
        f.queues.shuffle()
        f.check { !f.queues.state.value.settings.shuffle }
        f.committed()
        f.queues.seek(3456)
        f.check { f.queues.state.value.library.active?.positionMs == 3456L }
        f.committed()
        fun fence() {
            val reached = CompletableDeferred<Unit>()
            f.queues.settings { reached.complete(Unit); it }
            runBlocking { withTimeout(10_000) { reached.await() } }
        }
        f.main {
            // Model a detached/unavailable native snapshot: only the queued arrivals can update the cursor.
            probe.hideCurrent = true
            probe.emit(stale)
        }
        fence()
        assertEquals(3456L, f.queues.state.value.library.active!!.positionMs)
        val valid = f.queues.state.value.library.active!!.journey!!.next!!
        f.main {
            val item = (0 until f.player.mediaItemCount).map(f.player::getMediaItemAt).single { it.queueVisit == valid.token }
            probe.emit(stale, item, stale)
        }
        fence()
        assertEquals(valid, f.queues.state.value.library.active!!.journey!!.current)
        assertEquals(0L, f.queues.state.value.library.active!!.positionMs)
        f.main { probe.hideCurrent = false }
        f.committed()
    }

    @Test fun pausedDuplicateOrderReceiptWaitsForSaveBetweenOppositeShuffleToggles() = fixture { f ->
        val anchor = f.queue.journey!!.current!!
        assertEquals(1, f.queue.entries.map { it.track.id }.distinct().size)
        val combined = f.dao.arm()
        f.main { f.queues.shuffle(); f.player.seekTo(2345) }
        val modeAttempt = runBlocking { withTimeout(10_000) { combined.entered.await() } }
        assertFalse(modeAttempt.after.active!!.journey!!.modes.shuffle)
        assertEquals(2345L, modeAttempt.after.active!!.positionMs)

        // A seeded identity permutation still changes this queue by pinning its exact occurrence.
        // Reusing it after the pin is a deterministic effective no-op, including the SavedOrder.
        val tail = f.ids.filterNot { it == anchor.entryId }
        val seed = (0..100_000).first { tail.shuffled(Random(it)) == tail }
        val expectedOrder = listOf(anchor.entryId) + tail
        val result = CompletableDeferred<OrderObservation>()
        f.main {
            f.queues.randomizeOrder(f.queue.id, Random(seed)) { result.complete(f.observe(it)) }
            f.queues.shuffle()
        }
        assertFalse(result.isCompleted)
        val orderGate = f.dao.armOrder()
        combined.release.complete(false)
        val pending = runBlocking { withTimeout(10_000) { orderGate.entered.await() } }
        assertFalse(result.isCompleted)
        assertEquals(expectedOrder, pending.after.active!!.entries.map { it.id })
        assertEquals(anchor, pending.after.active!!.journey!!.current)
        assertEquals(pending.before, runBlocking { f.db.queues().read() })
        orderGate.release.complete(false)
        val observed = runBlocking { withTimeout(10_000) { result.await() } }
        val receipt = requireNotNull(observed.receipt)
        assertTrue(receipt.changed)
        assertSame(observed.queue.entries, receipt.revision)
        assertEquals(receipt.order, observed.queue.order)
        assertEquals(pending.after.active!!.order, receipt.order)
        assertEquals(anchor.entryId, receipt.currentEntryId)
        assertEquals(expectedOrder, observed.queue.entries.map { it.id })
        assertEquals(anchor, observed.queue.journey!!.current)
        assertEquals(anchor.token, observed.visit)
        assertEquals(2345L, observed.position)
        assertFalse(observed.playWhenReady)
        assertFalse(observed.shuffle)
        assertEquals(observed.queue.journey!!.window().map { it.token }, observed.timeline)
        assertEquals((-64..64).map { expectedOrder[Math.floorMod(it, expectedOrder.size)] }, observed.timelineEntries)
        assertEquals(tail.first(), observed.nextEntry)
        f.check { f.queues.state.value.settings.shuffle && f.player.shuffleModeEnabled }
        f.committed()
        val saved = runBlocking { f.db.queues().read() }.active!!
        assertEquals(expectedOrder, saved.entries.map { it.id })
        assertEquals(receipt.order, saved.order)
        f.main {
            assertEquals(anchor.token, f.player.currentMediaItem!!.queueVisit)
            assertEquals(2345L, f.player.currentPosition)
            assertFalse(f.player.playWhenReady)
        }

        val repeated = CompletableDeferred<OrderObservation>()
        f.queues.randomizeOrder(f.queue.id, Random(seed)) { repeated.complete(f.observe(it)) }
        val noop = runBlocking { withTimeout(10_000) { repeated.await() } }
        val noopReceipt = requireNotNull(noop.receipt)
        assertFalse(noopReceipt.changed)
        assertSame(receipt.revision, noopReceipt.revision)
        assertSame(noop.queue.entries, noopReceipt.revision)
        assertEquals(receipt.order, noopReceipt.order)
        assertEquals(anchor.entryId, noopReceipt.currentEntryId)
    }

    @Test fun queuedOrderCapturesNativeArrivalRejectsStaleVisitAndRetriesFailedReceipt() = fixture(probed = true) { f ->
        val probe = f.probe!!
        lateinit var stale: MediaItem
        f.main { stale = f.player.getMediaItemAt(f.player.currentMediaItemIndex + 1) }
        val combined = f.dao.arm()
        f.queues.shuffle()
        val modeAttempt = runBlocking { withTimeout(10_000) { combined.entered.await() } }
        val arrived = modeAttempt.after.active!!.journey!!.next!!
        val result = CompletableDeferred<OrderObservation>()
        f.main {
            // Queue the order command first: its capture must consume transitions received while disk waits.
            f.queues.randomizeOrder(f.queue.id, Random(37)) { result.complete(f.observe(it)) }
            val index = (0 until f.player.mediaItemCount).single { f.player.getMediaItemAt(it).queueVisit == arrived.token }
            f.player.seekTo(index, 0)
            probe.hideCurrent = true
            probe.emit(f.player.getMediaItemAt(index), stale)
        }
        val orderGate = f.dao.armOrder()
        combined.release.complete(false)
        val pending = runBlocking { withTimeout(10_000) { orderGate.entered.await() } }
        assertFalse(result.isCompleted)
        // Hide the native snapshot so a stale callback cannot be silently corrected by snapshot capture.
        assertEquals(arrived, pending.before.active!!.journey!!.current)
        assertEquals(arrived, pending.after.active!!.journey!!.current)
        assertEquals(0L, pending.before.active!!.positionMs)
        assertEquals(arrived.entryId, pending.after.active!!.entries.first().id)
        assertEquals(pending.before, runBlocking { f.db.queues().read() })
        f.main { probe.hideCurrent = false }
        orderGate.release.complete(true)
        val failed = runBlocking { withTimeout(10_000) { result.await() } }
        assertNull(failed.receipt)
        assertSame(pending.before.active!!.entries, failed.queue.entries)
        assertEquals(arrived, failed.queue.journey!!.current)
        assertEquals(pending.before, runBlocking { f.db.queues().read() })

        val retryGate = f.dao.armOrder()
        val retried = CompletableDeferred<OrderObservation>()
        f.queues.randomizeOrder(f.queue.id, Random(37)) { retried.complete(f.observe(it)) }
        val retry = runBlocking { withTimeout(10_000) { retryGate.entered.await() } }
        assertFalse(retried.isCompleted)
        assertEquals(pending.before, retry.before)
        assertEquals(pending.after.active!!.entries, retry.after.active!!.entries)
        retryGate.release.complete(false)
        val success = runBlocking { withTimeout(10_000) { retried.await() } }
        val receipt = requireNotNull(success.receipt)
        assertTrue(receipt.changed)
        assertSame(success.queue.entries, receipt.revision)
        assertEquals(receipt.order, success.queue.order)
        assertEquals(arrived.entryId, receipt.currentEntryId)
        assertEquals(arrived.token, success.visit)
        assertFalse(success.playWhenReady)
        assertEquals(success.queue.journey!!.window().map { it.token }, success.timeline)
        val saved = runBlocking { f.db.queues().read() }.active!!
        assertEquals(success.queue.entries, saved.entries)
        assertEquals(receipt.order, saved.order)
        assertTrue(f.dao.retryUsedUnchangedBaseline.get())
    }

    private data class OrderObservation(val receipt: OrderCommit?, val queue: ListeningQueue,
        val visit: String?, val position: Long, val playWhenReady: Boolean, val shuffle: Boolean,
        val timeline: List<String?>, val timelineEntries: List<String>, val nextEntry: String?)

    private fun fixture(probed: Boolean = false, body: (Fixture) -> Unit) {
        val f = Fixture(probed)
        try { f.start(); body(f) } finally { f.close() }
    }
    /** Test-only callback source; actual native tests above attach the ExoPlayer directly. */
    private class ArrivalProbe(player: Player) : ForwardingPlayer(player) {
        private val listeners = mutableListOf<Player.Listener>()
        var hideCurrent = false
        override fun addListener(listener: Player.Listener) { listeners += listener }
        override fun removeListener(listener: Player.Listener) { listeners -= listener }
        override fun getCurrentMediaItem(): MediaItem? = if (hideCurrent) null else super.getCurrentMediaItem()
        fun emit(vararg items: MediaItem) {
            items.forEach { item -> listeners.toList().forEach { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) } }
        }
    }
    private class Fixture(private val probed: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val file = File(context.cacheDir, "shuffle-mode-tone.mp3")
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val dao = ShuffleGateDao(db.queues())
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var attached: Player
        var probe: ArrivalProbe? = null
        lateinit var queues: QueueController
        lateinit var queue: ListeningQueue
        lateinit var ids: List<String>
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun check(condition: () -> Boolean) = await(timeoutMs = 10_000) {
            var ready = false
            main { player.playerError?.let { throw AssertionError("Native playback failed", it) }; ready = condition() }
            ready
        }
        fun committed() = await(timeoutMs = 10_000) { dao.committed == queues.state.value.library }
        fun at(index: Int) {
            check { player.currentMediaItem?.mediaId == ids[index] && queues.state.value.library.active?.currentEntryId == ids[index] && player.playbackState == Player.STATE_READY }
            committed()
        }
        fun start() {
            preferences.write(QueueSettings(shuffle = true, repeat = QueueRepeat.All))
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
            val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
            queue = QueueEdits.create(List(7) { song }, 0, "Shuffle history")
            ids = queue.entries.map { it.id }
            var journey = QueueJourney.start(ids, ids[0], JourneyModes(shuffle = true, loop = true))
            for (index in listOf(3, 1, 5)) journey = journey.select(ids[index], ids)
            journey = journey.arrive(journey.history[1].token, ids)
            queue = queue.copy(currentEntryId = ids[3], positionMs = 1000, journey = journey)
            runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            main {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                attached = if (probed) ArrivalProbe(player).also { probe = it } else player
                queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(attached)
            }
            at(3)
        }
        fun observe(receipt: OrderCommit?) = OrderObservation(receipt, queues.state.value.library.active!!,
            player.currentMediaItem?.queueVisit, player.currentPosition, player.playWhenReady, player.shuffleModeEnabled,
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit },
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            player.nextMediaItemIndex.takeIf { it >= 0 }?.let { player.getMediaItemAt(it).mediaId })
        fun restart() {
            main {
                queues.detach(attached)
                player.release()
                scope.cancel()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                attached = player
                queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(attached)
            }
        }
        fun close() {
            dao.gate?.release?.complete(false)
            main {
                if (::queues.isInitialized && ::attached.isInitialized) queues.detach(attached)
                if (::player.isInitialized) player.release()
                scope.cancel()
            }
            db.close(); file.delete(); preferences.write(original)
        }
    }
}

private class ShuffleGateDao(private val delegate: QueueDao) : QueueDao() {
    data class Attempt(val before: QueueLibrary, val after: QueueLibrary)
    class Gate(val accepts: (Attempt) -> Boolean) {
        val entered = CompletableDeferred<Attempt>()
        val release = CompletableDeferred<Boolean>()
        val claimed = AtomicBoolean()
    }
    @Volatile var gate: Gate? = null
    @Volatile var committed: QueueLibrary? = null
    @Volatile var committedAt = 0L
    private var failedBaseline: QueueLibrary? = null
    val failed = AtomicBoolean()
    val retryUsedUnchangedBaseline = AtomicBoolean()
    fun arm(accepts: (Attempt) -> Boolean = { true }) = Gate(accepts).also { gate = it }
    fun armOrder() = arm { it.before.active?.entries != it.after.active?.entries || it.before.active?.order != it.after.active?.order }
    override suspend fun read() = delegate.read().also { committed = it }
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        gate?.takeIf { it.accepts(Attempt(before, after)) && it.claimed.compareAndSet(false, true) }?.let {
            it.entered.complete(Attempt(before, after))
            if (it.release.await()) {
                failedBaseline = before
                failed.set(true)
                throw IllegalStateException("Injected combined save failure")
            }
        }
        failedBaseline?.let {
            check(it == before) { "Failed save advanced the durable baseline" }
            retryUsedUnchangedBaseline.set(true)
            failedBaseline = null
        }
        delegate.save(before, after)
        committedAt = SystemClock.elapsedRealtime()
        committed = after
    }
    override suspend fun readQueues(): List<StoredQueue> = error("Use read")
    override suspend fun readActive(): ActiveQueueRecord? = error("Use read")
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
