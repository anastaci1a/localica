package io.github.anastaci1a.localica.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class LyricsTest {
    @Test fun timestampsOffsetsRepeatedLinesAndBackwardSeeks() {
        val parsed = LrcParser.parse("\uFEFF[ar:Example]\r\n[offset:-250]\n[00:02.50][00:10.005]Repeat\n[00:01]First\n[00:02.50]Harmony")
        assertTrue(parsed.synced)
        assertEquals(listOf(750L, 2250L, 9755L), parsed.lines.map { it.timeMs })
        assertEquals("Repeat\nHarmony", parsed.lines[1].text)
        assertEquals(-1, LrcParser.current(parsed.lines, 0))
        assertEquals(2, LrcParser.current(parsed.lines, 11000))
        assertEquals(0, LrcParser.current(parsed.lines, 1000))
    }
    @Test fun malformedAndUnsyncedLyricsStayReadable() {
        val plain = LrcParser.parse("First line\n\nSecond line")
        assertFalse(plain.synced); assertEquals(3, plain.lines.size)
        val mixed = LrcParser.parse("[00:90]Bad time\n[00:01]Valid\nUntimed words")
        assertEquals("Valid", mixed.lines.single().text)
        assertNotNull(mixed.warning)
        assertTrue(mixed.plain.any { it.text == "Untimed words" })
        assertTrue(LrcParser.parse("").lines.isEmpty())
        assertTrue(runCatching { LrcParser.parse("a".repeat(2 * 1024 * 1024 + 1)) }.isFailure)
    }
    @Test fun syltSupportsMillisecondsAndRejectsTruncationOrFrameTiming() {
        val output = ByteArrayOutputStream()
        for ((text, time) in listOf("First" to 500, "Second" to 2000)) {
            output.write(text.toByteArray()); output.write(0); output.write(ByteBuffer.allocate(4).putInt(time).array())
        }
        val parsed = SyltParser.parse(output.toByteArray(), 3, 2)
        assertEquals(listOf(500L, 2000L), parsed.lines.map { it.timeMs })
        assertTrue(runCatching { SyltParser.parse(output.toByteArray(), 3, 1) }.isFailure)
        assertTrue(runCatching { SyltParser.parse(output.toByteArray().dropLast(1).toByteArray(), 3, 2) }.isFailure)
    }
    @Test fun syltUtf16AndLineBreaksKeepWordsTogether() {
        val output = ByteArrayOutputStream()
        for ((text, time) in listOf("\uFEFFOne " to 100, "two\n" to 500, "Next" to 2000)) {
            output.write(text.toByteArray(Charsets.UTF_16LE)); output.write(byteArrayOf(0, 0)); output.write(ByteBuffer.allocate(4).putInt(time).array())
        }
        val parsed = SyltParser.parse(output.toByteArray(), 1, 2)
        assertEquals(listOf("One two", "Next"), parsed.lines.map { it.text })
        assertEquals(listOf(100L, 2000L), parsed.lines.map { it.timeMs })
    }
}
