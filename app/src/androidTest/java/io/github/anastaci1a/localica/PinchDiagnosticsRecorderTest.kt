package io.github.anastaci1a.localica

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.diagnostics.Diagnostics
import io.github.anastaci1a.localica.diagnostics.DiagnosticsExport
import io.github.anastaci1a.localica.ui.PinchBreadcrumbs
import io.github.anastaci1a.localica.ui.StuckStateWatchdog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #535 (b): the always-on watchdog records a dump then a recovery, and the export shares the file. */
class PinchDiagnosticsRecorderTest {
    @get:Rule val compose = createComposeRule()

    private fun lines() = Diagnostics.ring.snapshot().map { it.message }

    @Test fun syntheticStuckTransactionRecordsADumpThenItsRecovery() {
        Diagnostics.ring.clear()
        var stuck by mutableStateOf(false)
        var phase by mutableStateOf("idle")
        compose.setContent {
            PinchBreadcrumbs { "crumb $phase" }
            StuckStateWatchdog({ stuck }, { "synthetic-dump phase=$phase" }, { "synthetic-clear phase=$phase" })
        }
        compose.mainClock.autoAdvance = false
        // A healthy idle run records nothing, however long it lasts.
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(emptyList<String>(), lines())

        compose.runOnIdle { phase = "held"; stuck = true }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("no dump before 2 s: ${lines()}", lines().none { it.startsWith("lingering") })
        compose.mainClock.advanceTimeBy(1_500)
        val dump = Diagnostics.ring.snapshot().single { it.message.startsWith("lingering") }
        assertEquals('W', dump.level)
        assertEquals("lingering synthetic-dump phase=held", dump.message)
        assertTrue(lines().contains("crumb held"))

        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("one dump per stuck episode", 1, lines().count { it.startsWith("lingering") })
        compose.runOnIdle { stuck = false }
        compose.mainClock.advanceTimeBy(100)
        assertEquals("recovered synthetic-clear phase=held", lines().last())
    }

    @Test fun aStateThatClearsInsideTheWindowRecordsNothing() {
        Diagnostics.ring.clear()
        var stuck by mutableStateOf(false)
        compose.setContent { StuckStateWatchdog({ stuck }, { "dump" }, { "clear" }) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stuck = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { stuck = false }
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(emptyList<String>(), lines())
    }

    @Test fun exportWritesTheBufferAndBuildsAShareIntentForTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Diagnostics.ring.clear()
        Diagnostics.warn("PinchDiagnostics", "lingering export-marker")
        val file = DiagnosticsExport.write(context)
        assertTrue(file.exists())
        assertEquals(DiagnosticsExport.DirectoryName, file.parentFile?.name)
        val text = file.readText()
        assertTrue(text.startsWith("Localica diagnostics"))
        assertTrue("Device: " in text && "Android: " in text && "Exported: " in text && "App: ${context.packageName}" in text)
        assertTrue("W/PinchDiagnostics" in text && "lingering export-marker" in text)

        val chooser = DiagnosticsExport.shareIntent(context, file)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.diagnostics", uri.authority)
        assertEquals(file.name, uri.lastPathSegment)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The provider serves exactly the file written, and a second export replaces it.
        assertEquals(text, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        val again = DiagnosticsExport.write(context, nowMillis = System.currentTimeMillis() + 5_000)
        assertEquals(listOf(again.name), file.parentFile!!.list()!!.toList())
    }
}
