package io.github.anastaci1a.localica.editing

import org.junit.Assert.*
import org.junit.Test

class ArtworkGrantsTest {
    private class Fixture {
        var owned = emptySet<String>()
        var recent = emptyList<String>()
        val persisted = mutableSetOf("tree:music", "external:shared")
        var saveFails = false
        var acquireFails = false
        var releaseFails = false
        val owner = ArtworkGrants(
            { owned }, { if (saveFails) false else { owned = it; true } },
            { recent.toSet() }, { it in persisted }, { !it.startsWith("tree:") },
            { check(!acquireFails); persisted += it },
            { check(!releaseFails); persisted -= it },
        )
        fun choose(uri: String) {
            recent = (listOf(uri) + recent).distinct().take(12)
            owner.selected(uri)
        }
    }

    @Test fun cancelledAndReplacedChoicesStayBoundedWithoutTouchingOtherGrants() {
        val f = Fixture()
        repeat(200) { f.choose("image:$it") }
        assertEquals(12, f.owned.size)
        assertEquals(f.recent.toSet() + setOf("tree:music", "external:shared"), f.persisted)
        f.choose("external:shared")
        assertFalse("external:shared" in f.owned)
        repeat(12) { f.choose("new:$it") }
        assertTrue("external:shared" in f.persisted)
        assertTrue("tree:music" in f.persisted)
    }

    @Test fun sharedCropAndReadLeasesOutliveEvictionUntilLastClose() {
        val f = Fixture(); f.choose("image:shared")
        val crop = f.owner.retain("image:shared")
        val reader = f.owner.retain("image:shared")
        repeat(20) { f.choose("image:$it") }
        crop.close(); crop.close()
        assertTrue("image:shared" in f.persisted)
        reader.close()
        assertFalse("image:shared" in f.persisted)
        assertEquals(12, f.owned.size)
    }

    @Test fun failedSaveDoesNotAcquireAndFailedAcquisitionCanRetry() {
        val f = Fixture(); f.saveFails = true; f.choose("image:save")
        assertFalse("image:save" in f.persisted)
        f.saveFails = false; f.acquireFails = true; f.choose("image:acquire")
        assertFalse("image:acquire" in f.owned)
        assertFalse("image:acquire" in f.persisted)
        f.acquireFails = false; f.choose("image:acquire")
        assertTrue("image:acquire" in f.persisted)
    }

    @Test fun releaseFailureAndCleanupCommitFailureKeepRetryableOwnership() {
        val f = Fixture(); f.choose("image:old"); f.recent = emptyList()
        f.releaseFails = true; f.owner.prune()
        assertTrue("image:old" in f.owned); assertTrue("image:old" in f.persisted)
        f.releaseFails = false; f.saveFails = true; f.owner.prune()
        assertTrue("image:old" in f.owned); assertFalse("image:old" in f.persisted)
        f.saveFails = false; f.owner.prune(); assertTrue(f.owned.isEmpty())
    }

    @Test fun restartPrunesOnlyRecordedObsoleteImagesAndProtectsTrees() {
        var owned = setOf("image:old", "image:recent", "tree:music")
        val persisted = (owned + "legacy:unknown").toMutableSet()
        val owner = ArtworkGrants({ owned }, { owned = it; true }, { setOf("image:recent") },
            { it in persisted }, { !it.startsWith("tree:") }, { persisted += it }, { persisted -= it })
        owner.prune()
        assertEquals(setOf("image:recent", "tree:music", "legacy:unknown"), persisted)
    }

    @Test fun unreadableLedgerDoesNotAcquireOrRelease() {
        val owner = ArtworkGrants({ error("corrupt") }, { error("must not write") }, { emptySet() },
            { false }, { true }, { error("must not acquire") }, { error("must not release") })
        owner.selected("image:new"); owner.retain("image:new").close()
    }
    @Test fun legacyBudgetUsesTransientAccessWithoutAdoptingOrReleasingUnknownGrants() {
        var owned = emptySet<String>()
        var acquisitions = 0
        var releases = 0
        val owner = ArtworkGrants({ owned }, { owned = it; true }, { setOf("new") },
            { false }, { true }, { acquisitions++ }, { releases++ }, canAcquire = { false })
        owner.selected("new")
        assertEquals(0, acquisitions); assertEquals(0, releases); assertTrue(owned.isEmpty())
    }

    @Test fun failedOrCancelledReadClosesOnlyItsLease() {
        val f = Fixture(); f.choose("image:old")
        runCatching {
            f.owner.retain("image:old").use {
                f.recent = emptyList(); f.owner.prune()
                assertTrue("image:old" in f.persisted)
                throw java.util.concurrent.CancellationException()
            }
        }
        assertFalse("image:old" in f.persisted)
    }

    @Test fun interruptedUnrecordedAcquisitionIsNeverAdoptedOnRestart() {
        var owned = emptySet<String>()
        val persisted = mutableSetOf("image:uncertain")
        val owner = ArtworkGrants({ owned }, { owned = it; true }, { emptySet() },
            { it in persisted }, { true }, { persisted += it }, { persisted -= it })
        owner.selected("image:uncertain"); owner.prune()
        assertTrue(owned.isEmpty()); assertEquals(setOf("image:uncertain"), persisted)
    }

    @Test fun failedOwnershipCommitReleasesFreshGrantWithoutClaimingIt() {
        var writes = 0
        val persisted = mutableSetOf<String>()
        val owner = ArtworkGrants({ emptySet() }, { ++writes == 1 }, { setOf("new") },
            { it in persisted }, { true }, { persisted += it }, { persisted -= it })
        owner.selected("new")
        assertTrue(persisted.isEmpty())
    }

}
