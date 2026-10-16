package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic only: the original regression stays unchanged. These extra probes perturb timing. */
@RunWith(Parameterized::class)
class ChoiceFirstTapEvidenceTest(private val trial: Int, private val traced: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun oneYearTapWithPresentationEvidence() {
        assumeFalse("Stopped after the first failed diagnostic single tap", failed.get())
        val label = "trial=$trial traced=$traced"
        val traceEnabled = AtomicBoolean(true)
        val trace: ((String) -> Unit)? = if (traced) ({ message ->
            if (traceEnabled.get()) Log.i(TAG, "${SystemClock.uptimeMillis()} $label $message")
        }) else null
        var probe: PresentationProbe? = null
        var year: SemanticsNodeInteraction? = null
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalChoiceTapTrace provides trace) {
                    BrowseSortDialog(BrowseRoute(MainTab.Albums), BrowseStyle(), false, {}, { _, _ -> }, {})
                }
            } }
            compose.onNodeWithText("Sort by").performClick()
            val target = compose.onNodeWithText("Year")
            year = target
            val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
            probe = compose.runOnUiThread { PresentationProbe(view) }
            target.performScrollTo()
            snapshot("before", label, target, probe)
            // Keep the single injection and immediate selection assertion inseparable.
            target.performClick().assertIsSelected()
            snapshot("passed", label, target, probe)
        } catch (failure: Throwable) {
            failed.set(true)
            Log.e(TAG, "${SystemClock.uptimeMillis()} failed $label", failure)
            runCatching { year?.let { snapshot("after-failure", label, it, probe) } }
                .exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                    .forEachIndexed { index, node ->
                        Log.e(TAG, "$label root=$index id=${node.id} bounds=${node.boundsInRoot}")
                    }
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog(TAG)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val directory = File(instrumentation.targetContext.filesDir, "choice-first-tap").apply { mkdirs() }
                val file = File(directory, "trial-$trial-traced-$traced.png")
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
                val bytes = try {
                    java.io.ByteArrayOutputStream().also {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }.toByteArray()
                } finally { bitmap.recycle() }
                runCatching { file.writeBytes(bytes) }
                // Same logcat transport used by GlassMorphTest; existing CI uploads per-test logcat.
                // Number and hash every chunk so an incomplete artifact cannot look like a valid capture.
                val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(3000)
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                Log.e(TAG, "PNG-BEGIN $label bytes=${bytes.size} chunks=${chunks.size} sha256=$hash")
                chunks.forEachIndexed { index, chunk -> Log.e(TAG, "PNG $label ${index + 1}/${chunks.size} $chunk") }
                Log.e(TAG, "PNG-END $label sha256=$hash; captured after assertion and diagnostics")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            // No global trace state. Disable retained callbacks and remove native observers.
            traceEnabled.set(false)
            compose.runOnUiThread { probe?.close() }
        }
    }

    private fun snapshot(phase: String, label: String, target: SemanticsNodeInteraction, probe: PresentationProbe?) {
        val node = target.fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val native = compose.runOnUiThread {
            val screen = IntArray(2).also(view::getLocationOnScreen)
            "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} focused=${view.hasWindowFocus()} " +
                "shown=${view.isShown} size=${view.width}x${view.height} screen=${screen.contentToString()} ${probe?.state()}"
        }
        Log.i(TAG, "${SystemClock.uptimeMillis()} $label phase=$phase node=${node.id} bounds=${node.boundsInRoot} " +
            "selected=${node.config.getOrElse(SemanticsProperties.Selected) { false }} $native")
    }

    /** Observes frames without invalidating, requesting focus, waiting, or changing input. */
    private class PresentationProbe(private val view: View) : AutoCloseable {
        private val draws = AtomicInteger()
        private val commits = AtomicInteger()
        private val lastDraw = AtomicLong(-1)
        private val lastCommit = AtomicLong(-1)
        private val open = AtomicBoolean(true)
        private val observer = view.viewTreeObserver
        private val commit = Runnable {
            if (open.get()) { commits.incrementAndGet(); lastCommit.set(SystemClock.uptimeMillis()) }
        }
        private val draw = ViewTreeObserver.OnDrawListener {
            draws.incrementAndGet()
            lastDraw.set(SystemClock.uptimeMillis())
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) observer.registerFrameCommitCallback(commit)
        }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener {
            Log.i(TAG, "${SystemClock.uptimeMillis()} view=${System.identityHashCode(view)} focus-changed=$it")
        }
        init { observer.addOnDrawListener(draw); observer.addOnWindowFocusChangeListener(focus) }
        fun state() = "probeView=${System.identityHashCode(view)} draws=${draws.get()} lastDraw=${lastDraw.get()} " +
            "commits=${commits.get()} lastCommit=${lastCommit.get()} commitSupported=${Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated}"
        override fun close() {
            open.set(false)
            if (observer.isAlive) {
                observer.removeOnDrawListener(draw)
                observer.removeOnWindowFocusChangeListener(focus)
                if (Build.VERSION.SDK_INT >= 29) observer.unregisterFrameCommitCallback(commit)
            }
        }
    }

    companion object {
        private const val TAG = "ChoiceFirstTapEvidence"
        private val failed = AtomicBoolean(false)
        @JvmStatic @Parameterized.Parameters(name = "trial={0}, traced={1}")
        fun trials(): List<Array<Any>> = (1..10).flatMap { trial ->
            (if (trial % 2 == 1) listOf(false, true) else listOf(true, false)).map { arrayOf<Any>(trial, it) }
        }
    }
}
