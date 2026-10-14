package io.github.anastaci1a.localica

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.ContainerTags
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LyricsRepository
import kotlinx.coroutines.runBlocking
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
}
