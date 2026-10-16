package io.github.anastaci1a.localica.library

import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
import org.junit.Assert.*
import org.junit.Test

class SemanticTagFieldsTest {
    @Test fun numberUsesOnlyAPositiveNumerator() {
        for (value in listOf(null, "", "0", "0/12", "/12", "-2/12", "broken", "2147483648"))
            assertNull(value, SemanticTagFields.number(value))
        assertEquals(3, SemanticTagFields.number(" 003 /12"))
    }

    @Test fun technicalOnlyAndTechnicalFirstCommentsHaveUserSemantics() {
        val tag = ID3v23Tag()
        tag.addField(tag.createFrame("COMM").apply { body = FrameBodyCOMM(0, "eng", "iTunNORM", "technical") })
        assertNull(SemanticTagFields.read(tag, FieldKey.COMMENT))
        tag.addField(tag.createFrame("COMM").apply { body = FrameBodyCOMM(0, "eng", "", "User comment") })
        assertEquals("User comment", SemanticTagFields.read(tag, FieldKey.COMMENT))
    }
}
