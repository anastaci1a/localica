package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.RoundedCorner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.scanner.ScanPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

/** Transport pilot only. Captures the real activity; never supplies replacement UI or edge masks. */
class NativePreviewCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun exportRealPlayerAndQueueThroughExistingDeviceReports() {
        val arguments = InstrumentationRegistry.getArguments()
        val enabled = arguments.getString("localicaPreviewCapture") == "trusted-hosted-pilot-v1"
        if (!enabled) {
            Log.i(TAG, "SCOPE capture disabled: no trusted hosted pilot opt-in")
            assumeTrue("Capture disabled outside the explicitly opted-in hosted pilot", false)
            return
        }
        val run = requireNotNull(arguments.getString("localicaPreviewRun"))
        val attempt = requireNotNull(arguments.getString("localicaPreviewAttempt"))
        val sha = requireNotNull(arguments.getString("localicaPreviewSha"))
        check(run.matches(Regex("[1-9][0-9]*")) && attempt.matches(Regex("[1-9][0-9]*")) && sha.matches(Regex("[0-9a-f]{40}")))
        Log.i(TAG, "ATTRIBUTION repository=anastaci1a/localica run=$run attempt=$attempt testCommit=$sha verification=external-run-metadata-required")
        // This capture-only dispatch does not replace the ordinary full API26/36 test matrix.
        if (Build.VERSION.SDK_INT == 26) {
            Log.i(TAG, "SCOPE API${Build.VERSION.SDK_INT}: API36-only recording pilot not run")
            assumeTrue("Native preview transport pilot is API36-only; this is not capture verification", false)
            return
        }
        check(Build.VERSION.SDK_INT == 36) { "Enabled capture requires API36" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug") && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Capture requires a disposable debug emulator" }
        val app = context.applicationContext as LocalicaApplication
        val library = runBlocking { withTimeout(10_000) { app.library.snapshots.first() } }
        await { !app.playlists.state.value.loading && !app.queues.state.value.loading }
        check(library.folders.isEmpty() && library.tracks.isEmpty() &&
            app.queues.state.value.library.queues.isEmpty() && app.playlists.state.value.playlists.isEmpty()) {
            "Capture refused: requires empty app library, folders, queues and playlists"
        }
        val automation = instrumentation.uiAutomation
        val device = UiDevice.getInstance(instrumentation)
        val scanner = ScanPreferences(context)
        val savedScan = scanner.scanOnLaunch
        val interfaceStore = context.getSharedPreferences("interface", 0)
        val motionKeys = listOf("expand-on-play", "reduced-motion", "faster-motion")
        val savedMotion = motionKeys.associateWith { key -> interfaceStore.all[key] as Boolean? }
        val fixture = File(context.cacheDir, "preview-pilot-${UUID.randomUUID()}.mp3")
        val recordPath = "/data/local/tmp/localica-preview-${UUID.randomUUID()}.mp4"
        val executor = Executors.newCachedThreadPool()
        var playback: PlaybackConnection? = null
        var savedSettings: QueueSettings? = null
        var fixtureQueueId: String? = null
        var recording: Future<String>? = null
        val recordDescriptor = AtomicReference<ParcelFileDescriptor?>()
        var originalFailure: Throwable? = null
        val recorderPid = CompletableFuture<Int>()
        val recorderEnded = AtomicBoolean(false)
        fun shellBytes(command: String, limit: Int = 4096): ByteArray {
            val descriptor = automation.executeShellCommand(command)
            val task = executor.submit<ByteArray> {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream(); val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block); if (count < 0) break
                        check(output.size() + count <= limit) { "Shell output exceeds capture budget" }
                        output.write(block, 0, count)
                    }
                    output.toByteArray()
                }
            }
            try { return task.get(5, TimeUnit.SECONDS) }
            finally { descriptor.close(); task.cancel(true) }
        }
        try {
            scanner.scanOnLaunch = false
            check(interfaceStore.edit().putBoolean("expand-on-play", true).putBoolean("reduced-motion", false)
                .putBoolean("faster-motion", false).commit()) { "Capture motion preferences could not be saved" }
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> fixture.outputStream().use(input::copyTo) }
            val tracks = listOf("Morning tone", "Quiet interval", "Evening tone").mapIndexed { index, title ->
                Track(Uri.fromFile(fixture).toString(), title, artist = "Localica synthetic demo", album = "Preview fixtures",
                    fileName = fixture.name, relativePath = fixture.name, id = "preview-pilot:$index")
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                instrumentation.runOnMainSync { playback = PlaybackConnection(context) }
                val connection = requireNotNull(playback)
                val queues = connection.queues
                await { connection.state.value.connected && !queues.state.value.loading }
                savedSettings = queues.state.value.settings
                queues.settings(QueueSettings()) // New queue, no maximum/removal and no shuffle.
                await { queues.state.value.settings == QueueSettings() }
                val previousIds = queues.state.value.library.queues.map { it.id }.toSet()
                queues.tap(tracks, 0, sourceName = "Synthetic preview")
                await { queues.state.value.library.active?.let { it.id !in previousIds && it.entries.map { e -> e.track.id } == tracks.map { t -> t.id } } == true }
                fixtureQueueId = queues.state.value.library.active!!.id
                await { connection.state.value.trackId == tracks.first().id && connection.state.value.isPlaying }
                instrumentation.runOnMainSync { connection.toggle(); connection.seekTo(500) }
                await { !connection.state.value.playWhenReady && !connection.state.value.isPlaying && !connection.state.value.isBuffering }
                compose.waitForIdle()
                if (compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("player-surface").performClick()
                }
                compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
                val openPoint = compose.onNodeWithContentDescription("Open queue").fetchSemanticsNode().boundsInRoot.center
                logGeometry(scenario, "player", "player-surface")
                val screenshot = requireNotNull(automation.takeScreenshot()) { "Native screenshot unavailable" }
                val png = ByteArrayOutputStream().also { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                Log.i(TAG, "SCREEN player width=${screenshot.width} height=${screenshot.height}")
                screenshot.recycle()
                export("player.png", png, 1024 * 1024)
                compose.onNodeWithContentDescription("Open queue").performClick()
                compose.onNodeWithContentDescription("Close queue").assertIsDisplayed()
                val closePoint = compose.onNodeWithContentDescription("Close queue").fetchSemanticsNode().boundsInRoot.center
                logGeometry(scenario, "queue", "queue-surface")
                compose.onNodeWithContentDescription("Close queue").performClick()
                compose.onNodeWithContentDescription("Open queue").assertIsDisplayed()
                var rootX = 0; var rootY = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.View>(android.R.id.content)
                    val xy = IntArray(2); content.getLocationOnScreen(xy); rootX = xy[0]; rootY = xy[1]
                }
                // Do not ask Compose to fast-forward to idle while recording. Advance its test clock
                // at wall-clock cadence, with real native taps and screenrecord's untouched display.
                compose.mainClock.autoAdvance = false
                val pipes = automation.executeShellCommandRw("sh")
                recordDescriptor.set(pipes[0])
                recording = executor.submit<String> {
                    ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { reader ->
                        val header = requireNotNull(reader.readLine()) { "Recorder PID handshake missing" }
                        check(header.matches(Regex("PID:[1-9][0-9]*"))) { "Invalid recorder PID handshake" }
                        recorderPid.complete(header.removePrefix("PID:").toInt())
                        val output = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            check(output.length + line.length < 4096) { "Recorder diagnostic output exceeded budget" }
                            output.appendLine(line)
                        }
                        recorderEnded.set(true)
                        output.toString()
                    }
                }
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                    // exec preserves the PID emitted by our shell; never kill by process name.
                    input.write(("echo PID:${'$'}${'$'}\nexec screenrecord --time-limit 8 --bit-rate 1000000 $recordPath 2>&1\n").toByteArray())
                }
                recorderPid.get(5, TimeUnit.SECONDS)
                val started = SystemClock.elapsedRealtime()
                val taps = listOf(1000L to openPoint, 3000L to closePoint, 5000L to openPoint, 7000L to closePoint)
                var nextTap = 0
                var advanced = 0L
                while (SystemClock.elapsedRealtime() - started < 8500L) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (nextTap < taps.size && elapsed >= taps[nextTap].first) {
                        val point = taps[nextTap++].second
                        check(device.click((point.x + rootX).toInt(), (point.y + rootY).toInt())) { "Native navigation tap rejected" }
                    }
                    val target = (elapsed / 16) * 16
                    if (target > advanced) { compose.mainClock.advanceTimeBy(target - advanced, ignoreFrameDuration = true); advanced = target }
                    SystemClock.sleep(16)
                }
                val recorderOutput = requireNotNull(recording).get(15, TimeUnit.SECONDS)
                check(recorderOutput.isBlank()) { "screenrecord reported an error: ${recorderOutput.take(500)}" }
                compose.mainClock.autoAdvance = true
                val mp4 = shellBytes("cat $recordPath", 2 * 1024 * 1024)
                check(mp4.size > 32 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") { "Missing or invalid native MP4" }
                export("navigation.mp4", mp4, 2 * 1024 * 1024)
                Log.i(TAG, "COMPLETE pilot exports emitted; retrieval checksums, media decoding and visual review still required")
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            cleanup { compose.mainClock.autoAdvance = true }
            // Kill only this invocation's exact PID after verifying its unique file argument.
            cleanup {
                val current = recording
                if (current != null && !recorderEnded.get()) {
                    val pid = recorderPid.get(5, TimeUnit.SECONDS)
                    val command = shellBytes("cat /proc/$pid/cmdline").toString(Charsets.UTF_8)
                    if (command.split('\u0000').contains(recordPath)) shellBytes("kill -INT $pid")
                }
            }
            var recorderFinished = recording == null
            cleanup { recording?.get(10, TimeUnit.SECONDS); recorderFinished = true }
            cleanup { recordDescriptor.getAndSet(null)?.close() }
            playback?.let { connection ->
                cleanup {
                    // Identify our queue by its unique file even if setup failed before assigning its ID.
                    val ownIds = connection.queues.state.value.library.queues.filter { queue ->
                        queue.id == fixtureQueueId || queue.entries.any { it.track.uri == Uri.fromFile(fixture).toString() }
                    }.map { it.id }.toSet()
                    if (ownIds.isNotEmpty()) {
                        connection.queues.removeQueues(ownIds)
                        await { connection.queues.state.value.library.queues.none { it.id in ownIds } }
                    }
                }
                cleanup { savedSettings?.let { saved -> connection.queues.settings(saved); await { connection.queues.state.value.settings == saved } } }
                cleanup { instrumentation.runOnMainSync { connection.close() } }
            }
            if (recorderFinished) cleanup {
                shellBytes("rm -f $recordPath")
            }
            cleanup { executor.shutdownNow() }
            cleanup { check(fixture.delete() || !fixture.exists()) { "Synthetic fixture cleanup failed" } }
            cleanup { scanner.scanOnLaunch = savedScan }
            cleanup {
                val edit = interfaceStore.edit()
                savedMotion.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putBoolean(key, value) }
                check(edit.commit()) { "Interface preference restoration failed" }
            }
            cleanupFailure?.let { failure -> if (originalFailure == null) throw failure else originalFailure!!.addSuppressed(failure) }
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun logGeometry(scenario: ActivityScenario<MainActivity>, stage: String, tag: String) {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            val xy = IntArray(2); content.getLocationOnScreen(xy)
            val insets = decor.rootWindowInsets
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            Log.i(TAG, "GEOMETRY stage=$stage api=${Build.VERSION.SDK_INT} package=${activity.packageName} version=${info.versionName} " +
                "density=${activity.resources.displayMetrics.density} rotation=${decor.display.rotation} decor=${decor.width}x${decor.height} " +
                "rootOrigin=${xy[0]},${xy[1]} surfaceBoundsInRoot=$bounds clock=wall-paced-16ms sourceAttribution=external-run-manifest-required")
            check(insets != null) { "Missing window insets; cannot establish runtime corner geometry" }
            for (position in listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT)) {
                val corner = insets.getRoundedCorner(position)
                Log.i(TAG, "CORNER stage=$stage position=$position radiusPx=${corner?.radius ?: 0} center=${corner?.center} reported=${corner != null}")
                assertTrue("Pilot requires genuinely square reported window geometry", corner == null || corner.radius == 0)
            }
            Log.i(TAG, "INSETS stage=$stage systemBars=${insets.getInsets(android.view.WindowInsets.Type.systemBars())} cutout=${insets.displayCutout}")
        }
    }

    private fun export(name: String, bytes: ByteArray, limit: Int) {
        check(bytes.isNotEmpty() && bytes.size <= limit) { "$name outside transport budget: ${bytes.size}/$limit" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(3000)
        Log.i(TAG, "BEGIN name=$name bytes=${bytes.size} sha256=$hash chunks=${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            Log.i(TAG, "DATA name=$name part=${index + 1}/${chunks.size} $chunk")
            SystemClock.sleep(20) // Let the existing per-test logcat collector drain; never enlarge its buffer.
        }
        Log.i(TAG, "END name=$name bytes=${bytes.size} sha256=$hash chunks=${chunks.size}")
    }

    private companion object { const val TAG = "NativePreviewPilot" }
}
