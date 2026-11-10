package io.github.anastaci1a.localica.queue

/** Legacy scalar reader. Small results bound Kotlin memory; native SQLite input cost needs separate qualification. */
@androidx.annotation.WorkerThread
internal class LegacyQueueOrderStream(private val db: androidx.sqlite.db.SupportSQLiteDatabase,
    private val queueId: String) : java.io.InputStream() {
    private var chunk = ByteArray(0)
    private var index = 0
    private var offset = 1L
    private var ended = false
    override fun read(): Int {
        if (index == chunk.size && !refill()) return -1
        return chunk[index++].toInt() and 255
    }
    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        if (length == 0) return 0
        if (index == chunk.size && !refill()) return -1
        val count = minOf(length, chunk.size - index)
        chunk.copyInto(bytes, start, index, index + count)
        index += count
        return count
    }
    private fun refill(): Boolean {
        if (ended) return false
        chunk = db.query("SELECT substr(CAST(orderState AS BLOB),?,16384) FROM queues WHERE id = ?",
            arrayOf(offset, queueId)).use { check(it.moveToFirst()); it.getBlob(0) ?: ByteArray(0) }
        offset += chunk.size
        index = 0
        ended = chunk.isEmpty()
        return !ended
    }
}
