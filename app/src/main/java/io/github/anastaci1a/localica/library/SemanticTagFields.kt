package io.github.anastaci1a.localica.library

import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX

/** Application values, distinct from the raw bytes used for preservation checks. */
internal object SemanticTagFields {
    fun number(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun isTechnicalComment(field: TagField): Boolean =
        ((field as? AbstractID3v2Frame)?.body as? FrameBodyCOMM)?.description?.startsWith("iTun") == true

    fun read(tag: Tag, key: FieldKey): String? = when (key) {
        FieldKey.TRACK, FieldKey.DISC_NO -> number(tag.getFirst(key))?.toString()
        FieldKey.COMMENT -> {
            val fields = tag.getFields(key)
            val comments = if (fields.any { it is AbstractID3v2Frame }) fields.mapNotNull { field ->
                ((field as? AbstractID3v2Frame)?.body as? FrameBodyCOMM)?.takeUnless { isTechnicalComment(field) }?.text
            } else tag.getAll(key)
            comments.firstOrNull { it.isNotBlank() } ?: sequenceOf("TXXX", "TXX").flatMap { tag.getFields(it).asSequence() }
                .mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodyTXXX }
                .firstOrNull { it.description.equals("comment", true) && it.text.isNotBlank() }?.text
        }
        else -> tag.getFirst(key)
    }?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() }
}
