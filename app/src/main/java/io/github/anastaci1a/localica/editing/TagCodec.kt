package io.github.anastaci1a.localica.editing

import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.anastaci1a.localica.library.SemanticTagFields
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.wav.WavTag
import org.jaudiotagger.tag.wav.WavInfoTag
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Only edits a private working copy. The document is untouched until all checks pass. */
object TagCodec {
    val extensions = setOf("mp3", "m4a", "mp4", "flac", "ogg", "wav")
    init { io.github.anastaci1a.localica.library.ContainerTags.configure() }

    fun edit(file: File, patch: TagPatch) {
        patch.validate()
        require(file.extension.lowercase() in extensions) { "Writing this audio format is not supported yet." }
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        if (tag is WavTag) tag.syncToId3FromInfoIfEmpty()
        val oldInfo = if (tag is WavTag) fields(tag.infoTag) else emptyMap()
        val touchedInfo = mutableSetOf<String>()
        val oldFields = fields(tag)
        val trackTotal = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK_TOTAL) }.getOrDefault("")
        val discTotal = runCatching { tag.getFirst(org.jaudiotagger.tag.FieldKey.DISC_TOTAL) }.getOrDefault("")
        val touched = patch.fields.keys.flatMap { tag.getFields(it.key).map { field -> field.id } }.toMutableSet()
        val technicalComments = if (EditField.Comment in patch.fields)
            tag.getFields(org.jaudiotagger.tag.FieldKey.COMMENT).filter(SemanticTagFields::isTechnicalComment)
        else emptyList()
        val technicalCommentBytes = technicalComments.map { digest(it.rawContent).toList() }
        val retainedCustomComments = mutableMapOf<String, List<String>>()
        if (EditField.Comment in patch.fields) {
            for (id in listOf("TXXX", "TXX")) {
                val custom = tag.getFields(id)
                val retained = custom.filterNot { field ->
                    val body = (field as? AbstractID3v2Frame)?.body as? FrameBodyTXXX
                    body?.description.equals("comment", ignoreCase = true)
                }
                if (retained.size != custom.size) {
                    tag.deleteField(id); retained.forEach(tag::addField); touched += id
                    retainedCustomComments[id] = fields(tag)[id].orEmpty()
                }
            }
            if (tag is org.jaudiotagger.tag.flac.FlacTag || tag is org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag) {
                for (id in listOf("COMMENT", "DESCRIPTION")) if (tag.getFields(id).isNotEmpty()) {
                    touched += id; tag.deleteField(id)
                }
            }
        }
        val beforeArtwork = if (patch.artwork == ArtworkChange.Keep) tag.artworkList.map { it.binaryData?.let(::digest)?.toList() } else null
        val beforeAudio = audioHash(file)
        patch.fields.forEach { (field, value) ->
            // setField may replace the first COMM, including a technical frame. Rebuild only
            // the user-comment group, then restore the original technical frame objects.
            if (field == EditField.Comment) tag.deleteField(field.key)
            if (value.isBlank()) tag.deleteField(field.key) else tag.setField(field.key, value)
            if (field == EditField.Comment) technicalComments.forEach(tag::addField)
            if (field == EditField.TrackNumber && trackTotal.isNotEmpty()) tag.setField(org.jaudiotagger.tag.FieldKey.TRACK_TOTAL, trackTotal)
            if (field == EditField.Disc && discTotal.isNotEmpty()) tag.setField(org.jaudiotagger.tag.FieldKey.DISC_TOTAL, discTotal)
            touched += tag.getFields(field.key).map { it.id }
            if (tag is WavTag && field.key in WavInfoTag.getSupportedKeys()) {
                touchedInfo += tag.infoTag.getFields(field.key).map { it.id }
                if (value.isBlank()) tag.infoTag.deleteField(field.key) else tag.infoTag.setField(field.key, value)
                touchedInfo += tag.infoTag.getFields(field.key).map { it.id }
            }
        }
        if (patch.artwork != ArtworkChange.Keep) {
            // Formats use different field IDs for pictures; discover both old and new IDs.
            val pre = fields(tag)
            tag.deleteArtworkField()
            touched += pre.keys - fields(tag).keys
            if (patch.artwork is ArtworkChange.Replace) {
                val image = patch.artwork.image
                tag.setField(object : AndroidArtwork() {
                    override fun setImageFromData() = true // Already decoded and bounded by CoverImages.
                }.apply {
                    binaryData = image.bytes; mimeType = image.mime; width = image.width; height = image.height
                    pictureType = 3; description = "Front cover"
                })
            }
            touched += fields(tag).filter { (key, value) -> pre[key] != value }.keys
        }
        audio.commit()
        val read = AudioFileIO.read(file).tag
        check(runCatching { read.getFirst(org.jaudiotagger.tag.FieldKey.TRACK_TOTAL) }.getOrDefault("") == trackTotal &&
            runCatching { read.getFirst(org.jaudiotagger.tag.FieldKey.DISC_TOTAL) }.getOrDefault("") == discTotal) { "Track/disc totals did not verify." }
        patch.fields.forEach { (field, value) ->
            val expected = if (field == EditField.TrackNumber || field == EditField.Disc)
                SemanticTagFields.number(value)?.toString().orEmpty() else value.takeUnless(String::isBlank).orEmpty()
            check(SemanticTagFields.read(read, field.key).orEmpty() == expected) { "${field.label} did not verify; original kept." }
        }
        if (EditField.Comment in patch.fields) check(
            read.getFields(org.jaudiotagger.tag.FieldKey.COMMENT).filter(SemanticTagFields::isTechnicalComment)
                .map { digest(it.rawContent).toList() } == technicalCommentBytes
        ) { "Technical comments did not verify; original kept." }
        val after = fields(read)
        check(retainedCustomComments.all { (id, contents) -> after[id].orEmpty() == contents }) { "Custom tags did not verify; original kept." }
        if (read is WavTag) check(oldInfo.filterKeys { it !in touchedInfo }.all { (id, contents) -> fields(read.infoTag)[id] == contents }) {
            "This write would alter unrelated WAV INFO tags; original kept."
        }
        val changedUnrelated = oldFields.filterKeys { it !in touched }.filter { (id, contents) -> after[id] != contents }.keys
        check(changedUnrelated.isEmpty()) {
            "This format would alter unrelated tags ($changedUnrelated); original kept."
        }
        when (val artwork = patch.artwork) {
            ArtworkChange.Keep -> check(read.artworkList.map { it.binaryData?.let(::digest)?.toList() } == beforeArtwork) { "Existing artwork did not verify." }
            ArtworkChange.Remove -> check(read.artworkList.isEmpty()) { "Artwork removal did not verify." }
            is ArtworkChange.Replace -> check(read.artworkList.any { it.binaryData?.contentEquals(artwork.image.bytes) == true }) { "Artwork did not verify." }
        }
        check(beforeAudio.contentEquals(audioHash(file))) { "Audio verification failed; original kept." }
    }

    private fun fields(tag: Tag): Map<String, List<String>> = tag.fields.asSequence().groupBy { it.id }
        .mapValues { (_, values) -> values.map { field ->
            // Copying WAV INFO's encoder into ID3 normalizes its text encoding/terminator.
            if (tag is WavTag && field.id == "TENC" && field is org.jaudiotagger.tag.TagTextField)
                field.content.trimEnd('\u0000')
            else digest(field.rawContent).joinToString("") { "%02x".format(it) }
        } }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Compare encoded audio samples, excluding metadata, before accepting a tag writer's output. */
    internal fun audioHash(file: File): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio stream to verify.")
            extractor.selectTrack(index)
            val hash = MessageDigest.getInstance("SHA-256")
            val format = extractor.getTrackFormat(index)
            hash.update(format.getString(MediaFormat.KEY_MIME).orEmpty().toByteArray())
            for (key in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
                if (format.containsKey(key)) hash.update("$key:${format.getInteger(key)}".toByteArray())
            }
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                check(size <= buffer.capacity()) { "Audio packet too large to verify." }
                hash.update(buffer.array(), 0, size); samples++
                if (!extractor.advance()) break
            }
            check(samples > 0) { "Empty audio stream." }
            return hash.digest()
        } finally { extractor.release() }
    }
}
