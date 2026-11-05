package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListScrollTargetTest {
    @Test fun headerDestinationsKeepOnePhysicalCoordinateAcrossHeaderAndRowSizes() {
        for (height in listOf(420, 680, 1100)) for (row in listOf(48, 72, 136)) {
            val home = 317f
            for (position in listOf(0f, 100f, 900f, 8000f)) {
                // First row's absolute top includes the whole header, regardless of density.
                val rowTop = height - position
                val top = ListScrollTarget.header(height)
                val target = ListScrollTarget.header(height, home)
                assertEquals(-position, rowTop + top.offset, 0f)
                assertEquals(home - position, rowTop + target.offset, 0f)
                // The equivalent coordinate from a later ordinary row is unchanged.
                assertEquals(home - position, rowTop + row * 5 + target.offset - row * 5, 0f)
            }
        }
    }
}
