package io.github.anastaci1a.localica

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Five actual caller surfaces, not short/tall GlassMenu replicas. This records presentation
 * evidence ONLY: no optical oracle, band-absence assertion or production change. One original
 * Window PixelCopy per surface (TOTAL FIVE); no screenshot polling, redraw request or retry.
 * Compose idleness, native focus/draw and semantic placement are separate observations, not
 * atomic proof that a compositor presented a particular source revision or settled animation.
 * Synthetic catalog + in-memory real controllers; no player/service/audio or phone validation. */
class PopupFamilyPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var host: GlassDialogs
    private lateinit var output: File
    private val records = JSONArray()
    private var requests = 0
    private val labels = listOf("library-utility", "playlist-plus", "sort-view", "collection-actions", "song-information")
    private val tracks = (1..3).map { index ->
        Track("popup-family:$index", "Diagnostic song $index", artist = "Diagnostic artist", album = "Diagnostic album",
            fileName = "diagnostic-$index.mp3", relativePath = "Diagnostic album/diagnostic-$index.mp3",
            locations = listOf(TrackLocation("popup-family-root", "popup-family:$index", "Diagnostic album/diagnostic-$index.mp3")))
    }

    @Test fun fiveActualCallerSurfacesRetainOriginalNativeWindowsAndProvenance() {
        output = File(requireNotNull(context.getExternalFilesDir(null)), "popup-family-presentation/api${Build.VERSION.SDK_INT}-${SystemClock.uptimeMillis()}")
        assertTrue(output.mkdirs())
        writeManifest("starting")
        // Preserve preferences exactly, including absence of migration flags; no production data store is seeded.
        val preferences = listOf("interface", "browse-style", "library-query", "library-filters", "pins", "queue-settings")
            .associate { name -> context.getSharedPreferences(name, 0).let { it to it.all.toMap() } }
        val playlistDb = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        var mounted by mutableStateOf(true)
        var contentInstalled = false
        var primary: Throwable? = null
        try {
            preferences.keys.forEach { assertTrue(it.edit().clear().commit()) }
            InterfacePreferences(context).write(InterfaceSettings())
            val app = context.applicationContext as LocalicaApplication
            val editor = app.tagEditing.state.value
            assertTrue("Do not alter an existing editor/recovery/job presentation", editor.tracks.isEmpty() &&
                !editor.busy && !editor.jobsVisible && editor.recovery == null)
            lateinit var playlists: PlaylistController
            lateinit var queues: QueueController
            compose.runOnUiThread {
                playlists = PlaylistController(playlistDb.playlists(), scope)
                queues = QueueController(queueDb.queues(), QueuePreferences(context), app.library, scope)
            }
            runBlocking { withTimeout(5000) {
                playlists.state.first { !it.loading }; queues.state.first { !it.loading }
            } }
            val catalog = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("popup-family-root", "Diagnostic music")), tracks), loading = false)
            compose.setContent {
                if (mounted) LocalicaTheme {
                    val ordinaryHost = requireNotNull(LocalGlassDialogs.current)
                    SideEffect { host = ordinaryHost }
                    val playlistState by playlists.state.collectAsState()
                    val queueState by queues.state.collectAsState()
                    // Same provider chain as MainActivity, including real Edit tags menu availability.
                    TagEditorHost(app.tagEditing) {
                        LocalicaScreen(catalog, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                            queues = queueState, queueController = queues,
                            playlist = playlistState, playlistController = playlists)
                    }
                }
            }
            contentInstalled = true
            compose.onNodeWithContentDescription("Library").performClick()
            compose.onNode(hasTestTag("page-heading") and hasText("Library")).assertIsDisplayed()

            capture("library-utility", "Library root > utility More", hasText("Scan music"), retainedDialog = false) {
                compose.onNodeWithContentDescription("More", substring = false).performClick()
            }
            dismiss()
            capture("playlist-plus", "Library root > Add playlist", hasText("Create playlist"), retainedDialog = false) {
                compose.onNodeWithContentDescription("Add playlist").performClick()
            }
            dismiss()
            capture("sort-view", "Library root > Sort/View (Sort tab)", hasTestTag("browse-options-dialog"), retainedDialog = true) {
                compose.onNodeWithContentDescription("Sort", substring = false).performClick()
            }
            dismiss()

            compose.onNode(hasText("Diagnostic album") and hasClickAction()).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Play album").fetchSemanticsNodes().size == 1 }
            // Ordinary user-visible scroll, not changed padding/placement or a fabricated popup body.
            compose.onNodeWithTag("browser-list").performScrollToIndex(1)
            capture("collection-actions", "Library > Diagnostic album > first song Actions", hasText("Information"), retainedDialog = false) {
                compose.onNodeWithContentDescription("Actions for Diagnostic song 1").performClick()
            }
            compose.onNodeWithText("Edit tags", substring = false).assertExists()
            // The real menu callback closes its caller and launches the real SongInformation dialog.
            capture("song-information", "Previous collection action menu > Information", hasTestTag("song-information"), retainedDialog = true) {
                compose.onNodeWithText("Information", substring = false).performClick()
            }
            dismiss()
            assertEquals("One request for each declared surface; no replacements", labels.size, requests)
            assertEquals(labels.size, records.length())
            writeManifest("diagnostic-recorded-NOT-band-acceptance")
        } catch (failure: Throwable) {
            primary = failure
            runCatching { writeManifest("failed", failure.toString()) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            // Attempt every cleanup; never replace the first diagnostic failure with teardown noise.
            fun clean(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary!!.addSuppressed(failure)
            } }
            val bodyFailed = primary != null
            clean { compose.mainClock.autoAdvance = true }
            if (contentInstalled) clean { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
            clean { runBlocking { job.cancelAndJoin() } }
            clean { queueDb.close() }; clean { playlistDb.close() }
            preferences.forEach { (store, values) -> clean { restore(store, values) } }
            if (!bodyFailed && primary != null) {
                runCatching { writeManifest("cleanup-failed", primary.toString()) }.onFailure(primary!!::addSuppressed)
                throw primary!!
            }
        }
    }

    private data class Draw(val count: Int, val uptime: Long, val boundsInRoot: Rect, val focus: Boolean)
    private fun view(node: SemanticsNode) = (node.root as ViewRootForTest).view
    private fun surfaces() = compose.onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes()

    private fun capture(label: String, route: String, marker: SemanticsMatcher, retainedDialog: Boolean, open: () -> Unit) {
        assertEquals("Fixed route order", labels[requests], label)
        val preceding = surfaces().map(::view)
        val record = JSONObject().put("label", label).put("route", route).put("status", "opening")
            .put("activation", "ordinary production callback through semantics performClick, NOT physical contact")
        records.put(record)
        writeManifest("running")
        var observedView: View? = null
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        val draw = AtomicReference<Draw?>()
        try {
            // Pause only to attach a passive native draw witness to the newly created real window.
            // Frames below seek a public native root, never an image/color or favorable optical result.
            compose.mainClock.autoAdvance = false
            open()
            var initial: SemanticsNode? = null
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                initial = surfaces().singleOrNull { candidate -> preceding.none { it === view(candidate) } }
                initial != null
            }
            val surface = requireNotNull(initial)
            val root = view(surface).also { observedView = it }
            val window = compose.runOnUiThread {
                assertTrue("Actual production Dialog root", root.parent is DialogWindowProvider)
                (root.parent as DialogWindowProvider).window
            }
            compose.runOnUiThread {
                val tree = root.viewTreeObserver.also { observer = it }
                val watch = ViewTreeObserver.OnDrawListener {
                    val count = (draw.get()?.count ?: 0) + 1
                    draw.set(Draw(count, SystemClock.uptimeMillis(), surface.boundsInRoot, root.hasWindowFocus()))
                }.also { listener = it }
                tree.addOnDrawListener(watch)
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val routeNode = compose.onNode(marker).assertIsDisplayed()
            compose.awaitExpectedWindowFocus(routeNode)
            val markerNode = routeNode.fetchSemanticsNode()
            assertSame("Marker identifies this new native window, not its retiring caller", root, view(markerNode))
            record.put("routeMarkerSemantics", routeNode.printToString(maxDepth = 3))
                .put("routeMarkerBoundsInRoot", compose.runOnUiThread { rect(markerNode.boundsInRoot) })
                .put("routeMarkerViewIdentity", id(root))
            compose.waitUntil(5000) {
                val current = surfaces().singleOrNull()?.takeIf { view(it) === root }
                current != null && compose.runOnUiThread {
                    val last = draw.get()
                    root.isAttachedToWindow && root.isLaidOut && root.hasWindowFocus() && !root.isLayoutRequested &&
                        last != null && last.focus && last.boundsInRoot == current.boundsInRoot && !current.boundsInRoot.isEmpty
                }
            }
            assertEquals("The old menu must retire before the one settled-surface capture", 1, surfaces().size)
            val finalSurface = surfaces().single()
            assertSame(root, view(finalSurface))
            val before = facts(root, window, finalSurface, draw.get(), retainedDialog)
            record.put("before", before).put("status", "copy-requested")
            writeManifest("running")
            requests++
            val bitmap = copyOnce(window)
            try {
                val original = File(output, "$label-original-window.png")
                // Persist the complete original buffer BEFORE any pixel inspection; no crop replaces it.
                original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("image", original.name).put("bytes", original.length()).put("sha256", sha256(original))
                    .put("imageWidth", bitmap.width).put("imageHeight", bitmap.height)
                // Deliberately no optical thresholds, pixel scanning, image-based readiness or absence assertion.
            } finally { bitmap.recycle() }
            record.put("after", facts(root, window, surfaces().single { view(it) === root }, draw.get(), retainedDialog))
                .put("status", "original-retained")
            writeManifest("running")
        } catch (failure: Throwable) {
            record.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            compose.mainClock.autoAdvance = true
            val watch = listener
            val tree = observer
            if (watch != null) compose.runOnUiThread {
                if (tree?.isAlive == true) tree.removeOnDrawListener(watch)
                else observedView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(watch)
            }
        }
    }

    private fun dismiss() {
        pressSystemBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { surfaces().isEmpty() }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("browse-background"))
    }

    /** Main-thread owner observations before/after PixelCopy, explicitly NOT atomic with it. */
    private fun facts(root: View, window: Window, node: SemanticsNode, draw: Draw?, retained: Boolean) = compose.runOnUiThread {
        assertTrue("Record coordinate conversion only for an untransformed native root", root.matrix.isIdentity)
        val inWindow = IntArray(2).also(root::getLocationInWindow)
        val onScreen = IntArray(2).also(root::getLocationOnScreen)
        val bounds = node.boundsInRoot
        val screenBounds = bounds.translate(Offset(onScreen[0].toFloat(), onScreen[1].toFloat()))
        val windowBounds = bounds.translate(Offset(inWindow[0].toFloat(), inWindow[1].toFloat()))
        assertTrue("Measured surface is inside the captured native window", !windowBounds.isEmpty &&
            windowBounds.left >= 0f && windowBounds.top >= 0f &&
            windowBounds.right <= window.decorView.width && windowBounds.bottom <= window.decorView.height)
        val entry = if (retained) host.entries.single { it.mounted } else null
        val anchor = entry?.anchor
        JSONObject().put("observationUptime", SystemClock.uptimeMillis()).put("nonAtomicWithCopy", true)
            .put("viewIdentity", id(root)).put("windowIdentity", id(window)).put("tokenIdentity", id(root.windowToken))
            .put("attached", root.isAttachedToWindow).put("focused", root.hasWindowFocus())
            .put("layoutRequested", root.isLayoutRequested).put("viewSize", JSONArray(listOf(root.width, root.height)))
            .put("decorSize", JSONArray(listOf(window.decorView.width, window.decorView.height)))
            .put("viewOriginInWindow", JSONArray(inWindow.toList())).put("viewOriginOnScreen", JSONArray(onScreen.toList()))
            .put("density", root.resources.displayMetrics.density).put("fontScale", root.resources.configuration.fontScale)
            .put("surfaceBoundsInRoot", rect(bounds))
            .put("surfaceBoundsInWindow", rect(windowBounds))
            .put("surfaceBoundsOnScreen", rect(screenBounds))
            .put("nativeDraw", draw?.let { JSONObject().put("count", it.count).put("uptime", it.uptime)
                .put("focused", it.focus).put("boundsInRoot", rect(it.boundsInRoot)) } ?: "UNKNOWN")
            .put("hostEntries", host.entries.size).put("retainedDialog", retained)
            .put("placement", entry?.placement?.name ?: "UNKNOWN: caller-private direct menu")
            .put("expansion", anchor?.contact?.expansion?.value ?: "UNKNOWN: caller-private direct menu")
            .put("bodySettled", anchor?.contact?.state?.momentum?.settled ?: "UNKNOWN: caller-private direct menu")
            .put("shadowOwnerInModal", anchor?.shadowInModal ?: "UNKNOWN: caller-private direct menu")
            .put("triggerHidden", anchor?.hidden ?: "UNKNOWN: caller-private direct menu")
            .put("anchorBounds", anchor?.bounds?.let(::rect) ?: "UNKNOWN")
            .put("anchorSource", sourceFacts(anchor?.source, screenBounds))
            .put("ordinaryHostBackdropCandidate", sourceFacts(host.backdrop, screenBounds))
            .put("actualNativeSourceConsumption", "UNKNOWN: no per-consumer receipt from these callers")
            .put("homeFootprintAndWeight", "UNKNOWN: no direct-menu anchor or frame-correlated sampling receipt")
            .put("shadowRasterConsumption", "UNKNOWN: configured shadow is not a native consumption receipt")
    }

    private fun sourceFacts(source: FrostedBackdrop?, screen: Rect): Any {
        if (source == null) return "UNKNOWN"
        val projection = source.location.registrationToScreen()?.inverse()?.corners(screen)
        return JSONObject().put("identity", id(source)).put("parentIdentity", id(source.parent))
            .put("recorded", source.recorded).put("materialOnly", source.materialOnly).put("dark", source.dark)
            .put("size", JSONArray(listOf(source.size.width, source.size.height)))
            .put("origin", JSONArray(listOf(source.origin.x, source.origin.y)))
            .put("revision", source.revision).put("requestedReadback", source.readbackRequest)
            .put("publishedReadback", source.snapshotReadbackRequest)
            .put("snapshotIdentity", id(source.snapshot)).put("frostIdentity", id(source.fallback))
            .put("lensIdentity", id(source.lensSnapshot)).put("drawReceipt", source.snapshotDraws?.toString() ?: "UNKNOWN")
            .put("unrefractedSurfaceCornersInCandidateSource", projection?.let { corners ->
                JSONArray(corners.map { JSONArray(listOf(it.x, it.y)) })
            } ?: "UNKNOWN")
            .put("qualification", "candidate owner recording; projection is not an optical crop or proof of consumed pixels")
    }

    private fun copyOnce(window: Window): Bitmap {
        val image = compose.runOnUiThread {
            Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        val completed = CountDownLatch(1)
        val ownership = Any()
        var returned = false
        var abandoned = false
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(window, image, { code ->
                synchronized(ownership) { result = code; returned = true; if (abandoned) image.recycle() }
                completed.countDown()
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) { image.recycle(); throw failure }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            synchronized(ownership) { if (returned) image.recycle() else abandoned = true }
            throw AssertionError("Single original window copy timed out; no retry")
        }
        if (result != PixelCopy.SUCCESS) { image.recycle(); throw AssertionError("Single original window copy failed: $result; no retry") }
        return image
    }

    private fun writeManifest(status: String, failure: String? = null) {
        File(output, "manifest.json").writeText(JSONObject().put("status", status).put("failure", failure ?: JSONObject.NULL)
            .put("api", Build.VERSION.SDK_INT).put("declaredTotalCaptures", 5).put("requestedCopies", requests)
            .put("fixedOrder", JSONArray(labels)).put("records", records)
            .put("fixture", "Actual LocalicaScreen/TagEditorHost; synthetic three-song album; real in-memory Queue/Playlist controllers; default InterfaceSettings; no playback service attached")
            .put("configuredAppearance", InterfaceSettings().glass.toString())
            .put("readiness", "Default motion; clock paused only to install native draw witness on new production window, then ordinary Compose idle + focus/layout/draw. No forced redraw.")
            .put("limitations", "Presentation diagnostics, NOT band acceptance. No physical display/compositor identity, native sampled revision, direct-menu expansion/anchor/shadow receipt, or cross-family equal-crop claim. No image-driven readiness or optical thresholds. Original full window buffers retained; only ownership/route/readiness assertions.")
            .toString(2))
    }

    private fun rect(bounds: Rect) = JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
    private fun id(value: Any?): Any = value?.let(System::identityHashCode) ?: "UNKNOWN"
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun restore(store: SharedPreferences, values: Map<String, *>) {
        val edit = store.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            null -> edit.remove(key)
            else -> error("Unsupported preference type: ${value.javaClass.name}")
        } }
        assertTrue("Restore exact fixture preferences", edit.commit())
    }
}
