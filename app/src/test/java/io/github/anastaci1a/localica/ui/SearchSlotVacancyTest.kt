package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSlotVacancyTest {
    @Test fun rowContentLandsAtVisiblePillTopAcrossDensityAndRowSpacing() {
        for (density in listOf(1f, 2.625f, 3f)) for (rowInset in listOf(1.2f, 4f)) {
            val available = 56f * density
            val target = 8f * density
            val contentInset = rowInset * density
            val vacancy = searchSlotVacancy(available, target, contentInset)
            assertEquals(target, available + contentInset - vacancy, .0001f)
        }
    }

    @Test fun largeTextAndPartialScrollPreserveTheirOwnMeasuredSpace() {
        assertEquals(80f, searchSlotVacancy(80f, 4f, 4f), 0f)
        assertEquals(8f, 36f + 4f - searchSlotVacancy(36f, 8f, 4f), 0f)
        assertEquals(0f, searchSlotVacancy(2f, 8f, 4f), 0f)
        assertEquals(0f, searchSlotVacancy(0f, 8f, 1.2f), 0f)
    }
}
