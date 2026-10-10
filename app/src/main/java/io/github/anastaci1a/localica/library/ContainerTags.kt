package io.github.anastaci1a.localica.library

import android.content.Context
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.wav.WavTag
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.jaudiotagger.tag.id3.*

/** Seekable descriptor reads supplement platform metadata without copying whole audio files. */
internal object ContainerTags {
    init { TagOptionSingleton.getInstance().apply { setAndroid(true); wavOptions = WavOptions.READ_ID3_ONLY } }
    fun configure() = Unit
    fun read(context: Context, uri: String, fileName: String? = null): Tag? {
        val parsed = Uri.parse(uri)
        val name = fileName ?: if (parsed.scheme == "file") parsed.lastPathSegment.orEmpty() else
            context.contentResolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        fun readFile(file: File) = AudioFileIO.readAs(file, extension).tag?.also { if (it is WavTag) it.syncToId3FromInfoIfEmpty() }
        return if (parsed.scheme == "file") readFile(File(requireNotNull(parsed.path)))
        else context.contentResolver.openFileDescriptor(parsed, "r")?.use { descriptor ->
            // Read the granted descriptor itself. Reopening /proc/self/fd or access() can
            // incorrectly require filesystem permissions that the document grant does not give.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                when (extension) {
                    "mp3", "aac" -> readId3(input.channel)
                    "opus" -> null // Media3 supplies Opus comments; jaudiotagger's Ogg reader is Vorbis-only.
                    "flac", "m4a", "mp4", "m4b", "m4p", "wav", "ogg", "oga" ->
                        TagFileView.read(context, input.channel, extension) { file -> when (extension) {
                            "flac" -> org.jaudiotagger.audio.flac.FlacTagReader().read(file.toPath())
                            "m4a", "mp4", "m4b", "m4p" -> org.jaudiotagger.audio.mp4.Mp4TagReader().read(file.toPath())
                            "wav" -> org.jaudiotagger.audio.wav.WavTagReader(name).read(file.toPath()).also { it.syncToId3FromInfoIfEmpty() }
                            else -> RandomAccessFile(file, "r").use { OggTags().readTags(it) }
                        } }
                    else -> null
                }
            }
        }
    }
    private class OggTags : org.jaudiotagger.audio.ogg.OggFileReader() {
        fun readTags(file: RandomAccessFile): Tag = getTag(file)
    }
    private fun readId3(channel: FileChannel): Tag? {
        fun read(size: Int): ByteBuffer {
            val buffer = ByteBuffer.allocate(size)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw java.io.EOFException("Incomplete ID3 tag")
            return buffer.apply { flip() }
        }
        if (channel.size() < 10) return null
        channel.position(0)
        val header = read(10).array()
        if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
            require((6..9).all { header[it].toInt() and 0x80 == 0 }) { "Invalid ID3 size" }
            val size = (6..9).fold(0) { n, index -> (n shl 7) or (header[index].toInt() and 0x7f) }
            require(size <= 16 * 1024 * 1024 && size + 10L <= channel.size()) { "ID3 tag is too large or incomplete" }
            channel.position(0)
            val bytes = read(size + 10)
            return when (header[3].toInt()) {
                2 -> ID3v22Tag(bytes)
                3 -> ID3v23Tag(bytes)
                4 -> ID3v24Tag(bytes)
                else -> throw java.io.IOException("Unsupported ID3 version")
            }
        }
        if (channel.size() < 128) return null
        channel.position(channel.size() - 128)
        return try { ID3v1Tag().apply { read(read(128)) } } catch (_: org.jaudiotagger.tag.TagNotFoundException) { null }
    }
}
