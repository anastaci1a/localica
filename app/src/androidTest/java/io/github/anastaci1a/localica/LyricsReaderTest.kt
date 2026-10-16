package io.github.anastaci1a.localica

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import io.github.anastaci1a.localica.library.TrackLocation
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ContainerTags
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LyricsRepository
import kotlinx.coroutines.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class LyricsReaderTest {
    @Test fun opusEmbeddedLyricsUseThePlaybackContainerReader() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "lyrics.opus")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        try {
            val track = Track(Uri.fromFile(file).toString(), "Song", fileName = file.name, relativePath = file.name)
            val result = LyricsRepository(context).load(track, 0)
            assertTrue(result.toString(), result.sources.first().document.synced)
            assertFalse(result.sources.any { it.id.endsWith(":plain") })
            assertEquals("An original test line", result.sources.first().document.lines.first().text)
        } finally { file.delete() }
    }
    @Test fun embeddedTimedAndPlainLyricsRemainSelectableAndInvalidate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "lyrics-test.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        try {
            ContainerTags.configure()
            val lyrics = ByteArrayOutputStream().apply {
                write("A synthetic lyric".toByteArray()); write(0); write(ByteBuffer.allocate(4).putInt(1000).array())
            }
            val audio = AudioFileIO.read(file)
            audio.tag = ID3v24Tag().apply {
                setField(FieldKey.LYRICS, "Plain lyrics")
                addField(ID3v24Frame("SYLT").apply { body = FrameBodySYLT(3, "eng", 2, 1, "", lyrics.toByteArray()) })
            }
            audio.commit()
            val track = Track(Uri.fromFile(file).toString(), "Song", fileName = file.name, relativePath = file.name)
            val reader = LyricsRepository(context)
            val result = reader.load(track, 0)
            assertTrue(result.toString(), result.sources.first().document.synced)
            assertFalse(result.sources.any { it.id.endsWith(":plain") })
            assertEquals(1000L, result.sources.first().document.lines.first().timeMs)
            assertTrue(result.sources.any { it.document.lines.first().text == "Plain lyrics" })
            AudioFileIO.read(file).apply { tag.setField(FieldKey.LYRICS, "Updated lyrics"); commit() }
            assertTrue(reader.load(track, 0).sources.any { it.document.lines.first().text == "Plain lyrics" })
            assertTrue(reader.load(track, 1).sources.any { it.document.lines.first().text == "Updated lyrics" })
        } finally { file.delete() }
    }
    @Test fun serializedId3VersionsKeepGoodSourcesAfterInvalidSiblings() = runBlocking {
        for (version in 2..4) for (oversized in listOf("x\n".repeat(10001), "x".repeat(2 * 1024 * 1024 + 1))) withSerializedLyrics(version, listOf(
            "USLT" to plainBody(oversized),
            "USLT" to plainBody("Good plain", "second"),
            "SYLT" to syltBody("Good timed", description = "second"),
        )) { file ->
            val result = LyricsRepository(context).load(track(file), 0)
            assertEquals("ID3v2.$version: $result", listOf("Good timed", "Good plain"),
                result.sources.map { it.document.lines.single().text })
            assertEquals(listOf("sylt:0", "embedded:1"), result.sources.map { it.id })
            assertEquals(1000L, result.sources.first().document.lines.single().timeMs)
            assertNotNull(result.problem)
        }
    }

    @Test fun unsupportedOrTruncatedSyltLeavesPlainLyricsUsable() = runBlocking {
        for (version in 2..4) for (body in listOf(syltBody("Frame timing", format = 1), syltBody("Truncated").dropLast(1).toByteArray())) {
            withSerializedLyrics(version, listOf("USLT" to plainBody("Good plain"), "SYLT" to body)) { file ->
                val result = LyricsRepository(context).load(track(file), 0)
                assertEquals("ID3v2.$version: $result", listOf("Good plain"), result.sources.map { it.document.lines.single().text })
                assertNotNull(result.problem)
            }
        }
    }

    @Test fun repeatedCustomSyncedCommentsAreIsolated() = runBlocking {
        val file = File(context.cacheDir, "isolated-lyrics.ogg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tagged.ogg").use { input -> file.outputStream().use(input::copyTo) }
        try {
            ContainerTags.configure()
            AudioFileIO.read(file).apply {
                val comments = tag as org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
                comments.deleteField(FieldKey.LYRICS)
                comments.addField("SYNCEDLYRICS", "x".repeat(2 * 1024 * 1024 + 1))
                comments.addField("SYNCEDLYRICS", "[00:01]Good custom")
                comments.addField("SYNCHRONIZEDLYRICS", "[00:02]Other custom")
                commit()
            }
            val result = LyricsRepository(context).load(track(file), 0)
            assertEquals(listOf("Good custom", "Other custom"), result.sources.map { it.document.lines.single().text })
            assertEquals(listOf("embedded:SYNCEDLYRICS:1", "embedded:SYNCHRONIZEDLYRICS"), result.sources.map { it.id })
            assertNotNull(result.problem)
        } finally { file.delete() }
    }

    @Test fun failedSidecarDoesNotSuppressSiblingsAndOrderingAndDedupStayStable() = runBlocking {
        withLyricTree { tree -> withSerializedLyrics(4, listOf(
            "USLT" to plainBody("Embedded plain"), "SYLT" to syltBody("Embedded timed")
        )) { file ->
            val primary = TrackLocation(tree.toString(), file.toURI().toString(), "song.mp3")
            val result = LyricsRepository(context).load(track(file).copy(locations = listOf(
                primary, primary.copy(uri = "secondary"), primary.copy(uri = "plain", relativePath = "plain.mp3")
            )), 0)
            assertEquals(listOf("Sidecar timed", "Embedded timed", "Sidecar plain", "Embedded plain"),
                result.sources.map { it.document.lines.single().text })
            assertEquals(result.sources.size, result.sources.map { it.id }.distinct().size)
            assertNotNull(result.problem)
        } }
    }

    @Test fun cancellationDuringSidecarReadPropagatesAndDoesNotCachePartialSources() = runBlocking {
        withLyricTree { tree -> withSerializedLyrics(4, listOf("USLT" to plainBody("Embedded plain"))) { file ->
            val song = track(file).copy(locations = listOf(TrackLocation(tree.toString(), file.toURI().toString(), "song.mp3")))
            val reader = LyricsRepository(context)
            val pending = async { reader.load(song, 0) }
            val status = DocumentsContract.buildDocumentUriUsingTree(tree, "lyrics-status")
            withTimeout(10000) {
                while (context.contentResolver.query(status, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                    it.moveToFirst() && it.getInt(0) == 1
                } != true) delay(10)
            }
            pending.cancel()
            try { pending.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            val result = reader.load(song, 0)
            assertEquals(listOf("Sidecar timed", "Embedded plain"), result.sources.map { it.document.lines.single().text })
            assertNotNull(result.problem)
        } }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun track(file: File) = Track(Uri.fromFile(file).toString(), "Song", fileName = file.name, relativePath = file.name)
    private fun plainBody(text: String, description: String = "") =
        byteArrayOf(0) + "eng".toByteArray() + description.toByteArray() + byteArrayOf(0) + text.toByteArray()
    private fun syltBody(text: String, format: Int = 2, description: String = "") =
        byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(format.toByte(), 1) + description.toByteArray() +
            byteArrayOf(0) + text.toByteArray() + byteArrayOf(0) + ByteBuffer.allocate(4).putInt(1000).array()
    private fun synchsafe(size: Int) = byteArrayOf((size ushr 21 and 127).toByte(), (size ushr 14 and 127).toByte(),
        (size ushr 7 and 127).toByte(), (size and 127).toByte())
    // Write real ID3 bytes, including the native v2.2 frame IDs, rather than testing in-memory converted tags.
    private suspend fun withSerializedLyrics(version: Int, frames: List<Pair<String, ByteArray>>, block: suspend (File) -> Unit) {
        val file = File(context.cacheDir, "isolated-lyrics-$version.mp3")
        val payload = ByteArrayOutputStream().apply {
            for ((id, body) in frames) {
                write((if (version == 2) if (id == "USLT") "ULT" else "SLT" else id).toByteArray())
                write(if (version == 2) byteArrayOf((body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
                    else if (version == 4) synchsafe(body.size) else ByteBuffer.allocate(4).putInt(body.size).array())
                if (version != 2) write(byteArrayOf(0, 0))
                write(body)
            }
        }.toByteArray()
        val audio = InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { it.readBytes() }
        val start = if (audio.take(3).toByteArray().toString(Charsets.US_ASCII) == "ID3")
            10 + (6..9).fold(0) { size, index -> (size shl 7) or (audio[index].toInt() and 127) } else 0
        try {
            file.outputStream().use { output ->
                output.write("ID3".toByteArray()); output.write(byteArrayOf(version.toByte(), 0, 0))
                output.write(synchsafe(payload.size)); output.write(payload); output.write(audio, start, audio.size - start)
            }
            block(file)
        } finally { file.delete() }
    }
    private suspend fun withLyricTree(block: suspend (Uri) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val tree = DocumentsContract.buildTreeDocumentUri("io.github.anastaci1a.localica.test.documents", "lyrics")
        fun grant(action: String) {
            context.startActivity(Intent(action).setClassName(instrumentation.context.packageName,
                TestDocumentsProvider.LyricsGrantActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        grant("grant")
        try {
            withTimeout(10000) {
                while (runCatching {
                    context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, "lyrics"),
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
                }.getOrDefault(false).not()) delay(20)
            }
            block(tree)
        } finally {
            grant("revoke")
            withContext(NonCancellable) {
                withTimeout(10000) {
                    while (runCatching {
                        context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, "lyrics"),
                            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.close()
                    }.isSuccess) delay(20)
                }
            }
        }
    }

}
