package io.github.anastaci1a.localica.diagnostics

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters of catalog-scale work (whole-archive matching, whole-library copies, catalog
 * rebuilds, database re-reads). Each is one atomic increment at the site that performs the work, so
 * a stress test can assert that scrolling performs none of it. Values are never user-facing.
 */
internal object WorkCounters {
    /** Last.fm archive matched against the whole library. */
    val lastFmMatches = AtomicLong()
    /** Local-play overlay recomputed over every matched song. */
    val lastFmOverlays = AtomicLong()
    /** Effective statistics (local plus Last.fm) recomputed as a whole-library map. */
    val lastFmApplications = AtomicLong()
    /** Library snapshot copied to carry play counts. */
    val snapshotCountCopies = AtomicLong()
    /** Library rows re-read from the database. */
    val libraryReads = AtomicLong()
    /** Browse catalog (every row of a view) rebuilt. */
    val catalogBuilds = AtomicLong()
    /** Shared group plan projected onto a count overlay (one copy per group row). */
    val groupProjections = AtomicLong()
    /** Play-derived row information computed for one row. */
    val playInformationRows = AtomicLong()

    private val all = listOf("lastFmMatches" to lastFmMatches, "lastFmOverlays" to lastFmOverlays,
        "lastFmApplications" to lastFmApplications, "snapshotCountCopies" to snapshotCountCopies, "libraryReads" to libraryReads,
        "catalogBuilds" to catalogBuilds, "groupProjections" to groupProjections, "playInformationRows" to playInformationRows)

    fun reset() = all.forEach { it.second.set(0) }
    fun snapshot(): Map<String, Long> = all.associate { it.first to it.second.get() }
}
