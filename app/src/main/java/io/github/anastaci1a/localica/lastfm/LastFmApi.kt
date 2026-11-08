package io.github.anastaci1a.localica.lastfm

import kotlinx.coroutines.*
import io.github.anastaci1a.localica.network.NetworkConsent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LastFmFailure(message: String, val retryable: Boolean = false) : Exception(message)
data class LastFmPage(val number: Int, val totalPages: Int, val tracks: List<LastFmTrack>)
fun interface LastFmApi {
    suspend fun page(username: String, number: Int): LastFmPage
}

/** Read-only, fixed HTTPS endpoint. URLs and transport exceptions never reach diagnostics/UI. */
class HttpLastFmApi(private val key: String, private val consent: NetworkConsent,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : LastFmApi {
    override suspend fun page(username: String, number: Int): LastFmPage = withContext(Dispatchers.IO) {
        if (!consent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
        check(key.isNotBlank())
        val url = URL("https://ws.audioscrobbler.com/2.0/?method=user.getTopTracks&period=overall&format=json&limit=200&page=$number&user=" +
            URLEncoder.encode(username, "UTF-8") + "&api_key=" + URLEncoder.encode(key, "UTF-8"))
        val connection = connectionFactory(url)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            if (!consent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
            val status = connection.responseCode
            if (status != 200) throw LastFmFailure(if (status == 429) "Last.fm rate limit reached. Try again later." else "Last.fm could not be reached.", status == 429 || status >= 500)
            val body = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer); if (read < 0) break
                    if (result.length + read > 4_000_000) throw LastFmFailure("Last.fm returned an oversized response.")
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
            LastFmResponses.parse(body)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LastFmFailure) { throw failure }
        catch (_: Exception) { throw LastFmFailure("Last.fm is unavailable. Your previous snapshot is kept.", true) }
        finally { connection.disconnect() }
    }
}

object LastFmResponses {
    private fun JSONObject.optionalText(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    fun parse(body: String): LastFmPage {
        try {
            val json = JSONObject(body)
            if (json.has("error")) {
                val code = json.getInt("error")
                throw LastFmFailure(when (code) {
                    6, 7 -> "The Last.fm username was not found or is private."
                    10, 26 -> "The bundled Last.fm API key is unavailable."
                    29 -> "Last.fm rate limit reached. Try again later."
                    else -> "Last.fm could not complete the request."
                }, code in setOf(8, 11, 16, 29))
            }
            val root = json.getJSONObject("toptracks"); val attr = root.getJSONObject("@attr")
            val page = attr.get("page").toString().toInt(); val total = attr.get("totalPages").toString().toInt()
            require(page > 0 && total in 0..10_000)
            val raw = when (val value = root.opt("track")) {
                null, JSONObject.NULL -> JSONArray()
                is JSONArray -> value
                is JSONObject -> JSONArray().put(value)
                else -> error("Invalid track list")
            }
            val tracks = (0 until raw.length()).map { index ->
                val track = raw.getJSONObject(index)
                val artist = when (val value = track.get("artist")) {
                    is JSONObject -> value.optionalText("name") ?: value.optionalText("#text").orEmpty()
                    is String -> value
                    else -> error("Invalid artist")
                }
                val title = track.getString("name"); val plays = track.get("playcount").toString().toLong()
                require(artist.isNotBlank() && title.isNotBlank() && plays >= 0)
                val album = when (val value = track.opt("album")) {
                    is JSONObject -> value.optionalText("#text") ?: value.optionalText("name")
                    is String -> value
                    else -> null
                }?.takeIf(String::isNotBlank)
                LastFmTrack(artist, title, plays, album, track.optionalText("mbid"))
            }
            require(total != 0 || tracks.isEmpty())
            return LastFmPage(page, maxOf(1, total), tracks)
        } catch (failure: LastFmFailure) { throw failure }
        catch (_: Exception) { throw LastFmFailure("Last.fm returned an invalid response. Your previous snapshot is kept.") }
    }
}

/** A failed/cancelled page never publishes a partial snapshot. Every request is paced, including retries. */
class LastFmSync(private val api: LastFmApi, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun fetch(username: String, onProgress: suspend (LastFmProgress) -> Unit = {}, mayRequest: () -> Boolean = { true }): List<LastFmTrack> {
        val collected = linkedMapOf<String, LastFmTrack>(); var page = 1; var total = 1; var requests = 0
        while (page <= total) {
            var result: LastFmPage? = null
            for (attempt in 0..2) {
                if (requests++ > 0) pause(if (attempt == 0) 500 else 2000L * attempt)
                if (!mayRequest()) throw LastFmFailure("Online features are off. Enable them before syncing.")
                try { result = api.page(username, page); break }
                catch (failure: LastFmFailure) { if (!failure.retryable || attempt == 2) throw failure }
            }
            val response = requireNotNull(result)
            if (response.number != page || response.totalPages < page || (response.tracks.isEmpty() && page < response.totalPages))
                throw LastFmFailure("Last.fm returned incomplete pagination. Your previous snapshot is kept.")
            total = maxOf(total, response.totalPages)
            for (track in response.tracks) {
                val previous = collected[track.key]
                if (previous == null || previous.plays < track.plays) collected[track.key] = track
            }
            if (collected.size > 1_000_000 || total > 10_000) throw LastFmFailure("This Last.fm library is too large to sync.")
            onProgress(LastFmProgress(page, total, collected.size))
            page++
        }
        return collected.values.toList()
    }
}
