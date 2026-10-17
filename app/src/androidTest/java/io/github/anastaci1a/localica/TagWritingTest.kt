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
            EditField.AlbumArtist to "Album artist", EditField.Year to "2025", EditField.Disc to "2", EditField.TrackNumber to "3", EditField.Comment to "Keep my comment", EditField.Composer to "Composer", EditField.Lyricist to "Lyricist", EditField.Genre to "Ambient")
        TagCodec.edit(file, TagPatch(fields))
        fields.forEach { (field, value) -> assertEquals(name + field.name, value, AudioFileIO.read(file).tag.getFirst(field.key)) }
        val metadata = runBlocking { io.github.anastaci1a.localica.library.AndroidDocuments(context).metadata(android.net.Uri.fromFile(file).toString(), file.name) }
        assertEquals(name, "Edited café", metadata.title)
        assertEquals(name, "Album artist", metadata.albumArtist)
        assertEquals(name, 2, metadata.discNumber)
        assertEquals(name, "Keep my comment", metadata.comment)
        assertEquals(name, "Composer", metadata.composer)
        assertEquals(name, "Lyricist", metadata.lyricist)
        assertEquals(name, "Ambient", metadata.genre)
        assertNotNull(metadata.modifiedAt)
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
    @Test fun paddedTrackAndDiscNumbersAndClearsPreserveTotals() {
        for (name in listOf("tagged.mp3", "tone.wav")) fixture(name) { file ->
            io.github.anastaci1a.localica.library.ContainerTags.configure()
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TRACK, "3"); tag.setField(FieldKey.TRACK_TOTAL, "12")
            tag.setField(FieldKey.DISC_NO, "2"); tag.setField(FieldKey.DISC_TOTAL, "4")
            audio.commit()
            for ((track, disc) in listOf("007" to "03", "" to "")) {
                TagCodec.edit(file, TagPatch(mapOf(EditField.TrackNumber to track, EditField.Disc to disc)))
                val read = AudioFileIO.read(file).tag
                assertEquals(name, "12", read.getFirst(FieldKey.TRACK_TOTAL))
                assertEquals(name, "4", read.getFirst(FieldKey.DISC_TOTAL))
                val metadata = runBlocking { io.github.anastaci1a.localica.library.AndroidDocuments(context)
                    .metadata(android.net.Uri.fromFile(file).toString(), name) }
                assertEquals(name, track.toIntOrNull(), metadata.trackNumber)
                assertEquals(name, disc.toIntOrNull(), metadata.discNumber)
            }
        }
    }

    @Test fun replacingAndClearingCommentsPreservesTechnicalBytesInEitherOrder() {
        for (name in listOf("tagged.mp3", "tone.wav")) for (version in if (name.endsWith("mp3")) listOf(2, 3, 4) else listOf(3))
            for (technicalFirst in listOf(true, false)) for (userComment in listOf(true, false))
            for (replacement in listOf("Replacement café", "")) fixture(name) { file ->
                seedComments(file, technicalFirst, userComment, version)
                val before = technicalCommentBytes(AudioFileIO.read(file).tag)
                val onDiskBefore = technicalCommentFrames(file)
                assertEquals(2, before.size)
                assertEquals(2, onDiskBefore.size)
                TagCodec.edit(file, TagPatch(mapOf(EditField.Comment to replacement)))
                val after = AudioFileIO.read(file).tag
                assertEquals(before, technicalCommentBytes(after))
                assertEquals(onDiskBefore, technicalCommentFrames(file))
                val metadata = runBlocking { io.github.anastaci1a.localica.library.AndroidDocuments(context)
                    .metadata(android.net.Uri.fromFile(file).toString(), name) }
                assertEquals(replacement.takeIf { it.isNotEmpty() }, metadata.comment)
            }
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

/** Serialize actual COMM frames, including technical-first layouts, for reader/writer regressions. */
internal fun seedComments(file: File, technicalFirst: Boolean, userComment: Boolean = true, version: Int = 3) {
    io.github.anastaci1a.localica.library.ContainerTags.configure()
    val audio = AudioFileIO.read(file)
    val container = audio.tagOrCreateAndSetDefault
    val tag = when (version) {
        2 -> org.jaudiotagger.tag.id3.ID3v22Tag()
        3 -> org.jaudiotagger.tag.id3.ID3v23Tag()
        else -> org.jaudiotagger.tag.id3.ID3v24Tag()
    }
    if (container is org.jaudiotagger.tag.wav.WavTag) container.setID3Tag(tag) else audio.tag = tag
    val commentId = if (version == 2) "COM" else "COMM"
    val technical = listOf("iTunNORM", "iTunSMPB").map { description ->
        tag.createFrame(commentId).apply {
            body = org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM(0, "eng", description, " 000002AB 00000145")
        }
    }
    val user = tag.createFrame(commentId).apply {
        body = org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM(0, "eng", "", "Personal notes")
    }
    if (technicalFirst) technical.forEach(tag::addField)
    if (userComment) tag.addField(user)
    if (!technicalFirst) technical.forEach(tag::addField)
    audio.commit()
}

internal fun technicalCommentBytes(tag: org.jaudiotagger.tag.Tag): List<List<Byte>> =
    tag.getFields(FieldKey.COMMENT)
        .filter(io.github.anastaci1a.localica.library.SemanticTagFields::isTechnicalComment)
        .map { it.rawContent.toList() }

/** Direct frame slices for these controlled, non-unsynchronized ID3 fixtures, not a tag reserializer. */
internal fun technicalCommentFrames(file: File): List<List<Byte>> {
    val bytes = file.readBytes()
    fun number(offset: Int, count: Int, bits: Int = 8): Int = (offset until offset + count)
        .fold(0) { value, index -> (value shl bits) or (bytes[index].toInt() and ((1 shl bits) - 1)) }
    fun text(offset: Int, count: Int) = bytes.copyOfRange(offset, offset + count).toString(Charsets.ISO_8859_1)
    var start = 0
    if (file.extension == "wav") {
        start = 12
        while (text(start, 4).lowercase() != "id3 ") {
            val size = java.nio.ByteBuffer.wrap(bytes, start + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            start += 8 + size + (size and 1)
            check(start + 8 <= bytes.size) { "Missing WAV ID3 fixture" }
        }
        start += 8
    }
    check(text(start, 3) == "ID3")
    check(bytes[start + 5].toInt() == 0) { "Fixture must not use extended headers or unsynchronization" }
    val version = bytes[start + 3].toInt()
    val end = start + 10 + number(start + 6, 4, 7)
    var offset = start + 10
    val frames = mutableListOf<List<Byte>>()
    val headerSize = if (version == 2) 6 else 10
    while (offset + headerSize <= end && bytes[offset] != 0.toByte()) {
        val id = text(offset, if (version == 2) 3 else 4)
        val size = if (version == 2) number(offset + 3, 3) else number(offset + 4, 4, if (version == 4) 7 else 8)
        check(size >= 0 && offset + headerSize + size <= end)
        if (id == "COM" || id == "COMM") {
            val body = text(offset + headerSize, size)
            if (body.contains("iTunNORM") || body.contains("iTunSMPB"))
                frames += bytes.copyOfRange(offset, offset + headerSize + size).toList()
        }
        offset += headerSize + size
    }
    return frames
}
