package io.github.anastaci1a.localica.lyrics

import android.content.Context
import android.net.Uri
import android.util.LruCache
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.wav.WavTag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import java.io.ByteArrayOutputStream

class LyricsRepository(private val context: Context) {
    private val documents = AndroidDocuments(context)
    private val gate = Mutex()
    private var revision = -1L
    private val cache = object : LruCache<String, LyricsResult>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LyricsResult) = 256 + key.length * 2 + value.sources.sumOf { source ->
            (source.document.lines + source.document.plain).sumOf { it.text.length * 2 + 48 }
        }
    }
    suspend fun load(track: Track, currentRevision: Long): LyricsResult = withContext(Dispatchers.IO) { gate.withLock {
        if (revision != currentRevision) { cache.evictAll(); revision = currentRevision }
        val key = "${track.id}:${track.uri}:${track.locations}"
        cache.get(key)?.let { return@withLock it }
        val sources = mutableListOf<LyricSource>(); val problems = mutableListOf<String>()
        fun add(id: String, label: String, parsed: ParsedLyrics, sidecar: Boolean = false) {
            if (parsed.lines.isEmpty()) return
            sources += LyricSource(id, label, parsed, sidecar)
        }
        suspend fun attempt(message: String = "An embedded lyric source could not be read.", block: suspend () -> Unit) {
            try { currentCoroutineContext().ensureActive(); block(); currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problems += message }
        }
        var tag: Tag? = null
        attempt { tag = ContainerTags.read(context, track.uri, track.fileName) }
        tag?.let { container ->
            // Discover each family independently, then decode each field independently.
            // getAll() can decode several fields before returning and lose good siblings.
            attempt {
                container.getFields(FieldKey.LYRICS).forEachIndexed { index, field -> attempt {
                    val body = (field as? AbstractID3v2Frame)?.body
                    val text = when {
                        body is FrameBodyUSLT -> body.firstTextValue
                        field is TagTextField -> field.content
                        else -> container.getValue(FieldKey.LYRICS, index)
                    }
                    if (text.isNotBlank()) add("embedded:$index", "Embedded lyrics ${index + 1}", LrcParser.parse(text))
                } }
            }
            attempt {
                val id3 = if (container is WavTag) container.iD3Tag else container
                val frameId = if (id3 is ID3v22Tag) "SLT" else "SYLT"
                container.getFields(frameId).forEachIndexed { index, field -> attempt {
                    val body = (field as? AbstractID3v2Frame)?.body as? FrameBodySYLT
                    if (body != null && body.contentType in 0..1) add("sylt:$index", "Embedded synchronized lyrics ${index + 1}",
                        SyltParser.parse(body.lyrics, body.textEncoding.toInt(), body.timeStampFormat))
                } }
            }
            for (id in listOf("SYNCEDLYRICS", "SYNCHRONIZEDLYRICS")) attempt {
                container.getFields(id).forEachIndexed { index, field -> attempt {
                    val text = (field as? TagTextField)?.content ?: error("Unsupported synchronized lyric field.")
                    val sourceId = if (index == 0) "embedded:$id" else "embedded:$id:$index"
                    if (text.isNotBlank()) add(sourceId, "Embedded synchronized lyrics", LrcParser.parse(text))
                } }
            }
        }
        if (sources.isEmpty()) attempt {
            embeddedTags(context, track.uri).lyrics.forEachIndexed { index, text -> attempt {
                add("embedded:$index", "Embedded lyrics ${index + 1}", LrcParser.parse(text))
            } }
        }
        for (location in track.locations.sortedBy { it.uri != track.uri }) attempt("A lyric file or folder could not be read. Check folder access and file format.") {
            var directory = documents.root(location.folderUri)
            val folders = location.relativePath.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty)
            for (name in folders) {
                currentCoroutineContext().ensureActive()
                directory = documents.children(location.folderUri, directory.id).firstOrNull { it.isDirectory && it.name == name }
                    ?: error("The lyric folder is unavailable.")
            }
            val base = location.relativePath.substringAfterLast('/').substringBeforeLast('.')
            val sidecars = documents.children(location.folderUri, directory.id).filter {
                !it.isDirectory && it.name.substringBeforeLast('.') == base && it.name.substringAfterLast('.').equals("lrc", true)
            }.sortedBy { it.name }
            for (file in sidecars) {
                if (sources.any { it.id == file.identity }) continue
                attempt("A lyric file or folder could not be read. Check folder access and file format.") {
                    val bytes = context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024) { "Lyric file exceeds 2 MB." }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    } ?: error("Couldn’t read ${file.name}.")
                    val encoding = when {
                        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                        else -> Charsets.UTF_8
                    }
                    add(file.identity, file.name, LrcParser.parse(bytes.toString(encoding)), true)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        LyricsResult(sources.sortedWith(compareByDescending<LyricSource> { it.document.synced }.thenByDescending { it.sidecar }),
            problems.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")).also { cache.put(key, it) }
    } }
}
