package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ChoiceTraceBufferTest {
    @Test fun fixedCapacityKeepsFirstEventsAndReportsEveryDroppedRecord() {
        val buffer = ChoiceTraceBuffer(2)
        buffer.record(3, identity = 11, value = 7, bits = 1, eventTime = 25, x = 2f, y = 4f)
        buffer.record(4)
        buffer.record(5)
        buffer.record(6)
        assertEquals(2, buffer.size)
        assertTrue(buffer.overflow)
        assertEquals(2, buffer.dropped)
        val lines = buffer.export()
        assertEquals(4, lines.size)
        assertTrue(lines.first().contains("count=2 capacity=2 overflow=true dropped=2"))
        assertTrue(lines[1].contains("code=3 identity=11 value=7 bits=1 eventTime=25"))
        assertTrue(lines[1].contains("x=2.0 y=4.0"))
        assertTrue(lines[2].contains("code=4"))
        assertTrue(lines.last().contains("count=2 overflow=true dropped=2"))
    }

    @Test fun emptyTraceIsExplicitAndExportDoesNotChangeRecords() {
        val buffer = ChoiceTraceBuffer()
        assertFalse(buffer.overflow)
        assertEquals(listOf("BUFFER version=1 count=0 capacity=512 overflow=false dropped=0",
            "BUFFER-END count=0 overflow=false dropped=0"), buffer.export())
        buffer.record(1)
        val first = buffer.export()
        assertEquals(first, buffer.export())
        assertEquals(1, buffer.size)
        assertEquals(0, buffer.dropped)
    }
}
