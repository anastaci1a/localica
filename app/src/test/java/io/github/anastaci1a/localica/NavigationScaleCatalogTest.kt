package io.github.anastaci1a.localica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host-only planning contracts: no provider, scanned rows, installed queue or playback claims. */
class NavigationScaleCatalogTest {
    private val authority = "io.github.localica.navigation.fixture"
    private val prefix = "localica-navigation-scale-v1:track:"
    private val catalog = NavigationScaleCatalog(authority)

    @Test
    fun all13313IdentitiesAndCanonicalUrisAreStableUniqueAndOrdered() {
        assertEquals(13_313, NavigationScaleCatalog.TRACK_COUNT)
        assertEquals(13_313, catalog.identities.size)
        assertEquals(13_313, catalog.identities.toSet().size)
        val second = NavigationScaleCatalog(authority)
        val uris = HashSet<String>()
        for (index in 0..13_312) {
            val expected = NavigationScaleIdentity(authority, "$prefix$index")
            assertEquals(expected, catalog.identityAt(index))
            assertEquals(expected, catalog.identities[index])
            assertEquals(expected, second.identityAt(index))
            assertEquals(index, catalog.indexOfDocumentId(expected.documentId))
            val uri = "content://$authority/document/localica-navigation-scale-v1%3Atrack%3A$index"
            assertEquals(uri, catalog.canonicalUri(index))
            assertEquals(uri, second.canonicalUri(index))
            assertEquals(expected, catalog.identityFromUri(uri))
            assertTrue("Duplicate canonical URI at $index", uris.add(uri))
        }
        assertEquals(13_313, uris.size)
    }

    @Test
    fun bothTreeAliasesAndPercentEncodingAliasesPreserveDocumentIdentity() {
        for (tree in listOf(NavigationScaleCatalog.ROOT_DOCUMENT_ID, NavigationScaleCatalog.OVERLAP_DOCUMENT_ID)) {
            assertEquals("content://$authority/tree/${tree.replace(":", "%3A")}", catalog.treeUri(tree))
            for (index in listOf(0, 50, 99, 13_312)) {
                val alias = catalog.treeAccessUri(index, tree)
                assertEquals(catalog.identityAt(index), catalog.identityFromUri(alias))
                assertEquals(catalog.identityAt(index), catalog.identityFromUri(alias.replace("%3A", "%3a")))
            }
        }
        assertEquals(catalog.identityAt(0), catalog.identityFromUri("content://$authority/document/${prefix}%30"))
        assertEquals(catalog.identityAt(0), catalog.identityFromUri("content://$authority/document/${prefix}0"))
    }

    @Test
    fun authorityIsPartOfIdentityAndForeignUrisAreRejected() {
        val other = NavigationScaleCatalog("other.navigation.fixture")
        assertEquals(catalog.identityAt(0).documentId, other.identityAt(0).documentId)
        assertNotEquals(catalog.identityAt(0), other.identityAt(0))
        rejects { catalog.identityFromUri(other.canonicalUri(0)) }
        rejects { other.identityFromUri(catalog.canonicalUri(0)) }
        for (invalid in listOf("", "UPPER.provider", "a..b", ".a", "a.", "a/b", "a:80", "user@a", " a", "a ", "a?b", "a#b")) {
            rejects { NavigationScaleCatalog(invalid) }
        }
    }

    @Test
    fun indexBoundariesAreStrictForEveryIndexTakingEntryPoint() {
        for (index in listOf(Int.MIN_VALUE, -1, 13_313, Int.MAX_VALUE)) {
            rejects { catalog.identityAt(index) }
            rejects { catalog.canonicalUri(index) }
            rejects { catalog.treeAccessUri(index, NavigationScaleCatalog.ROOT_DOCUMENT_ID) }
        }
        assertEquals(0, catalog.indexOfDocumentId("${prefix}0"))
        assertEquals(13_312, catalog.indexOfDocumentId("${prefix}13312"))
    }

    @Test
    fun malformedNamespacesAndNonCanonicalIndicesAreRejected() {
        val suffixes = listOf("", "-1", "+1", "00", "01", "13313", "99999", "100000", "2147483648",
            "999999999999999999999999999999", " 1", "1 ", "1\n", "1\t", "1.0", "1/2", "١")
        for (suffix in suffixes) rejects { catalog.indexOfDocumentId(prefix + suffix) }
        for (id in listOf("0", "localica-navigation-scale-v2:track:0", "localica-navigation-scale-v1:tree:0",
            "LOCALICA-navigation-scale-v1:track:0", "/${prefix}0", "${prefix}0:extra")) {
            rejects { catalog.indexOfDocumentId(id) }
        }
    }

    @Test
    fun malformedUrisQueriesFragmentsAndExtraSegmentsFailClosed() {
        val canonical = catalog.canonicalUri(0)
        val root = NavigationScaleCatalog.ROOT_DOCUMENT_ID.replace(":", "%3A")
        val bad = listOf(
            "", "not a uri", "content:opaque", canonical.replace("content:", "file:"),
            canonical.replace("content:", "CONTENT:"), canonical.replace(authority, "user@$authority"),
            canonical.replace(authority, "$authority:80"), canonical + "?", canonical + "?x=1",
            canonical + "#", canonical + "#fragment", canonical + "/", canonical + "/extra",
            "content://$authority", "content://$authority/document", "content://$authority/document/",
            "content://$authority//document/${prefix}0", "content://$authority/Document/${prefix}0",
            "content://$authority/document/${prefix}00", "content://$authority/document/${prefix}13313",
            "content://$authority/document/${prefix}%2B1", "content://$authority/document/${prefix}1+",
            "content://$authority/document/${prefix}1%20", "content://$authority/document/${prefix}1%2F2",
            "content://$authority/document/%", "content://$authority/document/%GG",
            "content://$authority/tree/$root", "content://$authority/tree/unknown/document/${prefix}0",
            "content://$authority/tree/$root/document/${prefix}0/extra",
            "content://$authority/tree/$root/Document/${prefix}0"
        )
        for (uri in bad) rejects { catalog.identityFromUri(uri) }
    }

    @Test
    fun onlyTheTwoPlannedTreeNamespacesAreAccepted() {
        for (tree in listOf("", "root", "${prefix}0", "localica-navigation-scale-v2:tree:root",
            NavigationScaleCatalog.ROOT_DOCUMENT_ID + "/child", NavigationScaleCatalog.ROOT_DOCUMENT_ID + " ")) {
            rejects { catalog.treeUri(tree) }
            rejects { catalog.treeAccessUri(0, tree) }
        }
    }

    @Test
    fun catalogAndQueueListsAreImmutableSnapshotsIncludingCallerOwnedInput() {
        val expected = catalog.identities.toList()
        unsupported { (catalog.identities as MutableList<NavigationScaleIdentity>).clear() }
        unsupported { (catalog.identities as MutableList<NavigationScaleIdentity>)[0] = catalog.identityAt(1) }
        assertEquals(expected, catalog.identities)

        val caller = catalog.identities.take(100).toMutableList()
        val before = caller.toList()
        val request = NavigationScaleQueueRequest(caller)
        caller[0] = catalog.identityAt(100)
        caller.clear()
        assertEquals(before, request.orderedIdentities)
        unsupported { (request.orderedIdentities as MutableList<NavigationScaleIdentity>).removeAt(0) }
        unsupported { (request.orderedIdentities as MutableList<NavigationScaleIdentity>).add(catalog.identityAt(100)) }
        assertEquals(before, request.orderedIdentities)
    }

    @Test
    fun singleQueueIsOnlyAFirst100OrderedPausedRequestNotPersistedPlayback() {
        assertEquals(100, NavigationScaleCatalog.QUEUE_ENTRY_COUNT)
        val request = catalog.singleQueueRequest()
        assertEquals(catalog.identities.take(100), request.orderedIdentities)
        assertEquals(100, request.orderedIdentities.distinct().size)
        assertEquals(50, request.desiredCursorOrdinal)
        assertEquals(catalog.identityAt(50), request.orderedIdentities[request.desiredCursorOrdinal])
        assertEquals(0L, request.desiredPositionMs)
        assertTrue(request.requestedPaused)
        assertEquals(request.orderedIdentities, catalog.singleQueueRequest().orderedIdentities)
        // The request carries document identities and desired values, never actual queue/entry IDs.
        assertEquals(setOf("orderedIdentities", "desiredCursorOrdinal", "desiredPositionMs", "requestedPaused"),
            NavigationScaleQueueRequest::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet())
        assertFalse(request.orderedIdentities.isEmpty())
    }

    @Test
    fun queueRequestRejectsWrongCardinalityAndDuplicateIdentities() {
        for (size in listOf(0, 99, 101)) rejects { NavigationScaleQueueRequest(catalog.identities.take(size)) }
        rejects { NavigationScaleQueueRequest(List(100) { catalog.identityAt(0) }) }
    }

    @Test
    fun repeatedSyntheticAssetProvenanceIsExplicitAndPinned() {
        assertTrue(catalog.isSyntheticControl)
        assertEquals("queue-tone.mp3", catalog.repeatedAssetName)
        assertEquals("93b3ad40749d4b57cba6e5bb77ffd697b36c04ba007c04f036ac26a72953f7f2", catalog.repeatedAssetSha256)
        assertEquals("app/src/androidTest/assets/README.txt", catalog.assetProvenancePath)
        assertEquals("Original 20-second quiet sine tone; identical bytes repeated for all planned identities.", catalog.assetProvenance)
        assertEquals("localica-navigation-scale-v1", NavigationScaleCatalog.NAMESPACE)
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Exact failure category; unrelated exceptions must fail the test.
        }
    }

    private fun unsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Kotlin read-only views alone are insufficient: mutation must be rejected.
        }
    }
}
