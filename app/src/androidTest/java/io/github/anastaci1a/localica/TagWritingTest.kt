package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class TagWritingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String, block: (File) -> Unit) {
        val dir = File(context.cacheDir, "tag-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(dir, name)
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
            block(file)
        } finally { dir.deleteRecursively() }
    }
    @Test fun mp3EditsVerifyAudioAndUnchangedTags() = edits("tagged.mp3")
    @Test fun m4aEditsVerifyAudioAndUnchangedTags() = edits("tagged.m4a")
    @Test fun flacEditsVerifyAudioAndUnchangedTags() = edits("tagged.flac")
    @Test fun oggEditsVerifyAudioAndUnchangedTags() = edits("tagged.ogg")
    @Test fun wavEditsVerifyAudioAndUnchangedTags() = edits("tone.wav")
    private fun edits(name: String) = fixture(name) { file ->
        val fields = mapOf(EditField.Title to "Edited café", EditField.Album to "Album", EditField.Artist to "Artist",
            EditField.AlbumArtist to "Album artist", EditField.Year to "2025", EditField.Disc to "2", EditField.TrackNumber to "3", EditField.Comment to "Keep my comment")
        TagCodec.edit(file, TagPatch(fields))
        fields.forEach { (field, value) -> assertEquals(name + field.name, value, AudioFileIO.read(file).tag.getFirst(field.key)) }
        val metadata = runBlocking { io.github.anastaci1a.localica.library.AndroidDocuments(context).metadata(android.net.Uri.fromFile(file).toString(), file.name) }
        assertEquals(name, "Edited café", metadata.title)
        assertEquals(name, "Album artist", metadata.albumArtist)
        assertEquals(name, 2, metadata.discNumber)
        assertEquals(name, "Keep my comment", metadata.comment)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val output = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }
        TagCodec.edit(file, TagPatch(artwork = ArtworkChange.Replace(CoverImage(output.toByteArray(), "image/png", 32, 32))))
        assertEquals("Keep my comment", AudioFileIO.read(file).tag.getFirst(FieldKey.COMMENT))
        TagCodec.edit(file, TagPatch(mapOf(EditField.Title to "Only title"), ArtworkChange.Remove))
        assertEquals("Artist", AudioFileIO.read(file).tag.getFirst(FieldKey.ARTIST))
        assertTrue(AudioFileIO.read(file).tag.artworkList.isEmpty())
        TagCodec.edit(file, TagPatch(mapOf(EditField.Comment to "")))
        assertNull(runBlocking { io.github.anastaci1a.localica.library.AndroidDocuments(context).metadata(android.net.Uri.fromFile(file).toString(), file.name) }.comment)
    }
    @Test fun partialWriteRestoresOriginalAndCrashRecoverySurvivesNewWriter() = fixture("tagged.mp3") { file -> runBlocking {
        val original = file.readBytes()
        var fail = true
        val access = object : EditableDocuments {
            override fun writable(track: Track) = file.path
            override fun open(uri: String) = File(uri).inputStream()
            override fun replace(uri: String, source: File) {
                if (fail) { fail = false; File(uri).writeBytes(byteArrayOf(1, 2)); throw IOException("Provider disconnected") }
                source.copyTo(File(uri), overwrite = true)
            }
        }
        val directory = File(file.parentFile, "recovery")
        val track = Track(file.path, "Before", fileName = file.name, relativePath = file.name)
        val writer = RecoverableTagWriter(directory, access)
        assertTrue(runCatching { writer.edit(track, TagPatch(mapOf(EditField.Title to "After"))) }.isFailure)
        assertArrayEquals(original, file.readBytes()); assertNull(writer.pending())
        writer.edit(track, TagPatch(mapOf(EditField.Title to "After")))
        val restored = RecoverableTagWriter(directory, access)
        assertNotNull(restored.pending())
        restored.recover()
        assertArrayEquals(original, file.readBytes()); assertNull(restored.pending())
    } }
    @Test fun unsupportedAndUnwritableFilesAreUntouched() = fixture("tagged.opus") { file -> runBlocking {
        val original = file.readBytes()
        val access = object : EditableDocuments {
            override fun writable(track: Track): String? = null
            override fun open(uri: String): InputStream = error("Must not read")
            override fun replace(uri: String, source: File): Unit = error("Must not write")
        }
        val writer = RecoverableTagWriter(File(file.parentFile, "recovery"), access)
        val track = Track(file.path, "Before", fileName = file.name, relativePath = file.name)
        assertTrue(runCatching { writer.edit(track, TagPatch(mapOf(EditField.Title to "After"))) }.isFailure)
        assertTrue(runCatching { writer.edit(track.copy(fileName = "song.mp3"), TagPatch(mapOf(EditField.Title to "After"))) }.isFailure)
        assertArrayEquals(original, file.readBytes())
    } }
}
