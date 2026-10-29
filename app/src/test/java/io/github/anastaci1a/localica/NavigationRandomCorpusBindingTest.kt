package io.github.anastaci1a.localica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure descriptor tests. No paths are opened and no manifest/file bytes are verified here. */
class NavigationRandomCorpusBindingTest {
    private val authority = "io.github.localica.random.fixture"
    private val manifestHash = hash(900_000)
    private val prefix = "${NavigationScaleCatalog.NAMESPACE}:track:"

    private fun hash(value: Int): String = value.toString(16).padStart(64, '0')

    private fun descriptors(): List<NavigationRandomCorpusTrack> = List(13_313) { index ->
        NavigationRandomCorpusTrack(
            index = index,
            documentId = "$prefix$index",
            relativeMp3Path = "not-created/audio/track$index.mp3",
            declaredFileBytes = 100_000L,
            fileSha256 = hash(index + 1),
            audioSha256 = hash(index + 50_000),
            coverRelativePath = "not-created/covers/shared.cover",
            artworkSha256 = hash(800_000)
        )
    }

    private fun bind(tracks: List<NavigationRandomCorpusTrack> = descriptors()) =
        NavigationRandomCorpusBinding(authority, manifestHash, tracks)

    private fun changed(change: (NavigationRandomCorpusTrack) -> NavigationRandomCorpusTrack): List<NavigationRandomCorpusTrack> =
        descriptors().toMutableList().also { it[0] = change(it[0]) }

    @Test
    fun validShuffledDescriptorsNormalizeToEveryIndexAndAuthorityBoundIdentity() {
        val expected = descriptors()
        // Deterministic interleaving exercises normalization without a random seed or I/O.
        val shuffled = expected.filter { it.index % 2 == 1 }.reversed() + expected.filter { it.index % 2 == 0 }
        val binding = bind(shuffled)
        assertEquals(authority, binding.authority)
        assertEquals(manifestHash, binding.declaredManifestSha256)
        assertEquals(expected, binding.tracks)
        assertEquals(13_313, binding.tracks.size)
        for (index in 0..13_312) {
            assertEquals(index, binding.tracks[index].index)
            assertEquals(NavigationScaleIdentity(authority, "$prefix$index"), binding.plannedIdentityAt(index))
        }
    }

    @Test
    fun plannedIdentityRejectsOutsideCatalogAndRetainsAuthorityIdentity() {
        val binding = bind()
        for (index in listOf(Int.MIN_VALUE, -1, 13_313, Int.MAX_VALUE)) rejects { binding.plannedIdentityAt(index) }
        assertEquals("${prefix}0", binding.plannedIdentityAt(0).documentId)
        assertEquals("${prefix}13312", binding.plannedIdentityAt(13_312).documentId)
        val other = NavigationRandomCorpusBinding("another.fixture", manifestHash, descriptors())
        assertNotEquals(binding.plannedIdentityAt(0), other.plannedIdentityAt(0))
    }

    @Test
    fun callerListIsDefensivelyCopiedAndEveryDescriptorHasItsOwnCopy() {
        val caller = descriptors().toMutableList()
        val expected = caller.toList()
        val binding = bind(caller)
        for (index in expected.indices) {
            assertEquals(expected[index], binding.tracks[index])
            assertNotSame(expected[index], binding.tracks[index])
        }
        caller[0] = caller[0].copy(relativeMp3Path = "changed.mp3")
        caller.clear()
        assertEquals(expected, binding.tracks)
        unsupported { (binding.tracks as MutableList<NavigationRandomCorpusTrack>).clear() }
        unsupported { (binding.tracks as MutableList<NavigationRandomCorpusTrack>)[0] = expected[1] }
        unsupported { (binding.tracks as MutableList<NavigationRandomCorpusTrack>).add(expected[0]) }
        assertEquals(expected, binding.tracks)
    }

    @Test
    fun missingExtraDuplicateAndOutOfRangeIndicesAreRejected() {
        val tracks = descriptors()
        rejects { bind(emptyList()) }
        rejects { bind(tracks.dropLast(1)) }
        rejects { bind(tracks + tracks.last()) }
        rejects { bind(tracks.toMutableList().also { it[0] = it[1] }) }
        for (index in listOf(Int.MIN_VALUE, -1, 13_313, Int.MAX_VALUE)) {
            rejects { bind(changed { it.copy(index = index, documentId = "$prefix$index") }) }
        }
    }

    @Test
    fun documentIdsMustExactlyMatchTheirOwnIndexAndNamespace() {
        for (id in listOf("", "${prefix}1", "${prefix}00", "${prefix}+0", "${prefix}-0", "${prefix}0 ",
            " ${prefix}0", "${prefix}0\n", "localica-navigation-scale-v2:track:0", "${NavigationScaleCatalog.NAMESPACE}:tree:0")) {
            rejects { bind(changed { it.copy(documentId = id) }) }
        }
    }

    @Test
    fun invalidAuthoritiesAndManifestDigestsAreRejected() {
        for (invalid in listOf("", "Upper.fixture", "a..b", ".a", "a.", "a:80", "a/b", "user@a", " a", "a ")) {
            rejects { NavigationRandomCorpusBinding(invalid, manifestHash, descriptors()) }
        }
        for (invalid in malformedHashes()) {
            rejects { NavigationRandomCorpusBinding(authority, invalid, descriptors()) }
        }
    }

    @Test
    fun everyTrackDigestRequiresExactly64LowercaseHexCharacters() {
        for (invalid in malformedHashes()) {
            rejects { bind(changed { it.copy(fileSha256 = invalid) }) }
            rejects { bind(changed { it.copy(audioSha256 = invalid) }) }
            rejects { bind(changed { it.copy(artworkSha256 = invalid) }) }
        }
    }

    private fun malformedHashes(): List<String> = listOf(
        "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
        " " + "a".repeat(63), "a".repeat(63) + "\n", "sha256:" + "a".repeat(64)
    )

    @Test
    fun duplicateMp3PathsFileDigestsAndAudioDigestsAreRejectedIndependently() {
        val second = descriptors()[1]
        rejects { bind(changed { it.copy(relativeMp3Path = second.relativeMp3Path) }) }
        rejects { bind(changed { it.copy(fileSha256 = second.fileSha256) }) }
        rejects { bind(changed { it.copy(audioSha256 = second.audioSha256) }) }
    }

    @Test
    fun bothPathKindsRejectUnsafeOrAmbiguousLexicalComponents() {
        val paths = listOf("", "/a.mp3", "a//b.mp3", "a.mp3/", "./a.mp3", "../a.mp3", "a/../b.mp3",
            "a/./b.mp3", "a\\b.mp3", "a%2Fb.mp3", "a:b.mp3", "a b.mp3", "a\tb.mp3", "a\nb.mp3",
            "a\u0000b.mp3", "é.mp3", ".hidden.mp3", "_a.mp3", "-a.mp3")
        for (path in paths) {
            rejects { bind(changed { it.copy(relativeMp3Path = path) }) }
            rejects { bind(changed { it.copy(coverRelativePath = path) }) }
        }
    }

    @Test
    fun audioRequiresLowercaseMp3SuffixButCoverHasNoExtensionPromise() {
        for (path in listOf("audio/track", "audio/track.MP3", "audio/track.Mp3", "audio/track.mp3.bak", "audio/track.wav")) {
            rejects { bind(changed { it.copy(relativeMp3Path = path) }) }
        }
        for (cover in listOf("covers/art", "covers/art.UNKNOWN", "covers/art.mp3", "covers/A-1_b.c")) {
            assertEquals(cover, bind(changed { it.copy(coverRelativePath = cover) }).tracks[0].coverRelativePath)
        }
        assertEquals("A-1_b.c/Track-0.mp3", bind(changed { it.copy(relativeMp3Path = "A-1_b.c/Track-0.mp3") }).tracks[0].relativeMp3Path)
    }

    @Test
    fun sharedCoverPathsRequireConsistentHashesButDifferentPathsMayShareBytes() {
        assertEquals(1, bind().tracks.map { it.coverRelativePath }.distinct().size)
        rejects { bind(changed { it.copy(artworkSha256 = hash(800_001)) }) }
        val differentPath = bind(changed { it.copy(coverRelativePath = "not-created/covers/other.cover") })
        assertNotEquals(differentPath.tracks[0].coverRelativePath, differentPath.tracks[1].coverRelativePath)
        assertEquals(differentPath.tracks[0].artworkSha256, differentPath.tracks[1].artworkSha256)
    }

    @Test
    fun audioCoverCollisionsAndDeclaredFileAncestorCollisionsAreRejected() {
        val tracks = descriptors()
        rejects { bind(changed { it.copy(coverRelativePath = tracks[1].relativeMp3Path) }) }
        rejects { bind(changed { it.copy(relativeMp3Path = tracks[1].relativeMp3Path + "/child.mp3") }) }
        rejects { bind(changed { it.copy(coverRelativePath = tracks[1].relativeMp3Path + "/child.cover") }) }
        rejects { bind(changed { it.copy(relativeMp3Path = tracks[1].coverRelativePath + "/child.mp3") }) }
        rejects { bind(changed { it.copy(coverRelativePath = tracks[1].coverRelativePath + "/child.cover") }) }
        // Prefix text without a slash boundary is not an ancestor collision.
        assertEquals("not-created/audio/track1.mp3-more.mp3",
            bind(changed { it.copy(relativeMp3Path = "not-created/audio/track1.mp3-more.mp3") }).tracks[0].relativeMp3Path)
    }

    @Test
    fun declaredLengthsMustBePositiveAndAggregateOverflowFailsAsArgumentError() {
        for (length in listOf(Long.MIN_VALUE, -1L, 0L)) {
            rejects { bind(changed { it.copy(declaredFileBytes = length) }) }
        }
        rejects { bind(changed { it.copy(declaredFileBytes = Long.MAX_VALUE) }) }
        val atLimit = descriptors().map { it.copy(declaredFileBytes = 1L) }.toMutableList()
        atLimit[0] = atLimit[0].copy(declaredFileBytes = Long.MAX_VALUE - 13_312L)
        assertEquals(atLimit, bind(atLimit).tracks)
        atLimit[0] = atLimit[0].copy(declaredFileBytes = Long.MAX_VALUE - 13_311L)
        rejects { bind(atLimit) }
    }

    @Test
    fun shapeAcceptanceIsNotFileHashPermissionInstallationOrBudgetAdmission() {
        val binding = bind()
        assertEquals("manifest-shape-and-binding-only", binding.evidenceScope)
        assertTrue(binding.tracks.all { it.relativeMp3Path.startsWith("not-created/") })
        // Dummy declared hashes and path strings are accepted without opening any files.
        assertEquals(hash(1), binding.tracks[0].fileSha256)
        assertEquals(manifestHash, binding.declaredManifestSha256)
        assertTrue(binding.tracks.sumOf { it.declaredFileBytes } > 1_000_000_000L)
    }

    @Test
    fun randomCorpusBindingRemainsSeparateFromTheRepeatedToneControl() {
        val control = NavigationScaleCatalog(authority)
        val binding = bind()
        assertTrue(control.isSyntheticControl)
        assertEquals("queue-tone.mp3", control.repeatedAssetName)
        assertEquals(control.identityAt(0), binding.plannedIdentityAt(0))
        assertEquals(13_313, binding.tracks.map { it.fileSha256 }.distinct().size)
        assertEquals(13_313, binding.tracks.map { it.audioSha256 }.distinct().size)
        assertTrue(binding.tracks.none { it.fileSha256 == control.repeatedAssetSha256 })
        rejects { bind(descriptors().map { it.copy(fileSha256 = control.repeatedAssetSha256) }) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ArithmeticException and unrelated failures do not satisfy this contract.
        }
    }

    private fun unsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // The exposed snapshot must reject mutation, not merely have a read-only Kotlin type.
        }
    }
}
