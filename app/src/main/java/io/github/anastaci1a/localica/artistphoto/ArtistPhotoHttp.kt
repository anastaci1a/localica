package io.github.anastaci1a.localica.artistphoto

import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal fun interface PhotoTransport {
    suspend fun get(url: String, maxBytes: Int): ByteArray
}

/** Shared pacing for all provider requests, including pictures and failed attempts. No redirects. */
internal class ArtistPhotoHttp(private val consent: NetworkConsent,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) : PhotoTransport {
    private val pacing = Mutex()
    private var lastStart: Long? = null
    override suspend fun get(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        try {
            if (!safePhotoUrl(url) || !consent.allowed.value) throw PhotoUnavailable()
            pacing.withLock {
                lastStart?.let { pause((1100 - (now() - it)).coerceAtLeast(0)) }
                currentCoroutineContext().ensureActive()
                if (!consent.allowed.value) throw PhotoUnavailable()
                lastStart = now()
            }
            if (!consent.allowed.value) throw PhotoUnavailable()
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Localica/1.0 (https://github.com/anastaci1a/localica)")
                connection.setRequestProperty("Accept", "application/json,image/jpeg,image/png,image/webp")
                if (!consent.allowed.value) throw PhotoUnavailable()
                if (connection.responseCode != 200 || connection.contentLengthLong > maxBytes) throw PhotoUnavailable()
                connection.inputStream.use { boundedPhotoBytes(it, maxBytes) }
            } finally { connection.disconnect() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PhotoUnavailable() }
    }
}

internal fun safePhotoUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null && uri.host in setOf(
        "api.deezer.com", "cdn-images.dzcdn.net", "cdn-images.deezer.com", "e-cdns-images.dzcdn.net",
        "musicbrainz.org", "www.wikidata.org", "commons.wikimedia.org", "upload.wikimedia.org")
}.getOrDefault(false)

internal suspend fun boundedPhotoBytes(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (count > maxBytes - output.size()) throw PhotoUnavailable()
        output.write(buffer, 0, count)
    }
}
