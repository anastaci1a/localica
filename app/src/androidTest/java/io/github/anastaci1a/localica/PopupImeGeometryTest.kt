package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import io.github.anastaci1a.localica.queue.QueueEdits
import io.github.anastaci1a.localica.queue.QueueLibrary
import io.github.anastaci1a.localica.queue.QueueUiState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule

/** Diagnostic native callback + submitted-draw trace. Callbacks may affect timing; pair with optical evidence. */
class PopupImeGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val controller get() = (instrumentation.targetContext.applicationContext as LocalicaApplication).tagEditing
    private val track = Track("ime-geometry:missing", "IME geometry song", fileName = "ime.mp3", relativePath = "ime.mp3")
    private var savedActions: PlayerActionSettings? = null

    @After fun restorePlayerActions() {
        savedActions?.let { PlayerActionPreferences(instrumentation.targetContext).write(it) }
        savedActions = null
        compose.runOnIdle { if (!controller.state.value.busy) controller.close() }
    }

    @Test fun tagEditorNativeImeTrace() = trace(true)
    @Test fun addToPlaylistsNativeImeTrace() = trace(false)
    @Test fun playerAddToPlaylistsNativeImeTrace() = trace(false, playerScene = true)
    @Test fun playerTagEditorNativeImeTrace() = trace(true, playerScene = true)
    @Test fun mixedContentDuringNativeImeTrace() = trace(false, mixedContent = true)

    private fun trace(editor: Boolean, playerScene: Boolean = false, mixedContent: Boolean = false) {
        val opticalOnly = InstrumentationRegistry.getArguments().getString("popupImeOpticalOnly") == "true"
        if (playerScene && editor) {
            val actions = PlayerActionPreferences(instrumentation.targetContext)
            savedActions = actions.read()
            actions.write(savedActions!!.place(PlayerAction.Edit, ActionSlot(0, 0)))
        }
        compose.runOnIdle {
            assertFalse(controller.state.value.busy)
            assertNull(controller.state.value.recovery)
            controller.close(); controller.hideJobs()
        }
        val telemetry = java.util.concurrent.ConcurrentHashMap<String, Float>()
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var activeProbe: NativeProbe? = null
        val playlists = mutableStateOf(PlaylistState(loading = false))
        val sceneAudio = if (playerScene) File(instrumentation.targetContext.cacheDir, "ime-player-scene.mp3").apply {
            instrumentation.context.assets.open("tagged.mp3").use { input -> outputStream().use(input::copyTo) }
        } else null
        val sceneTrack = sceneAudio?.let { track.copy(id = it.toURI().toString(), uri = it.toURI().toString(), title = "IME player song") } ?: track
        val sceneQueue = QueueEdits.create(listOf(sceneTrack), 0, "IME player fixture")
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            var adding by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                    LocalReducedMotion provides false,
                    LocalGlassMorphWorkObserver provides (if (opticalOnly) null else { name, window, value ->
                        if (value != null) telemetry[name] = value
                        if (name.startsWith("trace@") || name.startsWith("layout-")) events +=
                            "${SystemClock.uptimeMillis()},${System.nanoTime()},$name,${System.identityHashCode(window)},$value,${Thread.currentThread().id}"
                        if (name.startsWith("trace@content-after-draw")) activeProbe?.contentDraw()
                    })) {
                    TagEditorHost(controller) {
                        val edit = presentedTagEdit()
                        if (playerScene) {
                            LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(sceneTrack)), loading = false),
                                PlaybackState(connected = true, trackUri = sceneTrack.uri, title = sceneTrack.title, durationMs = 180000),
                                {}, {}, {}, {}, {}, {},
                                queues = QueueUiState(QueueLibrary(listOf(sceneQueue), sceneQueue.id), loading = false),
                                playlist = playlists.value)
                        } else {
                            ActionTextButton(onClick = { if (editor) edit?.invoke(listOf(track), {}) else adding = true }) { Text("Open fixture") }
                            if (adding) AddToPlaylistDialog(listOf(track), playlists.value, null,
                                { adding = false }, { adding = false })
                        }
                    }
                }
            }
        } }
        if (playerScene) {
            compose.onNodeWithText(sceneTrack.title).performClick()
            compose.onNodeWithContentDescription(if (editor) "Edit tags" else "Add to playlist").performClick()
        } else compose.onNodeWithText("Open fixture").performClick()
        val surface = compose.onNodeWithTag("glass-morph-surface", true)
        compose.awaitExpectedWindowFocus(surface)
        val field = if (editor) compose.onNode(hasSetTextAction() and hasText("Name")) else compose.onNode(hasSetTextAction())
        if (mixedContent) field.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Existing")) }
        if (editor) field.performScrollTo()
        val sheetNode = (if (editor) compose.onNodeWithTag("tag-editor-sheet", true) else surface).fetchSemanticsNode()
        val view = (sheetNode.root as ViewRootForTest).view
        val window = (view.parent as DialogWindowProvider).window
        if (opticalOnly) {
            // Fixed holds keep both complete native transitions in the external recording.
            // No callback, draw observer, invalidation, frame driver or slowed animation is installed.
            field.performClick()
            SystemClock.sleep(1000)
            field.assertIsFocused().performTextInput("IME optical draft")
            SystemClock.sleep(1000)
            UiDevice.getInstance(instrumentation).pressBack()
            SystemClock.sleep(1000)
            field.assertTextContains("IME optical draft").assertIsDisplayed()
            compose.runOnIdle { controller.close() }
            sceneAudio?.delete()
            return
        }
        val slots = linkedMapOf("surface" to surface.fetchSemanticsNode().layoutInfo.coordinates,
            "field" to field.fetchSemanticsNode().layoutInfo.coordinates)
        if (editor) for (slot in listOf("sheet", "frame", "header", "body", "footer"))
            slots[slot] = compose.onNodeWithTag("tag-editor-$slot", true).fetchSemanticsNode().layoutInfo.coordinates
        var mixedPublished = false
        val probe = NativeProbe(view, slots, telemetry) { ime ->
            if (mixedContent && !mixedPublished && ime in 85..599) {
                mixedPublished = true
                events += "${SystemClock.uptimeMillis()},${System.nanoTime()},mixed-content-published,${System.identityHashCode(window)},$ime,${Thread.currentThread().id}"
                playlists.value = PlaylistState(listOf(ManualPlaylist(id = "ime-existing", name = "Existing")), loading = false)
            }
        }
        compose.runOnIdle {
            activeProbe = probe
            (window.decorView as ViewGroup).addView(probe, ViewGroup.LayoutParams(0, 0))
            view.viewTreeObserver.addOnPreDrawListener(probe.beforeDraw)
            view.viewTreeObserver.addOnDrawListener(probe.draw)
        }
        try {
            probe.phase = "opening"
            field.performClick()
            assertTrue("Native IME opens", probe.opened.await(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            if (mixedContent) {
                assertTrue("The update was published during a native IME delta", mixedPublished)
                compose.onNodeWithText("A playlist with this name already exists.").assertIsDisplayed()
                field.assertTextContains("Existing")
            }
            probe.phase = "closing"
            UiDevice.getInstance(instrumentation).pressBack()
            assertTrue("Native IME closes", probe.closed.await(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            field.assertIsDisplayed()
            assertTrue("Native IME supplied changing geometry", probe.nativeInsets.distinct().size > 2)
        } finally {
            compose.runOnUiThread {
                activeProbe = null
                view.viewTreeObserver.removeOnPreDrawListener(probe.beforeDraw)
                view.viewTreeObserver.removeOnDrawListener(probe.draw)
                ViewCompat.setWindowInsetsAnimationCallback(probe, null)
                (probe.parent as? ViewGroup)?.removeView(probe)
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "popup-ime-geometry").apply { mkdirs() }
                val name = if (editor && playerScene) "player-tag-editor" else if (editor) "tag-editor" else if (playerScene) "player-add-to-playlists" else if (mixedContent) "mixed-content" else "add-to-playlists"
                File(dir, "$name.csv").writeText(probe.rows.joinToString("\n"))
                File(dir, "$name-events.csv").writeText(
                    "uptime,nanos,event,windowId,value,threadId\n" + events.joinToString("\n"))
            }
            compose.runOnIdle { controller.close() }
            sceneAudio?.delete()
        }
    }

    private inner class NativeProbe(private val host: View, private val slots: Map<String, LayoutCoordinates>, private val telemetry: Map<String, Float>, private val onImeProgress: (Int) -> Unit) : View(host.context) {
        var phase = "idle"
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val nativeInsets = mutableListOf<Int>()
        val rows = mutableListOf("uptime,nanos,phase,event,ime,source,target,nativeTarget,viewHeight,slot,x,y,width,height,scaleX,scaleY,composeCurrent,composeSource,composeTarget,consumed,viewport,viewId,windowId,probeId,callbackId,traversalId,animationIds,measureSequence,normalizedTarget,heightCorrection,expandedHeight")
        private var ime = 0
        private var source = 0
        private var target = 0
        private var callbackId = 0
        private var traversalId = 0
        private var animationIds = ""
        val beforeDraw = ViewTreeObserver.OnPreDrawListener { traversalId++; record("pre-draw"); true }
        val draw = ViewTreeObserver.OnDrawListener { record("draw") }
        fun contentDraw() = record("content-after-draw")
        init {
            isFocusable = false; isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            ViewCompat.setWindowInsetsAnimationCallback(this, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) { source = ime; record("prepare") }
                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                    target = ViewCompat.getRootWindowInsets(this@NativeProbe)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    record("start"); return bounds
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    callbackId++
                    animationIds = runningAnimations.joinToString("|") { System.identityHashCode(it).toString() }
                    ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    nativeInsets += ime
                    onImeProgress(ime)
                    record("progress"); return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    record("end")
                    if (ime > 0) opened.countDown() else if (phase == "closing") closed.countDown()
                }
            })
        }
        private fun record(event: String) {
            val nativeTarget = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1
            val prefix = "${SystemClock.uptimeMillis()},${System.nanoTime()},$phase,$event,$ime,$source,$target,$nativeTarget,${host.height}"
            slots.forEach { (name, c) -> if (c.isAttached) {
                val a = c.localToScreen(Offset.Zero)
                val b = c.localToScreen(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
                val layout = listOf("current", "source", "target", "consumed", "viewport").joinToString(",") { telemetry["layout-$it"]?.toString() ?: "NaN" }
                val heightMotion = listOf("sequence", "normalized", "correction", "expanded").joinToString(",") { telemetry["layout-$it"]?.toString() ?: "NaN" }
                rows += "$prefix,$name,${a.x},${a.y},${c.size.width},${c.size.height},${(b.x-a.x)/c.size.width.coerceAtLeast(1)},${(b.y-a.y)/c.size.height.coerceAtLeast(1)},$layout,${System.identityHashCode(host)},${System.identityHashCode((host.parent as DialogWindowProvider).window)},${System.identityHashCode(this)},$callbackId,$traversalId,$animationIds,$heightMotion"
            } }
        }
    }
}
