package io.github.anastaci1a.localica.library

import android.content.Context
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.wav.WavTag
import java.io.File

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
            readFile(File("/proc/self/fd/${descriptor.fd}"))
        }
    }
}
