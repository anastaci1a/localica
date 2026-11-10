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

/** Production activity, repositories and native input under the process-wide fatal Main policy. */
class MainThreadGuardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as LocalicaApplication
    private val compose = localicaEmptyComposeRule()
    private lateinit var tracks: List<Track>
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(android.os.Build.HARDWARE in setOf("goldfish", "ranchu")) {
                "The threading guard's persistent scale fixtures require a disposable emulator"
            }
            // Rule setup runs on the instrumentation worker, before ActivityScenario launches MainActivity.
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            reportMemory("before-fixture")
            val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(*LibraryDatabase.MIGRATIONS).build()
            try {
                tracks = runBlocking { RoomLibraryStore(database.libraryDao()).current().tracks }
                    .filter { it.id.startsWith("guard:") }
                if (tracks.size != 13_400) {
                    // Complete native runs also discover this class without the prototype cold-launch wrapper.
                    MainThreadGuardFixture.seed(context, instrumentation)
                    tracks = runBlocking { RoomLibraryStore(database.libraryDao()).current().tracks }
                        .filter { it.id.startsWith("guard:") }
                }
                check(tracks.size == 13_400)
            } finally { database.close() }
            reportMemory("after-fixture")
        }
        override fun after() { reportMemory("after-scenario") }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun galleryPlayerLastFmSettingsAndSearch() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
        waitForBrowserDiagnosing()
        // Every URI is new for this invocation. Invalidate before the first fling as well.
        ArtworkCache.invalidate()
        repeat(6) { compose.onNodeWithTag("browser-list").performTouchInput { swipeUp(durationMillis = 180) } }
        reportMemory("gallery-fling")
        instrumentation.runOnMainSync { app.queues.tap(tracks.take(20), tracks.first().id) }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("player-surface").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-surface").performTouchInput { swipeUp() }
        compose.onNodeWithTag("player-artwork").assertExists()
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Online features").performScrollTo().performClick()
        compose.onNodeWithText("Last.fm").performScrollTo().performClick()
        compose.onNodeWithText("Matches", useUnmergedTree = true).performScrollTo().performClick()
        reportMemory("lastfm-matches")
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        repeat(2) { device.pressBack(); compose.waitForIdle() }
        for (page in listOf("Interface", "Playback and queues", "Library and tags", "About and licenses")) {
            compose.onNodeWithText(page).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back from settings").performClick()
        }
        device.pressBack()
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Guard song 00001")
        compose.onNodeWithText("Guard song 00001").assertExists()
        reportMemory("search")
        }
    }

    @Test fun millionEntryQueueRestoresThroughTheProductionMainActor() {
        val name = "main-thread-guard-million.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val sql = database.openHelper.writableDatabase
            val encoded = tracks.map(TrackSnapshot::encode)
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO queues (id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,ordinal,lastActivatedAt,orderState) VALUES ('guard','Guard',0,'guard:500000',1234,0,'Off',0,0,'')")
                sql.execSQL("INSERT INTO queue_state (id,queueId) VALUES (1,'guard')")
                sql.compileStatement("INSERT INTO queue_entries (id,queueId,ordinal,track) VALUES (?,'guard',?,?)").use { statement ->
                    repeat(1_000_000) { index ->
                        statement.bindString(1, "guard:$index")
                        statement.bindLong(2, index.toLong())
                        statement.bindString(3, encoded[index % encoded.size])
                        statement.executeInsert()
                    }
                }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            reportMemory("million-persisted")
            // Do not construct a giant in-memory fixture before testing restore.
            lateinit var controller: QueueController
            val preferences = QueuePreferences(context).also { it.read() }
            instrumentation.runOnMainSync {
                controller = QueueController(database.queues(), preferences, app.library, scope)
            }
            runBlocking {
                withTimeout(180_000) { controller.state.first { !it.loading } }
            }
            assertNull(controller.state.value.problem)
            assertEquals(1_000_000, controller.state.value.library.queues.single().entries.size)
            assertEquals("guard:500000", controller.state.value.library.active?.currentEntryId)
            assertEquals(1234L, controller.state.value.library.active?.positionMs)
            reportMemory("million-restored")
        } finally {
            scope.cancel()
            database.close()
            context.deleteDatabase(name)
        }
    }

    // #817 DIAGNOSTIC ARM ONLY: sample what Main and the workers do while the wait runs.
    private fun waitForBrowserDiagnosing() {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Looper.getMainLooper().thread
        Thread {
            var tick = 0
            while (!done.get()) {
                Thread.sleep(2_000); tick++
                val mainTop = main.stackTrace.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Log.i("Guard817", "t=${tick * 2}s main state=${main.state} $mainTop")
                if (tick % 5 == 0) {
                    Thread.getAllStackTraces().forEach { (thread, stack) ->
                        if (thread === main) return@forEach
                        val local = stack.filter { it.className.contains("localica") }.take(10)
                        if (local.isNotEmpty()) Log.i("Guard817", "t=${tick * 2}s thread=${thread.name} state=${thread.state} " +
                            stack.take(4).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" } + " || " +
                            local.joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val started = android.os.SystemClock.uptimeMillis()
        try {
            var lastDump = 0L
            compose.waitUntil(60_000) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastDump > 4_000) {
                    lastDump = now
                    val nodes = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                    val seen = mutableListOf<String>()
                    fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                        val cfg = node.config
                        val props = androidx.compose.ui.semantics.SemanticsProperties
                        val tag = if (props.TestTag in cfg) cfg[props.TestTag] else null
                        val text = if (props.Text in cfg) cfg[props.Text].joinToString("|") { it.text } else null
                        val desc = if (props.ContentDescription in cfg) cfg[props.ContentDescription].joinToString("|") else null
                        if (tag != null || text != null || desc != null) seen += "${tag ?: ""}[${(text ?: desc ?: "").take(30)}]"
                        node.children.forEach(::walk)
                    }
                    nodes.forEach(::walk)
                    Log.i("Guard817", "t=${(now - started) / 1000}s nodes=${seen.size} " + seen.take(60).joinToString(" "))
                }
                compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty()
            }
            Log.i("Guard817", "browser-list present after ${android.os.SystemClock.uptimeMillis() - started} ms")
        } finally { done.set(true) }
    }

    private fun reportMemory(point: String) {
        val runtime = Runtime.getRuntime()
        Log.i("MainThreadGuard", "$point tracks=13400 queueScale=1000000 heapUsed=${runtime.totalMemory() - runtime.freeMemory()} heapMax=${runtime.maxMemory()} nativeHeap=${Debug.getNativeHeapAllocatedSize()} gc=${Debug.getRuntimeStats()}")
    }
}
