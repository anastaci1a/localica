package io.github.anastaci1a.localica

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** URI syntax and packaged synthetic-asset contracts only; no provider or app data is touched. */
@RunWith(AndroidJUnit4::class)
class NavigationScaleCatalogNativeTest {
    private val catalog = NavigationScaleCatalog("io.github.anastaci1a.localica.navigation.scale.contract")

    @Test
    fun allCanonicalUrisMatchPublicDocumentsContractConstruction() {
        assertEquals(13_313, catalog.identities.size)
        catalog.identities.forEachIndexed { index, identity ->
            val expected = DocumentsContract.buildDocumentUri(identity.authority, identity.documentId)
            val actual = Uri.parse(catalog.canonicalUri(index))
            assertEquals("Canonical URI at index $index", expected, actual)
            assertEquals(identity.authority, actual.authority)
            assertEquals(identity.documentId, DocumentsContract.getDocumentId(actual))
            assertEquals(identity, catalog.identityFromUri(expected.toString()))
        }
    }

    @Test
    fun rootAndOverlapTreeAliasesPreserveBoundaryAndQueueDocumentIdentities() {
        val queue = catalog.singleQueueRequest()
        assertEquals(100, queue.orderedIdentities.size)
        val indices = (listOf(0, 9, 10, 99, 100, 999, 1_000, 12_999, 13_000, 13_312) +
            queue.orderedIdentities.map { catalog.indexOfDocumentId(it.documentId) }).distinct()

        listOf(NavigationScaleCatalog.ROOT_DOCUMENT_ID, NavigationScaleCatalog.OVERLAP_DOCUMENT_ID)
            .forEach { treeId ->
                val tree = DocumentsContract.buildTreeDocumentUri(catalog.authority, treeId)
                assertEquals(tree, Uri.parse(catalog.treeUri(treeId)))
                assertEquals(treeId, DocumentsContract.getTreeDocumentId(tree))
                indices.forEach { index ->
                    val identity = catalog.identityAt(index)
                    val expected = DocumentsContract.buildDocumentUriUsingTree(tree, identity.documentId)
                    val actual = Uri.parse(catalog.treeAccessUri(index, treeId))
                    assertEquals("Tree $treeId at index $index", expected, actual)
                    assertEquals(identity.authority, actual.authority)
                    assertEquals(treeId, DocumentsContract.getTreeDocumentId(actual))
                    assertEquals(identity.documentId, DocumentsContract.getDocumentId(actual))
                    assertEquals(identity, catalog.identityFromUri(expected.toString()))
                }
            }
    }

    @Test
    fun existingInstrumentationAssetMatchesPinnedHashAndDocumentedProvenance() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expectedHash = "93b3ad40749d4b57cba6e5bb77ffd697b36c04ba007c04f036ac26a72953f7f2"
        assertEquals("queue-tone.mp3", catalog.repeatedAssetName)
        assertEquals(expectedHash, catalog.repeatedAssetSha256)
        assertEquals("app/src/androidTest/assets/README.txt", catalog.assetProvenancePath)
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open(catalog.repeatedAssetName).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        assertEquals(expectedHash, actualHash)
        val provenance = assets.open("README.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "Packaged README must identify the existing original synthetic asset",
            provenance.contains(
                "queue-tone.mp3 is an original 20-second quiet sine tone used to exercise live " +
                    "queue edits without the track ending during the assertions."
            )
        )
        // This checks documentation and encoded bytes, not decoded duration or representative media.
    }
}
