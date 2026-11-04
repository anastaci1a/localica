package io.github.anastaci1a.localica.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One recorded line: wall-clock time, a severity letter (I/W), the source tag and the text. */
internal data class DiagnosticsEntry(val sequence: Long, val timeMillis: Long, val level: Char, val tag: String, val message: String)

/**
 * Fixed-size in-memory log for rare-fault capture (#535 b). Holds at most [maxEntries] lines and about
 * [maxChars] characters (roughly 256 KB of UTF-16); the oldest lines go first. Appending is O(1)
 * amortized and nothing is written anywhere until the user exports.
 */
internal class DiagnosticsRing(val maxEntries: Int = 500, val maxChars: Int = 128 * 1024, val maxMessage: Int = 4000) {
    init { require(maxEntries > 0 && maxChars > 0 && maxMessage > 0) }
    private val entries = ArrayDeque<DiagnosticsEntry>()
    private var chars = 0
    private var next = 0L

    @Synchronized fun add(level: Char, tag: String, message: String, timeMillis: Long = System.currentTimeMillis()) {
        val text = if (message.length > maxMessage) message.take(maxMessage) + "…[truncated ${message.length - maxMessage}]" else message
        entries.addLast(DiagnosticsEntry(next++, timeMillis, level, tag, text))
        chars += text.length + tag.length
        while (entries.size > maxEntries || chars > maxChars && entries.size > 1) {
            val dropped = entries.removeFirst()
            chars -= dropped.message.length + dropped.tag.length
        }
    }

    /** Oldest first. */
    @Synchronized fun snapshot(): List<DiagnosticsEntry> = entries.toList()
    val size: Int get() = synchronized(this) { entries.size }
    /** Total lines ever added, including those already dropped. */
    val totalAdded: Long get() = synchronized(this) { next }
    @Synchronized fun clear() { entries.clear(); chars = 0 }

    companion object {
        fun format(entry: DiagnosticsEntry, zone: ZoneId = ZoneId.systemDefault()): String =
            "${TIME.withZone(zone).format(Instant.ofEpochMilli(entry.timeMillis))} ${entry.level}/${entry.tag} #${entry.sequence}: ${entry.message}"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx")
    }
}
