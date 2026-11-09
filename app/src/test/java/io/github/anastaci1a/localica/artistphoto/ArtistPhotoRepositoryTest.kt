package io.github.anastaci1a.localica.artistphoto

import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ArtistPhotoRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val request = ArtistPhotoRequest("artist:Twin", "Twin", setOf("Local Album"), "album:latest")
    private val jpg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(1021)
    private class Consent(on: Boolean) : NetworkConsent {
        override val allowed = MutableStateFlow(on)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/artistphoto/$name.json")).use { it.readBytes() }
    private inner class Server : PhotoTransport {
        val calls = mutableListOf<String>()
        var offline = false
        var deezer = true
        var invalidImage = false
        var missingLicence = false
        var wrongReleases = false
        override suspend fun get(url: String, maxBytes: Int): ByteArray {
            calls += url
            check(!offline) { "https://private.example/secret error must never escape" }
            val body = when {
                url.contains("/search/artist") -> if (deezer) fixture("deezer-search") else "{\"data\":[]}".toByteArray()
                url.contains("/artist/7/top") -> "{\"data\":[{\"album\":{\"title\":\"Other band\"}}]}".toByteArray()
                url.contains("/top?") -> if (wrongReleases) "{\"data\":[]}".toByteArray() else fixture("deezer-top")
                url.contains("api.deezer.com/artist/8") -> "{\"id\":8,\"name\":\"Twin\",\"picture_big\":\"https://cdn-images.dzcdn.net/right.jpg\"}".toByteArray()
                url.contains("musicbrainz.org/ws/2/artist/?") -> fixture("mb-search")
                url.contains("musicbrainz.org/ws/2/artist/") -> fixture("mb-artist")
                url.contains("/release-group?") -> if (wrongReleases) "{\"release-groups\":[]}".toByteArray() else fixture("mb-releases")
                url.contains("/Special:EntityData/") -> fixture("wikidata")
                url.contains("commons.wikimedia.org/w/api.php") -> if (missingLicence) String(fixture("commons")).replace("LicenseShortName", "Missing").toByteArray() else fixture("commons")
                url.contains("cdn-images") -> if (invalidImage) "<html>error</html>".toByteArray() else jpg
                url.contains("upload.wikimedia.org") -> jpg
                else -> error("Unexpected fixture route")
            }
            check(body.size <= maxBytes)
            return body
        }
    }
    private fun disk(limit: Long = 8L * 1024 * 1024) = ArtistPhotoDisk(File(temporary.root, "cache"), File(temporary.root, "choices"), limit)
    private fun repo(server: Server, consent: NetworkConsent = Consent(true), now: () -> Long = { 100_000 }) = ArtistPhotos(consent, disk(), server, now)

    @Test fun defaultOffIsPersistedAndDamagedConsentFailsClosed() = runBlocking {
        val file = File(temporary.root, "consent")
        val first = OnlineMetadataConsent(file)
        assertFalse(first.allowed.value)
        first.load(); assertFalse(first.allowed.value)
        first.setAllowed(true)
        val restored = OnlineMetadataConsent(file)
        assertFalse(restored.allowed.value)
        restored.load(); assertTrue(restored.allowed.value)
        restored.setAllowed(false)
        val off = OnlineMetadataConsent(file); off.load(); assertFalse(off.allowed.value)
        file.writeText("garbage")
        val damaged = OnlineMetadataConsent(file); damaged.load(); assertFalse(damaged.allowed.value)
    }
    @Test fun offMeansZeroRequestsIncludingReviewAndCachedPhotoRemainsOffline() = runBlocking {
        val server = Server(); val consent = Consent(false); val photos = repo(server, consent)
        assertEquals(listOf(ArtistPhotoState("album:latest")), photos.observe(request).toList())
        assertTrue(photos.candidates(request).isEmpty()); assertTrue(server.calls.isEmpty())
        consent.setAllowed(true)
        assertNotNull(photos.observe(request).last().photo)
        server.calls.clear(); server.offline = true; consent.setAllowed(false)
        val reopened = repo(server, consent)
        assertNotNull(reopened.observe(request).last().photo)
        assertTrue(server.calls.isEmpty())
    }
    @Test fun collectionIsLazyAndAlwaysEmitsAlbumArtBeforeAnyIO() = runBlocking {
        val server = Server(); val photos = repo(server)
        val flow = photos.observe(request)
        assertTrue(server.calls.isEmpty())
        val blocked = ArtistPhotos(Consent(true), disk(), PhotoTransport { _, _ -> awaitCancellation() })
        assertEquals(ArtistPhotoState("album:latest"), withTimeout(1000) { blocked.observe(request).first() })
    }
    @Test fun deezerChoosesReleaseOverlapAndPrefersXlOverBig() = runBlocking {
        val server = Server(); val states = repo(server).observe(request).toList()
        assertNull(states.first().photo)
        assertEquals(ArtistPhotoProvider.DEEZER, states.last().photo?.provider)
        assertTrue(server.calls.any { it.contains("/right/500x500.jpg") })
        assertFalse(server.calls.any { it.contains("musicbrainz.org") || it.contains("/test/1000x1000.jpg") })
        val match = disk().choice(request.artistKey)
        assertEquals(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), match.choice)
        val providers = ArtistPhotoProviders(PhotoTransport { url, _ ->
            if (url.contains("search/artist")) fixture("deezer-search") else fixture("deezer-top")
        })
        assertTrue(providers.search(ArtistPhotoProvider.DEEZER, request).first().image!!.contains("1000x1000"))
    }
    @Test fun emptyDeezerPictureIdentifiersAreNotArtistPhotos() = runBlocking {
        for (extra in listOf("\"md5_image\":\"\",", "")) {
            val providers = ArtistPhotoProviders(PhotoTransport { url, _ ->
                if (url.contains("search/artist"))
                    """{"data":[{"id":8,"name":"Twin",${extra}"picture_xl":"https://cdn-images.dzcdn.net/images/artist//1000x1000.jpg"}]}""".toByteArray()
                else fixture("deezer-top")
            })
            assertNull(providers.search(ArtistPhotoProvider.DEEZER, request).single().image)
        }
    }
    @Test fun deezerUnavailableOrInvalidPictureFallsBackThroughMbidWikidataAndCommons() = runBlocking {
        for (mode in 0..1) {
            val server = Server().apply { deezer = mode == 1; invalidImage = true }
            val photos = repo(server)
            photos.reset(request.artistKey)
            val photo = photos.observe(request).last().photo!!
            assertEquals(ArtistPhotoProvider.WIKIMEDIA, photo.provider)
            assertEquals("CC BY-SA 4.0", photo.attribution?.licence)
            assertEquals("Fixture Photographer", photo.attribution?.artist)
            assertEquals("Own work", photo.attribution?.credit)
            assertTrue(photo.attribution!!.sourcePage.contains("commons.wikimedia.org"))
            val mb = server.calls.indexOfFirst { it.contains("musicbrainz.org") }
            val wd = server.calls.indexOfFirst { it.contains("www.wikidata.org") }
            val commons = server.calls.indexOfFirst { it.contains("commons.wikimedia.org") }
            assertTrue(mb > 0 && wd > mb && commons > wd)
            assertFalse(server.calls.any { it.contains("last.fm") || it.contains("audioscrobbler") })
            val restored = repo(Server().apply { offline = true }, Consent(false)).observe(request).last().photo!!
            assertEquals(photo.attribution, restored.attribution)
        }
    }
    @Test fun missingLicenceOrNoReleaseOverlapUsesLatestAlbumArt() = runBlocking {
        for (wrong in listOf(false, true)) {
            val server = Server().apply { deezer = false; missingLicence = true; wrongReleases = wrong }
            val photos = repo(server); photos.reset(request.artistKey)
            assertEquals(ArtistPhotoState("album:latest"), photos.observe(request).last())
            assertFalse(server.calls.any { it.contains("upload.wikimedia.org") })
        }
    }
    @Test fun freshCacheSkipsNetworkAndThirtyDayRefreshKeepsOldPhotoOnFailure() = runBlocking {
        val server = Server(); var time = 100_000L; val photos = repo(server, now = { time })
        val original = photos.observe(request).last().photo!!
        server.calls.clear(); time += PHOTO_REFRESH_MS - 1
        assertEquals(original, photos.observe(request).last().photo); assertTrue(server.calls.isEmpty())
        time += 2; server.offline = true
        assertEquals(original, photos.observe(request).last().photo); assertTrue(server.calls.isNotEmpty())
        server.calls.clear(); time += 1000
        photos.observe(request).toList(); assertTrue(server.calls.isEmpty()) // persisted negative retry throttle
        time += PHOTO_RETRY_MS; server.offline = false
        val refreshed = photos.observe(request).last().photo!!
        assertEquals(time, refreshed.fetchedAt)
        assertTrue(server.calls.any { it.contains("api.deezer.com/artist/8") })
    }
    @Test fun clearAndRepickSurviveReopenAndCacheEviction() = runBlocking {
        val server = Server(); val photos = repo(server)
        photos.observe(request).toList(); photos.clear(request.artistKey)
        server.calls.clear()
        assertNull(repo(server).observe(request).last().photo); assertTrue(server.calls.isEmpty())
        photos.pick(request.artistKey, ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"))
        server.wrongReleases = true // explicit user choice can override automatic overlap
        assertNotNull(repo(server).observe(request).last().photo)
        disk().removePhoto(request.artistKey)
        assertEquals("manual", disk().choice(request.artistKey).mode)
        photos.reset(request.artistKey)
        assertEquals(SavedPhotoChoice(), disk().choice(request.artistKey))
    }
    @Test fun diskBoundEvictsOldImagesButRetainsMatchChoices() {
        val disk = disk((PHOTO_MAX_BYTES + 32768).toLong())
        val remote = RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), "Twin", listOf("Local Album"))
        val large = jpg + ByteArray(3 * 1024 * 1024)
        disk.saveChoice("a", SavedPhotoChoice("manual", remote.choice))
        disk.savePhoto("a", remote, large, 1)
        disk.savePhoto("b", remote, large, 2)
        assertNull(disk.cached("a", 3)); assertNotNull(disk.cached("b", 3))
        assertEquals("manual", disk.choice("a").mode)
        assertTrue(File(temporary.root, "cache").listFiles()!!.sumOf { it.length() } <= PHOTO_MAX_BYTES + 32768)
    }
    @Test fun oversizedResponsesAndUntrustedHostsFailWithoutLeakingErrors() = runBlocking {
        try { boundedPhotoBytes(ByteArrayInputStream(ByteArray(11)), 10); fail("oversize accepted") }
        catch (failure: PhotoUnavailable) { assertEquals("Artist photo unavailable", failure.message) }
        for (url in listOf("http://api.deezer.com/artist/1", "https://api.deezer.com.evil.test/a", "https://user@api.deezer.com/a", "https://127.0.0.1/a")) {
            assertFalse(safePhotoUrl(url))
        }
        val http = ArtistPhotoHttp(Consent(true), connect = { error("private exception URL") })
        try { http.get("https://api.deezer.com/test", 10); fail("transport failure accepted") }
        catch (failure: PhotoUnavailable) { assertFalse(failure.toString().contains("private")) }
    }
    @Test fun requestsArePacedAndConsentIsRecheckedAfterWaiting() = runBlocking {
        val consent = Consent(true); var connections = 0; var elapsed = 0L; val waits = mutableListOf<Long>()
        val http = ArtistPhotoHttp(consent, pause = { waits += it; elapsed += it; consent.setAllowed(false) }, now = { elapsed }, connect = {
            connections++
            object : HttpURLConnection(URL("https://api.deezer.com/test")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
            }
        })
        assertEquals("{}", String(http.get("https://api.deezer.com/test", 10)))
        try { http.get("https://api.deezer.com/test", 10); fail("revoked consent accepted") } catch (_: PhotoUnavailable) { }
        assertEquals(listOf(1100L), waits); assertEquals(1, connections)
        val off = ArtistPhotoHttp(Consent(false), connect = { error("must not connect") })
        try { off.get("https://api.deezer.com/test", 10); fail("consent off accepted") } catch (_: PhotoUnavailable) { }
    }
    @Test fun revokedConsentDuringFetchDoesNotPublishOrSaveThePhoto() = runBlocking {
        val consent = Consent(true); val server = Server()
        val transport = PhotoTransport { url, max -> server.get(url, max).also { if (url.contains("cdn-images")) consent.setAllowed(false) } }
        val photos = ArtistPhotos(consent, disk(), transport)
        assertNull(photos.observe(request).last().photo)
        assertNull(disk().cached(request.artistKey, 100_000))
    }
    @Test fun cancellationPropagatesAndBrokenSavedChoiceDoesNotRematch() = runBlocking {
        val transport = PhotoTransport { _, _ -> throw CancellationException("cancelled") }
        try { ArtistPhotos(Consent(true), disk(), transport).observe(request).toList(); fail("cancel swallowed") }
        catch (_: CancellationException) { }
        val directory = File(temporary.root, "choices")
        directory.listFiles()!!.single().writeText("invalid JSON")
        val server = Server()
        assertNull(repo(server).observe(request).last().photo); assertTrue(server.calls.isEmpty())
    }
}
