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
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
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

    @Test fun tagEditorNativeImeTrace() = trace(true)
    @Test fun addToPlaylistsNativeImeTrace() = trace(false)

    private fun trace(editor: Boolean) {
        compose.runOnIdle {
            assertFalse(controller.state.value.busy)
            assertNull(controller.state.value.recovery)
            controller.close(); controller.hideJobs()
        }
        val telemetry = mutableMapOf<String, Float>()
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit, dark = false)
            var adding by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                    LocalReducedMotion provides false,
                    LocalGlassMorphWorkObserver provides { name, _, value -> if (value != null) telemetry[name] = value }) {
                    TagEditorHost(controller) {
                        val edit = presentedTagEdit()
                        ActionTextButton(onClick = { if (editor) edit?.invoke(listOf(track), {}) else adding = true }) { Text("Open fixture") }
                        if (adding) AddToPlaylistDialog(listOf(track), PlaylistState(loading = false), null,
                            { adding = false }, { adding = false })
                    }
                }
            }
        } }
        compose.onNodeWithText("Open fixture").performClick()
        val surface = compose.onNodeWithTag("glass-morph-surface", true)
        compose.awaitExpectedWindowFocus(surface)
        val field = compose.onNode(hasSetTextAction() and hasText(if (editor) "Name" else "New playlist name"))
        if (editor) field.performScrollTo()
        val sheetNode = (if (editor) compose.onNodeWithTag("tag-editor-sheet", true) else surface).fetchSemanticsNode()
        val view = (sheetNode.root as ViewRootForTest).view
        val window = (view.parent as DialogWindowProvider).window
        val slots = linkedMapOf("surface" to surface.fetchSemanticsNode().layoutInfo.coordinates,
            "field" to field.fetchSemanticsNode().layoutInfo.coordinates)
        if (editor) for (slot in listOf("sheet", "frame", "header", "body", "footer"))
            slots[slot] = compose.onNodeWithTag("tag-editor-$slot", true).fetchSemanticsNode().layoutInfo.coordinates
        val probe = NativeProbe(view, slots, telemetry)
        compose.runOnIdle {
            (window.decorView as ViewGroup).addView(probe, ViewGroup.LayoutParams(0, 0))
            view.viewTreeObserver.addOnDrawListener(probe.draw)
        }
        try {
            probe.phase = "opening"
            field.performClick()
            assertTrue("Native IME opens", probe.opened.await(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            probe.phase = "closing"
            UiDevice.getInstance(instrumentation).pressBack()
            assertTrue("Native IME closes", probe.closed.await(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            field.assertIsDisplayed()
            assertTrue("Native IME supplied changing geometry", probe.nativeInsets.distinct().size > 2)
        } finally {
            compose.runOnUiThread {
                view.viewTreeObserver.removeOnDrawListener(probe.draw)
                ViewCompat.setWindowInsetsAnimationCallback(probe, null)
                (probe.parent as? ViewGroup)?.removeView(probe)
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "popup-ime-geometry").apply { mkdirs() }
                File(dir, if (editor) "tag-editor.csv" else "add-to-playlists.csv").writeText(probe.rows.joinToString("\n"))
            }
            compose.runOnIdle { controller.close() }
        }
    }

    private inner class NativeProbe(private val host: View, private val slots: Map<String, LayoutCoordinates>, private val telemetry: Map<String, Float>) : View(host.context) {
        var phase = "idle"
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val nativeInsets = mutableListOf<Int>()
        val rows = mutableListOf("uptime,phase,event,ime,source,target,nativeTarget,viewHeight,slot,x,y,width,height,scaleX,scaleY,composeCurrent,composeSource,composeTarget,consumed,viewport")
        private var ime = 0
        private var source = 0
        private var target = 0
        val draw = ViewTreeObserver.OnDrawListener { record("draw") }
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
                    ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    nativeInsets += ime
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
            val prefix = "${SystemClock.uptimeMillis()},$phase,$event,$ime,$source,$target,$nativeTarget,${host.height}"
            slots.forEach { (name, c) -> if (c.isAttached) {
                val a = c.localToScreen(Offset.Zero)
                val b = c.localToScreen(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
                val layout = listOf("current", "source", "target", "consumed", "viewport").joinToString(",") { telemetry["layout-$it"]?.toString() ?: "NaN" }
                rows += "$prefix,$name,${a.x},${a.y},${c.size.width},${c.size.height},${(b.x-a.x)/c.size.width.coerceAtLeast(1)},${(b.y-a.y)/c.size.height.coerceAtLeast(1)},$layout"
            } }
        }
    }
}
