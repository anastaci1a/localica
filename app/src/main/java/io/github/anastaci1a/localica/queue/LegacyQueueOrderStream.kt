package io.github.anastaci1a.localica.queue

/**
 * Forward-only legacy transfer. The approved one-time exception permits a payload-sized native copy;
 * fixed chunks bound only the transfer buffers. Persist copied segments before resuming at their byte offset.
 */
@androidx.annotation.WorkerThread
internal class LegacyQueueOrderStream(private val db: androidx.sqlite.db.SupportSQLiteDatabase,
    private val queueId: String, private val chunkBytes: Int = MigrationChunkBytes,
    startOffset: Long = 0) : java.io.InputStream() {
    init {
        QueueSequence(db).checkWorker()
        require(chunkBytes in 1..MigrationChunkBytes)
        require(startOffset >= 0 && startOffset < Long.MAX_VALUE)
    }
    private var chunk = ByteArray(0)
    private var index = 0
    private var offset = startOffset + 1
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
        QueueSequence(db).checkWorker()
        chunk = db.query("SELECT substr(CAST(orderState AS BLOB),?,?) FROM queues WHERE id = ?",
            arrayOf(offset, chunkBytes, queueId)).use { check(it.moveToFirst()); it.getBlob(0) ?: ByteArray(0) }
        offset += chunk.size
        index = 0
        ended = chunk.isEmpty()
        return !ended
    }

    companion object {
        // R8 verified this transfer size on API 26 and 36, below the API 26 cursor-window boundary.
        const val MigrationChunkBytes = 1024 * 1024
    }
}
