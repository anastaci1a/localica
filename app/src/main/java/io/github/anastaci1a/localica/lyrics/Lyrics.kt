package io.github.anastaci1a.localica.lyrics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class LyricLine(val text: String, val timeMs: Long? = null)
data class ParsedLyrics(val lines: List<LyricLine>, val plain: List<LyricLine>, val warning: String? = null) {
    val synced get() = lines.isNotEmpty() && lines.all { it.timeMs != null }
}
data class LyricSource(val id: String, val label: String, val document: ParsedLyrics, val sidecar: Boolean = false)
data class LyricsResult(val sources: List<LyricSource> = emptyList(), val problem: String? = null)

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,8}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)
    private val metadata = Regex("^\\[(ar|al|ti|au|by|re|ve|length|offset):.*]$", RegexOption.IGNORE_CASE)
    private val wordStamp = Regex("<\\d{1,8}:\\d{2}(?:[.:]\\d{1,3})?>")
    fun parse(text: String): ParsedLyrics {
        require(text.length <= 2 * 1024 * 1024) { "Lyrics exceed the 2 MB limit." }
        val input = text.removePrefix("\uFEFF").lines()
        require(input.size <= 10000) { "Lyrics contain too many lines." }
        val shift = input.asReversed().firstNotNullOfOrNull { offset.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
            ?.coerceIn(-86400000, 86400000) ?: 0L
        val timed = mutableListOf<LyricLine>(); val plain = mutableListOf<LyricLine>()
        var malformed = false
        for (raw in input) {
            val line = raw.trim()
            if (metadata.matches(line)) continue
            val times = mutableListOf<Long>(); var end = 0
            for (match in stamp.findAll(line)) {
                if (match.range.first != end) break
                val seconds = match.groupValues[2].toInt()
                if (seconds >= 60) { malformed = true; break }
                val fraction = match.groupValues[3].padEnd(3, '0').toLong()
                times += (match.groupValues[1].toLong() * 60000 + seconds * 1000 + fraction + shift).coerceAtLeast(0)
                end = match.range.last + 1
            }
            val words = line.substring(end).replace(wordStamp, "")
            if (times.isEmpty() && line.startsWith('[')) malformed = true
            if (words.isNotEmpty() || plain.isNotEmpty()) plain += LyricLine(words)
            times.forEach { timed += LyricLine(words, it) }
        }
        val ordered = timed.groupBy { it.timeMs!! }.toSortedMap().map { (time, lines) ->
            LyricLine(lines.joinToString("\n") { it.text }, time)
        }
        val untimed = plain.dropLastWhile { it.text.isEmpty() }
        return ParsedLyrics(ordered.ifEmpty { untimed }, untimed,
            if (malformed) "Some timestamps could not be read. Plain text is available in Sources." else null)
    }
    fun current(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0; var high = lines.size - 1; var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val time = lines[middle].timeMs ?: return -1
            if (time <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found
    }
}

/** ID3 SYLT: timestamp format 2 uses absolute milliseconds; frame-count timing stays unsupported. */
object SyltParser {
    fun parse(data: ByteArray, encoding: Int, timestampFormat: Int): ParsedLyrics {
        require(timestampFormat == 2) { "Embedded lyrics use unsupported frame-based timestamps." }
        require(data.size <= 2 * 1024 * 1024 && encoding in 0..3) { "Unsupported embedded lyrics." }
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        var charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1, 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val pieces = mutableListOf<LyricLine>(); var start = 0
        while (start < data.size) {
            var end = start
            while (end + width <= data.size && !(0 until width).all { data[end + it] == 0.toByte() }) end += width
            require(end + width + 4 <= data.size) { "Incomplete embedded lyric timestamp." }
            if (encoding == 1 && end - start >= 2) {
                if (data[start] == 0xff.toByte() && data[start + 1] == 0xfe.toByte()) charset = Charsets.UTF_16LE
                if (data[start] == 0xfe.toByte() && data[start + 1] == 0xff.toByte()) charset = Charsets.UTF_16BE
            }
            val text = String(data, start, end - start, charset).removePrefix("\uFEFF")
            val time = ByteBuffer.wrap(data, end + width, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            pieces += LyricLine(text, time)
            require(pieces.size <= 10000) { "Too many embedded lyric fragments." }
            start = end + width + 4
        }
        val lines = if (pieces.any { '\n' in it.text }) {
            val grouped = mutableListOf<LyricLine>(); var text = ""; var time: Long? = null
            for (piece in pieces) piece.text.replace("\r", "").split('\n').forEachIndexed { index, part ->
                if (index > 0) { if (text.isNotBlank()) grouped += LyricLine(text.trim(), time); text = ""; time = null }
                if (time == null && part.isNotEmpty()) time = piece.timeMs
                text += part
            }
            if (text.isNotBlank()) grouped += LyricLine(text.trim(), time)
            grouped
        } else pieces
        val sorted = lines.groupBy { it.timeMs!! }.toSortedMap().map { (time, rows) -> LyricLine(rows.joinToString("\n") { it.text }, time) }
        return ParsedLyrics(sorted, sorted.map { LyricLine(it.text) })
    }
}
