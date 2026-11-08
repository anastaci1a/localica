package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** List lines form one consistent family even when operation symbols occupy different corners. */
class ListIconGeometryTest {
    @Test fun allListRowsHaveTheSamePitchStrokeAndRoundCaps() {
        val rowCounts = mapOf("ic_playlist" to 3, "ic_queue" to 3, "ic_playlist_add" to 3,
            "ic_menu_playlist_add" to 4, "ic_menu_play_next" to 4, "ic_menu_queue_add" to 4)
        for ((name, count) in rowCounts) {
            val xml = File("src/main/res/drawable/$name.xml").readText()
            val rows = Regex("<path[^>]*android:name=\"list_rows\"[^>]*/>").find(xml)?.value
            assertNotNull("$name identifies the list rows separately from its operation symbol", rows)
            val path = Regex("android:pathData=\"([^\"]+)\"").find(rows!!)!!.groupValues[1]
            val lines = Regex("M([0-9.]+),([0-9.]+)H([0-9.]+)").findAll(path).toList()
            assertEquals("$name has $count horizontal rows", count, lines.size)
            assertEquals("$name contains only horizontal row segments", "", path.replace(Regex("M([0-9.]+),([0-9.]+)H([0-9.]+)"), "").trim())
            val centers = lines.map { it.groupValues[2].toDouble() }
            centers.zipWithNext().forEach { (a, b) -> assertEquals("$name constant pitch", 4.5, b - a, .0001) }
            assertTrue("$name common stroke", "android:strokeWidth=\"2\"" in rows)
            assertTrue("$name rounded caps", "android:strokeLineCap=\"round\"" in rows)
            assertTrue("$name rounded joins", "android:strokeLineJoin=\"round\"" in rows)
        }
    }
}
