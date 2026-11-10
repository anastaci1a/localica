package io.github.anastaci1a.localica.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricSelectionTest {
    private val first = LyricSource("first", "First", LrcParser.parse("First text"))
    private val saved = LyricSource("saved", "Saved", LrcParser.parse("[00:01.00]Saved text"))

    @Test fun savedSecondSourceIsSelectedBeforeAnyMigrationEffect() {
        assertSame(saved, selectedLyricSource(listOf(first, saved), "saved"))
        assertSame(saved, selectedLyricSource(listOf(first, saved), "saved:plain"))
    }

    @Test fun missingOrUnusableLegacySourceRetainsTheExistingFallback() {
        assertSame(first, selectedLyricSource(listOf(first, saved), "missing"))
        assertSame(first, selectedLyricSource(listOf(first, saved), "first:plain"))
        assertSame(first, selectedLyricSource(listOf(first, saved), null))
        assertNull(selectedLyricSource(emptyList(), "saved"))
    }

    @Test fun literalSavedSourceWinsOverItsLegacyInterpretation() {
        val literal = saved.copy(id = "saved:plain")
        assertSame(literal, selectedLyricSource(listOf(first, saved, literal), "saved:plain"))
    }
}
