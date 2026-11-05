package io.github.anastaci1a.localica.diagnostics

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsRingTest {
    private val utc = ZoneId.of("UTC")

    @Test fun keepsTheNewestEntriesInOrderWhenFull() {
        val ring = DiagnosticsRing(maxEntries = 3)
        for (i in 1..5) ring.add('I', "T", "m$i", timeMillis = i * 1000L)
        assertEquals(listOf("m3", "m4", "m5"), ring.snapshot().map { it.message })
        assertEquals(listOf(2L, 3L, 4L), ring.snapshot().map { it.sequence })
        assertEquals(3, ring.size)
        assertEquals(5L, ring.totalAdded)
    }

    @Test fun staysWithinTheCharacterBudgetButKeepsTheLatestEntry() {
        val ring = DiagnosticsRing(maxEntries = 100, maxChars = 100, maxMessage = 1000)
        repeat(10) { ring.add('I', "T", "x".repeat(40), 0) }
        assertTrue(ring.snapshot().sumOf { it.message.length + it.tag.length } <= 100)
        assertEquals(2, ring.size)
        ring.add('W', "T", "y".repeat(500), 0)
        assertEquals(1, ring.size)
        assertEquals('W', ring.snapshot().single().level)
    }

    @Test fun truncatesOversizedMessagesAndSaysSo() {
        val ring = DiagnosticsRing(maxMessage = 10)
        ring.add('I', "T", "0123456789ABCDE", 0)
        assertEquals("0123456789…[truncated 5]", ring.snapshot().single().message)
    }

    @Test fun formatsTimeLevelTagSequenceAndMessage() {
        val ring = DiagnosticsRing()
        ring.add('W', "PinchDiagnostics", "lingering transaction=3", timeMillis = 1_762_300_800_123L)
        assertEquals("2025-11-05 00:00:00.123 +00:00 W/PinchDiagnostics #0: lingering transaction=3",
            DiagnosticsRing.format(ring.snapshot().single(), utc))
    }

    @Test fun snapshotsAreIndependentAndClearResets() {
        val ring = DiagnosticsRing()
        ring.add('I', "T", "a", 0)
        val before = ring.snapshot()
        ring.add('I', "T", "b", 0)
        assertEquals(1, before.size)
        ring.clear()
        assertEquals(0, ring.size)
        ring.add('I', "T", "c", 0)
        assertEquals(listOf("c"), ring.snapshot().map { it.message })
    }

    @Test fun reportCarriesEnvironmentAndEveryEntry() {
        val ring = DiagnosticsRing()
        ring.add('I', "PinchDiagnostics", "pinch tx=1", 1_762_300_800_000L)
        ring.add('W', "PinchDiagnostics", "lingering tx=1", 1_762_300_802_000L)
        val text = DiagnosticsExport.report(DiagnosticsEnvironment("io.github.anastaci1a.localica", "0.31.1", 89, false,
            "Google", "Pixel 8", "15", 35), ring, 1_762_300_900_000L, utc)
        assertTrue(text.startsWith("Localica diagnostics\n"))
        assertTrue(text, "App: io.github.anastaci1a.localica 0.31.1 (89)\n" in text)
        assertTrue("Device: Google Pixel 8\n" in text)
        assertTrue("Android: 15 (API 35)\n" in text)
        assertTrue("Exported: 2025-11-05T00:01:40Z[UTC]\n" in text)
        assertTrue("Entries: 2 kept of 2 recorded" in text)
        val lines = text.trimEnd().lines()
        assertTrue(lines[lines.size - 2].endsWith("I/PinchDiagnostics #0: pinch tx=1"))
        assertTrue(lines.last().endsWith("W/PinchDiagnostics #1: lingering tx=1"))
    }
}
