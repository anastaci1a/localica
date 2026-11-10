package io.github.anastaci1a.localica

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.queue.LegacyQueueOrderStream
import io.github.anastaci1a.localica.queue.QueueDatabase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnostic evidence for the actual legacy scalar reader, not a queue or frame-time acceptance gate. */
class QueueOrderBlobProbeTest {
    @Test fun recordActualScalarReaderBytecodeAndNativePeakAcrossLargeManualPayloads() {
        assumeTrue("Explicit diagnostic opt-in", InstrumentationRegistry.getArguments().getString("queueOrderBlobProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Measurements require the non-debuggable target", context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val name = "queue-order-scalar-probe.db"
        context.deleteDatabase(name)
        val room = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT sqlite_version()").use { it.moveToFirst(); Log.i(Tag, "sqlite=${it.getString(0)} api=${android.os.Build.VERSION.SDK_INT}") }
            db.query("EXPLAIN SELECT substr(CAST(orderState AS BLOB),16385,16384) FROM queues WHERE id = 'probe'").use { rows ->
                while (rows.moveToNext()) Log.i(Tag, "bytecode=" + (0 until rows.columnCount).joinToString("|") {
                    if (rows.isNull(it)) "null" else rows.getString(it)
                })
            }
            for (count in listOf(13_400, 50_000, 1_000_000)) {
                db.execSQL("DELETE FROM queues WHERE id = 'probe'")
                // Only SQLite constructs this fixture value; Kotlin never allocates a queue-sized input string.
                db.execSQL("""
                    WITH RECURSIVE ids(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM ids WHERE n + 1 < ?)
                    INSERT INTO queues (id,name,createdAt,currentEntryId,positionMs,shuffle,repeat,orderState)
                    SELECT 'probe','Probe',0,NULL,0,0,'Off',
                        '{"manual":[' || group_concat('"' || printf('%036d',n) || '"') || ']}' FROM ids
                """.trimIndent(), arrayOf(count))
                val bytes = db.query("SELECT length(CAST(orderState AS BLOB)) FROM queues WHERE id = 'probe'").use {
                    it.moveToFirst(); it.getLong(0)
                }
                val runtime = Runtime.getRuntime()
                val beforeNative = Debug.getNativeHeapAllocatedSize()
                val beforeManaged = runtime.totalMemory() - runtime.freeMemory()
                val peakNative = AtomicLong(beforeNative)
                val peakManaged = AtomicLong(beforeManaged)
                val sampling = AtomicBoolean(true)
                val sampler = Thread({
                    while (sampling.get()) {
                        peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                        peakManaged.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                        Thread.sleep(1)
                    }
                }, "queue-order-peak")
                val started = SystemClock.elapsedRealtimeNanos()
                sampler.start()
                var consumed = 0L
                try {
                    LegacyQueueOrderStream(db, "probe").use { input ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            consumed += read
                        }
                    }
                } finally { sampling.set(false); sampler.join() }
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                assertEquals(bytes, consumed)
                Log.i(Tag, "count=$count bytes=$bytes elapsedNanos=$elapsed nativeBefore=$beforeNative nativePeak=${peakNative.get()} " +
                    "managedBefore=$beforeManaged managedPeak=${peakManaged.get()} gc=${Debug.getRuntimeStats()}")
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }

    companion object { private const val Tag = "QueueOrderBlobProbe" }
}
